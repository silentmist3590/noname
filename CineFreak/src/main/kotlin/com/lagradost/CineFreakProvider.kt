package com.cinefreak

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * CineFreak v11
 *
 * Từ 11/10/2026 trang chi tiết KHÔNG còn chứa link cinecloud. Trình phát của web gọi
 *   POST wp-json/cf/v1/pl/<khóa>   {op=play, ep=m | e<mùa>_<tập>, ts=<Turnstile>}
 * -> {state:"ready", url:"<địa chỉ vidzer có ticket>"}  (hoặc uploading / failed ...)
 * Cần mã Turnstile, nên plugin chạy đúng đoạn JS đó trong WebView rồi lấy kết quả.
 *
 * Mọi lỗi đều được ghi vào "log" và tự copy vào clipboard để gửi lại.
 */
class CineFreakProvider : MainAPI() {
    override var mainUrl = "https://cinefreak.ch"
    override var name = "CineFreak"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/hindi-movies/" to "Hindi Movies",
        "$mainUrl/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/english-movies/" to "English Movies",
        "$mainUrl/dual-audio/" to "Dual Audio",
        "$mainUrl/k-drama/" to "K-Drama",
        "$mainUrl/web-series/" to "Web Series"
    )

    private val mapper = jacksonObjectMapper()
    private val indexApi = "https://search.yagaverse.net/api/search"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private val version = 15
    private val defaultTsKey = "0x4AAAAAAFUUpvP7IaTI_12V"

    // ───────────────────────── log + clipboard ─────────────────────────

    private var lastCopy = 0L
    @Volatile private var probeFailed = false
    private val copyLogOnSuccess = true // đặt false khi đã ổn định

    private inner class Dbg(private val tag: String) {
        private val sb = StringBuilder()
        private val t0 = System.currentTimeMillis()

        fun log(msg: String) {
            val line = "[${System.currentTimeMillis() - t0}ms] $msg"
            synchronized(sb) { sb.append(line).append('\n') }
            Log.d("CineFreak", line)
        }

        fun err(msg: String, e: Throwable?) {
            log("LỖI $msg: ${e?.javaClass?.simpleName}: ${e?.message}")
            Log.e("CineFreak", msg, e)
        }

        /** Copy toàn bộ log vào clipboard (tối đa 1 lần / 3 giây). */
        fun copy(reason: String) {
            val now = System.currentTimeMillis()
            if (now - lastCopy < 3000) return
            lastCopy = now
            val text = synchronized(sb) {
                "CineFreak v$version | $tag | $reason\n" +
                    "Android ${android.os.Build.VERSION.RELEASE} | ${android.os.Build.MODEL}\n" +
                    sb.toString()
            }
            try {
                val act = CommonActivity.activity ?: return
                act.runOnUiThread {
                    try {
                        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("CineFreak log", text))
                        Toast.makeText(act, "CineFreak: đã copy log lỗi ($reason)", Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    private inline fun <reified T> parseJsonOrNull(text: String): T? =
        runCatching { mapper.readValue<T>(text) }.getOrNull()

    private fun absolute(rawValue: String?, base: String = "$mainUrl/"): String {
        val raw = rawValue?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val candidate = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            else -> runCatching { URI(base).resolve(URI(raw)).toString() }.getOrDefault("")
        }
        return candidate.takeIf {
            runCatching {
                val u = URI(it)
                (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrBlank()
            }.getOrDefault(false)
        }.orEmpty()
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(Regex("\\s*[|–-]\\s*CineFreak.*$", RegexOption.IGNORE_CASE), "")

    private fun isSeries(text: String, url: String = ""): Boolean =
        Regex("web.?series|series|season|episode|k-drama|tv show|anime", RegexOption.IGNORE_CASE)
            .containsMatchIn("$text $url")

    private fun indexUrl(slug: String): String {
        val clean = slug.trim()
        return if (clean.startsWith("http://") || clean.startsWith("https://")) clean
        else "$mainUrl/${clean.trim('/')}/"
    }

    // ───────────────────────── danh mục / tìm kiếm ─────────────────────────

    private data class SearchKeyJson(val k: String? = null)
    private data class SearchJson(val results: List<SearchItem> = emptyList())
    private data class SearchItem(val t: String? = null, val l: String? = null, val i: String? = null, val c: String? = null)
    private data class IndexJson(val hits: List<IndexHit> = emptyList())
    private data class IndexHit(val document: IndexDocument? = null)
    private data class IndexDocument(
        val title: String? = null,
        val slug: String? = null,
        val thumb: String? = null,
        val cats: List<String> = emptyList()
    )

    private fun indexItems(json: IndexJson?): List<SearchResponse> =
        json?.hits.orEmpty().mapNotNull { hit ->
            val item = hit.document ?: return@mapNotNull null
            val slug = item.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = cleanTitle(item.title.orEmpty()).ifBlank {
                slug.substringAfterLast('/').replace('-', ' ')
            }
            val url = indexUrl(slug)
            val poster = absolute(item.thumb)
            if (isSeries("$title ${item.cats.orEmpty().joinToString(" ")}", slug)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster.ifBlank { null } }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster.ifBlank { null } }
            }
        }.distinctBy { it.url }

    private suspend fun indexSearch(query: String, page: Int = 1): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val text = app.get(
            "$indexApi?q=$q&pg=$page",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
        ).text
        return indexItems(parseJsonOrNull<IndexJson>(text))
    }

    private suspend fun searchSiteApi(query: String, page: Int = 1): List<SearchResponse> {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "Referer" to "$mainUrl/fast-search/"
        )
        val key = runCatching {
            parseJsonOrNull<SearchKeyJson>(
                app.post(
                    "$mainUrl/search-api.php",
                    headers = headers + ("Origin" to mainUrl),
                    data = mapOf("st" to "1")
                ).text
            )?.k.orEmpty()
        }.getOrDefault("")

        val result = app.get(
            "$mainUrl/search-api.php?q=${URLEncoder.encode(query, "UTF-8")}&pg=$page",
            headers = headers + ("X-CF-ST" to key)
        ).text.let { parseJsonOrNull<SearchJson>(it) }

        return result?.results.orEmpty().mapNotNull { item ->
            val slug = item.l?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = cleanTitle(item.t.orEmpty()).ifBlank {
                slug.substringAfterLast('/').replace('-', ' ')
            }
            val url = indexUrl(slug)
            val poster = absolute(item.i)
            if (isSeries("$title ${item.c.orEmpty()}", slug)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster.ifBlank { null } }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster.ifBlank { null } }
            }
        }.distinctBy { it.url }
    }

    private fun cards(html: String, pageUrl: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = LinkedHashMap<String, SearchResponse>()
        val candidates = doc.select("article, .post, .movie-item, .item, .post-item, .latest-release, .result-item")
        for (card in candidates) {
            val a = card.selectFirst("a[href]") ?: continue
            val href = absolute(a.attr("href"), pageUrl)
            if (href.isBlank() || !href.startsWith(mainUrl) || href == mainUrl ||
                href.contains("/category/") || href.contains("/tag/")) continue
            val titleNode = card.selectFirst("h1,h2,h3,h4,.title,.entry-title,[title]")
            val title = cleanTitle(titleNode?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("title").ifBlank { a.text() })
            if (title.length < 2) continue
            val poster = card.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
            }?.let { absolute(it, pageUrl) }?.ifBlank { null }
            val response = if (isSeries(title, href)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            }
            out.putIfAbsent(href, response)
        }

        if (out.isEmpty()) {
            for (a in doc.select("a[href]:has(img)")) {
                val href = absolute(a.attr("href"), pageUrl)
                if (href.isBlank() || !href.startsWith(mainUrl) || href == mainUrl ||
                    href.contains("/category/") || href.contains("/tag/")) continue
                val img = a.selectFirst("img")
                val title = cleanTitle(a.attr("title").ifBlank {
                    img?.attr("alt").orEmpty()
                }.ifBlank { a.text() })
                if (title.length < 2) continue
                val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                    ?.let { absolute(it, pageUrl) }?.ifBlank { null }
                val response = if (isSeries(title, href)) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
                }
                out.putIfAbsent(href, response)
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val d = Dbg("getMainPage ${request.name} p$page")
        val base = request.data.trim()
        if (!base.matches(Regex("(?i)^https?://[^\\s]+$"))) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        try {
            val pageUrl = if (page <= 1) base else base.trimEnd('/') + "/page/$page/"
            val response = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            d.log("GET $pageUrl -> ${response.code}, ${response.text.length} ký tự")
            var items = cards(response.text, pageUrl)
            d.log("cards=${items.size}")
            if (items.isEmpty()) {
                items = runCatching {
                    val indexed = indexSearch("*", page)
                    d.log("indexSearch=${indexed.size}")
                    when {
                        request.name.equals("Latest Releases", true) -> indexed
                        request.name.contains("Hindi Dubbed", true) -> indexed.filter { it.name.contains("hindi", true) }
                        request.name.contains("Hindi", true) -> indexed.filter { it.name.contains("hindi", true) }
                        request.name.contains("English", true) -> indexed.filter { it.name.contains("english", true) }
                        request.name.contains("Dual", true) -> indexed.filter { it.name.contains("dual", true) }
                        request.name.contains("K-Drama", true) -> indexed.filter { isSeries(it.name, it.url) || it.name.contains("Korean", true) }
                        request.name.contains("Series", true) -> indexed.filter { isSeries(it.name, it.url) }
                        else -> indexed
                    }
                }.getOrDefault(emptyList())
            }
            if (items.isEmpty() && page <= 1) {
                d.log("Trang chủ trống. Đầu HTML: ${response.text.take(300).replace('\n', ' ')}")
                d.copy("trang chủ trống")
            }
            val hasNext = items.isNotEmpty() &&
                (response.text.contains("rel=\"next\"", true) ||
                    response.text.contains("/page/${page + 1}/") || items.size >= 20)
            return newHomePageResponse(request.name, items, hasNext = hasNext)
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            d.err("getMainPage", e)
            d.copy("lỗi trang chủ")
            throw e
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val d = Dbg("search '$query'")
        try {
            runCatching { searchSiteApi(query) }
                .onFailure { d.err("searchSiteApi", it) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            runCatching { indexSearch(query) }
                .onFailure { d.err("indexSearch", it) }
                .getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
            val html = app.get(
                "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}",
                headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
            ).text
            return cards(html, mainUrl)
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            d.err("search", e)
            d.copy("lỗi tìm kiếm")
            throw e
        }
    }

    // ───────────────────────── trang chi tiết ─────────────────────────

    private fun firstInt(s: String?): Int? = Regex("""\d+""").find(s.orEmpty())?.value?.toIntOrNull()

    override suspend fun load(url: String): LoadResponse? {
        val d = Dbg("load")
        try {
            val response = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            val html = response.text
            d.log("GET $url -> ${response.code}, ${html.length} ký tự")
            val doc = Jsoup.parse(html, url)
            val title = cleanTitle(
                doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: doc.selectFirst("h1.entry-title, h1, .post-title")?.text().orEmpty()
            )
            if (title.isBlank()) {
                d.log("Không đọc được tiêu đề")
                d.copy("trang chi tiết không có tiêu đề")
                return null
            }

            val poster = doc.selectFirst(
                "article img[src], .post img[src], .entry-content img[src], img[src*=image.tmdb.org], img[src*=cineimg.xyz]"
            )?.let {
                it.absUrl("src").ifBlank { it.absUrl("data-src") }.ifBlank { absolute(it.attr("data-src"), url) }
            }?.ifBlank { null } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { !it.contains("admin-ajax") }?.let { absolute(it, url) }

            val description = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
                ?: doc.selectFirst(".entry-content, .description, .summary")?.text()
            val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.value?.toIntOrNull()

            // API phát mới (từ 11/10/2026)
            val playApi = Regex("""const\s+PLAY_API\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?.replace("\\/", "/").orEmpty()
            val siteKey = Regex("""const\s+TS_SITE_KEY\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                ?: defaultTsKey
            d.log("PLAY_API=${playApi.ifBlank { "(không thấy)" }} siteKey=$siteKey")

            // dataset = {"type":"movie"|"series","sources":"m","seasons":[{season_name,episodes:[{ep_num,ep_title,sources,k}]}]}
            val datasetNode: JsonNode? = html.indexOf("const dataset = ").let { i ->
                if (i < 0) null else runCatching {
                    mapper.readTree(html.substring(i + "const dataset = ".length))
                }.onFailure { d.err("parse dataset", it) }.getOrNull()
            }
            val isSeriesPage = datasetNode?.path("type")?.asText("") == "series" ||
                (datasetNode == null && isSeries(title, url))
            d.log("dataset type=${datasetNode?.path("type")?.asText("?")} series=$isSeriesPage")

            fun payload(ep: String) = listOf(playApi, ep, url, siteKey).joinToString("|||")

            val episodes = mutableListOf<Episode>()
            if (isSeriesPage && datasetNode != null) {
                val seasons = datasetNode.path("seasons")
                for (si in 0 until seasons.size()) {
                    val s = seasons[si]
                    val seasonNo = firstInt(s.path("season_name").asText("")) ?: (si + 1)
                    val eps = s.path("episodes")
                    for (ei in 0 until eps.size()) {
                        val e = eps[ei]
                        val src = e.path("sources").asText("")
                        if (src.isBlank()) continue
                        val epNo = firstInt(e.path("ep_num").asText("")) ?: (ei + 1)
                        val label = e.path("ep_title").asText("").ifBlank { "Episode $epNo" }
                        val isPack = e.path("k").asText("") == "p"
                        episodes.add(newEpisode(payload(src)) {
                            this.name = if (isPack) "$label (pack)" else label
                            this.season = seasonNo
                            this.episode = epNo
                        })
                    }
                }
                d.log("episodes=${episodes.size}")
            }

            if (episodes.isEmpty() && isSeriesPage) {
                d.log("Series nhưng không có tập")
                d.copy("không đọc được danh sách tập")
            }

            return if (isSeriesPage && episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    posterUrl = poster
                    plot = description
                    this.year = year
                }
            } else {
                val ep = datasetNode?.path("sources")?.asText("")?.ifBlank { null } ?: "m"
                newMovieLoadResponse(title, url, TvType.Movie, payload(ep)) {
                    posterUrl = poster
                    plot = description
                    this.year = year
                }
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            d.err("load", e)
            d.copy("lỗi trang chi tiết")
            throw e
        }
    }

    // ───────────────────────── phát: PLAY_API ─────────────────────────

    private class PlayResp(val status: Int, val state: String, val url: String, val need: String, val message: String, val raw: String)

    private fun parsePlay(status: Int, text: String): PlayResp {
        val n = runCatching { mapper.readTree(text) }.getOrNull()
        return PlayResp(
            status = status,
            state = n?.path("state")?.asText("").orEmpty(),
            url = n?.path("url")?.asText("").orEmpty(),
            need = n?.path("need")?.asText("").orEmpty(),
            message = n?.path("message")?.asText("").orEmpty(),
            raw = text.take(300)
        )
    }

    /** Tầng A: gọi thẳng bằng HTTP (chỉ chạy được khi web chưa đòi Turnstile / cho phép fallback). */
    private suspend fun playHttp(d: Dbg, api: String, ep: String, pageUrl: String): PlayResp? {
        return try {
            val r = app.post(
                api,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to pageUrl,
                    "Origin" to mainUrl,
                    "Accept" to "application/json"
                ),
                data = mapOf("op" to "play", "ep" to ep, "tsfb" to "none")
            )
            val p = parsePlay(r.code, r.text)
            d.log("A) HTTP play -> ${p.status} state='${p.state}' need='${p.need}' msg='${p.message}' raw=${p.raw.take(120)}")
            p
        } catch (e: Throwable) {
            d.err("A) HTTP play", e)
            null
        }
    }

    private fun playScript(api: String, ep: String, siteKey: String): String = """
(function(){
 if (String(location.hostname).indexOf('cinefreak') < 0) return;
 if (window.__cfgo) return; window.__cfgo = 1;
 var API = __API__, EP = __EP__, KEY = __KEY__, trace = [], t0 = Date.now(), finished = false;
 function T(s){ trace.push(((Date.now()-t0)/1000).toFixed(1)+'s '+s); }
 function beacon(o){
   if (finished) return; finished = true;
   o.trace = trace;
   var s = encodeURIComponent(JSON.stringify(o));
   if (s.length > 7000) { o.trace = trace.slice(-6); s = encodeURIComponent(JSON.stringify(o)); }
   var i = new Image(); i.src = 'https://cfresult.invalid/r?d=' + s;
 }
 function wait(ms){ return new Promise(function(r){ setTimeout(r, ms); }); }
 function post(op, ts, extra){
   var b = new URLSearchParams({op: op, ep: EP});
   if (ts) b.set('ts', ts);
   if (extra) for (var k in extra) b.set(k, extra[k]);
   return fetch(API, {method:'POST', body:b, cache:'no-store', credentials:'same-origin'}).then(function(r){
     return r.text().then(function(t){ var j = null; try { j = JSON.parse(t); } catch(e) {}
       return {status: r.status, j: j, raw: j ? null : t.slice(0,200)}; });
   });
 }
 function loadTs(){
   return new Promise(function(res, rej){
     if (window.turnstile && window.turnstile.render) return res(window.turnstile);
     var s = document.createElement('script');
     s.src = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';
     s.async = true;
     s.onload = function(){ res(window.turnstile); };
     s.onerror = function(){ rej(new Error('ts-load')); };
     document.head.appendChild(s);
   });
 }
 function tsToken(){
   return loadTs().then(function(ts){
     return new Promise(function(res, rej){
       var box = document.createElement('div');
       box.style.cssText = 'position:fixed;left:0;top:0;z-index:99999;background:#000;padding:8px';
       document.body.appendChild(box);
       var tries = 0, wid = null;
       var tm = setTimeout(function(){ rej(new Error('ts-timeout')); }, 60000);
       wid = ts.render(box, {
         sitekey: KEY, appearance: 'interaction-only', theme: 'dark', retry: 'never',
         callback: function(tok){ clearTimeout(tm); res(tok); },
         'error-callback': function(c){
           if (++tries < 3) { setTimeout(function(){ try { ts.reset(wid); } catch(e) {} }, 1500); }
           else { clearTimeout(tm); rej(new Error('ts-error:' + c)); }
           return true;
         },
         'timeout-callback': function(){ try { ts.reset(wid); } catch(e) {} },
         'unsupported-callback': function(){ clearTimeout(tm); rej(new Error('ts-unsupported')); }
       });
     });
   });
 }
 function brief(x){ return x.status + ' ' + JSON.stringify(x.j || x.raw).slice(0, 160); }
 async function main(){
   try {
     T('origin ' + location.origin);
     var ts = null;
     var x = await post('play'); T('play ' + brief(x));
     if (x.status === 401 && x.j && x.j.need === 'ts') {
       try { ts = await tsToken(); T('turnstile ok'); x = await post('play', ts); T('play+ts ' + brief(x)); }
       catch(e) { T('turnstile fail ' + e.message); x = await post('play', null, {tsfb: String(e.message).slice(0, 20)}); T('fallback ' + brief(x)); }
     }
     var n = 0;
     while (x.j && x.j.state === 'uploading' && n++ < 24) {
       await wait(4000);
       x = await post('stat', ts); T('stat ' + brief(x));
     }
     beacon({status: x.status, j: x.j, raw: x.raw});
   } catch(e) { T('exception ' + e); beacon({err: String(e)}); }
 }
 setTimeout(function(){ beacon({err: 'script-timeout'}); }, 140000);
 main();
})();
""".trimIndent()
        .replace("__API__", JSONObject.quote(api))
        .replace("__EP__", JSONObject.quote(ep))
        .replace("__KEY__", JSONObject.quote(siteKey))

    /** Tầng B: chạy đúng đoạn JS của web trong WebView (có Turnstile) rồi nhận kết quả qua "beacon". */
    private suspend fun playWebView(d: Dbg, api: String, ep: String, siteKey: String, pageUrl: String): PlayResp? {
        return try {
            d.log("B) WebView mở $pageUrl (ep=$ep)")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""cfresult\.invalid/r\?"""),
                additionalUrls = emptyList(),
                userAgent = null,
                useOkhttp = false,
                script = playScript(api, ep, siteKey),
                scriptCallback = { d.log("B) script trả về: ${it.take(80)}") },
                timeout = 150_000L
            )
            val (req, _) = resolver.resolveUsingWebView(
                Request.Builder().url(pageUrl).header("Referer", "$mainUrl/").build()
            )
            if (req == null) {
                d.log("B) WebView hết thời gian, không nhận được kết quả")
                return null
            }
            val raw = req.url.queryParameter("d").orEmpty()
            val n = runCatching { mapper.readTree(raw) }.getOrNull()
            if (n == null) {
                d.log("B) beacon không đọc được: ${raw.take(200)}")
                return null
            }
            val trace = n.path("trace")
            for (i in 0 until trace.size()) d.log("   js: ${trace[i].asText()}")
            if (n.has("err")) {
                d.log("B) JS báo lỗi: ${n.path("err").asText()}")
                return null
            }
            val j = n.path("j")
            val p = PlayResp(
                status = n.path("status").asInt(0),
                state = j.path("state").asText(""),
                url = j.path("url").asText(""),
                need = j.path("need").asText(""),
                message = j.path("message").asText(""),
                raw = n.path("raw").asText("").ifBlank { j.toString().take(300) }
            )
            d.log("B) kết quả: status=${p.status} state='${p.state}' need='${p.need}' msg='${p.message}' url=${p.url.take(120)}")
            p
        } catch (e: Throwable) {
            d.err("B) WebView play", e)
            null
        }
    }

    // ───────────────────────── vidzer ─────────────────────────

    private fun decodeB64Url(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun decryptVidzer(encrypted: String, qk: String): String {
        val key = decodeB64Url(qk.reversed())
        val input = decodeB64Url(encrypted)
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key size ${key.size}" }
        require(input.size > 12) { "Encrypted Vidzer payload too short" }
        val iv = input.copyOfRange(0, 12)
        val ciphertext = input.copyOfRange(12, input.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    /** Gọi thử đúng URL video bằng Range để biết server có chấp nhận header này không. */
    private suspend fun probe(d: Dbg, tag: String, url: String, headers: Map<String, String>): Boolean {
        return try {
            val r = app.get(url, headers = headers + ("Range" to "bytes=0-1023"))
            val ct = r.headers["Content-Type"].orEmpty()
            val head = runCatching {
                r.okhttpResponse.body.byteStream().use { ins ->
                    val buf = ByteArray(60)
                    val n = ins.read(buf)
                    (if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else "").replace(Regex("[^\\x20-\\x7E]"), ".")
                }
            }.getOrDefault("")
            d.log("   probe[$tag] -> ${r.code} type=$ct len=${r.headers["Content-Length"]} range=${r.headers["Content-Range"]} accept=${r.headers["Accept-Ranges"]} head='$head'")
            r.code in 200..206 && !ct.contains("html", true) && !ct.contains("json", true)
        } catch (e: Throwable) {
            d.log("   probe[$tag] lỗi ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private suspend fun emit(
        d: Dbg,
        url: String,
        referer: String,
        label: String,
        quality: Int,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val origin = runCatching { URI(referer).let { "${it.scheme}://${it.host}" } }.getOrDefault(mainUrl)
        d.log("LINK $label type=$type len=${url.length} url=${url.take(110)}...")
        d.log("   headers=$headers")
        // vài kiểu header, chọn kiểu đầu tiên mà server trả được dữ liệu
        val ck = headers["Cookie"]?.let { mapOf("Cookie" to it) } ?: emptyMap()
        val variants = linkedMapOf(
            "như-trình-duyệt" to (mapOf("User-Agent" to userAgent) + ck),
            "gốc" to headers,
            "origin" to (headers + ("Origin" to origin) + ("Referer" to "$origin/")),
            "cinefreak" to (mapOf("Referer" to "$mainUrl/", "Origin" to mainUrl, "User-Agent" to userAgent) + ck),
            "không-referer-không-cookie" to mapOf("User-Agent" to userAgent)
        )
        var chosen: Map<String, String>? = null
        for ((tag, h) in variants) {
            if (probe(d, tag, url, h)) { chosen = h; d.log("   -> dùng header kiểu '$tag'"); break }
        }
        if (chosen == null) {
            d.log("   -> không kiểu nào probe được, vẫn phát với header gốc")
            probeFailed = true
        }
        val use = chosen ?: headers
        callback(
            newExtractorLink(source = name, name = label, url = url, type = type) {
                this.referer = use["Referer"] ?: referer
                this.quality = if (quality > 0) quality else Qualities.Unknown.value
                this.headers = use
            }
        )
    }

    /** Lấy name=value từ các header Set-Cookie của một phản hồi. */
    private fun setCookies(r: com.lagradost.nicehttp.NiceResponse): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            for (line in r.headers.values("Set-Cookie")) {
                val nv = line.substringBefore(';')
                val k = nv.substringBefore('=').trim()
                val v = nv.substringAfter('=', "").trim()
                if (k.isNotEmpty()) out[k] = v
            }
        }
        if (out.isEmpty()) runCatching { out.putAll(r.cookies) }
        return out
    }

    /** Tầng C1: đọc trang trình phát vidzer bằng HTTP (qk + apiUrl + AES-GCM). */
    private suspend fun vidzerHttp(
        d: Dbg,
        playerUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val url = if (playerUrl.contains("?")) playerUrl else "$playerUrl?autoplay=1"
            val page = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            val html = page.text
            d.log("C1) vidzer GET -> ${page.code}, ${html.length} ký tự")
            val jar = LinkedHashMap<String, String>()
            jar.putAll(setCookies(page))
            d.log("C1) cookie từ trang: ${jar.keys}")
            if (page.code !in 200..299) {
                d.log("C1) đầu HTML: ${html.take(200).replace('\n', ' ')}")
                return false
            }
            val qk = Regex("""\bqk\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""["']qk["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            val apiPath = Regex("""\bapiUrl\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
                ?: Regex("""["']apiUrl["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            if (qk.isNullOrBlank() || apiPath.isNullOrBlank()) {
                d.log("C1) thiếu qk/apiUrl (qk=${!qk.isNullOrBlank()}, api=${!apiPath.isNullOrBlank()}). Đầu HTML: ${html.take(300).replace('\n', ' ')}")
                // thử tìm link media ngay trong HTML
                val direct = Regex("""https?:[^"'\s<>\\]+\.(?:m3u8|mp4|mkv|webm)[^"'\s<>\\]*""", RegexOption.IGNORE_CASE)
                    .find(html.replace("\\/", "/"))?.value
                if (direct != null) {
                    d.log("C1) tìm thấy media trong HTML: ${direct.take(120)}")
                    emit(d, direct, url, "Vidzer", 0, mapOf("Referer" to url, "User-Agent" to userAgent), callback)
                    return true
                }
                return false
            }

            val apiUrl = absolute(apiPath, url)
            val api = app.get(
                apiUrl,
                headers = mapOf("User-Agent" to userAgent, "Referer" to url, "Accept" to "application/json") +
                    (if (jar.isNotEmpty()) mapOf("Cookie" to jar.entries.joinToString("; ") { "${it.key}=${it.value}" }) else emptyMap())
            )
            jar.putAll(setCookies(api))
            d.log("C1) cookie sau api: ${jar.keys}")
            d.log("C1) api GET $apiUrl -> ${api.code}, ${api.text.length} ký tự")
            if (api.code !in 200..299) return false

            val raw = api.text.trim()
            val payload = if (raw.startsWith("{")) raw else decryptVidzer(raw.substringAfterLast('.', raw), qk)
            val root = mapper.readTree(payload)
            d.log("C1) payload keys=${root.fieldNames().asSequence().toList()}")
            d.log("C1) gateway=${root.path("gateway").toString().take(300)} hevc=${root.path("hevc").toString().take(120)}")
            d.log("C1) resolutions=${root.path("resolutions").toString().replace(Regex("\"url\":\"([^\"]{0,60})[^\"]*\""), "\"url\":\"$1...\"").take(500)}")
            d.log("C1) audio=${root.path("audioTracks").toString().replace(Regex("\"url\":\"([^\"]{0,60})[^\"]*\""), "\"url\":\"$1...\"").take(400)}")
            val videoUrl = root.path("videoUrl").asText("").ifBlank {
                if (root.path("video").isTextual) root.path("video").asText("") else root.path("video").path("url").asText("")
            }
            if (!videoUrl.startsWith("http://", true) && !videoUrl.startsWith("https://", true)) {
                d.log("C1) không có videoUrl hợp lệ")
                return false
            }

            val resolutionNodes = root.path("resolutions")
            var quality = 0
            for (i in 0 until resolutionNodes.size()) {
                val item = resolutionNodes[i]
                if (item.path("url").asText() == videoUrl) {
                    quality = firstInt(item.path("quality").asText("")) ?: 0
                    break
                }
            }
            val subtitles = root.path("subtitleTracks")
            for (i in 0 until subtitles.size()) {
                val item = subtitles[i]
                val subtitleUrl = absolute(item.path("url").asText(""), url)
                if (subtitleUrl.isNotBlank()) {
                    subtitleCallback(SubtitleFile(item.path("language").asText("en"), subtitleUrl))
                }
            }
            val cookieHeader = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            emit(
                d, videoUrl, url, "Vidzer${if (quality > 0) " ${quality}p" else ""}", quality,
                mapOf("Referer" to url, "User-Agent" to userAgent) +
                    (if (cookieHeader.isNotEmpty()) mapOf("Cookie" to cookieHeader) else emptyMap()),
                callback
            )
            d.log("C1) OK quality=$quality url=${videoUrl.take(120)}")
            return true
        } catch (e: Throwable) {
            d.err("C1) vidzerHttp", e)
            return false
        }
    }

    /** Tầng C2: mở trình phát vidzer trong WebView, bắt request video thật. */
    private suspend fun vidzerWebView(d: Dbg, playerUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = if (playerUrl.contains("?")) playerUrl else "$playerUrl?autoplay=1"
            d.log("C2) WebView mở trình phát $url")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)\.(m3u8|mp4|mkv|webm)(\?|$)"""),
                additionalUrls = emptyList(),
                userAgent = null,
                useOkhttp = false,
                script = null,
                scriptCallback = null,
                timeout = 60_000L
            )
            val (req, _) = resolver.resolveUsingWebView(
                Request.Builder().url(url).header("Referer", "$mainUrl/").build()
            )
            if (req == null) {
                d.log("C2) không bắt được request video")
                return false
            }
            val media = req.url.toString()
            d.log("C2) bắt được ${media.take(150)}")
            val hdr = HashMap<String, String>()
            for (n in req.headers.names()) hdr[n] = req.header(n).orEmpty()
            if (!hdr.containsKey("Referer")) hdr["Referer"] = url
            if (!hdr.containsKey("User-Agent")) hdr["User-Agent"] = userAgent
            runCatching { android.webkit.CookieManager.getInstance().getCookie(media) }.getOrNull()
                ?.takeIf { it.isNotBlank() && !hdr.containsKey("Cookie") }?.let { hdr["Cookie"] = it; d.log("C2) cookie WebView: ${it.substringBefore('=')}=...") }
            emit(d, media, url, "Vidzer (WebView)", 0, hdr, callback)
            true
        } catch (e: Throwable) {
            d.err("C2) vidzerWebView", e)
            false
        }
    }

    // ───────────────────────── loadLinks ─────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val d = Dbg("loadLinks")
        probeFailed = false
        val parts = data.split("|||")
        val api = parts.getOrNull(0).orEmpty()
        val ep = parts.getOrNull(1).orEmpty()
        val pageUrl = parts.getOrNull(2).orEmpty()
        val siteKey = parts.getOrNull(3)?.ifBlank { null } ?: defaultTsKey
        d.log("data: api=${api.ifBlank { "(trống)" }} ep=$ep page=$pageUrl")

        var found = false
        var reason = "không có link"
        try {
            if (api.isBlank() || ep.isBlank() || !pageUrl.startsWith("http")) {
                reason = "dữ liệu tập phim cũ/thiếu - hãy mở lại phim (tải lại trang chi tiết)"
                d.log(reason)
            } else {
                var resp = playHttp(d, api, ep, pageUrl)
                if (resp == null || resp.state != "ready" || resp.url.isBlank()) {
                    resp = playWebView(d, api, ep, siteKey, pageUrl)
                }
                when {
                    resp == null -> reason = "không gọi được API phát (WebView/Turnstile thất bại)"
                    resp.state == "ready" && resp.url.isNotBlank() -> {
                        val playerUrl = absolute(resp.url, pageUrl)
                        d.log("Trình phát: $playerUrl")
                        found = vidzerHttp(d, playerUrl, pageUrl, subtitleCallback, callback)
                        if (!found) found = vidzerWebView(d, playerUrl, callback)
                        if (!found) reason = "đã có địa chỉ vidzer nhưng không lấy được video"
                    }
                    else -> reason = "API phát trả state='${resp.state}' need='${resp.need}' ${resp.message}".trim()
                }
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            d.err("loadLinks", e)
            reason = "ngoại lệ: ${e.message}"
        }

        if (!found) {
            d.log("KẾT QUẢ: thất bại - $reason")
            d.copy(reason)
        } else {
            d.log("KẾT QUẢ: có link")
            if (probeFailed || copyLogOnSuccess) d.copy(if (probeFailed) "link phát có thể lỗi" else "thành công (log kiểm tra)")
        }
        return found
    }
}
