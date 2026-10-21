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

/** Máy chủ HTTP nhỏ trên 127.0.0.1 để đưa playlist đã giải mã cho trình phát. */
private object LocalPlaylist {
    private val store = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var server: java.net.ServerSocket? = null

    @Synchronized
    fun url(text: String): String {
        val s = server ?: java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also {
            server = it
            start(it)
        }
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
                val body = path?.let { store[it] }?.toByteArray()
                val out = c.getOutputStream()
                if (body == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                } else {
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
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
            return debugHome("lỗi tải: ${e.javaClass.simpleName} ${e.message?.take(80)}")
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
            return debugHome("Bấm vào đây xem chẩn đoán v12 (code=${res.code} len=${res.text.length} title=${doc.title()})")
        }

        val homePage = mutableListOf<HomePageList>()
        if (responses.isNotEmpty()) homePage.add(HomePageList("Phim Hot / Phim mới", responses))
        // TẠM THỜI: thẻ chẩn đoán các bước lấy link phát
        homePage.add(
            HomePageList(
                "DEBUG",
                listOf(newMovieSearchResponse("Chẩn đoán phát v12", "$mainUrl/__debug", TvType.Movie))
            )
        )
        return newHomePageResponse(homePage, hasNext = false)
    }

    // Hiện thông tin chẩn đoán thành các thẻ phim ngay ở trang chủ
    private fun debugHome(vararg lines: String): HomePageResponse {
        val cards = lines.mapIndexed { i, t ->
            newMovieSearchResponse(t.replace("<", "‹").replace(">", "›"), "$mainUrl/__debug$i", TvType.Movie)
        }
        return newHomePageResponse(listOf(HomePageList("DEBUG NguonTV", cards)), hasNext = false)
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

    // Trang chẩn đoán v12: thử nhiều phim cùng lúc, mỗi phim một khối ngắn
    private suspend fun debugLoad(): LoadResponse {
        fun esc(t: String) = t.replace("<", "‹").replace(">", "›").replace("\n", "<br>")
        val sb = StringBuilder()
        try {
            val home = fetch("$mainUrl/")
            val films = home.document.select("a[href]").map { it.attr("abs:href") }
                .filter { Regex("-f\\d+\\.html", RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.contains("/xem-phim/") }
                .distinct().take(8)
            sb.append("v12 - thử ${films.size} phim\n")
            films.forEachIndexed { i, filmUrl ->
                val lines = mutableListOf<String>()
                val t0 = System.currentTimeMillis()
                var res: Res? = null
                try {
                    val page = fetch(filmUrl)
                    val ep = page.document.select("a[href]").map { it.attr("abs:href") }
                        .firstOrNull { episodeNumber(it) != null }
                    res = resolveFilm(filmUrl, ep ?: toPlayerUrl(filmUrl)) { lines.add(it) }
                } catch (e: Exception) {
                    lines.add("LỖI ${e.javaClass.simpleName}: ${e.message?.take(100)}")
                }
                val rr = res
                val ok = rr?.st?.via ?: rr?.other?.let { "iframe-ngoài ${it.take(50)}" } ?: "KHÔNG"
                sb.append("\n#${i + 1} ${filmUrl.substringAfter("life/").take(40)}\n=> $ok (${System.currentTimeMillis() - t0}ms)\n")
                if (rr?.st == null) gateProbe(filmUrl).forEach { sb.append("  ").append(it.take(400)).append("\n") }
                if (rr?.st == null) lines.take(14).forEach { sb.append("  ").append(it.take(160)).append("\n") }
                else sb.append("  ").append(lines.lastOrNull { it.contains("flat") || it.contains("GET") }?.take(160) ?: "").append("\n")
            }
        } catch (e: Exception) {
            sb.append("DỪNG: ${e.javaClass.simpleName}: ${e.message?.take(150)}\n")
        }
        return newMovieLoadResponse("DEBUG phát v13", "$mainUrl/__debug", TvType.Movie, "debug") {
            this.plot = esc(sb.toString())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("__debug")) return debugLoad()
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


    // ================= Lấy luồng phát từ trang embed (nhiều tầng dự phòng) =================

    private class Stream(val url: String?, val text: String?, val via: String, val ref: String? = null, val hdr: Map<String, String>? = null)

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
        val r = app.get(url, referer = ref.ifBlank { null }, headers = segHeaders(ref))
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

    private suspend fun viaBootstrap(embed: String, page: String): Stream? {
        val origin = originOf(embed)
        val shell = app.get(embed, referer = page, headers = mapOf("User-Agent" to ua))
        val cfg = Regex("""<script id="stream-bootstrap"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(shell.text)?.groupValues?.get(1) ?: return null
        val api = JSONObject(cfg).optString("api").takeIf { it.isNotBlank() } ?: return null
        val body = JSONObject().apply {
            put("action", "bootstrap")
            put("referrer", page)
            put("frame_origins", org.json.JSONArray().put(mainUrl))
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
        val video = j.optString("video").ifBlank { hashOf(embed) }
        val playlist = j.optJSONObject("preissued")?.optString("playlist")?.takeIf { it.isNotBlank() } ?: return null
        return direct(playlist, embed, "bootstrap", listOf(video))
    }

    private suspend fun viaDataObf(embed: String, page: String): Stream? {
        val origin = originOf(embed)
        val html = app.get(embed, referer = page, headers = mapOf("User-Agent" to ua)).text
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
        val html = app.get(embed, referer = page, headers = mapOf("User-Agent" to ua)).text
        var found = 0
        for (m in Regex("""["']([A-Za-z0-9+/=_-]{40,})["']""").findAll(html)) {
            val decoded = runCatching {
                String(android.util.Base64.decode(m.groupValues[1], android.util.Base64.DEFAULT))
            }.getOrNull() ?: continue
            if (!decoded.contains("http")) continue
            val text = decoded.replace("\\/", "/")
            val url = Regex("""https?://[^\s"'<>\\]+""").findAll(text).map { it.value }
                .firstOrNull { it.contains(".m3u8") } ?: continue
            found++
            log("  base64 -> $url")
            for (ref in listOf(embed, "$mainUrl/", "${originOf(embed)}/", "")) {
                direct(url, embed, "base64", emptyList(), ref, log)?.let { return it }
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

    private suspend fun resolveEmbed(embed: String, page: String, log: (String) -> Unit): Stream? {
        for (tag in listOf("bootstrap", "data-obf", "base64", "scan", "webview")) {
            val st = runCatching {
                when (tag) {
                    "bootstrap" -> viaBootstrap(embed, page)
                    "data-obf" -> viaDataObf(embed, page)
                    "base64" -> viaB64(embed, page, log)
                    "scan" -> viaScan(embed, page)
                    else -> viaWebView(embed, page)
                }
            }.onFailure {
                log("tầng $tag lỗi: ${it.javaClass.simpleName} ${it.message?.take(100)}")
            }.getOrNull()
            if (st == null) { log("tầng $tag: không có kết quả"); continue }
            log("tầng $tag: dùng được (${st.via})")
            return st
        }
        return null
    }

    private suspend fun emitStream(st: Stream, embed: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val link = if (st.text != null) LocalPlaylist.url(st.text) else st.url!!
        val r = st.ref ?: embed
        callback(
            newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                this.referer = st.hdr?.get("Referer") ?: if (st.hdr != null) "" else r
                this.quality = Qualities.Unknown.value
                this.headers = st.hdr ?: segHeaders(r)
            }
        )
        true
    }.getOrDefault(false)

    // Chép các script nội tuyến có dựng JW Player để chẩn đoán cách nguồn phát bị giấu
    private fun embedDigest(html: String): String {
        val t = html.replace("\\/", "/")
        val sb = StringBuilder()
        val scripts = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(t).map { it.groupValues[1] }
            .filter { it.contains("npJWPlayer") || Regex("""function\s+f\d""").containsMatchIn(it) }
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
    private suspend fun probeSegments(text: String, embed: String, log: (String) -> Unit): Map<String, String>? {
        val lines = text.lines()
        log("  playlist: " + lines.map { it.trim() }.filter { it.isNotEmpty() }.take(7).joinToString(" | ") { it.take(90) })
        val seg = lines.map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val key = Regex("""#EXT-X-KEY:[^\n]*URI="([^"]+)"""").find(text)?.groupValues?.get(1)
        val mapUri = Regex("""#EXT-X-MAP:[^\n]*URI="([^"]+)"""").find(text)?.groupValues?.get(1)
        val origin = originOf(embed)
        val sets = listOf(
            "A:embed" to segHeaders(embed),
            "B:chỉ-UA" to mapOf("User-Agent" to ua),
            "C:ref-trang-chủ" to mapOf("User-Agent" to ua, "Referer" to "$mainUrl/"),
            "D:ref+origin" to mapOf("User-Agent" to ua, "Referer" to "$origin/", "Origin" to origin),
            "E:accept-all" to mapOf("User-Agent" to ua, "Accept" to "*/*", "Referer" to embed)
        )
        var chosen: Map<String, String>? = null
        for ((tag, h) in sets) {
            val res = mutableListOf<String>()
            var allOk = true
            for ((what, u) in listOfNotNull("seg" to seg, key?.let { "key" to it }, mapUri?.let { "map" to it })) {
                val r = runCatching { app.get(u, referer = h["Referer"], headers = h) }.getOrNull()
                if (r == null) { res.add("$what=LỖI"); allOk = false; continue }
                val b = r.text
                val ct = r.headers["content-type"] ?: r.headers["Content-Type"] ?: "?"
                val hexHead = b.take(4).toByteArray().joinToString("") { "%02x".format(it) }
                res.add("$what=${r.code}/$ct/len=${b.length}/head=$hexHead")
                if (r.code != 200) allOk = false
            }
            log("  probe $tag: ${res.joinToString(" ")} url=${seg.take(60)}")
            if (allOk && chosen == null) { chosen = h; break }
        }
        log("  probe chọn: ${chosen?.let { c -> sets.first { it.second == c }.first } ?: "KHÔNG CÓ (giữ A)"}")
        return chosen
    }

    // Tải playlist, nếu là master thì chọn chất lượng cao nhất, tải playlist con rồi phát qua máy chủ cục bộ
    private suspend fun flatten(st: Stream, embed: String, log: (String) -> Unit): Stream = runCatching {
        val ref = st.ref ?: embed
        val baseUrl = st.url
        var text = st.text ?: app.get(baseUrl!!, referer = ref.ifBlank { null }, headers = segHeaders(ref)).text
        val base = baseUrl ?: Regex("""https?://[^\s"]+""").find(text)?.value ?: return@runCatching st
        text = absAll(text, base)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val ls = text.lines()
            var best: String? = null
            var bw = -1L
            for (i in ls.indices) {
                if (ls[i].startsWith("#EXT-X-STREAM-INF")) {
                    val b = Regex("""BANDWIDTH=(\d+)""").find(ls[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val u = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                    if (u != null && b > bw) { bw = b; best = u }
                }
            }
            if (best != null) {
                val r = app.get(best, referer = ref.ifBlank { null }, headers = segHeaders(ref))
                log("  flat: playlist con code=${r.code} bw=$bw đầu=${r.text.trimStart().take(40).replace(Regex("\\s+"), " ")}")
                if (r.code == 200 && looksLikePlaylist(r.text)) text = absAll(r.text, best)
            }
        }
        val hdr = probeSegments(text, embed, log)
        Stream(null, text, st.via + "+flat", ref, hdr)
    }.getOrElse { log("  flat lỗi ${it.javaClass.simpleName} ${it.message?.take(80)}"); st }

    private suspend fun resolveFilm(basePage: String, playerPage: String, log: (String) -> Unit): Res? {
        val pageDoc = fetch(basePage).document
        val playerDoc = runCatching { fetch(playerPage, mapOf("Referer" to basePage)).document }.getOrNull()
        val state = playerDoc?.let { extractPlayerState(it, playerPage) } ?: extractPlayerState(pageDoc, basePage)
        if (state == null) {
            log("state=NULL (không tách được fid/key) playerDoc=${playerDoc != null}")
            val ifr = pageDoc.selectFirst("iframe[src*=grab.nguontv.life], iframe[src*=m3u8]")?.attr("abs:src")
            if (!ifr.isNullOrBlank()) return Res(null, null, ifr)
            return null
        }
        var other: String? = null
        for ((l, sl) in listOf(0 to 0, 1 to 0, 0 to 1, 2 to 0)) {
            val resp = postForm(
                playerPage,
                mapOf("fid" to state.fid, "time" to state.time, "key" to state.key,
                    "loadTime" to "0", "indexL" to "$l", "indexSL" to "$sl"),
                xhrHeaders(playerPage)
            )
            val body = resp.text
            val embed = findEmbedUrl(body)
            log("POST L=$l SL=$sl code=${resp.code} len=${body.length} embed=${embed?.substringBefore('?')?.takeLast(50)}")
            if (embed == null) {
                val hls = findHls(body)
                if (hls != null) {
                    val st = direct(hls, "", "api-hls", emptyList(), playerPage, log)
                    if (st != null) return Res(flatten(st, "", log), "", null)
                }
                val ifr = Regex("""<iframe[^>]+src=\\?["']([^"'\\]+)""").find(body.replace("\\/", "/"))?.groupValues?.get(1)
                log("  không có embed grab; iframe=${ifr ?: "không"} đầu=${body.take(120).replace(Regex("\\s+"), " ")}")
                if (ifr != null) other = ifr
                continue
            }
            val st = resolveEmbed(embed, playerPage, log)
            if (st != null) return Res(flatten(st, embed, log), embed, null)
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
        val lines = mutableListOf("NguonTV v15 ${java.util.Date()}", "film=$basePage", "player=$playerPage")
        val log: (String) -> Unit = { Log.d(TAG, it); lines.add(it) }
        var ok = false
        try {
            val res = resolveFilm(basePage, playerPage, log)
            ok = when {
                res?.st != null && emitStream(res.st, res.embed.orEmpty(), callback) -> { log("KẾT QUẢ: phát qua ${res.st.via}"); true }
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
        if (!ok) gateProbe(basePage).forEach(log)
        copyLog(lines.joinToString("\n"))
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
