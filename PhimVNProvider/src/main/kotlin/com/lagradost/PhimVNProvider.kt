package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import com.phimvn.ClipboardLogger
import com.phimvn.PhimVNSniffer
import com.phimvn.SniffHit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

private const val LINK_SEP = "|||"
private const val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

@CloudstreamPlugin
class PhimVNPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PhimVNProvider())
    }
}

class PhimVNProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "PhimVN"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/phim-le" to "Phim Lẻ",
        "danh-sach/hoat-hinh" to "Hoạt Hình"
    )

    // ---------------------------------------------------------------- HOME
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("HOME", "page=$page name=${request.name}")

        val res = app.get("$mainUrl/api/films/${request.data}?page=$page")
            .parsedSafe<PageResponse>()
        val list = res?.items?.mapNotNull { it.toSearch() } ?: emptyList()
        val hasNext = (res?.paginate?.currentPage ?: 0) < (res?.paginate?.totalPage ?: 0)

        ClipboardLogger.logAndCopy("HOME", "→ ${list.size} items | hasNext=$hasNext")
        return newHomePageResponse(request.name, list, hasNext = hasNext)
    }

    // ---------------------------------------------------------------- SEARCH
    override suspend fun search(query: String): List<SearchResponse> {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("SEARCH", "query=$query")

        val res = app.get("$mainUrl/api/films/search?keyword=$query")
            .parsedSafe<PageResponse>()
        val list = res?.items?.mapNotNull { it.toSearch() } ?: emptyList()

        ClipboardLogger.logAndCopy("SEARCH", "→ ${list.size} results")
        return list
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

    // ---------------------------------------------------------------- LOAD
    override suspend fun load(url: String): LoadResponse? {
        ClipboardLogger.reset()
        ClipboardLogger.logAndCopy("LOAD", "url=$url")

        val movie = app.get(url).parsedSafe<DetailResponse>()?.movie ?: run {
            ClipboardLogger.logAndCopy("LOAD", "✗ API trả về null")
            return null
        }
        val title = movie.name ?: return null

        val episodes = mutableListOf<Episode>()
        var totalServers = 0
        var totalEps = 0

        movie.episodes?.forEach { server ->
            val serverName = server.serverName ?: "NguonC"
            totalServers++
            server.items?.forEach { ep ->
                totalEps++
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

        ClipboardLogger.logAndCopy(
            "LOAD",
            "✓ title=$title | servers=$totalServers | eps=$totalEps | validEpisodes=${episodes.size}"
        )

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = movie.posterUrl ?: movie.thumbUrl
            this.plot = movie.description
            this.year = movie.year
        }
    }

    // ---------------------------------------------------------------- LOAD LINKS
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
        ClipboardLogger.reset()

        val parts = data.split(LINK_SEP, limit = 2)
        val embed = (if (parts.size == 2) parts[1] else data).trim().takeIf { it.isNotBlank() }
        val apiM3u8 = (if (parts.size == 2) parts[0] else "").trim().takeIf { it.contains(".m3u8") }

        ClipboardLogger.logAndCopy("=== PLAY ===", "data=${data.take(200)}")
        ClipboardLogger.logAndCopy("SESSION", "embed=${embed ?: "null"}")
        ClipboardLogger.logAndCopy("SESSION", "apiM3u8=${apiM3u8 ?: "null"}")

        // ============ TẦNG 0: CloudflareKiller ============
    if (embed != null) {
        ClipboardLogger.logAndCopy("LAYER-cfKiller", "→ thử CloudflareKiller trên: $embed")
        try {
            val response = app.get(embed, interceptor = CloudflareKiller())
            if (response.code == 200) {
                ClipboardLogger.logAndCopy("CF-KILLER", "✓ Lấy được HTML, code=200")
                // Tìm m3u8 trong HTML trả về
                val html = response.text
                val m3u8Urls = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(html)
                for (m in m3u8Urls) {
                    val stream = direct(m.value, embed, "cfKiller-scan")
                    if (stream != null && emit(stream, embed, callback)) {
                        ClipboardLogger.logAndCopy("LINKS", "=== SUCCESS via CloudflareKiller ===")
                        return true
                    }
                }
                ClipboardLogger.logAndCopy("CF-KILLER", "✗ Không tìm thấy m3u8 trong HTML")
            } else {
                ClipboardLogger.logAndCopy("CF-KILLER", "✗ code=${response.code}")
            }
        } catch (t: Throwable) {
            ClipboardLogger.logAndCopy("CF-KILLER", "✗ Lỗi: ${t.message?.take(120)}")
        }
    }

        // ============ TẦNG 1: API trả m3u8 trực tiếp
        if (apiM3u8 != null) {
            ClipboardLogger.logAndCopy("LAYER-api", "→ thử API m3u8 trực tiếp")
            direct(apiM3u8, embed ?: mainUrl, "api")?.let { st ->
                if (emit(st, embed ?: mainUrl, callback)) {
                    ClipboardLogger.logAndCopy("LINKS", "=== SUCCESS via API ===")
                    return true
                }
            }
            ClipboardLogger.logAndCopy("LAYER-api", "✗ API không dùng được")
        }

        // ============ TẦNG 2: WebView sniff thông minh
        if (embed != null) {
            ClipboardLogger.logAndCopy("LAYER-sniffer", "→ khởi động WebView sniffer")
            val hits = try {
                PhimVNSniffer.sniff(
                    url = embed,
                    referer = "$mainUrl/",
                    timeoutMs = 60_000L,
                    waitForResultsMs = 8000L
                )
            } catch (t: Throwable) {
                ClipboardLogger.logAndCopy("SNIFFER", "✗ throw: ${t.message?.take(120)}")
                emptyList<SniffHit>()
            }

            ClipboardLogger.logAndCopy("SNIFFER", "Bắt được ${hits.size} URL")

            for (hit in hits) {
                ClipboardLogger.logAndCopy("HIT", "[${hit.source}] ${hit.url.take(150)}")
                val stream = direct(hit.url, embed, "sniffer-${hit.source}")
                if (stream != null && emit(stream, embed, callback)) {
                    ClipboardLogger.logAndCopy("LINKS", "=== SUCCESS via sniffer [${hit.source}] ===")
                    return true
                }
                // Fallback: tin URL sniffer nếu có .m3u8
                if (hit.url.contains(".m3u8", true)) {
                    ClipboardLogger.logAndCopy("EMIT-FALLBACK", "tin URL sniffer: ${hit.url.take(120)}")
                    try {
                        callback(
                            newExtractorLink(name, name, hit.url, ExtractorLinkType.M3U8) {
                                this.referer = embed
                                this.quality = Qualities.Unknown.value
                                this.headers = segHeaders(embed)
                            }
                        )
                        ClipboardLogger.logAndCopy("LINKS", "=== SUCCESS via fallback [${hit.source}] ===")
                        return true
                    } catch (t: Throwable) {
                        ClipboardLogger.logAndCopy("EMIT-FALLBACK", "✗ ${t.message?.take(80)}")
                    }
                }
            }
            ClipboardLogger.logAndCopy("LAYER-sniffer", "✗ không URL nào dùng được")
        }

        ClipboardLogger.logAndCopy("LINKS", "=== FAILED: tất cả tầng thất bại ===")
        return false
    }

    private suspend fun emit(
        st: Stream,
        embed: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean = runCatching {
        val link = if (st.text != null) LocalPlaylist.url(st.text) else st.url!!
        ClipboardLogger.logAndCopy(
            "EMIT",
            "url=${link.take(180)} | via=${st.via} | ${if (st.text != null) "text=${st.text.length}B" else "raw"}"
        )
        callback(
            newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                this.referer = embed
                this.quality = Qualities.Unknown.value
                this.headers = segHeaders(embed)
            }
        )
        true
    }.onFailure {
        ClipboardLogger.logAndCopy("EMIT", "✗ callback throw: ${it.message?.take(120)}")
    }.getOrDefault(false)

    private fun looksLikePlaylist(t: String?): Boolean = t != null &&
            t.trimStart().startsWith("#EXTM3U") &&
            t.lines().any { it.isNotBlank() && !it.startsWith("#") }

    private fun hashOf(embed: String): String =
        Regex("""[?&]hash=([0-9a-fA-F]+)""").find(embed)?.groupValues?.get(1).orEmpty()

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

    private suspend fun direct(
        url: String,
        embed: String,
        via: String,
        hashes: List<String> = emptyList()
    ): Stream? {
        ClipboardLogger.logAndCopy("DIRECT-$via", "GET ${url.take(200)}")
        val r = try {
            app.get(url, referer = embed, headers = segHeaders(embed))
        } catch (t: Throwable) {
            ClipboardLogger.logAndCopy("DIRECT-$via", "✗ fetch fail: ${t.message?.take(120)}")
            return null
        }

        ClipboardLogger.logAndCopy("DIRECT-$via", "code=${r.code} size=${r.text.length}")
        if (r.code != 200) return null
        val body = r.text

        if (body.contains("#ENC-AESGCM")) {
            ClipboardLogger.logAndCopy("DIRECT-$via", "playlist bị mã hoá AES-GCM")
            val tryHashes = (hashes + hashOf(embed)).filter { it.isNotBlank() }.distinct()
            ClipboardLogger.logAndCopy("DIRECT-$via", "thử ${tryHashes.size} hash")
            for (h in tryHashes) {
                val plain = runCatching { decryptPlaylist(body, h) }.getOrNull()
                if (looksLikePlaylist(plain)) {
                    ClipboardLogger.logAndCopy("DIRECT-$via", "✓ giải mã OK hash=${h.take(16)}…")
                    return Stream(null, absolutize(plain!!, url), "$via+decrypted")
                }
            }
            ClipboardLogger.logAndCopy("DIRECT-$via", "✗ decrypt fail")
            return null
        }

        return if (looksLikePlaylist(body)) {
            ClipboardLogger.logAndCopy("DIRECT-$via", "✓ playlist OK")
            Stream(url, null, via)
        } else {
            ClipboardLogger.logAndCopy(
                "DIRECT-$via",
                "✗ không phải playlist | head=${body.take(40).replace("\n", "\\n")}"
            )
            null
        }
    }

    private fun sha256(b: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(b)

    private fun gcm(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) {
        s.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    // ---------------------------------------------------------------- Local playlist server
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
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        if (!line.startsWith("HEAD")) out.write(body)
                    }
                    out.flush()
                }
            }
        }
    }

    // ---------------------------------------------------------------- Data models
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

    data class EpisodeItem(
        val name: String?,
        val slug: String?,
        val embed: String?,
        val m3u8: String?
    )

    data class Server(
        @JsonProperty("server_name") val serverName: String?,
        val items: List<EpisodeItem>?
    )

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
