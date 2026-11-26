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
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

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
    private val version = 17
    private val defaultTsKey = "0x4AAAAAAFUUpvP7IaTI_12V"

    // Nguồn định danh để tra cứu phụ đề (được chuyển từ Vidup).
    // Dùng IMDb ID có sẵn trong HTML trước; chỉ gọi TMDB khi trang không cung cấp IMDb ID.
    private val tmdbHosts = listOf(
        "https://tmdb-proxy.silentmist3590.workers.dev/3",
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private var tmdbPrefer = 0
    private val tmdbKeys = listOf(
        "c3d997893bf55c01f39f821b53781547",
        "439c478a771f35c05022f9feabcca01c",
        "8476a7ab80ad76f0936744df0430e67c"
    )
    private val tmdbBearerToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"
 
    // API key mặc định được chuyển từ Vidup; thay giá trị này nếu bạn dùng key SubDL riêng.
    private val defaultSubdlApiKey = "xHeOWlCfS9o4EKmK7OwbeOBIhfbIHr2Y"

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

            val pageImdbId = extractImdbId(html)
            fun payload(ep: String) = listOf(
                playApi, ep, url, siteKey, title, year?.toString().orEmpty(), pageImdbId
            ).joinToString("|||")

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
// ─────────────────────── phụ đề: OpenSubtitles + SubDL ───────────────────────
 
    private data class SubtitleMediaRef(
        val type: String, // movie | tv
        val tmdbId: Int,
        val season: Int?,
        val episode: Int?
    )
 
    private data class SubtitleIdentity(val tmdbId: Int, val imdbId: String)
 
    private data class SubtitleCandidate(
        val source: String,
        val langLabel: String,
        val url: String,
        val releaseHint: String
    )
 
    private fun extractImdbId(text: String): String =
        Regex("""(?i)\btt\d{7,10}\b""").find(text)?.value.orEmpty()
 
    private fun appendSubExtFragment(url: String, formatHint: String = "srt"): String {
        val lower = url.lowercase()
        if (lower.contains(".srt") || lower.contains(".vtt") || lower.contains(".ass") || lower.contains(".ssa")) {
            return url
        }
        val ext = when (formatHint.lowercase()) {
            "vtt", "webvtt" -> "vtt"
            "ass" -> "ass"
            "ssa" -> "ssa"
            else -> "srt"
        }
        return "$url#file.$ext"
    }
 
    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        for (k in tmdbHosts.indices) {
            val hi = (tmdbPrefer + k) % tmdbHosts.size
            val host = tmdbHosts[hi]
            for (apiKey in tmdbKeys) {
                val text = runCatching {
                    app.get(
                        "$host$path${sep}api_key=$apiKey",
                        headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
                        timeout = 10L
                    ).text
                }.getOrNull()
                if (!text.isNullOrBlank()) {
                    val json = runCatching { JSONObject(text) }.getOrNull()
                    if (json != null && !json.has("status_code")) {
                        tmdbPrefer = hi
                        return json
                    }
                    if (json?.optInt("status_code") == 34) return null
                }
            }
            val bearerText = runCatching {
                app.get(
                    "$host$path",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Authorization" to "Bearer $tmdbBearerToken",
                        "Accept" to "application/json"
                    ),
                    timeout = 10L
                ).text
            }.getOrNull()
            if (!bearerText.isNullOrBlank()) {
                val json = runCatching { JSONObject(bearerText) }.getOrNull()
                if (json != null && !json.has("status_code")) {
                    tmdbPrefer = hi
                    return json
                }
            }
        }
        return null
    }
 
    private fun normalizeSubtitleTitle(value: String): String = value
        .replace(Regex("""\b(19\d{2}|20\d{2})\b"""), " ")
        .replace(
            Regex("""\b(hindi|english|dubbed|dual audio|multi audio|uncut|full movie|watch online|movie|web series|series|season|complete|anime|cartoon|k drama)\b""", RegexOption.IGNORE_CASE),
            " "
        )
        .replace(Regex("""\b(s\d{1,2}|e\d{1,3}|480p|720p|1080p|2160p|4k|hdrip|webrip|web-dl|bluray)\b""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""[^a-zA-Z0-9]+"""), " ")
        .trim()
        .lowercase()
 
    private fun subtitleTitleScore(a: String, b: String): Double {
        val left = normalizeSubtitleTitle(a)
        val right = normalizeSubtitleTitle(b)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) {
            return minOf(left.length, right.length).toDouble() / maxOf(left.length, right.length).toDouble()
        }
        val la = left.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        val rb = right.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (la.isEmpty() || rb.isEmpty()) return 0.0
        return la.intersect(rb).size.toDouble() / la.union(rb).size.toDouble()
    }
 
    /** Tìm TMDB/IMDb ID khi HTML CineFreak không nhúng IMDb ID. */
    private suspend fun resolveSubtitleIdentity(
        title: String,
        year: Int?,
        type: String
    ): SubtitleIdentity? {
        if (title.isBlank()) return null
        val query = URLEncoder.encode(normalizeSubtitleTitle(title), "UTF-8")
        if (query.isBlank()) return null
        val yearParam = if (year != null) {
            if (type == "tv") "&first_air_date_year=$year" else "&year=$year"
        } else ""
        val search = tmdbGet("/search/$type?query=$query&include_adult=false&page=1$yearParam") ?: return null
        val results = search.optJSONArray("results") ?: return null
        data class TmdbCandidate(val id: Int, val score: Double, val year: Int?)
        val candidates = mutableListOf<TmdbCandidate>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id", 0)
            if (id <= 0) continue
            val candidateTitle = item.optString(if (type == "tv") "name" else "title", "")
            val date = item.optString(if (type == "tv") "first_air_date" else "release_date", "")
            val candidateYear = Regex("""^(\d{4})""").find(date)?.groupValues?.getOrNull(1)?.toIntOrNull()
            val score = subtitleTitleScore(title, candidateTitle)
            if (score < 0.62) continue
            if (year != null && candidateYear != null && kotlin.math.abs(year - candidateYear) > 1) continue
            candidates.add(TmdbCandidate(id, score, candidateYear))
        }
        candidates.sortWith(
            compareByDescending<TmdbCandidate> { it.score }
                .thenBy { candidate ->
                    val candidateYear = candidate.year
                    if (year == null || candidateYear == null) 0 else kotlin.math.abs(year - candidateYear)
                }
        )
 
        for (candidate in candidates.take(4)) {
            val details = tmdbGet("/$type/${candidate.id}?append_to_response=external_ids") ?: continue
            val imdbId = details.optString("imdb_id", "").ifBlank {
                details.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
            }
            return SubtitleIdentity(candidate.id, imdbId.takeIf { it.startsWith("tt") }.orEmpty())
        }
        return null
    }
 
    // Proxy loopback: chỉ tải và giải nén ZIP SubDL khi người xem thực sự chọn subtitle.
    private object CineFreakSubdlProxy {
        private data class Entry(
            val zipUrl: String,
            val isTv: Boolean,
            val season: Int?,
            val episode: Int?,
            val userAgent: String
        )
 
        private var serverSocket: ServerSocket? = null
        private var port: Int = -1
        private val sequence = AtomicInteger(0)
        private val entries = ConcurrentHashMap<String, Entry>()
        private val memoryCache = ConcurrentHashMap<String, Pair<String, ByteArray>>()
 
        @Synchronized
        fun ensureStarted(): Int {
            if (port > 0 && serverSocket?.isClosed == false) return port
            return runCatching {
                val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                port = server.localPort
                Thread {
                    while (!server.isClosed) {
                        try {
                            val client = server.accept()
                            Thread { handleClient(client) }.apply { isDaemon = true }.start()
                        } catch (_: Exception) {
                            if (server.isClosed) break
                        }
                    }
                }.apply { isDaemon = true }.start()
                port
            }.getOrDefault(-1)
        }
 
        fun register(
            zipUrl: String,
            isTv: Boolean,
            season: Int?,
            episode: Int?,
            userAgent: String
        ): String? {
            val localPort = ensureStarted()
            if (localPort <= 0) return null
            if (entries.size > 200) entries.clear()
            val token = sequence.incrementAndGet().toString()
            entries[token] = Entry(zipUrl, isTv, season, episode, userAgent)
            return "http://127.0.0.1:$localPort/subdl/$token.srt"
        }
 
        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0)?.uppercase() ?: "GET"
                val rawPath = parts.getOrNull(1) ?: ""
                val token = Regex("""/subdl/(\d+)\.srt""").find(rawPath)?.groupValues?.getOrNull(1)
                val entry = token?.let { entries[it] }
                val output = socket.getOutputStream()
                if (token == null || entry == null) {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                val resolved = memoryCache[token] ?: fetchAndUnzip(entry)?.also {
                    if (memoryCache.size >= 12) memoryCache.clear()
                    memoryCache[token] = it
                }
                if (resolved == null) {
                    output.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                val (mime, bytes) = resolved
                val headers = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mime\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
                output.write(headers.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") output.write(bytes)
                output.flush()
            } catch (_: Exception) {
            } finally {
                try { socket.close() } catch (_: Exception) { }
            }
        }
 
        private fun fetchAndUnzip(entry: Entry): Pair<String, ByteArray>? {
            val zipBytes = downloadBlocking(
                entry.zipUrl,
                mapOf("User-Agent" to entry.userAgent, "Referer" to "https://subdl.com/", "Accept" to "*/*")
            ) ?: return null
            if (zipBytes.isEmpty()) return null
            if (zipBytes.size >= 4 && zipBytes[0] == 0x50.toByte() && zipBytes[1] == 0x4B.toByte()) {
                val validExts = listOf(".srt", ".vtt", ".ass", ".ssa")
                val found = mutableListOf<Pair<String, ByteArray>>()
                ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                    var item = zis.nextEntry
                    while (item != null) {
                        val name = item.name ?: ""
                        val lower = name.lowercase()
                        if (!item.isDirectory && !lower.contains("__macosx/") && validExts.any { lower.endsWith(it) }) {
                            val bytes = zis.readBytes()
                            if (bytes.isNotEmpty()) found.add(name to bytes)
                        }
                        zis.closeEntry()
                        item = zis.nextEntry
                    }
                }
                if (found.isEmpty()) return null
                val best = found.sortedWith(
                    compareBy<Pair<String, ByteArray>>(
                        { if (entry.isTv && !matchesEpisodeHint(it.first, entry.season, entry.episode)) 1 else 0 },
                        {
                            when {
                                it.first.endsWith(".srt", true) -> 0
                                it.first.endsWith(".vtt", true) -> 1
                                else -> 2
                            }
                        }
                    )
                ).first()
                val mime = if (best.first.endsWith(".vtt", true)) {
                    "text/vtt; charset=utf-8"
                } else "application/x-subrip; charset=utf-8"
                return mime to best.second
            }
            val head = zipBytes.take(150).toByteArray().toString(Charsets.UTF_8).trimStart()
            if (head.startsWith("<!doctype", true) || head.startsWith("<html", true)) return null
            val mime = if (head.contains("WEBVTT", true)) "text/vtt; charset=utf-8" else "application/x-subrip; charset=utf-8"
            return mime to zipBytes
        }
 
        private fun matchesEpisodeHint(fileName: String, season: Int?, episode: Int?): Boolean {
            if (season == null || episode == null) return true
            val lower = fileName.lowercase()
            val s2 = season.toString().padStart(2, '0')
            val e2 = episode.toString().padStart(2, '0')
            return listOf(
                "s${s2}e${e2}", "s${season}e${e2}", "s${s2}e${episode}",
                "${season}x${e2}", "${s2}x${e2}", "episode.${e2}", "episode_${e2}", "ep${e2}"
            ).any { lower.contains(it) }
        }
 
        private fun downloadBlocking(url: String, headers: Map<String, String>): ByteArray? {
            var current = url
            for (hop in 0..3) {
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(current).openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 12_000
                        readTimeout = 12_000
                        requestMethod = "GET"
                        headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location")?.trim()
                        if (!location.isNullOrBlank()) {
                            current = if (location.startsWith("http", true)) location else URL(URL(current), location).toString()
                            continue
                        }
                    }
                    if (code in 200..299) return connection.inputStream.use { it.readBytes() }
                    return null
                } catch (_: Exception) {
                    return null
                } finally {
                    connection?.disconnect()
                }
            }
            return null
        }
    }
 
    private suspend fun queryOpenSubtitlesRest(
        ref: SubtitleMediaRef,
        imdbId: String
    ): List<SubtitleCandidate> {
        if (imdbId.isBlank()) return emptyList()
        val cleanId = imdbId.removePrefix("tt").trimStart('0').ifEmpty { imdbId.removePrefix("tt") }
        val headers = mapOf("User-Agent" to "trailers.to-UA", "X-User-Agent" to "trailers.to-UA")
        return listOf("vie" to "Vietnamese", "eng" to "English").amap { (langCode, fallback) ->
            val out = mutableListOf<SubtitleCandidate>()
            runCatching {
                val url = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                    "https://rest.opensubtitles.org/search/episode-${ref.episode}/imdbid-$cleanId/season-${ref.season}/sublanguageid-$langCode"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$langCode"
                }
                val text = app.get(url, headers = headers, timeout = 8L).text.trim()
                if (!text.startsWith("[")) return@runCatching
                val array = JSONArray(text)
                val items = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .sortedBy { item -> if (item.optString("SubFormat", "").lowercase() in listOf("srt", "vtt")) 0 else 1 }
                for (item in items.take(6)) {
                    val raw = item.optString("SubDownloadLink", "").trim()
                    if (!raw.startsWith("http")) continue
                    val direct = raw.replace("/filead/", "/subencoding-utf8/filead/")
                        .replace("/file/", "/subencoding-utf8/file/").removeSuffix(".gz")
                    out.add(
                        SubtitleCandidate(
                            "OpenSubtitles",
                            item.optString("LanguageName", fallback).trim(),
                            appendSubExtFragment(direct, item.optString("SubFormat", "srt")),
                            item.optString("MovieReleaseName", "").trim()
                        )
                    )
                }
            }
            out
        }.flatten()
    }
 
    private suspend fun queryOpenSubtitlesStremio(
        ref: SubtitleMediaRef,
        imdbId: String
    ): List<SubtitleCandidate> {
        if (imdbId.isBlank()) return emptyList()
        val url = if (ref.type == "tv") {
            "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:${ref.season ?: 1}:${ref.episode ?: 1}.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
        }
        val output = mutableListOf<SubtitleCandidate>()
        runCatching {
            val text = app.get(url, headers = mapOf("User-Agent" to userAgent), timeout = 8L).text.trim()
            if (!text.startsWith("{")) return@runCatching
            val array = JSONObject(text).optJSONArray("subtitles") ?: return@runCatching
            val vi = mutableListOf<SubtitleCandidate>()
            val en = mutableListOf<SubtitleCandidate>()
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val subUrl = item.optString("url", "").trim()
                if (!subUrl.startsWith("http")) continue
                val lang = item.optString("lang", "").lowercase()
                val release = item.optString("releaseGroup", "").ifBlank {
                    item.optString("movieReleaseName", "")
                }.trim()
                val candidate = SubtitleCandidate(
                    "OpenSubtitles-V3", if (lang == "vie" || lang == "vi") "Vietnamese" else "English",
                    appendSubExtFragment(subUrl), release
                )
                when (lang) {
                    "vie", "vi" -> if (vi.size < 5) vi.add(candidate)
                    "eng", "en" -> if (en.size < 5) en.add(candidate)
                }
            }
            output.addAll(vi)
            output.addAll(en)
        }
        return output
    }
 
    private suspend fun querySubdl(
        ref: SubtitleMediaRef,
        imdbId: String,
        apiKey: String,
        d: Dbg
    ): List<SubtitleCandidate> {
        if (apiKey.isBlank() || (imdbId.isBlank() && ref.tmdbId <= 0)) return emptyList()
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val idParam = if (imdbId.startsWith("tt")) {
            "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}"
        } else "tmdb_id=${ref.tmdbId}"
        val queryUrl = if (ref.type == "tv") {
            "https://api.subdl.com/api/v1/subtitles?api_key=$encodedKey&$idParam&type=tv&season_number=${ref.season ?: 1}&episode_number=${ref.episode ?: 1}&languages=VI,EN"
        } else {
            "https://api.subdl.com/api/v1/subtitles?api_key=$encodedKey&$idParam&type=movie&languages=VI,EN"
        }
        val output = mutableListOf<SubtitleCandidate>()
        runCatching {
            val response = app.get(
                queryUrl,
                headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
                timeout = 10L
            )
            if (response.code !in 200..299) {
                d.log("SubDL HTTP ${response.code}: ${response.text.take(120)}")
                return@runCatching
            }
            val json = JSONObject(response.text)
            if (!json.optBoolean("status", false)) {
                d.log("SubDL status=false: ${json.optString("error")}")
                return@runCatching
            }
            val subtitles = json.optJSONArray("subtitles") ?: return@runCatching
            val viItems = mutableListOf<JSONObject>()
            val enItems = mutableListOf<JSONObject>()
            for (i in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(i) ?: continue
                val language = item.optString("language", "").lowercase()
                if (language.contains("vietnam") || language == "vi" || language == "vie") viItems.add(item)
                else if (language.contains("english") || language == "en" || language == "eng") enItems.add(item)
            }
            val groups = listOf(
                "Vietnamese" to viItems.sortedBy { if (it.optBoolean("hi", false)) 1 else 0 }.take(5),
                "English" to enItems.sortedBy { if (it.optBoolean("hi", false)) 1 else 0 }.take(5)
            )
            for ((language, items) in groups) {
                for (item in items) {
                    val rawPath = item.optString("url", "").trim()
                    if (rawPath.isBlank()) continue
                    val zipUrl = when {
                        rawPath.startsWith("http", true) -> rawPath
                        rawPath.startsWith("/") -> "https://dl.subdl.com$rawPath"
                        else -> "https://dl.subdl.com/$rawPath"
                    }
                    val localUrl = CineFreakSubdlProxy.register(
                        zipUrl, ref.type == "tv", ref.season, ref.episode, userAgent
                    ) ?: continue
                    val release = item.optString("release_name", "").ifBlank {
                        item.optString("name", "")
                    }.trim()
                    output.add(SubtitleCandidate("SubDL", language, localUrl, release))
                }
            }
        }.onFailure { d.err("querySubdl", it) }
        return output
    }
 
    private suspend fun fetchSubtitleList(
        ref: SubtitleMediaRef,
        imdbId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        d: Dbg
    ): Int {
        val results = listOf(0, 1, 2).amap { index ->
            when (index) {
                0 -> queryOpenSubtitlesRest(ref, imdbId)
                1 -> queryOpenSubtitlesStremio(ref, imdbId)
                else -> querySubdl(ref, imdbId, defaultSubdlApiKey, d)
            }
        }.flatten()
        val seen = HashSet<String>()
        var count = 0
        for (candidate in results) {
            if (!seen.add(candidate.url)) continue
            val label = "${candidate.langLabel} • ${candidate.source}" +
                if (candidate.releaseHint.isNotBlank()) " (${candidate.releaseHint.take(28)})" else ""
            subtitleCallback.invoke(SubtitleFile(label, candidate.url))
            count++
        }
        d.log("Đã nạp $count phụ đề OpenSubtitles/SubDL (IMDb=${imdbId.ifBlank { "không có" }}, TMDB=${ref.tmdbId})")
        return count
    }
 
    /** Nạp danh sách phụ đề dựa trên metadata đã gắn vào payload ở bước load(). */
    private suspend fun loadSubtitlesFromPayload(
        parts: List<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        d: Dbg
    ): Int {
        val episodeKey = parts.getOrNull(1).orEmpty()
        val pageUrl = parts.getOrNull(2).orEmpty()
        var title = parts.getOrNull(4).orEmpty()
        var year = parts.getOrNull(5)?.toIntOrNull()
        var imdbId = parts.getOrNull(6).orEmpty().let { extractImdbId(it) }
 
        // Payload của bản CineFreak cũ chỉ có 4 trường; đọc lại HTML để lấy metadata khi cần.
        if (pageUrl.startsWith("http") && title.isBlank()) {
            val html = runCatching {
                app.get(pageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"), timeout = 12L).text
            }.getOrDefault("")
            if (html.isNotBlank()) {
                val doc = Jsoup.parse(html, pageUrl)
                title = cleanTitle(
                    doc.selectFirst("meta[property=og:title]")?.attr("content")
                        ?: doc.selectFirst("h1.entry-title, h1, .post-title")?.text().orEmpty()
                )
                if (year == null) year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.value?.toIntOrNull()
                if (imdbId.isBlank()) imdbId = extractImdbId(html)
            }
        }
 
        if (title.isBlank() && imdbId.isBlank()) {
            d.log("Bỏ qua tra cứu phụ đề: payload cũ không có title/IMDb ID và không đọc lại được trang phim")
            return 0
        }
 
        val episodeMatch = Regex("""(?i)^e(\d+)_(\d+)""").find(episodeKey)
            ?: Regex("""(?i)^s(\d+)e(\d+)""").find(episodeKey)
        val isTv = episodeMatch != null || isSeries(title, pageUrl)
        val type = if (isTv) "tv" else "movie"
        val season = episodeMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: if (isTv) 1 else null
        val episode = episodeMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: if (isTv) 1 else null
 
        var tmdbId = 0
        if (imdbId.isBlank()) {
            val identity = resolveSubtitleIdentity(title, year, type)
            if (identity != null) {
                tmdbId = identity.tmdbId
                imdbId = identity.imdbId
                d.log("TMDB match: id=$tmdbId IMDb=${imdbId.ifBlank { "không có" }}")
            } else {
                d.log("Không tìm được TMDB/IMDb ID cho '$title'${year?.let { " ($it)" } ?: ""}")
            }
        }
 
        val ref = SubtitleMediaRef(type, tmdbId, season, episode)
        return fetchSubtitleList(ref, imdbId, subtitleCallback, d)
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
        callback: (ExtractorLink) -> Unit,
        fixedHeaders: Map<String, String>? = null
    ): Map<String, String> {
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
        var chosen: Map<String, String>? = fixedHeaders
        if (chosen == null) {
            for ((tag, h) in variants) {
                if (probe(d, tag, url, h)) { chosen = h; d.log("   -> dùng header kiểu '$tag'"); break }
            }
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
        return use
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

            val cookieHeader = jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val baseHeaders = mapOf("Referer" to url, "User-Agent" to userAgent) +
                (if (cookieHeader.isNotEmpty()) mapOf("Cookie" to cookieHeader) else emptyMap())

            // ── phụ đề ──
            val subtitles = root.path("subtitleTracks")
            d.log("C1) subtitleTracks=${subtitles.toString().replace(Regex("\"url\":\"([^\"]{0,70})[^\"]*\""), "\"url\":\"$1...\"").take(500)}")
            val subHeaders = baseHeaders
            val seenSub = HashSet<String>()
            for (i in 0 until subtitles.size()) {
                val item = subtitles[i]
                val subUrl = absolute(item.path("url").asText(""), url)
                if (subUrl.isBlank() || !seenSub.add(subUrl)) continue
                val code = item.path("language").asText("").ifBlank { item.path("lang").asText("") }
                val label = item.path("label").asText("").ifBlank {
                    java.util.Locale.getAvailableLocales().firstOrNull { l ->
                        runCatching { l.isO3Language.equals(code, true) }.getOrDefault(false) || l.language.equals(code, true)
                    }?.displayLanguage?.takeIf { it.isNotBlank() } ?: code.ifBlank { "Subtitle" }
                }
                runCatching {
                    subtitleCallback(newSubtitleFile(label, subUrl) { this.headers = subHeaders })
                }.onFailure {
                    d.err("newSubtitleFile", it)
                    runCatching { subtitleCallback(SubtitleFile(label, subUrl)) }
                }
                d.log("C1) phụ đề: $label -> ${subUrl.take(90)}")
            }

            // ── tất cả độ phân giải ──
            val list = ArrayList<Pair<String, Int>>() // url, quality
            val resolutionNodes = root.path("resolutions")
            for (i in 0 until resolutionNodes.size()) {
                val item = resolutionNodes[i]
                val u = absolute(item.path("url").asText(""), url)
                if (u.isNotBlank() && list.none { it.first == u }) {
                    list.add(u to (firstInt(item.path("quality").asText("")) ?: 0))
                }
            }
            if (list.none { it.first == videoUrl }) list.add(0, videoUrl to 0)
            list.sortByDescending { it.second }
            d.log("C1) ${list.size} độ phân giải: ${list.map { it.second }}")

            val hevcSet = HashSet<String>()
            root.path("hevc").forEach { hevcSet.add(it.asText("")) }
            var quality = 0
            var chosenHeaders: Map<String, String>? = null
            for ((u, q) in list) {
                if (quality == 0) quality = q
                val label = "Vidzer${if (q > 0) " ${q}p" else ""}${if (u in hevcSet) " HEVC" else ""}"
                chosenHeaders = emit(d, u, url, label, q, baseHeaders, callback, chosenHeaders)
            }
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
        var subtitleCount = 0
        try {
            if (pageUrl.startsWith("http")) {
                try {
                    subtitleCount = loadSubtitlesFromPayload(parts, subtitleCallback, d)
                } catch (subtitleError: Throwable) {
                    if (subtitleError is java.util.concurrent.CancellationException) throw subtitleError
                    d.err("nạp phụ đề", subtitleError)
                }
            }
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
            d.log("KẾT QUẢ: thất bại - $reason; phụ đề đã nạp=$subtitleCount")
            d.copy(reason)
        } else {
            d.log("KẾT QUẢ: có link; phụ đề đã nạp=$subtitleCount")
            if (probeFailed || copyLogOnSuccess) d.copy(if (probeFailed) "link phát có thể lỗi" else "thành công (log kiểm tra)")
        }
        return found
    }
}
