package com.nguontv

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale


private const val TAG = "NguonTV"

/**
 * true  = khi không tìm thấy phụ đề nào, tự chép log chẩn đoán vào clipboard (để gửi cho người phát triển).
 * false = tắt, chỉ ghi vào logcat. Đặt false khi đã hoàn thiện phần phụ đề.
 */
private const val COPY_LOG_WHEN_NO_SUB = true

/** true = chép log vào clipboard sau MỖI lần phát (tạm thời, để chẩn đoán việc tải chậm / phát lệch vị trí). Đặt false khi xong. */
private const val COPY_LOG_ALWAYS = true

/** Máy chủ HTTP nhỏ trên 127.0.0.1 để đưa playlist / khóa / phụ đề đã xử lý cho trình phát. */
private object LocalPlaylist {
    private val store = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val bin = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private val types = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var server: java.net.ServerSocket? = null

    private fun ensure(): java.net.ServerSocket =
        server ?: java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also {
            server = it
            start(it)
        }

    @Synchronized
    fun bytes(data: ByteArray, ext: String = "bin", ctype: String = "application/octet-stream"): String {
        val s = ensure()
        val id = "key_${System.nanoTime()}.$ext"
        bin[id] = data
        types[id] = ctype
        return "http://127.0.0.1:${s.localPort}/$id"
    }

    @Synchronized
    fun url(text: String): String {
        val s = ensure()
        val id = "pl_${System.nanoTime()}.m3u8"
        store[id] = text
        return "http://127.0.0.1:${s.localPort}/$id"
    }

    private fun start(s: java.net.ServerSocket) {
        Thread {
            while (!s.isClosed) {
                val c = try { s.accept() } catch (e: Exception) { break }
                Thread { handle(c) }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handle(c: java.net.Socket) {
        runCatching {
            c.use {
                val r = c.getInputStream().bufferedReader()
                val line = r.readLine() ?: return
                while (true) {
                    val h = r.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val path = line.split(" ").getOrNull(1)?.trimStart('/')?.substringBefore('?')
                val binBody = path?.let { bin[it] }
                val body = binBody ?: path?.let { store[it] }?.toByteArray()
                val ctype = if (binBody != null) (types[path] ?: "application/octet-stream") else "application/vnd.apple.mpegurl"
                val out = c.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: $ctype\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    if (!line.startsWith("HEAD")) out.write(body)
                }
                out.flush()
            }
        }
    }
}

class NguonTVProvider : MainAPI() {
    override var mainUrl = "https://nguontv.life"
    override var name = "NguonTV"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    // Cookie phiên của site. Site trả trang "NP Checker" (đặt cookie rồi tự chuyển về trang thật
    // sau 1 giây) cho client chưa có cookie, nên phải giữ cookie và gọi lại như trình duyệt.
    private var sessionCookies: Map<String, String> = emptyMap()

    private suspend fun fetch(url: String, extra: Map<String, String> = emptyMap()) = run {
        val h = headers + extra
        var r = app.get(url, headers = h, cookies = sessionCookies)
        var tries = 0
        while (tries < 2 && r.text.contains("NP Checker")) {
            sessionCookies = sessionCookies + r.cookies
            Thread.sleep(1100)
            r = app.get(url, headers = h, cookies = sessionCookies)
            tries++
        }
        if (r.cookies.isNotEmpty()) sessionCookies = sessionCookies + r.cookies
        r
    }

    private suspend fun postForm(url: String, data: Map<String, String>, extra: Map<String, String> = emptyMap()) = run {
        if (sessionCookies.isEmpty()) fetch("$mainUrl/")
        app.post(url, data = data, headers = headers + extra, cookies = sessionCookies)
    }

    private data class FilmItem(val title: String, val url: String, val poster: String?)

    private fun absolute(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }

    private fun decodeHtml(value: String): String = Jsoup.parse(value).text().trim()

    /**
     * Lấy danh sách phim ở trang chủ NguonTV để hiển thị trong Home của CloudStream.
     * NguonTV hiện cung cấp các mục phim ngay trên trang chủ; chưa giả định URL phân trang
     * vì cấu trúc phân trang chưa được xác nhận từ HTML/HAR đã cung cấp.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

        // Do not send the AJAX header on normal page requests.
        val res = try {
            fetch("$mainUrl/")
        } catch (e: Exception) {
            Log.d(TAG, "lỗi tải trang chủ: ${e.javaClass.simpleName} ${e.message}")
            return newHomePageResponse(emptyList(), hasNext = false)
        }
        val doc = res.document
        val items = doc.select(".categories-file-index .item-file-index, .item-file-index").mapNotNull { item ->
            val link = item.selectFirst(".info-item-file-index h3 a[href*='-f']")
                ?: item.selectFirst(".img-item-file-index a[href*='-f']")
                ?: item.selectFirst("a[href*='-f']")
                ?: return@mapNotNull null

            val href = link.attr("abs:href").ifBlank { absolute(link.attr("href")) ?: "" }
            if (href.isBlank() || !Regex("-f\\d+\\.html", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
                return@mapNotNull null
            }

            val title = link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val image = item.selectFirst(".img-item-file-index img, img.hover-img")
            val poster = image?.let {
                absolute(it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } })
            }
            FilmItem(title, href, poster)
        }.distinctBy { it.url }

        val responses = items.map { item ->
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                posterUrl = item.poster
            }
        }

        if (responses.isEmpty()) {
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val homePage = mutableListOf<HomePageList>()
        if (responses.isNotEmpty()) homePage.add(HomePageList("Phim Hot / Phim mới", responses))
        return newHomePageResponse(homePage, hasNext = false)
    }

    private suspend fun searchItems(query: String): List<FilmItem> {
        if (query.isBlank()) return emptyList()

        val response = postForm(
            "$mainUrl/tim-kiem-a.html",
            mapOf("q" to query.trim(), "t" to "film"),
            mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        // Endpoint normally returns {"code":200,"html":"..."}.
        // Fall back to parsing raw HTML if a proxy/server returns the fragment directly.
        val html = try {
            val json = JSONObject(response)
            if (json.optInt("code", 200) != 200) return emptyList()
            json.optString("html", "")
        } catch (_: Exception) {
            response
        }
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parse(html, "$mainUrl/")
        // The HAR response contains <li class="result-item"><a href="/...-f123.html">.
        // Parse anchors directly instead of relying on a particular list-item class.
        val items = doc.select("a[href]").mapNotNull { link ->
            val rawHref = link.attr("href").trim()
            val href = link.attr("abs:href").ifBlank { absolute(rawHref) ?: "" }
            if (href.isBlank() ||
                !Regex("-f\\d+\\.html(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(href)
            ) return@mapNotNull null

            val title = link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val image = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
            val poster = image?.let {
                val src = it.attr("data-src").ifBlank {
                    it.attr("data-original").ifBlank { it.attr("src") }
                }
                absolute(src)
            }
            FilmItem(title, href.substringBefore('#'), poster)
        }.distinctBy { it.url }

        return items
    }

    override suspend fun search(query: String): List<SearchResponse> = searchItems(query).map { item ->
        newMovieSearchResponse(item.title, item.url, TvType.Movie) {
            this.posterUrl = item.poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query) ?: emptyList()

    private fun parseTitle(doc: Document, fallback: String): String {
        return doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" - NguồnTV").trim().ifBlank { fallback }
    }

    private fun episodeNumber(url: String): Int? = Regex("-(\\d+)-e\\d+\\.html", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absolute(url) ?: return null
        val doc = fetch(pageUrl).document
        val title = parseTitle(doc, doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "NguonTV")
        val poster = absolute(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".film-poster img, .movie-poster img, .detail-poster img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".film-content, .description, .movie-description")?.text()
        val episodeLinks = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { absolute(a.attr("href")) ?: "" }
            val num = episodeNumber(href) ?: return@mapNotNull null
            val label = a.text().trim().ifBlank { "Tập $num" }
            Triple(num, href, label)
        }.distinctBy { it.second }.sortedBy { it.first }

        val isSeries = episodeLinks.isNotEmpty() || doc.select(".list-episode, .episode-list, .episodes-list, [class*=episode]").isNotEmpty()
        if (isSeries && episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.map { (num, href, label) ->
                // Preserve both the public detail page (for its dynamic key) and the episode POST URL.
                newEpisode("$pageUrl||$href") {
                    name = label
                    season = 1
                    episode = num
                }
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // The site's film detail URL is /slug-f123.html; its player endpoint is /xem-phim/slug-f123.html.
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, "$pageUrl||${toPlayerUrl(pageUrl)}") {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun toPlayerUrl(url: String): String {
        if (url.contains("/xem-phim/")) return url
        val path = url.substringAfter(mainUrl).trimStart('/')
        return "$mainUrl/xem-phim/$path"
    }

    private data class PlayerState(val fid: String, val time: String, val key: String)

    // Trang phát của tập có đoạn JS: npPhim = { key: 'xxx', ... } và
    // $.ajax({ data: { 'fid':74165, 'time':1792322811, 'key': this.getKey(), ... } })
    private fun extractPlayerState(doc: Document, pageUrl: String): PlayerState? {
        val source = doc.html()
        val fid = Regex("['\"]fid['\"]\\s*[:=]\\s*['\"]?(\\d+)", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
            ?: Regex("-f(\\d+)(?:-|\\.html)").find(pageUrl)?.groupValues?.get(1)
            ?: return null
        val time = Regex("['\"]time['\"]\\s*[:=]\\s*['\"]?(\\d{9,})", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
            ?: (System.currentTimeMillis() / 1000L).toString()
        // Chỉ nhận dạng  key: 'xxx'  (dấu hai chấm). Không nhận jwplayer.key="..." (khóa bản quyền JW Player).
        val key = Regex("(?<![.\\w])['\"]?key['\"]?\\s*:\\s*['\"]([A-Za-z0-9+/=_-]{4,})['\"]")
            .find(source)?.groupValues?.get(1)
            ?: return null
        return PlayerState(fid, time, key)
    }

    // Header giống lời gọi $.ajax của trang phát
    private fun xhrHeaders(referer: String) = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer
    )

    private fun findEmbedUrl(html: String): String? {
        val decoded = html.replace("\\/", "/").replace("\\u0026", "&")
        return Regex("https?://grab\\.nguontv\\.life/embed/[^\\\"'<>\\s]+", RegexOption.IGNORE_CASE)
            .find(decoded)?.value?.replace("&amp;", "&")
    }

    private fun findHls(html: String): String? {
        val decoded = html.replace("\\/", "/").replace("\\u0026", "&")
        return Regex("https?://[^\\\"'<>\\s]+\\.m3u8(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
            .find(decoded)?.value?.replace("&amp;", "&")
    }


    // ================= Phụ đề =================

    private class Sub(var lang: String, val label: String, val url: String, val ref: String) {
        var text: String? = null
    }

    private val subs = java.util.concurrent.CopyOnWriteArrayList<Sub>()
    private val subHints = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val viRe = Regex("""(?<![a-z])(vi|vie|vn|vnm|viet|vietsub|vietnam|vietnamese|tiếng việt|tieng viet|việt|viet nam)(?![a-z])""")
    private val enRe = Regex("""(?<![a-z])(en|eng|engsub|english|anh|tiếng anh|tieng anh)(?![a-z])""")
    private val subExtRe = Regex("""https?://[^\s"'<>\\]+?\.(?:vtt|srt|ass|ssa|ttml)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
    private val notSubRe = Regex("""(?i)thumb|sprite|storyboard|preview|chapter""")

    private fun langOf(s: String): String? {
        val x = s.lowercase()
        return when {
            viRe.containsMatchIn(x) -> "Vietnamese"
            enRe.containsMatchIn(x) -> "English"
            else -> null
        }
    }

    // Nhãn quyết định trước; nếu không có nhãn thì đoán theo đường dẫn URL (không xét tên miền)
    private fun subLang(label: String, url: String): String? =
        langOf(label) ?: if (label.isBlank()) langOf(url.substringAfter("://").substringAfter('/')) else null

    private fun hlsAttr(line: String, name: String): String {
        val m = Regex("""(?<![A-Z-])$name=(?:"([^"]*)"|([^,]*))""").find(line) ?: return ""
        return m.groupValues[1].ifEmpty { m.groupValues[2] }
    }

    private fun setHlsFlag(l: String, k: String, v: String): String {
        val re = Regex("""(?<=[:,])$k=(YES|NO)(?=,|$)""")
        return if (re.containsMatchIn(l)) re.replace(l, "$k=$v") else "$l,$k=$v"
    }

    /** Trong master playlist: đặt phụ đề tiếng Việt (nếu không có thì tiếng Anh) làm mặc định. */
    private fun prioritizeSubs(master: String): String {
        val lines = master.lines()
        val subIdx = lines.indices.filter { lines[it].startsWith("#EXT-X-MEDIA") && hlsAttr(lines[it], "TYPE") == "SUBTITLES" }
        if (subIdx.isEmpty()) return master
        fun lg(l: String) = langOf(hlsAttr(l, "NAME") + " " + hlsAttr(l, "LANGUAGE"))
        val best = subIdx.firstOrNull { lg(lines[it]) == "Vietnamese" } ?: subIdx.firstOrNull { lg(lines[it]) == "English" }
        return lines.mapIndexed { i, l ->
            if (i in subIdx) {
                val isBest = i == best
                setHlsFlag(setHlsFlag(l, "DEFAULT", if (isBest) "YES" else "NO"), "AUTOSELECT", "YES")
            } else l
        }.joinToString("\n")
    }

    private fun enclosingObject(t: String, range: IntRange): String {
        val s = t.lastIndexOf('{', range.first)
        val e = t.indexOf('}', range.last)
        return if (s >= 0 && e > s && e - s < 700) t.substring(s, e + 1) else ""
    }

    /**
     * Quét một đoạn văn bản (HTML trang phát, trang embed, JSON phản hồi, chuỗi base64 đã giải...)
     * để tìm link phụ đề: JSON dạng {"file":"...vtt","label":"Vietnamese","kind":"captions"},
     * thẻ <track>, hoặc bất kỳ URL .vtt/.srt/.ass nào. Bỏ qua thumbnails/sprite/chapters.
     */
    private fun collectSubsFrom(raw: String, base: String, src: String, log: (String) -> Unit) {
        if (raw.isBlank()) return
        val t = unescapeJs(raw)
        var found = 0
        fun add(label: String, rawUrl: String) {
            val url = resolveUrl(rawUrl.trim(), base)
            if (!url.startsWith("http") || subs.any { it.url == url }) return
            if (notSubRe.containsMatchIn(url) || notSubRe.containsMatchIn(label)) return
            val lang = subLang(label, url)
            subs.add(Sub(lang ?: label.ifBlank { "Phụ đề" }, label, url, base))
            found++
            log("  sub[$src]: ${lang ?: label.ifBlank { "?" }} -> ${url.take(100)}")
        }

        // 1) Đối tượng JSON có kind captions/subtitles hoặc trỏ tới tệp phụ đề
        val jsonObj = Regex("""\{[^{}]*\}""")
        val kindOk = Regex("""(?i)"(?:kind|type)"\s*:\s*"(?:captions|subtitles?|subs?|text)"|\.(?:vtt|srt|ass|ssa|ttml)\b""")
        val kindBad = Regex("""(?i)"kind"\s*:\s*"(?:thumbnails|chapters|metadata)"""")
        val fileRe = Regex(""""(?:file|src|url|link|path)"\s*:\s*"([^"]+)"""")
        val labelRe = Regex(""""(?:label|name|title|lang|language|srclang)"\s*:\s*"([^"]{1,40})"""")
        for (m in jsonObj.findAll(t)) {
            val o = m.value
            if (!kindOk.containsMatchIn(o) || kindBad.containsMatchIn(o)) continue
            val u = fileRe.find(o)?.groupValues?.get(1) ?: continue
            val label = labelRe.findAll(o).joinToString(" ") { it.groupValues[1] }
            add(label, u)
        }

        // 2) Thẻ <track src=... label=... srclang=...>
        for (m in Regex("""<track\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(t)) {
            val tag = m.value
            val kind = Regex("""kind=["']([^"']+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.lowercase().orEmpty()
            if (kind == "metadata" || kind == "chapters" || kind == "thumbnails") continue
            val s = Regex("""src=["']([^"']+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1) ?: continue
            val label = listOf("label", "srclang").mapNotNull {
                Regex("""$it=["']([^"']+)""", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
            }.joinToString(" ")
            add(label, s)
        }

        // 3) URL .vtt/.srt/.ass bất kỳ
        for (m in subExtRe.findAll(t)) {
            val o = enclosingObject(t, m.range)
            if (kindBad.containsMatchIn(o)) continue
            val label = if (o.isNotEmpty()) labelRe.findAll(o).joinToString(" ") { it.groupValues[1] } else ""
            add(label, m.value)
        }

        if (found == 0 && subHints.size < 12) {
            Regex("""(?i)(subtitle|captions?|"tracks"|\.vtt|\.srt|phụ đề|phude|suburl|sub_url|subs?\s*[:=])""")
                .findAll(t).take(3).forEach {
                    val x = maxOf(0, it.range.first - 80)
                    subHints.add("  subhint[$src]> " + t.substring(x, minOf(t.length, it.range.last + 200)).replace(Regex("\\s+"), " "))
                }
        }
    }

    private fun unescapeJs(s: String): String {
        var t = s.replace("\\/", "/").replace("&amp;", "&").replace("\\\"", "\"")
        if (t.contains("\\u") || t.contains("\\x")) {
            t = Regex("""\\u([0-9a-fA-F]{4})""").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
            t = Regex("""\\x([0-9a-fA-F]{2})""").replace(t) { it.groupValues[1].toInt(16).toChar().toString() }
        }
        return t
    }

    // Quét cả các chuỗi base64 / percent-encoded giấu trong trang embed (JW Player dựng `tracks` từ JS)
    private fun deepCollect(html: String, base: String, src: String, log: (String) -> Unit) {
        collectSubsFrom(html, base, src, log)
        if (html.contains("%2F", true) || html.contains("%3A", true)) {
            runCatching { java.net.URLDecoder.decode(html, "UTF-8") }.getOrNull()
                ?.let { collectSubsFrom(it, base, "$src-url", log) }
        }
        val seen = HashSet<String>()
        for (m in Regex("""["'`]([A-Za-z0-9+/=_-]{24,})["'`]""").findAll(html)) {
            val s = m.groupValues[1]
            if (!seen.add(s)) continue
            val std = s.replace('-', '+').replace('_', '/')
            val dec = runCatching {
                String(android.util.Base64.decode(std + "=".repeat((4 - std.length % 4) % 4), android.util.Base64.DEFAULT))
            }.getOrNull() ?: continue
            if (dec.length < 12 || dec.count { it.code in 32..126 } < dec.length * 0.85) continue
            if (Regex("""(?i)srt|vtt|\.ass|subtitle|tracks|caption""").containsMatchIn(dec)) {
                collectSubsFrom(dec, base, "$src-b64", log)
            }
        }
    }

    // Đoán ngôn ngữ từ nội dung tệp phụ đề (tên tệp trên máy chủ không có mã ngôn ngữ, vd 446_1792525039_xxx.srt)
    private fun detectLang(t: String): String? {
        val body = t.lines().map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("-->") && !it.all { c -> c.isDigit() } && !it.startsWith("WEBVTT") }
            .take(400).joinToString(" ")
            .replace(Regex("<[^>]+>|\\{[^}]*\\}"), " ").lowercase()
        val letters = body.count { it.isLetter() }
        if (letters < 40) return null
        // Chỉ tính các chữ cái đặc trưng tiếng Việt (không tính à á è é... dùng chung với tiếng Pháp/Tây Ban Nha)
        val vi = body.count { it in "ăâđêôơưạảẩẫấầậắằẳẵặẹẻẽếềểễệỉĩịọỏốồổỗộớờởỡợụủũứừửữựỳỵỷỹ" }
        if (vi.toDouble() / letters > 0.04) return "Vietnamese"
        val en = Regex("""\b(the|you|and|to|is|that|it|of|what|this|are)\b""").findAll(body).count()
        return if (en >= 12) "English" else null
    }

    private suspend fun fetchSubText(s: Sub): String? {
        s.text?.let { return it }
        for (ref in listOf(s.ref, "$mainUrl/", "")) {
            val r = runCatching {
                app.get(s.url, referer = ref.ifBlank { null }, headers = mapOf("User-Agent" to ua))
            }.getOrNull() ?: continue
            if (r.code != 200) continue
            val t = r.text
            val head = t.trimStart()
            if (t.length < 10 || head.startsWith("<!DOCTYPE", true) || head.startsWith("<html", true)) continue
            s.text = t
            return t
        }
        return null
    }

    // Dự phòng: mở trang embed trong WebView, để JW Player tự chạy và gom mọi request .srt/.vtt/.ass.
    // JW Player tải phụ đề lúc khởi tạo, trước khi tải m3u8, nên dừng WebView khi gặp m3u8.
    private suspend fun viaWebViewSubs(embed: String, page: String, log: (String) -> Unit) {
        val subRe = Regex("""\.(?:srt|vtt|ass|ssa)(?:[?#]|$)""", RegexOption.IGNORE_CASE)
        val (hit, extra) = WebViewResolver(Regex("""\.m3u8"""), additionalUrls = listOf(subRe))
            .resolveUsingWebView(url = embed, referer = page, headers = emptyMap())
        log("  webview phụ đề: m3u8=${if (hit != null) "có" else "không"} request phụ đề=${extra.size}")
        for (u in extra.map { it.url.toString() }.filter { subRe.containsMatchIn(it) }.distinct().take(4)) {
            log("  webview phụ đề: $u")
            if (subs.none { it.url == u }) subs.add(Sub(subLang("", u) ?: "Phụ đề", "", u, embed))
        }
    }

    // Ghi lại các phần tử giao diện có vẻ liên quan phụ đề (nút chọn phụ đề...) để chẩn đoán
    private fun domSubHints(doc: Document?, src: String) {
        if (doc == null) return
        runCatching {
            doc.select("[class*=sub], [class*=Sub], [id*=sub], [id*=Sub], [class*=caption], [data-sub]")
                .filter { !it.outerHtml().contains("subscribe", true) && !it.outerHtml().contains("submenu", true) }
                .take(5).forEach {
                    if (subHints.size < 20) subHints.add("  domhint[$src]> " + it.outerHtml().replace(Regex("\\s+"), " ").take(260))
                }
        }
    }

    // Tải phụ đề về qua Referer đúng rồi phát qua máy chủ cục bộ, tránh lỗi chặn hotlink khi trình phát tải
    private suspend fun localizeSub(s: Sub): String {
        val ext = Regex("""\.(vtt|srt|ass|ssa|ttml)(?:\?|$)""", RegexOption.IGNORE_CASE)
            .find(s.url)?.groupValues?.get(1)?.lowercase() ?: "vtt"
        val ctype = when (ext) {
            "srt" -> "application/x-subrip"
            "ass", "ssa" -> "text/x-ssa"
            "ttml" -> "application/ttml+xml"
            else -> "text/vtt"
        }
        val t = fetchSubText(s) ?: return s.url
        return LocalPlaylist.bytes(t.toByteArray(Charsets.UTF_8), ext, ctype)
    }

    // Phát phụ đề cho CloudStream: tiếng Việt trước, rồi tiếng Anh. Nếu không có cả hai thì lấy tối đa 3 bản bất kỳ.
    private suspend fun emitSubs(subtitleCallback: (SubtitleFile) -> Unit, log: (String) -> Unit) {
        val all = subs.toList()
        if (all.isEmpty()) {
            log("PHỤ ĐỀ: không tìm thấy bản nào")
            subHints.forEach(log)
            return
        }
        // Bản chưa biết ngôn ngữ: tải về và đoán theo nội dung
        for (s in all.filter { it.lang != "Vietnamese" && it.lang != "English" }.take(6)) {
            val t = runCatching { fetchSubText(s) }.getOrNull() ?: continue
            detectLang(t)?.let {
                s.lang = it
                log("  sub nhận diện theo nội dung: $it <- ${s.url.takeLast(45)}")
            }
        }
        val pri = all.filter { it.lang == "Vietnamese" || it.lang == "English" }
        val chosen = (if (pri.isNotEmpty()) pri else all.take(3))
            .sortedBy { if (it.lang == "Vietnamese") 0 else if (it.lang == "English") 1 else 2 }
        val used = mutableMapOf<String, Int>()
        for (s in chosen) {
            val n = (used[s.lang] ?: 0) + 1
            used[s.lang] = n
            val nm = if (n == 1) s.lang else "${s.lang} $n"
            val u = runCatching { localizeSub(s) }.getOrNull() ?: s.url
            subtitleCallback.invoke(SubtitleFile(nm, u))
            log("PHỤ ĐỀ: $nm -> ${u.take(90)}")
        }
    }


    // ================= Lấy luồng phát từ trang embed (nhiều tầng dự phòng) =================

    private class Stream(
        val url: String?, val text: String?, val via: String, val ref: String? = null, val hdr: Map<String, String>? = null,
        val token: String? = null, val streamUrl: String? = null, val mp4: Boolean = false,
        // (chiều cao, bandwidth, URL playlist cục bộ) của từng chất lượng, dùng để thêm link chọn tay
        val variants: List<Triple<Int, Long, String>> = emptyList()
    )

    private class Variant(val bw: Long, val height: Int, val url: String)

    private val embedCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var tStart = 0L
    private fun el() = System.currentTimeMillis() - tStart

    private suspend fun embedHtml(embed: String, page: String): String =
        embedCache[embed] ?: app.get(embed, referer = page, headers = mapOf("User-Agent" to ua)).text
            .also { embedCache[embed] = it }

    private fun segHeaders(embed: String): Map<String, String> {
        val base = mapOf(
            "User-Agent" to ua,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-us,en;q=0.5",
            "Sec-Fetch-Mode" to "navigate"
        )
        return if (embed.isBlank()) base else base + ("Referer" to embed)
    }

    private fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.get(1).orEmpty()

    // Thống kê nhanh playlist: số đoạn, tổng thời lượng, có ENDLIST (VOD) hay không
    private fun plStats(t: String): String {
        val ls = t.lines()
        val inf = ls.filter { it.startsWith("#EXTINF") }
        val dur = inf.sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }
        val type = Regex("#EXT-X-PLAYLIST-TYPE:(\\w+)").find(t)?.groupValues?.get(1) ?: "-"
        val seq = Regex("#EXT-X-MEDIA-SEQUENCE:(\\d+)").find(t)?.groupValues?.get(1) ?: "-"
        val keys = Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""").findAll(t).map { it.groupValues[1] }.distinct().count()
        return "đoạn=${inf.size} dài=${dur.toInt()}s endlist=${t.contains("#EXT-X-ENDLIST")} type=$type seq=$seq khóa=$keys"
    }

    private fun looksLikePlaylist(t: String?): Boolean =
        t != null && t.trimStart().startsWith("#EXTM3U") && t.lines().any { it.isNotBlank() && !it.startsWith("#") }

    private fun hashOf(embed: String): String =
        Regex("""[?&]hash=([0-9a-fA-F]+)""").find(embed)?.groupValues?.get(1).orEmpty()

    private fun absolutize(text: String, base: String): String {
        val origin = originOf(base)
        val dir = base.substringBefore('?').substringBeforeLast('/') + "/"
        return text.lines().joinToString("\n") { line ->
            val l = line.trim()
            when {
                l.isEmpty() || l.startsWith("#") || l.startsWith("http") -> line
                l.startsWith("/") -> origin + l
                else -> dir + l
            }
        }
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)

    private fun gcm(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun decryptPlaylist(text: String, videoHash: String): String? {
        if (!text.contains("#ENC-AESGCM")) return text
        val iv = Regex("""iv=([0-9a-fA-F]+)""").find(text)?.groupValues?.get(1) ?: return null
        val line = text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("stream-derive-v1".toByteArray(), "HmacSHA256"))
        val key = mac.doFinal(videoHash.toByteArray())
        return String(gcm(key, hex(iv), android.util.Base64.decode(line, android.util.Base64.DEFAULT), null))
    }

    private suspend fun direct(
        url: String, embed: String, via: String, hashes: List<String> = emptyList(),
        ref: String = embed, log: (String) -> Unit = {}
    ): Stream? {
        val r = app.get(url, referer = ref.ifBlank { null }, headers = segHeaders(ref), timeout = 30L)
        val body = r.text
        log("  GET ${url.take(70)} ref=${ref.take(30)} code=${r.code} đầu=${body.trimStart().take(60).replace(Regex("\\s+"), " ")}")
        if (r.code != 200) return null
        if (body.contains("#ENC-AESGCM")) {
            for (h in (hashes + hashOf(embed)).filter { it.isNotBlank() }.distinct()) {
                val plain = runCatching { decryptPlaylist(body, h) }.getOrNull()
                if (looksLikePlaylist(plain)) return Stream(null, absolutize(plain!!, url), "$via+giải mã", ref)
            }
            return null
        }
        return if (looksLikePlaylist(body)) Stream(url, null, via, ref) else null
    }

    private suspend fun viaBootstrap(embed: String, page: String, log: (String) -> Unit = {}): Stream? {
        val origin = originOf(embed)
        val shellText = embedHtml(embed, page)
        val cfg = Regex("""<script id="stream-bootstrap"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(shellText)?.groupValues?.get(1) ?: return null
        val api = JSONObject(cfg).optString("api").takeIf { it.isNotBlank() } ?: return null
        val body = JSONObject().apply {
            put("action", "bootstrap")
            put("referrer", page)
            put("frame_origins", org.json.JSONArray().put(mainUrl).apply {
                val po = originOf(page)
                if (po.isNotBlank() && po != mainUrl) put(po)
            })
            put("request_grant", true)
            put("playlist_format", "aesgcm-v2")
            put("pretty_url", true)
            put("path_chunks", true)
            put("bootstrap_format", "aesgcm-v1")
        }.toString()
        val resp = app.post(
            api,
            requestBody = body.toRequestBody("application/json".toMediaType()),
            headers = mapOf("Origin" to origin, "Referer" to embed, "User-Agent" to ua, "Accept" to "*/*")
        )
        if (resp.code != 200) return null
        var j = JSONObject(resp.text)
        if (j.optString("format") == "aesgcm-v1") {
            val aad = "stream-bootstrap-v1\n$api".toByteArray()
            val plain = gcm(
                sha256(aad), hex(j.getString("iv")),
                android.util.Base64.decode(j.getString("data"), android.util.Base64.DEFAULT), aad
            )
            j = JSONObject(String(plain))
        }
        runCatching { collectSubsFrom(j.toString(), embed, "bootstrap", log) }
        val video = j.optString("video").ifBlank { hashOf(embed) }
        val playlist = j.optJSONObject("preissued")?.optString("playlist")?.takeIf { it.isNotBlank() } ?: return null
        return direct(playlist, embed, "bootstrap", listOf(video))
    }

    private suspend fun viaDataObf(embed: String, page: String): Stream? {
        val origin = originOf(embed)
        val html = embedHtml(embed, page)
        val obf = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val j = JSONObject(String(android.util.Base64.decode(obf, android.util.Base64.DEFAULT)))
        val sub = j.optString("sUb").takeIf { it.isNotBlank() } ?: return null
        return direct("$origin/$sub.m3u8", embed, "data-obf", listOf(j.optString("hD")))
    }

    private suspend fun viaScan(embed: String, page: String): Stream? {
        val seen = mutableSetOf<String>()
        suspend fun scan(url: String, depth: Int): Stream? {
            if (!seen.add(url) || depth > 1) return null
            val html = app.get(url, referer = page, headers = mapOf("User-Agent" to ua)).text.replace("\\/", "/")
            for (m in Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(html)) {
                direct(m.value, embed, "scan", listOf(hashOf(embed)))?.let { return it }
            }
            for (m in Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html)) {
                val src = m.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) scan(src, depth + 1)?.let { return it }
            }
            return null
        }
        return scan(embed, 0)
    }


    // Trang embed của NguonTV giấu nguồn phát trong biến chuỗi base64 (tên biến đổi mỗi lần tải),
    // ví dụ [{"file":"https://.../index.m3u8","type":"hls"}]. Giải mọi chuỗi base64 dài và tìm link .m3u8.
    private suspend fun viaB64(embed: String, page: String, log: (String) -> Unit): Stream? {
        val html = embedHtml(embed, page)
        var found = 0
        for (m in Regex("""["']([A-Za-z0-9+/=_-]{40,})["']""").findAll(html)) {
            val decoded = runCatching {
                String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
            }.getOrNull() ?: continue
            if (!decoded.contains("http")) continue
            val text = decoded.replace("\\/", "/")
            // Chuỗi base64 có thể chứa cả danh sách phụ đề
            runCatching { collectSubsFrom(text, embed, "b64", log) }
            val url = Regex("""https?://[^\s"'<>\\]+""").findAll(text).map { it.value }
                .firstOrNull { it.contains(".m3u8") } ?: continue
            found++
            log("  base64 -> $url")
            val token = Regex(""""token"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            val streamUrl = Regex(""""streamUrl"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            log("  token=${if (token != null) "có(${token.length})" else "không"} streamUrl=${streamUrl ?: "không"}")
            var failed = 0
            for (ref in listOf(embed, "$mainUrl/", "${originOf(embed)}/", "")) {
                val st = runCatching { direct(url, embed, "base64", emptyList(), ref, log) }
                    .onFailure {
                        failed++
                        log("  base64 ref=${ref.take(25)} lỗi mạng ${it.javaClass.simpleName}: ${it.message?.take(60)}")
                    }.getOrNull()
                if (st != null) return Stream(st.url, st.text, st.via, st.ref, st.hdr, token, streamUrl)
                if (failed >= 2) break
            }
            // Lỗi mạng (không phải phản hồi sai): trả link chưa kiểm tra để trình phát tự thử tải
            if (failed >= 2) {
                log("  base64: lỗi mạng ${failed} lần liên tiếp, trả link chưa kiểm tra")
                return Stream(url, null, "base64-unverified", embed, null, token, streamUrl)
            }
        }
        if (found == 0) log("  base64: không có chuỗi nào giải ra link .m3u8")
        return null
    }

    private suspend fun viaWebView(embed: String, page: String): Stream? {
        val sniffed = app.get(
            embed,
            referer = page,
            interceptor = WebViewResolver(Regex("""\.m3u8"""))
        ).url
        if (!sniffed.contains(".m3u8")) return null
        return direct(sniffed, embed, "webview", listOf(hashOf(embed)))
    }

    @Volatile private var driveDead = false

    // Google Drive: phát thẳng tệp video nếu còn được chia sẻ công khai
    private suspend fun viaDrive(id: String, log: (String) -> Unit): Stream? {
        val url = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        val r = runCatching { app.get(url, headers = mapOf("User-Agent" to ua, "Range" to "bytes=0-15")) }.getOrNull()
        val ct = (r?.headers?.get("Content-Type") ?: "").lowercase()
        log("  drive $id: code=${r?.code ?: "LỖI"} type=${ct.ifEmpty { "?" }}")
        if (r == null || (r.code != 200 && r.code != 206) || ct.contains("html")) {
            log("  drive: tệp không dùng được (đã xóa/không chia sẻ/hết lượt tải)")
            return null
        }
        return Stream(url, null, "gdrive", null, null, null, null, true)
    }

    private suspend fun resolveEmbed(embed: String, page: String, log: (String) -> Unit, allowWebView: Boolean = false, depth: Int = 0): Stream? {
        // Quét phụ đề trong chính trang embed
        runCatching {
            deepCollect(embedHtml(embed, page), embed, "embed", log)
        }
        val tags = if (allowWebView) listOf("bootstrap", "data-obf", "base64", "scan", "webview")
                   else listOf("bootstrap", "data-obf", "base64", "scan")
        for (tag in tags) {
            val tt = System.currentTimeMillis()
            val st = runCatching {
                when (tag) {
                    "bootstrap" -> viaBootstrap(embed, page, log)
                    "data-obf" -> viaDataObf(embed, page)
                    "base64" -> viaB64(embed, page, log)
                    "scan" -> viaScan(embed, page)
                    else -> viaWebView(embed, page)
                }
            }.onFailure {
                log("tầng $tag lỗi: ${it.javaClass.simpleName} ${it.message?.take(100)}")
            }.getOrNull()
            log("  [t=${el()}ms] tầng $tag xong sau ${System.currentTimeMillis() - tt}ms")
            if (st == null) { log("tầng $tag: không có kết quả"); continue }
            log("tầng $tag: dùng được (${st.via})")
            return st
        }
        // Trang embed của NguonTV có thể lồng iframe tới nhà cung cấp khác (ví dụ streamc.xyz): xử lý đệ quy
        if (depth < 2) {
            val html = runCatching { embedHtml(embed, page) }.getOrDefault("")
            for (m in Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html).take(3)) {
                val src = m.groupValues[1].replace("&amp;", "&").let { if (it.startsWith("//")) "https:$it" else it }
                if (!src.startsWith("http") || src == embed) continue
                log("  iframe lồng -> ${src.take(110)}")
                val driveId = Regex("""drive\.google\.com/(?:file/d/|open\?id=|uc\?[^"']*id=)([\w-]+)""").find(src)?.groupValues?.get(1)
                if (driveId != null) {
                    viaDrive(driveId, log)?.let { return it }
                    driveDead = true
                    continue
                }
                resolveEmbed(src, embed, log, false, depth + 1)?.let { return it }
            }
        }
        if (!allowWebView) embedHint(embed, page, log)
        return null
    }

    // Khi mọi tầng thất bại: ghi lại hình dạng trang embed để biết server này dùng giao thức gì
    private suspend fun embedHint(embed: String, page: String, log: (String) -> Unit) {
        runCatching {
            val r = app.get(embed, referer = page, headers = mapOf("User-Agent" to ua))
            val t = r.text.replace("\\/", "/")
            log("  hint: code=${r.code} len=${t.length} url=${r.url.take(100)}")
            Regex("""<iframe[^>]+src=["']([^"']+)""").findAll(t).take(3).forEach { log("  hint iframe: ${it.groupValues[1].take(140)}") }
            Regex("""<script[^>]+src=["']([^"']+)""").findAll(t).take(6).forEach { log("  hint script: ${it.groupValues[1].take(110)}") }
            Regex("""(?i)(sources|file|setup|m3u8|hls|player|stream-bootstrap|data-obf|atob|decrypt)""").findAll(t).take(5).forEach {
                val x = maxOf(0, it.range.first - 80)
                log("  hint> " + t.substring(x, minOf(t.length, it.range.last + 220)).replace(Regex("\\s+"), " "))
            }
        }.onFailure { log("  hint lỗi ${it.javaClass.simpleName} ${it.message?.take(60)}") }
    }

    private suspend fun emitStream(st: Stream, embed: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        if (st.mp4) {
            callback(
                newExtractorLink(name, "$name Drive", st.url!!, ExtractorLinkType.VIDEO) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf("User-Agent" to ua)
                }
            )
            return@runCatching true
        }
        val link = if (st.text != null) LocalPlaylist.url(st.text) else st.url!!
        val r = st.ref ?: embed
        val hasAbr = st.variants.isNotEmpty()
        // Link "Auto": master playlist có nhiều chất lượng, trình phát tự đổi theo tốc độ mạng.
        callback(
            newExtractorLink(name, if (hasAbr) "$name Auto" else name, link, ExtractorLinkType.M3U8) {
                this.referer = st.hdr?.get("Referer") ?: if (st.hdr != null) "" else r
                this.quality = if (hasAbr) 2160 else Qualities.Unknown.value
                this.headers = st.hdr ?: segHeaders(r)
            }
        )
        // Các link chọn tay một chất lượng cố định
        for ((h, bw, u) in st.variants) {
            val label = if (h > 0) "${h}p" else "${bw / 1000}kbps"
            callback(
                newExtractorLink(name, "$name $label", u, ExtractorLinkType.M3U8) {
                    this.referer = st.hdr?.get("Referer") ?: if (st.hdr != null) "" else r
                    this.quality = if (h > 0) h else Qualities.Unknown.value
                    this.headers = st.hdr ?: segHeaders(r)
                }
            )
        }
        true
    }.getOrDefault(false)

    // Chép các script nội tuyến có dựng JW Player để chẩn đoán cách nguồn phát bị giấu
    private fun embedDigest(html: String): String {
        val t = html.replace("\\/", "/")
        val sb = StringBuilder()
        val scripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(t).map { it.groupValues[1] }
            .filter { it.contains("jwplayer", true) || it.contains("npJWPlayer") || Regex("""function\s+f\d""").containsMatchIn(it) }
            .toList()
        sb.append("số script liên quan=${scripts.size}\n")
        scripts.take(3).forEachIndexed { i, sc ->
            val c = sc.replace(Regex("\\s+"), " ").trim()
            sb.append("SCRIPT $i (dài ${c.length}): ").append(c.take(3200)).append("\n")
            if (c.length > 3200) sb.append("... cuối: ").append(c.takeLast(500)).append("\n")
        }
        return sb.toString()
    }

    private class Res(val st: Stream?, val embed: String?, val other: String?)

    private fun resolveUrl(u: String, base: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> originOf(base) + u
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + u
    }

    // Đổi URI="..." (EXT-X-KEY, EXT-X-MAP...) và dòng đường dẫn thành URL tuyệt đối
    private fun absAll(text: String, base: String): String {
        val withUri = Regex("""URI="([^"]+)"""").replace(text) { """URI="${resolveUrl(it.groupValues[1], base)}"""" }
        return withUri.lines().joinToString("\n") { l ->
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#")) l else resolveUrl(t, base)
        }
    }

    // Thử nhiều bộ header trên segment đầu tiên (và key nếu có) rồi chọn bộ chạy được cho ExoPlayer
    // Trang gốc: với nguồn có token, mọi request /key đổi host sang streamUrl và thêm header "token"
    private fun rewriteKeyUrl(u: String, streamUrl: String?): String =
        if (streamUrl.isNullOrBlank() || u.contains(streamUrl)) u
        else u.replace(Regex("""https?://[^/]*/key""", RegexOption.IGNORE_CASE), streamUrl.trimEnd('/') + "/key")

    private suspend fun fetchKey(u: String, base: Map<String, String>, token: String?, streamUrl: String?, log: (String) -> Unit): ByteArray? {
        val url = rewriteKeyUrl(u, streamUrl)
        val h = if (token != null) base + ("token" to token) else base
        return runCatching {
            val r = app.get(url, referer = h["Referer"], headers = h)
            val b = r.okhttpResponse.body.bytes()
            if (r.code == 200 && b.size == 16) b else {
                log("  key ${url.takeLast(24)} -> ${r.code}/${r.headers["Content-Type"] ?: "?"}/${b.size}byte")
                null
            }
        }.getOrElse { log("  key lỗi ${it.javaClass.simpleName} ${it.message?.take(60)}"); null }
    }

    private fun headBytes(r: okhttp3.Response, n: Int): ByteArray = runCatching {
        r.body.byteStream().use { st ->
            val buf = ByteArray(n)
            var got = 0
            while (got < n) {
                val k = st.read(buf, got, n - got)
                if (k <= 0) break
                got += k
            }
            buf.copyOf(got)
        }
    }.getOrDefault(ByteArray(0))

    // Thử tải segment đầu + khóa AES với vài bộ header; chọn bộ chạy được. Không đọc cả segment vào bộ nhớ.
    private suspend fun probeSegments(text: String, embed: String, cookies: Map<String, String>, token: String?, streamUrl: String?, log: (String) -> Unit): Pair<Map<String, String>?, ByteArray?> {
        val lines = text.lines()
        log("  playlist: " + lines.map { it.trim() }.filter { it.isNotEmpty() }.take(7).joinToString(" | ") { it.take(90) })
        val seg = lines.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return Pair(null, null)
        val key = Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""").find(text)?.groupValues?.get(1)
        val mapUri = Regex("""#EXT-X-MAP:[^\n]*URI="([^"]+)"""").find(text)?.groupValues?.get(1)
        val origin = originOf(embed)
        val sets = listOf(
            "A:embed" to segHeaders(embed),
            "B:chỉ-UA" to mapOf("User-Agent" to ua),
            "C:ref-trang-chủ" to mapOf("User-Agent" to ua, "Referer" to "$mainUrl/"),
            "D:ref+origin" to mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin),
            "E:accept-all" to mapOf("User-Agent" to ua, "Accept" to "*/*", "Referer" to embed),
            "F:embed+origin" to (segHeaders(embed) + ("Origin" to origin)),
            "G:không-header" to emptyMap(),
            "H:cors-trình-duyệt" to mapOf(
                "User-Agent" to ua, "Accept" to "*/*", "Accept-Language" to "en-US,en;q=0.9",
                "Origin" to origin, "Referer" to "$origin/",
                "Sec-Fetch-Dest" to "empty", "Sec-Fetch-Mode" to "cors", "Sec-Fetch-Site" to "cross-site"
            )
        )
        log("  cookie CDN: " + (if (cookies.isEmpty()) "không có" else cookies.keys.joinToString(",")))
        var chosen: Map<String, String>? = null
        var chosenKey: ByteArray? = null
        var segOnly: Map<String, String>? = null
        var segOnlyTag = ""
        for ((tag, h) in sets) {
            val res = mutableListOf<String>()
            val ref = h["Referer"]
            val sr = runCatching { app.get(seg, referer = ref, headers = h + ("Range" to "bytes=0-15"), cookies = cookies) }.getOrNull()
            val hbBytes = if (sr != null) headBytes(sr.okhttpResponse, 16) else ByteArray(0)
            // Máy chủ grab.php gửi segment MPEG-TS (byte đầu 0x47) nhưng gắn Content-Type text/html: vẫn là segment hợp lệ
            val isTs = hbBytes.isNotEmpty() && hbBytes[0] == 0x47.toByte()
            val segOk = sr != null && (sr.code == 200 || sr.code == 206) && (isTs ||
                (sr.headers["Content-Type"] ?: sr.headers["content-type"] ?: "").lowercase().let { !it.contains("html") && !it.contains("json") })
            if (sr == null) res.add("seg=LỖI") else {
                val hb = hbBytes.joinToString("") { "%02x".format(it) }
                res.add("seg=${sr.code}/${sr.headers["Content-Type"] ?: "?"}/len=${sr.headers["Content-Length"] ?: "?"}/head=${hb.take(8)}")
            }
            var keyOk = key == null
            var kb: ByteArray? = null
            if (key != null) {
                val kr = runCatching { app.get(rewriteKeyUrl(key, streamUrl), referer = ref, headers = if (token != null) h + ("token" to token) else h, cookies = cookies) }.getOrNull()
                if (kr == null) res.add("key=LỖI") else {
                    kb = runCatching { kr.okhttpResponse.body.bytes() }.getOrDefault(ByteArray(0))
                    keyOk = kr.code == 200 && kb.size == 16
                    res.add("key=${kr.code}/${kr.headers["Content-Type"] ?: "?"}/${kb.size}byte/sc=${kr.cookies.keys.joinToString(",").ifEmpty { "-" }}/srv=${kr.headers["Server"] ?: "?"}/loc=${kr.headers["Location"] ?: "-"}")
                }
            }
            var mapOk = true
            if (mapUri != null) {
                val mr = runCatching { app.get(mapUri, referer = ref, headers = h, cookies = cookies) }.getOrNull()
                mapOk = mr != null && mr.code == 200 && headBytes(mr.okhttpResponse, 4).isNotEmpty()
                res.add("map=${mr?.code ?: "LỖI"}")
            }
            log("  probe $tag: ${res.joinToString(" ")} url=${seg.take(60)}")
            if (segOk && segOnly == null) { segOnly = h; segOnlyTag = tag }
            if (segOk && keyOk && mapOk) { chosen = h; chosenKey = kb?.takeIf { key != null && keyOk }; log("  probe chọn: $tag"); break }
        }
        if (chosen == null) {
            log("  probe: không bộ nào đủ seg+key; dùng bộ cho seg=${segOnly?.let { segOnlyTag } ?: "KHÔNG CÓ (giữ A)"}")
            chosen = segOnly
        }
        if (chosen != null && cookies.isNotEmpty()) {
            chosen = chosen + ("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        return Pair(chosen, chosenKey)
    }

    // Tải các khóa AES (song song, 8 khóa một lượt) và đổi URI khóa sang máy chủ cục bộ.
    // `cache` dùng chung giữa các chất lượng để khóa trùng URL chỉ tải một lần.
    private suspend fun localizeKeys(
        text0: String, hdr: Map<String, String>, token: String?, streamUrl: String?,
        cache: MutableMap<String, String>, log: (String) -> Unit
    ): String {
        val keyRe = Regex("""#EXT-X-KEY:[^\n]*?URI="([^"]+)"""")
        val keyUris = keyRe.findAll(text0).map { it.groupValues[1] }.toList().distinct()
        if (keyUris.isEmpty()) return text0
        val need = keyUris.take(300).filter { it !in cache }
        for (chunk in need.chunked(8)) {
            val res = chunk.amap { u -> u to fetchKey(u, hdr, token, streamUrl, log) }
            for ((u, kb) in res) if (kb != null) cache[u] = LocalPlaylist.bytes(kb)
        }
        var text = text0
        var ok = 0
        for (u in keyUris) {
            val loc = cache[u] ?: continue
            text = text.replace("URI=\"$u\"", "URI=\"$loc\"")
            ok++
        }
        log("  khóa AES: $ok/${keyUris.size} tải được và phát qua máy chủ cục bộ")
        return text
    }

    /**
     * Tải playlist. Nếu là master có nhiều chất lượng: giữ lại tối đa 4 chất lượng (cao nhất, thấp nhất và 2 mức giữa),
     * xử lý khóa của từng chất lượng, rồi dựng lại master trỏ tới playlist cục bộ để ExoPlayer tự đổi chất lượng
     * theo tốc độ mạng. Đồng thời trả về danh sách chất lượng để thêm link chọn tay.
     * Nếu chỉ có một playlist thì xử lý như trước.
     */
    private suspend fun flatten(st: Stream, embed: String, log: (String) -> Unit): Stream = runCatching {
        if (st.mp4) return@runCatching st
        val ref = st.ref ?: embed
        val baseUrl = st.url
        var cookies: Map<String, String> = emptyMap()
        var text = st.text ?: app.get(baseUrl!!, referer = ref.ifBlank { null }, headers = segHeaders(ref))
            .also { cookies = cookies + it.cookies }.text
        val base = baseUrl ?: Regex("""https?://[^\s"]+""").find(text)?.value ?: return@runCatching st
        text = absAll(text, base)
        val cache = mutableMapOf<String, String>()

        if (text.contains("#EXT-X-STREAM-INF")) {
            val ls = text.lines()
            val all = mutableListOf<Variant>()
            for (i in ls.indices) {
                if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue
                val b = Regex("""BANDWIDTH=(\d+)""").find(ls[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val hgt = Regex("""RESOLUTION=\d+x(\d+)""").find(ls[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val u = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                if (u != null) all.add(Variant(b, hgt, u))
            }
            val uniq = all.distinctBy { it.url }.sortedByDescending { it.bw }
            val picked = if (uniq.size <= 4) uniq
                else listOf(0, uniq.size / 3, 2 * uniq.size / 3, uniq.size - 1).distinct().map { uniq[it] }
            log("  master: ${uniq.size} chất lượng, giữ ${picked.size}: " + picked.joinToString(",") { "${it.height}p/${it.bw / 1000}k" })

            val fetched = LinkedHashMap<String, String>()
            for (v in picked) {
                val r = runCatching { app.get(v.url, referer = ref.ifBlank { null }, headers = segHeaders(ref)) }.getOrNull()
                if (r != null) cookies = cookies + r.cookies
                log("  flat: ${v.height}p bw=${v.bw} code=${r?.code ?: "LỖI"} đầu=${r?.text?.trimStart()?.take(40)?.replace(Regex("\\s+"), " ") ?: ""}")
                if (r != null && r.code == 200 && looksLikePlaylist(r.text)) {
                    fetched[v.url] = absAll(r.text, v.url)
                    log("  playlist ${v.height}p: ${plStats(r.text)}")
                }
            }

            if (fetched.isNotEmpty()) {
                val top = picked.first { it.url in fetched }
                val (hdr, _) = runCatching { probeSegments(fetched[top.url]!!, embed, cookies, st.token, st.streamUrl, log) }
                    .getOrElse { log("  probe lỗi ${it.javaClass.simpleName} ${it.message?.take(80)}"); Pair(null, null) }
                val kh = hdr ?: segHeaders(ref)

                val localUrls = mutableMapOf<String, String>()
                for ((u, t) in fetched) {
                    localUrls[u] = LocalPlaylist.url(localizeKeys(t, kh, st.token, st.streamUrl, cache, log))
                }

                // Dựng lại master: chỉ giữ các chất lượng tải được, trỏ về playlist cục bộ
                val out = mutableListOf<String>()
                var idx = 0
                while (idx < ls.size) {
                    val l = ls[idx]
                    when {
                        l.startsWith("#EXT-X-STREAM-INF") -> {
                            var j = idx + 1
                            while (j < ls.size && (ls[j].isBlank() || ls[j].startsWith("#"))) j++
                            val u = ls.getOrNull(j)?.trim()
                            val loc = u?.let { localUrls[it] }
                            if (loc != null) { out.add(l); out.add(loc) }
                            idx = j + 1
                        }
                        l.startsWith("#EXT-X-MEDIA") && hlsAttr(l, "TYPE") == "AUDIO" && hlsAttr(l, "URI").isNotBlank() -> {
                            // Nhóm audio riêng cũng có thể cần xử lý khóa
                            val u = hlsAttr(l, "URI")
                            val loc = runCatching {
                                val r = app.get(u, referer = ref.ifBlank { null }, headers = segHeaders(ref))
                                if (r.code == 200 && looksLikePlaylist(r.text))
                                    LocalPlaylist.url(localizeKeys(absAll(r.text, u), kh, st.token, st.streamUrl, cache, log))
                                else null
                            }.getOrNull()
                            out.add(if (loc != null) l.replace("URI=\"$u\"", "URI=\"$loc\"") else l)
                            idx++
                        }
                        else -> { out.add(l); idx++ }
                    }
                }
                val master = prioritizeSubs(out.joinToString("\n"))
                val subTracks = out.count { it.startsWith("#EXT-X-MEDIA") && hlsAttr(it, "TYPE") == "SUBTITLES" }
                log("  master mới: ${localUrls.size} chất lượng, ${subTracks} phụ đề nhúng HLS")

                val variantLinks = picked.filter { it.url in localUrls }.map { Triple(it.height, it.bw, localUrls[it.url]!!) }
                return@runCatching Stream(null, master, st.via + "+abr", ref, hdr, st.token, st.streamUrl, false, variantLinks)
            }
            log("  master: không tải được playlist con nào, dùng cách cũ")
        }

        // Chỉ có một playlist (không đổi chất lượng được)
        log("  playlist đơn: ${plStats(text)}")
        val (hdr, _) = runCatching { probeSegments(text, embed, cookies, st.token, st.streamUrl, log) }
            .getOrElse { log("  probe lỗi ${it.javaClass.simpleName} ${it.message?.take(80)}"); Pair(null, null) }
        text = localizeKeys(text, hdr ?: segHeaders(ref), st.token, st.streamUrl, cache, log)
        Stream(null, text, st.via + "+flat", ref, hdr, st.token, st.streamUrl)
    }.getOrElse { log("  flat lỗi ${it.javaClass.simpleName} ${it.message?.take(80)}"); st }

    private suspend fun resolveFilm(basePage: String, playerPage: String, log: (String) -> Unit): Res? {
        val pageDoc = fetch(basePage).document
        val playerDoc = runCatching { fetch(playerPage, mapOf("Referer" to basePage)).document }.getOrNull()
        // Phụ đề có thể nằm ngay trong trang phim / trang phát
        runCatching { collectSubsFrom(pageDoc.html(), basePage, "trang-phim", log) }
        runCatching { collectSubsFrom(playerDoc?.html() ?: "", playerPage, "trang-phát", log) }
        domSubHints(playerDoc, "trang-phát")
        val state = playerDoc?.let { extractPlayerState(it, playerPage) } ?: extractPlayerState(pageDoc, basePage)
        if (state == null) {
            log("state=NULL (không tách được fid/key) playerDoc=${playerDoc != null}")
            val ifr = pageDoc.selectFirst("iframe[src*=grab.nguontv.life], iframe[src*=m3u8]")?.attr("abs:src")
            if (!ifr.isNullOrBlank()) return Res(null, null, ifr)
            return null
        }
        val playerHtml = (playerDoc?.html() ?: "").replace("&amp;", "&")
        // Nút chọn server của trang: POST tới URL tập "...fromEmbed=1..." với indexL = số thứ tự server, loadTime=1
        val serverUrlRe = Regex("""/xem-phim/[^'"\s<>]*fromEmbed=1[^'"\s<>]*""")
        val serversRe = Regex("""data-index="?(\d+)"?[^>]*>([^<]*)<""")
        var serverUrl = serverUrlRe.find(playerHtml)?.value?.let { absolute(it) }
        var servers = serversRe.findAll(playerHtml).map { it.groupValues[1].toInt() to it.groupValues[2].trim() }.toList()
        log("servers=${servers.joinToString(",") { "${it.first}:${it.second}" }.ifEmpty { "không thấy" }} serverUrl=${serverUrl?.substringBefore('?')?.takeLast(50) ?: "không"}")
        class Attempt(val url: String, val l: Int, val sl: Int, val loadTime: String)
        val attempts = mutableListOf(Attempt(playerPage, 0, 0, "0"))
        if (serverUrl != null) {
            val idxs = servers.map { it.first }.ifEmpty { listOf(0, 1, 2) }
            for (i in idxs) attempts.add(Attempt(serverUrl!!, i, 0, "1"))
        }
        driveDead = false
        var other: String? = null
        var firstEmbed: String? = null
        var failovers = 0
        val seen = mutableSetOf<String>()
        var ai = 0
        while (ai < attempts.size) {
            val a = attempts[ai++]
            val resp = postForm(
                a.url,
                mapOf("fid" to state.fid, "time" to state.time, "key" to state.key,
                    "loadTime" to a.loadTime, "indexL" to "${a.l}", "indexSL" to "${a.sl}"),
                xhrHeaders(playerPage)
            )
            val body = resp.text
            val decoded = body.replace("\\/", "/").replace("\\u0026", "&").replace("\\\"", "\"").replace("&amp;", "&")
            // Phản hồi POST cũng có thể mang danh sách phụ đề
            runCatching { collectSubsFrom(body, playerPage, "post", log) }
            val embed = findEmbedUrl(body)
                ?: Regex("""<iframe[^>]+src=\\?["']([^"'\\]+)""").find(body.replace("\\/", "/"))?.groupValues?.get(1)?.replace("&amp;", "&")
            val js = runCatching { JSONObject(body) }.getOrNull()
            val rl = js?.optInt("indexL", -1) ?: -1
            val rsl = js?.optInt("indexSL", 0) ?: 0
            val rlt = js?.optInt("loadTime", -1) ?: -1
            log("POST ${if (a.url == playerPage) "trang-phát" else "server"} L=${a.l}(${servers.firstOrNull { it.first == a.l }?.second ?: "?"}) lt=${a.loadTime} code=${resp.code} len=${body.length} json[indexL=$rl indexSL=$rsl loadTime=$rlt fromIndexL=${js?.opt("fromIndexL")}] embed=${embed?.take(110) ?: "KHÔNG"}")
            // Danh sách server có thể nằm trong phản hồi POST thay vì trang phát
            if (serverUrl == null) {
                serverUrlRe.find(decoded)?.value?.let { u ->
                    serverUrl = absolute(u)
                    servers = serversRe.findAll(decoded).map { it.groupValues[1].toInt() to it.groupValues[2].trim() }.toList()
                    log("  phản hồi có danh sách server: ${servers.joinToString(",") { "${it.first}:${it.second}" }} url=${serverUrl?.substringBefore('?')?.takeLast(40)}")
                    val su = serverUrl!!
                    for (i in servers.map { it.first }.ifEmpty { listOf(0, 1, 2) }) if (i != a.l) attempts.add(Attempt(su, i, 0, "1"))
                }
            }
            if (embed == null) {
                val hls = findHls(body)
                if (hls != null) {
                    val st = direct(hls, "", "api-hls", emptyList(), playerPage, log)
                    if (st != null) return Res(flatten(st, "", log), "", null)
                }
                log("  đầu=${body.take(300).replace(Regex("\\s+"), " ")}")
                continue
            }
            if (!seen.add(embed)) { log("  embed trùng, bỏ qua") }
            else {
                if (firstEmbed == null) firstEmbed = embed
                if (!embed.contains("grab.nguontv.life")) other = other ?: embed
                val st = resolveEmbed(embed, playerPage, log)
                if (st != null) {
                    log("  [t=${el()}ms] bắt đầu flatten")
                    val fl = flatten(st, embed, log)
                    log("  [t=${el()}ms] flatten xong")
                    return Res(fl, embed, null)
                }
            }
            // Tự chuyển server như trình duyệt: gửi lại với indexL/indexSL/loadTime+1 mà phản hồi trả về
            if (failovers < 4 && rl >= 0) {
                failovers++
                attempts.add(Attempt(a.url, rl, rsl, "${maxOf(rlt, 0) + 1}"))
            }
        }
        // Dự phòng cuối: WebView (chậm, tối đa ~120 giây) cho embed đầu tiên
        if (firstEmbed != null && !driveDead) {
            val st = runCatching { viaWebView(firstEmbed, playerPage) }
                .onFailure { log("tầng webview lỗi: ${it.javaClass.simpleName} ${it.message?.take(80)}") }.getOrNull()
            if (st != null) { log("tầng webview: dùng được"); return Res(flatten(st, firstEmbed, log), firstEmbed, null) }
        }
        return if (other != null) Res(null, null, other) else null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("||", limit = 2)
        val basePage = absolute(parts.first()) ?: return false
        val playerPage = absolute(parts.getOrNull(1)) ?: toPlayerUrl(basePage)
        subs.clear()
        embedCache.clear()
        subHints.clear()
        val t0 = System.currentTimeMillis()
        tStart = t0
        val lines: MutableList<String> = java.util.Collections.synchronizedList(
            mutableListOf("NguonTV v30 ${java.util.Date()}", "film=$basePage", "player=$playerPage")
        )
        val log: (String) -> Unit = { Log.d(TAG, it); lines.add(it) }
        var ok = false
        var embedUrl: String? = null
        try {
            val res = resolveFilm(basePage, playerPage, log)
            embedUrl = res?.embed?.takeIf { it.isNotBlank() }
            ok = when {
                res?.st != null && emitStream(res.st, res.embed.orEmpty(), callback) -> { log("KẾT QUẢ: phát qua ${res.st.via} (trả link sau ${System.currentTimeMillis() - t0}ms)"); true }
                res?.other != null -> {
                    log("KẾT QUẢ: iframe ngoài ${res.other}")
                    loadExtractor(res.other, playerPage, subtitleCallback, callback)
                    true
                }
                else -> { log("KẾT QUẢ: KHÔNG LẤY ĐƯỢC LINK"); false }
            }
        } catch (e: Exception) {
            log("LỖI loadLinks ${e.javaClass.simpleName}: ${e.message}")
        }
        // Không thấy phụ đề trong HTML/JSON: để JW Player tự chạy trong WebView rồi bắt request .srt/.vtt
        val eu = embedUrl
        if (subs.isEmpty() && eu != null) {
            runCatching { viaWebViewSubs(eu, playerPage, log) }
                .onFailure { log("LỖI webview phụ đề ${it.javaClass.simpleName}: ${it.message?.take(80)}") }
            if (subs.isEmpty()) {
                // Vẫn không có: ghi lại đoạn script dựng JW Player để biết `tracks` được giấu thế nào
                runCatching {
                    val h = app.get(eu, referer = playerPage, headers = mapOf("User-Agent" to ua)).text
                    log("EMBED DIGEST:\n" + embedDigest(h))
                }
            }
        }
        // Phụ đề: tiếng Việt trước, tiếng Anh sau
        runCatching { emitSubs(subtitleCallback, log) }
            .onFailure { log("LỖI phụ đề ${it.javaClass.simpleName}: ${it.message?.take(80)}") }
        if (!ok) gateProbe(basePage).forEach(log)
        log("TỔNG THỜI GIAN loadLinks: ${System.currentTimeMillis() - t0}ms")
        if (!ok || COPY_LOG_ALWAYS || (COPY_LOG_WHEN_NO_SUB && subs.isEmpty())) copyLog(lines.joinToString("\n"))
        return ok
    }

    // Dò cơ chế xác nhận 18+ trên trang phim
    private suspend fun gateProbe(filmUrl: String): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            val pg = fetch(filmUrl).text.replace("\\/", "/")
            val kw = Regex("(?i)(đủ 18|chưa đủ|18\\s*tuổi|age-?verif|over-?18|adult|confirm-?age|\\.cookie|setCookie|localStorage)")
            kw.findAll(pg).take(6).forEach {
                val x = maxOf(0, it.range.first - 120)
                val y = minOf(pg.length, it.range.last + 220)
                out.add("18+> " + pg.substring(x, y).replace(Regex("\\s+"), " "))
            }
            out.add("cookie: " + sessionCookies.keys.joinToString(","))
        }
        return out
    }

    // Tự chép log của từng lần phát vào clipboard để gửi cho người phát triển
    private fun copyLog(text: String) {
        runCatching {
            val act = com.lagradost.cloudstream3.CommonActivity.activity ?: return
            act.runOnUiThread {
                runCatching {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("NguonTV log", text.take(60000)))
                    android.widget.Toast.makeText(act, "Đã chép log NguonTV vào clipboard", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
