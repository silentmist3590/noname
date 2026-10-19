package com.lagradost

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

// Ngăn cách giữa link m3u8 và link embed trong dữ liệu của mỗi tập
private const val LINK_SEP = "|||"

// Context của ứng dụng, dùng để hiện thông báo chẩn đoán
private var appCtx: Context? = null

// Máy chủ HTTP nhỏ trên 127.0.0.1 để phát playlist đã giải mã cho trình phát
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
class PhimNguonCPlugin : Plugin() {
    override fun load(context: Context) {
        appCtx = context.applicationContext ?: context
        registerMainAPI(PhimNguonCProvider())
    }
}

class PhimNguonCProvider : MainAPI() {
    override var mainUrl = "https://phim.nguonc.com"
    override var name = "Phim NguonC"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "the-loai/hanh-dong" to "Phim Hành Động",
        "the-loai/phim-hai" to "Phim Hài",
        "the-loai/chien-tranh" to "Phim Chiến Tranh",
        "the-loai/lich-su" to "Phim Lịch Sử",
        "the-loai/phim-18" to "Phim 18+",
        "danh-sach/phim-bo" to "Phim Bộ",
        "danh-sach/phim-le" to "Phim Lẻ"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/api/films/${request.data}?page=$page"
        val res = app.get(url).parsedSafe<NguonCListResponse>()
        val homeItems = res?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        val hasNext = (res?.paginate?.currentPage ?: 0) < (res?.paginate?.totalPage ?: 0)
        return newHomePageResponse(request.name, homeItems, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/films/search?keyword=$query"
        val res = app.get(url).parsedSafe<NguonCListResponse>()
        return res?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    private fun NguonCItem.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val poster = posterUrl ?: thumbUrl
        val yearInt = year?.toIntOrNull()
        return newMovieSearchResponse(title, "$mainUrl/api/film/$slug", TvType.Movie) {
            this.posterUrl = poster
            this.year = yearInt
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val res = app.get(url).parsedSafe<NguonCDetailResponse>() ?: return null
        val movie = res.movie ?: return null
        val title = movie.name ?: return null
        val poster = movie.posterUrl ?: movie.thumbUrl
        val desc = movie.description
        val episodes = mutableListOf<Episode>()

        movie.episodes?.forEach { server ->
            val serverName = server.serverName ?: "NguonC"
            server.items?.forEach { ep ->
                val epName = ep.name ?: "1"
                val epNum = epName.toIntOrNull()
                val embed = ep.embed
                val m3u8 = ep.m3u8
                if (!embed.isNullOrBlank() || !m3u8.isNullOrBlank()) {
                    episodes.add(
                        newEpisode("${m3u8.orEmpty()}$LINK_SEP${embed.orEmpty()}") {
                            this.name = "$serverName - Tập $epName"
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = desc
            this.year = movie.year
        }
    }

    private fun headOf(text: String?, n: Int = 70): String =
        text?.trimStart()?.take(n)?.replace(Regex("\\s+"), " ") ?: "null"

    // Hiện thông báo nổi để xem chẩn đoán khi không có công cụ xem log
    private fun toast(msg: String) {
        val c = appCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(c, msg.take(200), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Chép nhật ký chẩn đoán vào clipboard để người dùng dán ra xem
    private fun copyToClipboard(text: String) {
        val c = appCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("PhimNguonC log", text))
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val log = StringBuilder()
        fun note(msg: String) {
            log.append(msg).append('\n')
            toast(msg)
            copyToClipboard(log.toString())
        }
        note("v24 loadLinks data=${data.take(160)}")
        return try {
            loadLinksInner(data, callback, ::note)
        } catch (t: Throwable) {
            note("v24 EXCEPTION ${t.javaClass.simpleName}: ${t.message?.take(160)}")
            false
        }
    }

    private suspend fun loadLinksInner(
        data: String,
        callback: (ExtractorLink) -> Unit,
        note: (String) -> Unit
    ): Boolean {
        val parts = data.split(LINK_SEP, limit = 2)
        val embed = (if (parts.size == 2) parts[1] else data).takeIf { it.isNotBlank() }
        val apiM3u8 = (if (parts.size == 2) parts[0] else "").takeIf { it.isNotBlank() && it.contains(".m3u8") }
        val embedRef = embed?.let { originOf(it) } ?: "$mainUrl/"

        suspend fun emit(url: String, ref: String): Boolean {
            var ok = false
            runCatching {
                M3u8Helper.generateM3u8(name, url, ref).forEach {
                    callback(it)
                    ok = true
                }
            }
            if (!ok) {
                callback(
                    newExtractorLink(name, name, url, ExtractorLinkType.M3U8) {
                        this.referer = ref
                        this.quality = Qualities.Unknown.value
                    }
                )
                ok = true
            }
            return ok
        }

        // 1) Link m3u8 trực tiếp từ API (nếu có)
        note("v24 apiM3u8=${apiM3u8 ?: "null"} embed=${embed ?: "null"}")
        if (apiM3u8 != null && emit(apiM3u8, embedRef)) return true
        if (embed == null) return false

        // 1b) Giao thức bootstrap của chính player embed (AES-GCM), giải mã bằng khóa dẫn xuất công khai
        val boot = runCatching { bootstrapPlaylist(embed, note) }.getOrElse {
            note("v24 bootstrap lỗi: ${it.javaClass.simpleName} ${it.message?.take(120)}")
            null
        }
        if (boot != null) {
            val firstSeg = boot.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            note("v24 đoạn đầu=${firstSeg?.take(120)}")
            val embedOrigin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1).orEmpty()
            val browser = mapOf(
                "User-Agent" to UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-us,en;q=0.5",
                "Sec-Fetch-Mode" to "navigate",
                "Referer" to embed
            )
            val candidates = listOf(
                "A" to browser,
                "B" to browser + mapOf("Accept-Encoding" to "gzip, deflate"),
                "C" to browser + mapOf("Origin" to embedOrigin),
                "D" to mapOf("User-Agent" to UA, "Referer" to "$embedOrigin/")
            )
            var segHeaders: Map<String, String> = browser
            if (firstSeg != null && firstSeg.startsWith("http")) {
                var found = false
                for ((tag, h) in candidates) {
                    val pr = runCatching { app.get(firstSeg, headers = h) }.getOrNull()
                    note("v24 thử đoạn [$tag] code=${pr?.code} kích thước=${pr?.text?.length} server=${pr?.headers?.get("server")} mitigated=${pr?.headers?.get("cf-mitigated")} đầu=${headOf(pr?.text, 90)}")
                    if (pr != null && pr.code in 200..299) { segHeaders = h; found = true; break }
                }
                if (!found) {
                    note("v24 không bộ header nào qua được")
                    val h1 = runCatching { probeHttp11(firstSeg, browser) }.getOrElse { "lỗi ${it.javaClass.simpleName} ${it.message?.take(60)}" }
                    note("v24 thử HTTP/1.1: $h1")
                }
            }
            val ok = runCatching {
                val local = LocalPlaylist.url(boot)
                callback(
                    newExtractorLink(name, name, local, ExtractorLinkType.M3U8) {
                        this.referer = embed
                        this.quality = Qualities.Unknown.value
                        this.headers = segHeaders
                    }
                )
                true
            }.getOrDefault(false)
            note("v24 bootstrap phát ok=$ok")
            if (ok) return true
        }

        // 2) Trang embed mã hóa dữ liệu và tự giải mã bằng JS: cho nó chạy trong WebView
        //    thật rồi bắt yêu cầu .m3u8 mà player phát ra.
        note("v24: đang mở trang phát trong WebView...")
        val sniffed = runCatching {
            app.get(
                embed,
                referer = "$mainUrl/",
                interceptor = WebViewResolver(Regex("""\.m3u8"""))
            ).url
        }.getOrElse {
            note("v24 lỗi WebView: ${it.javaClass.simpleName} ${it.message?.take(80)}")
            null
        }
        if (sniffed == null || !sniffed.contains(".m3u8")) {
            note("v24: không bắt được .m3u8. sniff=${sniffed?.take(120)}")
            return false
        }
        note("v24: bắt được ${sniffed.take(150)}")

        val probe = runCatching { app.get(sniffed, referer = embedRef) }.getOrNull()
        val body = probe?.text
        note("v24: kiểm tra link code=${probe?.code} nội dung=${headOf(body, 60)}")
        if (body?.trimStart()?.startsWith("#EXTM3U") == true) {
            val r = emit(sniffed, embedRef)
            note("v24 đã gửi link cho trình phát ok=$r")
            return r
        }
        return false
    }


    private val UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


    // Thử tải bằng HTTP/1.1 thuần (app PC dùng HTTP/1.1 cho CDN, OkHttp mặc định dùng HTTP/2)
    private fun probeHttp11(url: String, hdrs: Map<String, String>): String {
        val client = app.baseClient.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .build()
        val rb = okhttp3.Request.Builder().url(url)
        hdrs.forEach { (k, v) -> rb.header(k, v) }
        return client.newCall(rb.build()).execute().use { r ->
            val b = r.body?.bytes() ?: ByteArray(0)
            "code=${r.code} proto=${r.protocol} kích thước=${b.size} server=${r.header("server")} đầu=${headOf(String(b.take(90).toByteArray()), 90)}"
        }
    }

    private fun sha256(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(b)

    private fun gcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv))
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Luồng giống player của trang embed (tham khảo NguonC-Downloader, GPL-3.0)
    private suspend fun bootstrapPlaylist(embed: String, note: (String) -> Unit): String? {
        val origin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1) ?: return null
        val shell = app.get(embed, referer = "$mainUrl/", headers = mapOf("User-Agent" to UA))
        note("v24 shell code=${shell.code} len=${shell.text.length}")
        val api = Regex("""<script id="stream-bootstrap"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(shell.text)?.groupValues?.get(1)?.let { org.json.JSONObject(it).optString("api") }
        if (api.isNullOrBlank()) { note("v24 không thấy stream-bootstrap"); return null }
        val hashFromUrl = Regex("""[?&]hash=([0-9a-f]+)""").find(embed)?.groupValues?.get(1).orEmpty()

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
        note("v24 bootstrap POST code=${resp.code} len=${resp.text.length}")
        if (resp.code != 200) { note("v24 ${headOf(resp.text, 80)}"); return null }

        var j = org.json.JSONObject(resp.text)
        if (j.optString("format") == "aesgcm-v1") {
            val aad = "stream-bootstrap-v1\n$api".toByteArray()
            val plain = gcmDecrypt(sha256(aad), hex(j.getString("iv")), android.util.Base64.decode(j.getString("data"), android.util.Base64.DEFAULT), aad)
            j = org.json.JSONObject(String(plain))
        }
        val video = j.optString("video").ifBlank { hashFromUrl }
        val pl = j.optJSONObject("preissued")?.optString("playlist")
        if (pl.isNullOrBlank()) { note("v24 không có preissued (turnstile=${j.optBoolean("turnstileEnabled")})"); return null }

        val plResp = app.get(pl, referer = embed, headers = mapOf("User-Agent" to UA))
        note("v24 playlist code=${plResp.code} đầu=${headOf(plResp.text, 40)}")
        var text = plResp.text
        if (text.contains("#ENC-AESGCM")) {
            val iv = Regex("""iv=([0-9a-fA-F]+)""").find(text)?.groupValues?.get(1) ?: return null
            val line = text.lines().map { it.trim() }.lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec("stream-derive-v1".toByteArray(), "HmacSHA256"))
            val key = mac.doFinal(video.toByteArray())
            text = String(gcmDecrypt(key, hex(iv), android.util.Base64.decode(line, android.util.Base64.DEFAULT), null))
        }
        return text.takeIf { it.trimStart().startsWith("#EXTM3U") }
    }

    private fun originOf(url: String): String {
        val m = Regex("""^(https?://[^/]+)""").find(url)
        return (m?.groupValues?.get(1) ?: mainUrl) + "/"
    }

    data class NguonCPaginate(
        @JsonProperty("current_page") val currentPage: Int?,
        @JsonProperty("total_page") val totalPage: Int?,
        @JsonProperty("total_items") val totalItems: Int?,
        @JsonProperty("items_per_page") val itemsPerPage: Int?
    )

    data class NguonCItem(
        val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        val slug: String?,
        val year: String?,
        val description: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("thumb_url") val thumbUrl: String?
    )

    data class NguonCListResponse(
        val status: String?,
        val paginate: NguonCPaginate?,
        val items: List<NguonCItem>?
    )

    data class NguonCEpisodeItem(
        val name: String?,
        val slug: String?,
        val embed: String?,
        val m3u8: String?
    )

    data class NguonCServer(
        @JsonProperty("server_name") val serverName: String?,
        val items: List<NguonCEpisodeItem>?
    )

    data class NguonCMovie(
        val name: String?,
        @JsonProperty("original_name") val originalName: String?,
        val slug: String?,
        val year: Int?,
        val description: String?,
        @JsonProperty("poster_url") val posterUrl: String?,
        @JsonProperty("thumb_url") val thumbUrl: String?,
        val episodes: List<NguonCServer>?
    )

    data class NguonCDetailResponse(
        val status: String?,
        val movie: NguonCMovie?
    )
}
