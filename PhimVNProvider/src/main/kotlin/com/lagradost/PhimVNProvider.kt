package com.phimvn

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

private const val TAG = "PhimVN"
private const val LINK_SEP = "|||"

private const val UA =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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

@CloudstreamPlugin
class PhimVNPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PhimVNProvider())
    }
}

/**
 * PhimVN lấy luồng phát theo nhiều tầng, thử lần lượt và kiểm tra kết quả ở từng tầng,
 * để khi trang đổi cách phát thì tầng khác vẫn có thể cứu được:
 *  1. m3u8 trực tiếp từ API
 *  2. giao thức bootstrap (AES-GCM) của player embed
 *  3. kiểu cũ data-obf
 *  4. quét HTML embed (và iframe con) tìm .m3u8, tự giải mã playlist nếu bị mã hóa
 *  5. WebView chạy player thật và bắt yêu cầu .m3u8
 */
class PhimVNProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "PhimVN"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/phim-le" to "Phim Lẻ",
        "danh-sach/hoat-hinh" to "Hoạt Hình"
    )

    // ---------------------------------------------------------------- danh sách / chi tiết

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("$mainUrl/api/films/${request.data}?page=$page").parsedSafe<PageResponse>()
        val list = res?.items?.mapNotNull { it.toSearch() } ?: emptyList()
        val hasNext = (res?.paginate?.currentPage ?: 0) < (res?.paginate?.totalPage ?: 0)
        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val res = app.get("$mainUrl/api/films/search?keyword=$query").parsedSafe<PageResponse>()
        return res?.items?.mapNotNull { it.toSearch() } ?: emptyList()
    }

    private fun Item.toSearch(): SearchResponse? {
        val title = name ?: return null
        val s = slug ?: return null
        val poster = posterUrl ?: thumbUrl
        val yr = year?.toIntOrNull()
        return newMovieSearchResponse(title, "$mainUrl/api/film/$s", TvType.Movie) {
            this.posterUrl = poster
            this.year = yr
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val movie = app.get(url).parsedSafe<DetailResponse>()?.movie ?: return null
        val title = movie.name ?: return null
        val episodes = mutableListOf<Episode>()
        movie.episodes?.forEach { server ->
            val serverName = server.serverName ?: "NguonC"
            server.items?.forEach { ep ->
                val embed = ep.embed
                val m3u8 = ep.m3u8
                if (!embed.isNullOrBlank() || !m3u8.isNullOrBlank()) {
                    episodes.add(
                        newEpisode("${m3u8.orEmpty()}$LINK_SEP${embed.orEmpty()}") {
                            this.name = "$serverName - Tập ${ep.name ?: "1"}"
                            this.episode = ep.name?.toIntOrNull()
                        }
                    )
                }
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.posterUrl ?: movie.thumbUrl
            this.plot = movie.description
            this.year = movie.year
        }
    }

    // ---------------------------------------------------------------- phát

    /** Kết quả của một tầng: hoặc URL m3u8 dùng được ngay, hoặc nội dung playlist đã giải mã. */
    private class Stream(val url: String?, val text: String?, val via: String)

    private fun segHeaders(embed: String) = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-us,en;q=0.5",
        "Sec-Fetch-Mode" to "navigate",
        "Referer" to embed
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split(LINK_SEP, limit = 2)
        val embed = (if (parts.size == 2) parts[1] else data).trim().takeIf { it.isNotBlank() }
        val apiM3u8 = (if (parts.size == 2) parts[0] else "").trim()
            .takeIf { it.contains(".m3u8") }

        val e = embed ?: "$mainUrl/"
        for (tag in listOf("api", "bootstrap", "data-obf", "scan", "webview")) {
            val st = runCatching {
                when (tag) {
                    "api" -> apiM3u8?.let { direct(it, e, "api") }
                    "bootstrap" -> embed?.let { viaBootstrap(it) }
                    "data-obf" -> embed?.let { viaDataObf(it) }
                    "scan" -> embed?.let { viaScan(it) }
                    else -> embed?.let { viaWebView(it) }
                }
            }.onFailure {
                Log.d(TAG, "tầng $tag lỗi: ${it.javaClass.simpleName} ${it.message?.take(120)}")
            }.getOrNull()
            if (st == null) {
                Log.d(TAG, "tầng $tag: không có kết quả")
                continue
            }
            Log.d(TAG, "tầng $tag: dùng được (${st.via})")
            if (emit(st, e, callback)) return true
        }
        return false
    }

    private suspend fun emit(st: Stream, embed: String, callback: (ExtractorLink) -> Unit): Boolean = runCatching {
        val link = if (st.text != null) LocalPlaylist.url(st.text) else st.url!!
        callback(
            newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                this.referer = embed
                this.quality = Qualities.Unknown.value
                this.headers = segHeaders(embed)
            }
        )
        true
    }.getOrDefault(false)

    // ----- kiểm tra / giải mã playlist dùng chung cho mọi tầng

    private fun looksLikePlaylist(t: String?): Boolean =
        t != null && t.trimStart().startsWith("#EXTM3U") && t.lines().any { it.isNotBlank() && !it.startsWith("#") }

    private fun hashOf(embed: String): String =
        Regex("""[?&]hash=([0-9a-fA-F]+)""").find(embed)?.groupValues?.get(1).orEmpty()

    /** Biến các dòng đường dẫn tương đối thành tuyệt đối để phát được từ máy chủ cục bộ. */
    private fun absolutize(text: String, base: String): String {
        val origin = Regex("""^(https?://[^/]+)""").find(base)?.groupValues?.get(1) ?: return text
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

    private fun decryptPlaylist(text: String, videoHash: String): String? {
        if (!text.contains("#ENC-AESGCM")) return text
        val iv = Regex("""iv=([0-9a-fA-F]+)""").find(text)?.groupValues?.get(1) ?: return null
        val line = text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec("stream-derive-v1".toByteArray(), "HmacSHA256"))
        val key = mac.doFinal(videoHash.toByteArray())
        return String(gcm(key, hex(iv), android.util.Base64.decode(line, android.util.Base64.DEFAULT), null))
    }

    /** Tải một URL m3u8, giải mã nếu cần, và trả về Stream nếu hợp lệ. */
    private suspend fun direct(url: String, embed: String, via: String, hashes: List<String> = emptyList()): Stream? {
        val r = app.get(url, referer = embed, headers = segHeaders(embed))
        if (r.code != 200) return null
        val body = r.text
        if (body.contains("#ENC-AESGCM")) {
            val tryHashes = (hashes + hashOf(embed)).filter { it.isNotBlank() }.distinct()
            for (h in tryHashes) {
                val plain = runCatching { decryptPlaylist(body, h) }.getOrNull()
                if (looksLikePlaylist(plain)) return Stream(null, absolutize(plain!!, url), "$via+giải mã")
            }
            return null
        }
        return if (looksLikePlaylist(body)) Stream(url, null, via) else null
    }

    // ----- tầng 2: bootstrap

    private suspend fun viaBootstrap(embed: String): Stream? {
        val origin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: return null
        val shell = app.get(embed, referer = "$mainUrl/", headers = mapOf("User-Agent" to UA))
        val cfg = Regex("""<script id="stream-bootstrap"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(shell.text)?.groupValues?.get(1) ?: return null
        val api = org.json.JSONObject(cfg).optString("api").takeIf { it.isNotBlank() } ?: return null

        val body = org.json.JSONObject().apply {
            put("action", "bootstrap")
            put("referrer", "$mainUrl/")
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
            headers = mapOf("Origin" to origin, "Referer" to embed, "User-Agent" to UA, "Accept" to "*/*")
        )
        if (resp.code != 200) return null

        var j = org.json.JSONObject(resp.text)
        if (j.optString("format") == "aesgcm-v1") {
            val aad = "stream-bootstrap-v1\n$api".toByteArray()
            val plain = gcm(
                sha256(aad), hex(j.getString("iv")),
                android.util.Base64.decode(j.getString("data"), android.util.Base64.DEFAULT), aad
            )
            j = org.json.JSONObject(String(plain))
        }
        val video = j.optString("video").ifBlank { hashOf(embed) }
        val playlist = j.optJSONObject("preissued")?.optString("playlist")?.takeIf { it.isNotBlank() } ?: return null
        return direct(playlist, embed, "bootstrap", listOf(video))
    }

    // ----- tầng 3: data-obf (kiểu cũ)

    private suspend fun viaDataObf(embed: String): Stream? {
        val origin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: return null
        val html = app.get(embed, referer = "$mainUrl/", headers = mapOf("User-Agent" to UA)).text
        val obf = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.get(1) ?: return null
        val j = org.json.JSONObject(String(android.util.Base64.decode(obf, android.util.Base64.DEFAULT)))
        val sub = j.optString("sUb").takeIf { it.isNotBlank() } ?: return null
        return direct("$origin/$sub.m3u8", embed, "data-obf", listOf(j.optString("hD")))
    }

    // ----- tầng 4: quét HTML (kể cả iframe con)

    private suspend fun viaScan(embed: String): Stream? {
        val seen = mutableSetOf<String>()
        suspend fun scan(url: String, depth: Int): Stream? {
            if (!seen.add(url) || depth > 1) return null
            val html = app.get(url, referer = "$mainUrl/", headers = mapOf("User-Agent" to UA)).text
                .replace("\\/", "/")
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

    // ----- tầng 5: WebView

    private suspend fun viaWebView(embed: String): Stream? {
        val sniffed = app.get(
            embed,
            referer = "$mainUrl/",
            interceptor = WebViewResolver(Regex("""\.m3u8"""))
        ).url
        if (!sniffed.contains(".m3u8")) return null
        return direct(sniffed, embed, "webview", listOf(hashOf(embed)))
    }

    // ---------------------------------------------------------------- mật mã

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

    // ---------------------------------------------------------------- dữ liệu

    data class Paginate(
        @JsonProperty("current_page") val currentPage: Int?,
        @JsonProperty("total_page") val totalPage: Int?
    )

    data class Item(
        val name: String?,
        val slug: String?,
        val year: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("thumb_url") val thumbUrl: String?
    )

    data class PageResponse(val paginate: Paginate?, val items: List<Item>?)

    data class EpisodeItem(val name: String?, val slug: String?, val embed: String?, val m3u8: String?)

    data class Server(@JsonProperty("server_name") val serverName: String?, val items: List<EpisodeItem>?)

    data class Movie(
        val name: String?,
        val year: Int?,
        val description: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("thumb_url") val thumbUrl: String?,
        val episodes: List<Server>?
    )

    data class DetailResponse(val movie: Movie?)
}
