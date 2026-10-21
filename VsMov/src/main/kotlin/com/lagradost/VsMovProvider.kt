package com.lagradost

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*

// Ngăn cách giữa m3u8 | embed | địa chỉ trang phim trong dữ liệu mỗi tập
private const val VS_SEP = "|||"

// Đặt false khi đã chạy ổn để tắt việc chép log vào clipboard
private const val DEBUG = true

/** Máy chủ HTTP nhỏ trên 127.0.0.1: đưa playlist đã tải/kiểm tra sẵn cho trình phát (mỗi lần phát một địa chỉ riêng, không dính cache cũ). */
private object LocalPlaylist {
    private val store = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var server: java.net.ServerSocket? = null

    @Synchronized
    fun url(text: String): String {
        val s = server ?: java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")).also {
            server = it
            Thread {
                while (!it.isClosed) {
                    val c = try { it.accept() } catch (e: Exception) { break }
                    Thread { handle(c) }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
        }
        val id = "pl_${System.nanoTime()}.m3u8"
        store[id] = text
        return "http://127.0.0.1:${s.localPort}/$id"
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

// Context của ứng dụng, dùng để mở trình duyệt khi không có link phát được
private var vsAppCtx: Context? = null

@CloudstreamPlugin
class VsMovPlugin : Plugin() {
    override fun load(context: Context) {
        vsAppCtx = context.applicationContext ?: context
        registerMainAPI(VsMovProvider())
    }
}

class VsMovProvider : MainAPI() {
    override var mainUrl = "https://vsmov.com"
    override var name = "VSMov"
    override val hasMainPage = true
    override var lang = "vi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    private val apiUrl = "$mainUrl/api"

    // Hai mục đầu có trong ví dụ của tài liệu API (đã kiểm tra).
    // Các mục sau chưa kiểm tra: nếu endpoint không tồn tại thì mục đó sẽ trống.
    override val mainPage = mainPageOf(
        "phim-moi-cap-nhat" to "Phim Mới Cập Nhật",
        "subteam" to "Phim Subteam",
        "phim-bo" to "Phim Bộ",
        "phim-le" to "Phim Lẻ",
        "hoat-hinh" to "Hoạt Hình"
    )

    // Địa chỉ trang web của phim (dùng cho nút mở trình duyệt). Dạng /phim/<slug> là suy đoán.
    private fun pageUrl(slug: String) = "$mainUrl/phim/$slug"

    private fun slugOf(url: String) =
        url.trimEnd('/').substringAfterLast('/').substringBefore('?')

    // Ảnh có thể là chuỗi (đường dẫn tuyệt đối/tương đối) hoặc đối tượng rỗng {}
    private fun fixImg(v: Any?, base: String?): String? {
        val s = (v as? String)?.takeIf { it.isNotBlank() } ?: return null
        return if (s.startsWith("http")) s else (base ?: "") + s.trimStart('/')
    }

    private fun stripHtml(s: String?): String? =
        s?.replace(Regex("<[^>]*>"), " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() }

    private fun VsItem.toSearchResponse(base: String?): SearchResponse? {
        val title = name ?: return null
        val slug = slug ?: return null
        val poster = fixImg(posterUrl, base) ?: fixImg(thumbUrl, base)
        val isSeries = tmdb?.type == "tv"
        return newMovieSearchResponse(
            title,
            pageUrl(slug),
            if (isSeries) TvType.TvSeries else TvType.Movie
        ) {
            this.posterUrl = poster
            this.year = this@toSearchResponse.year
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get("$apiUrl/danh-sach/${request.data}?page=$page").parsedSafe<VsListResponse>()
        val items = (res?.items ?: res?.data?.items)
            ?.mapNotNull { it.toSearchResponse(res?.pathImage) } ?: emptyList()
        val cur = res?.pagination?.currentPage ?: page
        val total = res?.pagination?.totalPages ?: 0
        return newHomePageResponse(request.name, items, hasNext = cur < total)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val res = app.get("$apiUrl/tim-kiem?keyword=$q&limit=30").parsedSafe<VsListResponse>()
        return (res?.items ?: res?.data?.items)
            ?.mapNotNull { it.toSearchResponse(res?.pathImage) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = slugOf(url)
        if (slug.isBlank()) return null
        val res = app.get("$apiUrl/phim/$slug").parsedSafe<VsDetailResponse>() ?: return null
        val movie = res.movie ?: return null
        val title = movie.name ?: return null
        val webUrl = pageUrl(slug)
        val base = res.pathImage
        val poster = fixImg(movie.posterUrl, base) ?: fixImg(movie.thumbUrl, base)
        val backdrop = fixImg(movie.thumbUrl, base)
        // Ghép thêm diễn viên và đạo diễn vào phần mô tả (addActors không có trong phiên bản CloudStream này)
        val cast = movie.actor?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")?.let { "Diễn viên: $it" }
        val dir = movie.director?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")?.let { "Đạo diễn: $it" }
        val plot = listOfNotNull(stripHtml(movie.content), cast, dir)
            .joinToString("\n\n").takeIf { it.isNotBlank() }
        val tags = movie.category?.mapNotNull { it.name }
        val duration = movie.time?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

        val servers = res.episodes ?: emptyList()
        val multiServer = servers.size > 1
        val episodes = mutableListOf<Episode>()
        servers.forEach { server ->
            val serverName = server.serverName?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotBlank() } ?: "Server"
            (server.serverData ?: server.items)?.forEach { ep ->
                val m3u8 = ep.linkM3u8 ?: ep.m3u8
                val embed = ep.linkEmbed ?: ep.embed
                if (m3u8.isNullOrBlank() && embed.isNullOrBlank()) return@forEach
                val epName = ep.name ?: ep.filename ?: "1"
                val label = if (epName.all { it.isDigit() }) "Tập $epName" else epName
                episodes.add(
                    newEpisode("${m3u8.orEmpty()}$VS_SEP${embed.orEmpty()}$VS_SEP$webUrl") {
                        this.name = if (multiServer) "$serverName - $label" else label
                        this.episode = Regex("\\d+").find(epName)?.value?.toIntOrNull()
                    }
                )
            }
        }

        val isSingle = movie.type == "single" || episodes.size <= 1
        val tvType = when {
            movie.type == "hoathinh" -> TvType.Anime
            isSingle -> TvType.Movie
            else -> TvType.TvSeries
        }

        return if (isSingle) {
            newMovieLoadResponse(title, webUrl, TvType.Movie, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = movie.year
                this.tags = tags
                this.duration = duration
            }
        } else {
            newTvSeriesLoadResponse(title, webUrl, tvType, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = movie.year
                this.tags = tags
                this.duration = duration
            }
        }
    }

    private fun openInBrowser(url: String) {
        val c = vsAppCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    c.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
    }

    private fun originOf(url: String): String {
        val m = Regex("^(https?://[^/]+)").find(url)
        return (m?.groupValues?.get(1) ?: mainUrl) + "/"
    }

    private suspend fun isHls(url: String, ref: String): Boolean {
        val r = runCatching { app.get(url, referer = ref) }.getOrNull()
        return r?.text?.trimStart()?.startsWith("#EXTM3U") == true
    }

    // Đọc trang embed bằng yêu cầu thường (Referer là vsmov.com) và tìm link .m3u8 trong mã nguồn.
    // Chỉ thành công nếu trang để lộ link playlist; không giải mã hay can thiệp gì thêm.
    private suspend fun findM3u8(embed: String, log: StringBuilder): String? {
        val embedOrigin = originOf(embed)
        val page = runCatching { app.get(embed, referer = "$mainUrl/").text }.getOrNull()
        log.append("embed=").append(embed).append('\n')
        log.append("html len=").append(page?.length).append('\n')
        log.append("html head=").append(page?.replace(Regex("\\s+"), " ")?.take(1500)).append('\n')
        val text = page?.replace("\\/", "/") ?: return null

        val scripts = Regex("src=[\"']([^\"']+\\.js[^\"']*)[\"']").findAll(text).map { it.groupValues[1] }.toList()
        log.append("scripts=").append(scripts).append('\n')

        val absolute = Regex("https?://[^\"'\\s<>\\\\]+\\.m3u8[^\"'\\s<>\\\\]*")
            .findAll(text).map { it.value }.distinct().toList()
        log.append("m3u8 tuyệt đối=").append(absolute).append('\n')
        for (c in absolute) if (isHls(c, embed)) return c

        val relative = Regex("[\"'](/[^\"'\\s]+\\.m3u8[^\"'\\s]*)[\"']")
            .findAll(text).map { it.groupValues[1] }.distinct().toList()
        log.append("m3u8 tương đối=").append(relative).append('\n')

        // Ghi lại đoạn mã quanh chữ master.m3u8 và chỗ khởi tạo trình phát để biết trang dùng link đó thế nào
        var idx = text.indexOf("master.m3u8")
        var n = 0
        while (idx >= 0 && n < 3) {
            val seg = text.substring(maxOf(0, idx - 300), minOf(text.length, idx + 200))
            log.append("ctx master.m3u8@").append(idx).append(": ")
                .append(seg.replace(Regex("\\s+"), " ")).append('\n')
            idx = text.indexOf("master.m3u8", idx + 1)
            n++
        }
        val setupIdx = text.indexOf("setup(")
        if (setupIdx >= 0) {
            val seg = text.substring(maxOf(0, setupIdx - 200), minOf(text.length, setupIdx + 500))
            log.append("ctx setup: ").append(seg.replace(Regex("\\s+"), " ")).append('\n')
        }

        // Thử các địa chỉ có thể suy ra từ đường dẫn tương đối (yêu cầu thường, Referer là trang embed)
        for (r in relative) {
            val cands = listOf(
                embedOrigin.trimEnd('/') + r,
                embed.trimEnd('/') + r,
                embed.substringBeforeLast('/') + r
            ).distinct()
            for (c in cands) {
                val resp = runCatching { app.get(c, referer = embed) }.getOrNull()
                val ok = resp?.text?.trimStart()?.startsWith("#EXTM3U") == true
                log.append("thử ").append(c).append(" code=").append(resp?.code)
                    .append(" hls=").append(ok)
                    .append(" nd=").append(resp?.text?.trimStart()?.take(60)?.replace(Regex("\\s+"), " "))
                    .append('\n')
                if (ok) return c
            }
        }
        return null
    }

    private val vsUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private fun playerHeaders(ref: String) = mapOf(
        "User-Agent" to vsUa,
        "Accept" to "*/*",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to ref
    )

    private fun resolveUrl(u: String, base: String): String = when {
        u.startsWith("http") -> u
        u.startsWith("//") -> "https:$u"
        u.startsWith("/") -> (Regex("^(https?://[^/]+)").find(base)?.groupValues?.get(1) ?: "") + u
        else -> base.substringBefore('?').substringBeforeLast('/') + "/" + u
    }

    // Đổi mọi đường dẫn tương đối (dòng segment, URI="..." của khóa/map) thành URL tuyệt đối
    private fun absAll(text: String, base: String): String {
        val withUri = Regex("""URI="([^"]+)"""").replace(text) { """URI="${resolveUrl(it.groupValues[1], base)}"""" }
        return withUri.lines().joinToString("\n") { l ->
            val t = l.trim()
            if (t.isEmpty() || t.startsWith("#")) l else resolveUrl(t, base)
        }
    }

    // Tải playlist đủ và hợp lệ: bắt đầu #EXTM3U; playlist VOD (có #EXTINF) phải kết thúc bằng #EXT-X-ENDLIST.
    // Playlist bị cắt cụt sẽ được coi là "trực tiếp" nên trình phát chạy đến hết đoạn có rồi lỗi 3002.
    private suspend fun fetchPlaylist(url: String, ref: String): String? {
        var best: String? = null
        for (attempt in 1..3) {
            val body = runCatching { app.get(url, referer = ref, headers = playerHeaders(ref)).text }.getOrNull()
            if (body != null && body.trimStart().startsWith("#EXTM3U")) {
                val isMedia = body.contains("#EXTINF")
                if (!isMedia || body.contains("#EXT-X-ENDLIST")) return body
                if (best == null || body.length > best.length) best = body
            }
        }
        return best
    }

    // Từ một link m3u8 (master hoặc media) tạo các link phát qua máy chủ cục bộ; trả về số link đã gửi
    private suspend fun emitHls(url: String, ref: String, callback: (ExtractorLink) -> Unit): Int {
        val text = fetchPlaylist(url, ref) ?: return 0
        val abs = absAll(text, url)
        var count = 0
        if (abs.contains("#EXT-X-STREAM-INF")) {
            val ls = abs.lines()
            class V(val bw: Long, val h: Int, val url: String)
            val vs = mutableListOf<V>()
            for (i in ls.indices) {
                if (!ls[i].startsWith("#EXT-X-STREAM-INF")) continue
                val bw = Regex("""BANDWIDTH=(\d+)""").find(ls[i])?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val h = Regex("""RESOLUTION=\d+x(\d+)""").find(ls[i])?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val u = ls.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim() ?: continue
                vs.add(V(bw, h, u))
            }
            for (v in vs.sortedByDescending { it.bw }.take(4)) {
                val vt = fetchPlaylist(v.url, ref) ?: continue
                val link = LocalPlaylist.url(absAll(vt, v.url))
                callback(
                    newExtractorLink(name, if (v.h > 0) "$name ${v.h}p" else name, link, ExtractorLinkType.M3U8) {
                        this.referer = ref
                        this.quality = if (v.h > 0) v.h else Qualities.Unknown.value
                        this.headers = playerHeaders(ref)
                    }
                )
                count++
            }
        } else {
            callback(
                newExtractorLink(name, name, LocalPlaylist.url(abs), ExtractorLinkType.M3U8) {
                    this.referer = ref
                    this.quality = Qualities.Unknown.value
                    this.headers = playerHeaders(ref)
                }
            )
            count++
        }
        return count
    }

    // Chép log vào clipboard để dán ra chat (chỉ khi DEBUG = true)
    private fun copyToClipboard(text: String) {
        val c = vsAppCtx ?: return
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("VSMov log", text))
                    android.widget.Toast.makeText(c, "VSMov: đã chép log vào clipboard", android.widget.Toast.LENGTH_LONG).show()
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
        val parts = data.split(VS_SEP, limit = 3)
        val m3u8 = parts.getOrNull(0).orEmpty()
        val embed = parts.getOrNull(1).orEmpty()
        val page = parts.getOrNull(2).orEmpty().ifBlank { mainUrl }
        val ref = "$mainUrl/"

        // 1) Link m3u8 từ API: chỉ dùng nếu là playlist HLS thật
        if (m3u8.startsWith("http")) {
            if (emitHls(m3u8, ref, callback) > 0) return true
            // Dự phòng: cách cũ của CloudStream
            val body = runCatching { app.get(m3u8, referer = ref).text }.getOrNull()
            if (body?.trimStart()?.startsWith("#EXTM3U") == true) {
                var ok = false
                runCatching {
                    M3u8Helper.generateM3u8(name, m3u8, ref).forEach {
                        callback(it)
                        ok = true
                    }
                }
                if (ok) return true
            }
        }

        // 2) Link embed: đọc trang embed, tìm link .m3u8 hiện trong mã nguồn trang (nếu có)
        if (embed.startsWith("http")) {
            val log = StringBuilder()
            val found = findM3u8(embed, log)
            if (found != null) {
                if (emitHls(found, originOf(embed), callback) > 0) return true
                var ok = false
                runCatching {
                    M3u8Helper.generateM3u8(name, found, originOf(embed)).forEach {
                        callback(it)
                        ok = true
                    }
                }
                if (ok) return true
            }
            // Dự phòng: các extractor có sẵn của CloudStream
            var viaExtractor = false
            runCatching {
                loadExtractor(embed, ref, subtitleCallback) {
                    viaExtractor = true
                    callback(it)
                }
            }
            if (viaExtractor) return true
            if (DEBUG) copyToClipboard("VSMOV debug\n$log")
        }

        // 3) Không có link phát được: mở trang phim trên trình duyệt
        openInBrowser(page)
        return false
    }

    // ---------- Mô hình dữ liệu ----------
    data class VsTmdb(
        val type: String?,
        val id: String?
    )

    data class VsItem(
        val tmdb: VsTmdb?,
        val name: String?,
        @JsonProperty("origin_name") val originName: String?,
        val slug: String?,
        @JsonProperty("poster_url") val posterUrl: Any?,
        @JsonProperty("thumb_url") val thumbUrl: Any?,
        val year: Int?
    )

    data class VsPagination(
        val totalItems: Int?,
        val totalItemsPerPage: Int?,
        val currentPage: Int?,
        val totalPages: Int?
    )

    data class VsListData(
        val items: List<VsItem>?
    )

    data class VsListResponse(
        val status: Boolean?,
        val items: List<VsItem>?,
        val data: VsListData?,
        val pathImage: String?,
        val pagination: VsPagination?
    )

    data class VsNamed(
        val id: Int?,
        val name: String?,
        val slug: String?
    )

    data class VsMovie(
        val name: String?,
        @JsonProperty("origin_name") val originName: String?,
        val slug: String?,
        val content: String?,
        val type: String?,
        @JsonProperty("poster_url") val posterUrl: Any?,
        @JsonProperty("thumb_url") val thumbUrl: Any?,
        val time: String?,
        val year: Int?,
        val actor: List<String>?,
        val director: List<String>?,
        val category: List<VsNamed>?,
        val country: List<VsNamed>?
    )

    // Cấu trúc tập phim chưa kiểm tra (ví dụ trong tài liệu có episodes rỗng),
    // nên khai báo cả hai kiểu tên trường thường gặp
    data class VsEpisodeItem(
        val name: String?,
        val slug: String?,
        val filename: String?,
        @JsonProperty("link_embed") val linkEmbed: String?,
        @JsonProperty("link_m3u8") val linkM3u8: String?,
        val embed: String?,
        val m3u8: String?
    )

    data class VsServer(
        @JsonProperty("server_name") val serverName: String?,
        @JsonProperty("server_data") val serverData: List<VsEpisodeItem>?,
        val items: List<VsEpisodeItem>?
    )

    data class VsDetailResponse(
        val status: Boolean?,
        val movie: VsMovie?,
        val episodes: List<VsServer>?,
        val pathImage: String?
    )
}
