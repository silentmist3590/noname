package com.phimvn

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private const val TAG = "PhimVN"
private const val LINK_SEP = "|||"
private const val UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

private object StreamCrypto {
    fun sha256(b: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(b)

    fun gcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv)
        )
        if (aad != null) c.updateAAD(aad)
        return c.doFinal(data)
    }

    fun hexToBytes(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun looksLikePlaylist(t: String?): Boolean {
        if (t == null) return false
        val clean = t.trimStart('\uFEFF').trimStart()
        return clean.startsWith("#EXTM3U") &&
            clean.lines().any { it.isNotBlank() && !it.trimStart().startsWith("#") }
    }

    fun decryptPlaylist(text: String, videoHash: String): String? {
        val clean = text.trimStart('\uFEFF')
        if (!clean.contains("#ENC-AESGCM") && !clean.contains("#EXT-X-B65")) return clean
        val iv = Regex("""iv=([0-9a-fA-F]+)""").find(clean)?.groupValues?.getOrNull(1) ?: return null
        val line = clean.lines().map { it.trim() }
            .lastOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("stream-derive-v1".toByteArray(), "HmacSHA256"))
        val key = mac.doFinal(videoHash.toByteArray())
        val plain = gcmDecrypt(key, hexToBytes(iv), Base64.decode(line, Base64.DEFAULT), null)
        return String(plain, Charsets.UTF_8)
    }

    fun originOf(url: String): String =
        Regex("""^(https?://[^/]+)""").find(url)?.groupValues?.getOrNull(1).orEmpty()

    fun resolveUrl(base: String, ref: String): String {
        val trimmed = ref.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        val origin = originOf(base)
        if (trimmed.startsWith("//")) {
            val scheme = if (base.startsWith("http://", ignoreCase = true)) "http:" else "https:"
            return scheme + trimmed
        }
        if (trimmed.startsWith("/") && origin.isNotEmpty()) {
            return origin + trimmed
        }
        val dir = base.substringBefore('#').substringBefore('?').substringBeforeLast('/') + "/"
        return dir + trimmed
    }

    fun hashOf(embed: String): String =
        Regex("""[?&]hash=([0-9a-fA-F]+)""").find(embed)?.groupValues?.getOrNull(1).orEmpty()
}

/**
 * Proxy HTTP cục bộ trên 127.0.0.1 dùng OkHttp HTTP/2.
 * Giải quyết triệt để 2 cơ chế chặn mới của streamc.xyz & CDN vệ tinh:
 *  1. Các CDN segment (*.hihihoho*.top, *.amass*.top, *.sbs...) trả về Cloudflare 403 nếu gọi qua HTTP/1.1 hoặc thiếu Referer.
 *  2. Segment bị ngụy trang thành .png (Content-Type: image/png) khiến trình phát/download gặp lỗi MIME.
 */
private object LocalProxy {
    private sealed class ProxyEntry {
        class InlinePlaylist(val body: ByteArray) : ProxyEntry()
        class RemotePlaylist(
            val url: String,
            val embed: String,
            val hashes: List<String>
        ) : ProxyEntry()

        class RemoteSegment(
            val url: String,
            val embed: String,
            val forceMime: String
        ) : ProxyEntry()
    }

    private val seq = AtomicLong(0)
    private val pool = Executors.newCachedThreadPool()
    private val entries = Collections.synchronizedMap(
        object : LinkedHashMap<String, ProxyEntry>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProxyEntry>?): Boolean =
                size > 60_000
        }
    )

    private var server: ServerSocket? = null
    private var listenPort: Int = -1

    @Volatile
    private var cachedClient: OkHttpClient? = null

    fun client(): OkHttpClient {
        cachedClient?.let { return it }
        return synchronized(this) {
            cachedClient ?: app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .followRedirects(true)
                .followSslRedirects(true)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
                .also { cachedClient = it }
        }
    }

    @Synchronized
    private fun ensureStarted(): Int {
        val current = server
        if (current != null && !current.isClosed && listenPort > 0) return listenPort
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = ss
        listenPort = ss.localPort
        Thread {
            while (!ss.isClosed) {
                val clientSock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                pool.execute { handle(clientSock) }
            }
        }.apply { isDaemon = true }.start()
        return listenPort
    }

    fun registerPlaylist(
        rawPlaylist: String,
        baseUrl: String,
        embed: String,
        hashes: List<String>
    ): String {
        val port = ensureStarted()
        val rewritten = rewritePlaylist(rawPlaylist, baseUrl, embed, hashes, port)
        val id = "pl_${seq.incrementAndGet()}.m3u8"
        entries[id] = ProxyEntry.InlinePlaylist(rewritten.toByteArray(Charsets.UTF_8))
        return "http://127.0.0.1:$port/$id"
    }

    private fun registerRemotePlaylist(
        url: String,
        embed: String,
        hashes: List<String>,
        port: Int
    ): String {
        val id = "pl_${seq.incrementAndGet()}.m3u8"
        entries[id] = ProxyEntry.RemotePlaylist(url, embed, hashes)
        return "http://127.0.0.1:$port/$id"
    }

    private fun registerSegment(
        url: String,
        embed: String,
        forceMime: String,
        ext: String,
        port: Int
    ): String {
        val id = "seg_${seq.incrementAndGet()}.$ext"
        entries[id] = ProxyEntry.RemoteSegment(url, embed, forceMime)
        return "http://127.0.0.1:$port/$id"
    }

    private fun detectSegMimeAndExt(url: String): Pair<String, String> {
        val clean = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            clean.endsWith(".m4s") || clean.endsWith(".mp4") -> "video/mp4" to "m4s"
            clean.endsWith(".aac") -> "audio/aac" to "aac"
            clean.endsWith(".vtt") -> "text/vtt; charset=utf-8" to "vtt"
            clean.endsWith(".key") -> "application/octet-stream" to "key"
            else -> "video/mp2t" to "ts"
        }
    }

    private fun rewritePlaylist(
        text: String,
        baseUrl: String,
        embed: String,
        hashes: List<String>,
        port: Int
    ): String {
        val lines = text.trimStart('\uFEFF').lines()
        val out = StringBuilder(text.length + 512)
        var nextIsVariant = false
        val uriAttrRegex = Regex("""URI="([^"]+)"""")

        for (rawLine in lines) {
            val trimmed = rawLine.trim()
            when {
                trimmed.isEmpty() -> {
                    out.append('\n')
                }

                trimmed.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-I-FRAME-STREAM-INF", ignoreCase = true) -> {
                    if (trimmed.contains("URI=\"")) {
                        val replaced = uriAttrRegex.replace(trimmed) { mr ->
                            val abs = StreamCrypto.resolveUrl(baseUrl, mr.groupValues[1])
                            val proxied = registerRemotePlaylist(abs, embed, hashes, port)
                            """URI="$proxied""""
                        }
                        out.append(replaced).append('\n')
                    } else {
                        nextIsVariant = true
                        out.append(trimmed).append('\n')
                    }
                }

                trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-KEY", ignoreCase = true) ||
                    trimmed.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    val replaced = uriAttrRegex.replace(trimmed) { mr ->
                        val abs = StreamCrypto.resolveUrl(baseUrl, mr.groupValues[1])
                        val proxied = if (trimmed.startsWith("#EXT-X-MEDIA", ignoreCase = true)) {
                            registerRemotePlaylist(abs, embed, hashes, port)
                        } else if (trimmed.startsWith("#EXT-X-MAP", ignoreCase = true)) {
                            registerSegment(abs, embed, "video/mp4", "mp4", port)
                        } else {
                            registerSegment(abs, embed, "application/octet-stream", "key", port)
                        }
                        """URI="$proxied""""
                    }
                    out.append(replaced).append('\n')
                }

                trimmed.startsWith("#") -> {
                    out.append(trimmed).append('\n')
                }

                else -> {
                    val abs = StreamCrypto.resolveUrl(baseUrl, trimmed)
                    val proxied = if (nextIsVariant) {
                        nextIsVariant = false
                        registerRemotePlaylist(abs, embed, hashes, port)
                    } else {
                        val (mime, ext) = detectSegMimeAndExt(abs)
                        registerSegment(abs, embed, mime, ext, port)
                    }
                    out.append(proxied).append('\n')
                }
            }
        }
        return out.toString()
    }

    fun fetchTextWithHttp2(
        url: String,
        embed: String,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String>? {
        val origin = StreamCrypto.originOf(embed)
        val reqBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
        if (embed.isNotBlank()) reqBuilder.header("Referer", embed)
        if (origin.isNotBlank()) reqBuilder.header("Origin", origin)
        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }

        return runCatching {
            client().newCall(reqBuilder.build()).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        }.getOrNull()
    }

    fun postJsonWithHttp2(
        url: String,
        jsonBody: String,
        embed: String
    ): Pair<Int, String>? {
        val origin = StreamCrypto.originOf(embed)
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
            .apply {
                if (origin.isNotBlank()) header("Origin", origin)
                if (embed.isNotBlank()) header("Referer", embed)
            }
            .build()

        return runCatching {
            client().newCall(req).execute().use { resp ->
                resp.code to (resp.body?.string() ?: "")
            }
        }.getOrNull()
    }

    fun probeFirstSegment(playlistText: String, baseUrl: String, embed: String): Boolean {
        val firstSeg = playlistText.lines()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return false

        // Nếu là Master playlist trỏ sang variant con thì chấp nhận luôn
        if (playlistText.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return true

        val abs = StreamCrypto.resolveUrl(baseUrl, firstSeg)
        val origin = StreamCrypto.originOf(embed)
        val referers = listOf(embed, if (origin.isNotBlank()) "$origin/" else "").filter { it.isNotBlank() }.distinct()

        for (ref in referers) {
            val req = Request.Builder()
                .url(abs)
                .header("User-Agent", UA)
                .header("Accept", "*/*")
                .header("Referer", ref)
                .apply { if (origin.isNotBlank()) header("Origin", origin) }
                .header("Range", "bytes=0-63")
                .build()

            val ok = runCatching {
                client().newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use false
                    val body = resp.body ?: return@use false
                    val buf = ByteArray(64)
                    val n = body.byteStream().read(buf)
                    if (n <= 0) return@use false
                    val head = String(buf, 0, n, Charsets.UTF_8).trimStart()
                    !head.startsWith("<!doctype", ignoreCase = true) &&
                        !head.startsWith("<html", ignoreCase = true)
                }
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            var rangeHeader: String? = null
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                val colon = h.indexOf(':')
                if (colon > 0) {
                    val key = h.substring(0, colon).trim()
                    if (key.equals("Range", ignoreCase = true)) {
                        rangeHeader = h.substring(colon + 1).trim()
                    }
                }
            }

            val reqParts = requestLine.split(" ")
            val method = reqParts.getOrNull(0)?.uppercase() ?: "GET"
            val id = reqParts.getOrNull(1)?.trimStart('/')?.substringBefore('?')
            val entry = id?.let { entries[it] }
            val out = socket.getOutputStream()

            if (entry == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                return
            }

            when (entry) {
                is ProxyEntry.InlinePlaylist -> {
                    val hdr = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                        "Content-Length: ${entry.body.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") out.write(entry.body)
                    out.flush()
                }

                is ProxyEntry.RemotePlaylist -> {
                    val fetched = fetchTextWithHttp2(entry.url, entry.embed)
                    if (fetched == null || fetched.first !in 200..299) {
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    var body = fetched.second
                    if (body.contains("#ENC-AESGCM") || body.contains("#EXT-X-B65")) {
                        var decoded: String? = null
                        for (h in entry.hashes) {
                            val candidate = runCatching { StreamCrypto.decryptPlaylist(body, h) }.getOrNull()
                            if (StreamCrypto.looksLikePlaylist(candidate)) {
                                decoded = candidate
                                break
                            }
                        }
                        if (decoded == null) {
                            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            out.flush()
                            return
                        }
                        body = decoded
                    }
                    if (!StreamCrypto.looksLikePlaylist(body)) {
                        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        return
                    }
                    val rewrittenBytes = rewritePlaylist(
                        body,
                        entry.url,
                        entry.embed,
                        entry.hashes,
                        ensureStarted()
                    ).toByteArray(Charsets.UTF_8)
                    val hdr = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/vnd.apple.mpegurl\r\n" +
                        "Content-Length: ${rewrittenBytes.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(hdr.toByteArray(Charsets.US_ASCII))
                    if (method != "HEAD") out.write(rewrittenBytes)
                    out.flush()
                }

                is ProxyEntry.RemoteSegment -> {
                    relaySegment(entry, method, rangeHeader, out)
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun relaySegment(
        entry: ProxyEntry.RemoteSegment,
        method: String,
        rangeHeader: String?,
        out: java.io.OutputStream
    ) {
        val origin = StreamCrypto.originOf(entry.embed)
        val referers = listOf(entry.embed, if (origin.isNotBlank()) "$origin/" else "")
            .filter { it.isNotBlank() }
            .distinct()

        for (i in referers.indices) {
            val req = Request.Builder()
                .url(entry.url)
                .apply {
                    if (method == "HEAD") head() else get()
                    header("User-Agent", UA)
                    header("Accept", "*/*")
                    header("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
                    header("Referer", referers[i])
                    if (origin.isNotBlank()) header("Origin", origin)
                    if (!rangeHeader.isNullOrBlank()) header("Range", rangeHeader)
                }
                .build()

            val resp = try {
                client().newCall(req).execute()
            } catch (_: Exception) {
                null
            }

            if (resp == null) continue

            resp.use { r ->
                if (r.code == 403 && i < referers.lastIndex) {
                    return@use
                }
                if (r.code !in 200..299) {
                    out.write("HTTP/1.1 ${r.code} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                    return
                }

                val body = r.body
                val len = body?.contentLength() ?: -1L
                val statusText = if (r.code == 206) "Partial Content" else "OK"
                val hdr = StringBuilder()
                    .append("HTTP/1.1 ").append(r.code).append(' ').append(statusText).append("\r\n")
                    .append("Content-Type: ").append(entry.forceMime).append("\r\n")
                    .append("Access-Control-Allow-Origin: *\r\n")
                r.header("Content-Range")?.let { hdr.append("Content-Range: ").append(it).append("\r\n") }
                r.header("Accept-Ranges")?.let { hdr.append("Accept-Ranges: ").append(it).append("\r\n") }
                if (len >= 0) hdr.append("Content-Length: ").append(len).append("\r\n")
                hdr.append("Connection: close\r\n\r\n")

                out.write(hdr.toString().toByteArray(Charsets.US_ASCII))
                if (method != "HEAD" && body != null) {
                    val buf = ByteArray(32 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                out.flush()
                return
            }
        }

        out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }
}

@CloudstreamPlugin
class PhimVNPlugin : Plugin() {
    override fun load(context: Context) {
        PhimVNProvider.appContext = context.applicationContext
        registerMainAPI(PhimVNProvider())
    }
}

/**
 * PhimVN lấy luồng phát theo nhiều tầng và phát qua LocalProxy HTTP/2:
 *  1. m3u8 trực tiếp từ API
 *  2. Giao thức bootstrap / issue (JSON & AES-GCM, cả hls và aesgcm-v2) của streamc.xyz
 *  3. Kiểu cũ data-obf
 *  4. Quét HTML embed (kể cả iframe con)
 *  5. WebView sniffer thực tế (vượt Turnstile / bắt playlist mã hóa & đã giải mã trực tiếp trong JS)
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

    companion object {
        @Volatile
        var appContext: Context? = null
    }

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
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val res = app.get("$mainUrl/api/films/search?keyword=$encoded").parsedSafe<PageResponse>()
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
                    val epNum = Regex("""(\d+)""").find(ep.name.orEmpty())
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    episodes.add(
                        newEpisode("${m3u8.orEmpty()}$LINK_SEP${embed.orEmpty()}") {
                            this.name = "$serverName - Tập ${ep.name ?: "1"}"
                            this.episode = epNum
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

    private class Stream(
        val playlistText: String,
        val baseUrl: String,
        val hashes: List<String>,
        val via: String
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
            .takeIf { it.contains(".m3u8", ignoreCase = true) }

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

    private suspend fun emit(st: Stream, embed: String, callback: (ExtractorLink) -> Unit): Boolean =
        runCatching {
            val proxyUrl = LocalProxy.registerPlaylist(st.playlistText, st.baseUrl, embed, st.hashes)
            callback(
                newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                    this.referer = embed
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        }.getOrDefault(false)

    private fun direct(
        url: String,
        embed: String,
        via: String,
        hashes: List<String> = emptyList()
    ): Stream? {
        val (code, body) = LocalProxy.fetchTextWithHttp2(url, embed) ?: return null
        if (code !in 200..299 || body.isBlank()) return null

        val tryHashes = (hashes + StreamCrypto.hashOf(embed)).filter { it.isNotBlank() }.distinct()
        val resolvedText = if (body.contains("#ENC-AESGCM") || body.contains("#EXT-X-B65")) {
            var decoded: String? = null
            for (h in tryHashes) {
                val plain = runCatching { StreamCrypto.decryptPlaylist(body, h) }.getOrNull()
                if (StreamCrypto.looksLikePlaylist(plain)) {
                    decoded = plain
                    break
                }
            }
            decoded ?: return null
        } else {
            if (!StreamCrypto.looksLikePlaylist(body)) return null
            body
        }

        val probeOk = LocalProxy.probeFirstSegment(resolvedText, url, embed)
        if (!probeOk) {
            Log.d(TAG, "$via: probe segment đầu tiên thất bại")
            return null
        }
        return Stream(resolvedText, url, tryHashes, via)
    }

    // ----- tầng 2: bootstrap (+ fallback action=issue khi không có preissued)

    private fun viaBootstrap(embed: String): Stream? {
        val origin = StreamCrypto.originOf(embed).takeIf { it.isNotBlank() } ?: return null
        val (shellCode, shellHtml) = LocalProxy.fetchTextWithHttp2(embed, "$mainUrl/") ?: return null
        if (shellCode !in 200..299) return null

        val cfg = Regex("""<script id="stream-bootstrap"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(shellHtml)?.groupValues?.getOrNull(1) ?: return null
        val api = JSONObject(cfg).optString("api").takeIf { it.isNotBlank() } ?: return null

        // Thử lần lượt hls (plain m3u8) rồi aesgcm-v2
        for (pFmt in listOf("hls", "aesgcm-v2")) {
            val body = JSONObject().apply {
                put("action", "bootstrap")
                put("referrer", "$mainUrl/")
                put("frame_origins", JSONArray().put(mainUrl))
                put("request_grant", true)
                put("playlist_format", pFmt)
                put("pretty_url", true)
                put("path_chunks", true)
                put("bootstrap_format", "aesgcm-v1")
            }.toString()

            val (postCode, postText) = LocalProxy.postJsonWithHttp2(api, body, embed) ?: continue
            if (postCode !in 200..299 || postText.isBlank()) continue

            var j = runCatching { JSONObject(postText) }.getOrNull() ?: continue
            if (j.optString("format") == "aesgcm-v1") {
                val aad = "stream-bootstrap-v1\n$api".toByteArray()
                val plain = StreamCrypto.gcmDecrypt(
                    StreamCrypto.sha256(aad),
                    StreamCrypto.hexToBytes(j.getString("iv")),
                    Base64.decode(j.getString("data"), Base64.DEFAULT),
                    aad
                )
                j = JSONObject(String(plain, Charsets.UTF_8))
            }

            val video = j.optString("video").ifBlank { StreamCrypto.hashOf(embed) }
            var playlist = j.optJSONObject("preissued")?.optString("playlist")?.takeIf { it.isNotBlank() }

            // Nếu server không cấp preissued nhưng chưa bật Turnstile -> gọi action="issue"
            if (playlist == null && !j.optBoolean("turnstileEnabled", false)) {
                val bootToken = j.optString("bootstrap").takeIf { it.isNotBlank() }
                if (bootToken != null) {
                    val issueBody = JSONObject().apply {
                        put("action", "issue")
                        put("bootstrap", bootToken)
                        put("turnstile_response", "")
                        put("frame_origins", JSONArray().put(mainUrl))
                        put("playlist_format", pFmt)
                        put("pretty_url", true)
                        put("path_chunks", true)
                    }.toString()
                    val (iCode, iText) = LocalProxy.postJsonWithHttp2(api, issueBody, embed) ?: (0 to "")
                    if (iCode in 200..299 && iText.isNotBlank()) {
                        playlist = runCatching { JSONObject(iText).optString("playlist") }
                            .getOrNull()?.takeIf { it.isNotBlank() }
                    }
                }
            }

            if (playlist != null) {
                direct(playlist, embed, "bootstrap-$pFmt", listOf(video))?.let { return it }
            }

            // Nếu Turnstile đang bật ở tầng API -> thoát nhanh sang WebView để vượt Turnstile
            if (j.optBoolean("turnstileEnabled", false)) {
                Log.d(TAG, "bootstrap: turnstileEnabled=true (gốc $origin), chuyển WebView")
                return null
            }
        }
        return null
    }

    // ----- tầng 3: data-obf (kiểu cũ)

    private fun viaDataObf(embed: String): Stream? {
        val origin = StreamCrypto.originOf(embed).takeIf { it.isNotBlank() } ?: return null
        val (code, html) = LocalProxy.fetchTextWithHttp2(embed, "$mainUrl/") ?: return null
        if (code !in 200..299) return null
        val obf = Regex("""data-obf="([^"]+)"""").find(html)?.groupValues?.getOrNull(1) ?: return null
        val j = JSONObject(String(Base64.decode(obf, Base64.DEFAULT)))
        val sub = j.optString("sUb").takeIf { it.isNotBlank() } ?: return null
        return direct("$origin/$sub.m3u8", embed, "data-obf", listOf(j.optString("hD")))
    }

    // ----- tầng 4: quét HTML (kể cả iframe con)

    private fun viaScan(embed: String): Stream? {
        val seen = mutableSetOf<String>()
        fun scan(url: String, depth: Int): Stream? {
            if (!seen.add(url) || depth > 1) return null
            val (_, htmlRaw) = LocalProxy.fetchTextWithHttp2(url, "$mainUrl/") ?: return null
            val html = htmlRaw.replace("\\/", "/")
            for (m in Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(html)) {
                direct(m.value, embed, "scan", listOf(StreamCrypto.hashOf(embed)))?.let { return it }
            }
            for (m in Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html)) {
                val src = m.groupValues[1].let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) scan(src, depth + 1)?.let { return it }
            }
            return null
        }
        return scan(embed, 0)
    }

    // ----- tầng 5: WebView Sniffer thông minh (vượt Turnstile + bắt playlist mã hóa/đã giải mã)

    private fun resolveContext(): Context? {
        appContext?.let { return it }
        CommonActivity.activity?.let { return it.applicationContext ?: it }
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
            at.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
    }

    private val adUrlPatterns = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "googletagmanager.com", "google-analytics.com", "popads.net",
        "propellerads.com", "popcash.net", "adsterra.com", "adcash.com",
        "/ads.js", "vast.js"
    )

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private suspend fun viaWebView(embed: String): Stream? {
        val signedPlaylistRegex = Regex(
            """\.m3u8(\?|$|#)|/conf\.php\?t=|^https?://[^/]*streamc[^/]*/(?:[A-Za-z0-9_-]{180}/){0,30}[A-Za-z0-9_-]{80,}(?:\?d)?$""",
            RegexOption.IGNORE_CASE
        )
        val assetSkipRegex = Regex(
            """\.(js|css|woff2?|ttf|svg|ico|png|jpe?g|webp|gif)(\?|$)|/assets/""",
            RegexOption.IGNORE_CASE
        )

        return suspendCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                val act = CommonActivity.activity
                val ctx = act ?: resolveContext()
                if (ctx == null) {
                    cont.resume(null)
                    return@post
                }

                try {
                    val w = 1080
                    val h = 1920
                    val wv = WebView(ctx)
                    val finished = AtomicBoolean(false)
                    val probedUrls = Collections.synchronizedSet(HashSet<String>())
                    val jsVideoHash = AtomicReference(StreamCrypto.hashOf(embed))

                    fun finish(res: Stream?) {
                        if (!finished.compareAndSet(false, true)) return
                        handler.removeCallbacksAndMessages(null)
                        runCatching {
                            wv.stopLoading()
                            (wv.parent as? ViewGroup)?.removeView(wv)
                            wv.destroy()
                        }
                        cont.resume(res)
                    }

                    val hookJs = """
                        (function(){
                            if (window.__phimvnHooked) return;
                            window.__phimvnHooked = true;
                            try { window.open = function(){ return null; }; } catch(e){}
                            try {
                                var origDecode = TextDecoder.prototype.decode;
                                TextDecoder.prototype.decode = function(input, opts){
                                    var out = origDecode.apply(this, arguments);
                                    try {
                                        if (typeof out === 'string' && out.indexOf('#EXTM3U') === 0 && out.length > 30) {
                                            if (!window.__phimvnSent && window.PhimVNBridge) {
                                                window.__phimvnSent = true;
                                                window.PhimVNBridge.onPlaylistText(out, String(window.videoHash || ''), String(location.href));
                                            }
                                        }
                                    } catch(ex){}
                                    return out;
                                };
                            } catch(e){}
                            try {
                                var origText = Response.prototype.text;
                                Response.prototype.text = function(){
                                    var respUrl = this.url || '';
                                    return origText.apply(this, arguments).then(function(txt){
                                        try {
                                            if (typeof txt === 'string' && txt.indexOf('#EXTM3U') === 0 &&
                                                txt.indexOf('#ENC-AESGCM') === -1 && txt.indexOf('#EXT-X-B65') === -1 &&
                                                !window.__phimvnSent && window.PhimVNBridge) {
                                                window.__phimvnSent = true;
                                                window.PhimVNBridge.onPlaylistText(txt, String(window.videoHash || ''), String(respUrl || location.href));
                                            }
                                        } catch(ex){}
                                        return txt;
                                    });
                                };
                            } catch(e){}
                        })();
                    """.trimIndent()

                    val autoPlayJs = """
                        (function(){
                            try {
                                if (window.PhimVNBridge && window.videoHash) {
                                    window.PhimVNBridge.onVideoHash(String(window.videoHash));
                                }
                                var btn = document.querySelector('.jw-display-icon-container, .jw-icon-playback, button[aria-label*="Phát"], button[aria-label*="Play"]');
                                if (btn) btn.click();
                                var v = document.querySelector('video');
                                if (v && v.paused) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) p.catch(function(){});
                                }
                            } catch(e){}
                        })();
                    """.trimIndent()

                    val bridge = object {
                        @JavascriptInterface
                        fun onVideoHash(vh: String?) {
                            if (!vh.isNullOrBlank()) jsVideoHash.set(vh.trim())
                        }

                        @JavascriptInterface
                        fun onPlaylistText(text: String?, vh: String?, baseUrl: String?) {
                            if (finished.get() || text.isNullOrBlank()) return
                            if (!vh.isNullOrBlank()) jsVideoHash.set(vh.trim())
                            if (!StreamCrypto.looksLikePlaylist(text)) return
                            if (text.contains("#ENC-AESGCM") || text.contains("#EXT-X-B65")) return
                            val resolvedBase = baseUrl?.takeIf { it.startsWith("http") } ?: embed
                            Thread {
                                if (finished.get()) return@Thread
                                if (LocalProxy.probeFirstSegment(text, resolvedBase, embed)) {
                                    val st = Stream(
                                        playlistText = text,
                                        baseUrl = resolvedBase,
                                        hashes = listOf(jsVideoHash.get(), StreamCrypto.hashOf(embed)).filter { it.isNotBlank() }.distinct(),
                                        via = "webview-js"
                                    )
                                    handler.post { finish(st) }
                                }
                            }.apply { isDaemon = true }.start()
                        }
                    }

                    wv.addJavascriptInterface(bridge, "PhimVNBridge")

                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)

                    wv.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        userAgentString = UA
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean = false
                    }

                    var taps = 0
                    fun tapLoop() {
                        if (finished.get()) return
                        taps++
                        runCatching {
                            wv.evaluateJavascript(hookJs, null)
                            wv.evaluateJavascript(autoPlayJs, null)
                            val t = SystemClock.uptimeMillis()
                            val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, w / 2f, h / 2f, 0)
                            val u = MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, w / 2f, h / 2f, 0)
                            wv.dispatchTouchEvent(d)
                            wv.dispatchTouchEvent(u)
                            d.recycle()
                            u.recycle()
                        }
                        if (taps < 12) handler.postDelayed({ tapLoop() }, 1600)
                    }

                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            runCatching { wv.evaluateJavascript(hookJs, null) }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            runCatching {
                                wv.evaluateJavascript(hookJs, null)
                                wv.evaluateJavascript(autoPlayJs, null)
                            }
                            handler.postDelayed({ tapLoop() }, 1500)
                        }

                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            if (finished.get()) return null
                            val u = request?.url?.toString() ?: return null
                            if (!u.startsWith("http", ignoreCase = true)) return null

                            if (adUrlPatterns.any { u.contains(it, ignoreCase = true) }) {
                                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (!assetSkipRegex.containsMatchIn(u) &&
                                signedPlaylistRegex.containsMatchIn(u) &&
                                probedUrls.add(u)
                            ) {
                                Thread {
                                    if (finished.get()) return@Thread
                                    val hashes = listOf(jsVideoHash.get(), StreamCrypto.hashOf(embed)).filter { it.isNotBlank() }.distinct()
                                    val st = direct(u, embed, "webview-net", hashes)
                                    if (st != null && !finished.get()) {
                                        handler.post { finish(st) }
                                    }
                                }.apply { isDaemon = true }.start()
                            }
                            return null
                        }

                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val u = request?.url?.toString() ?: return false
                            if (adUrlPatterns.any { u.contains(it, ignoreCase = true) }) return true
                            if (request.isForMainFrame) {
                                val allow = u.contains("streamc", ignoreCase = true) ||
                                    u.contains("challenges.cloudflare.com", ignoreCase = true) ||
                                    u.contains("nguonc", ignoreCase = true)
                                return !allow
                            }
                            return false
                        }
                    }

                    wv.measure(
                        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
                    )
                    wv.layout(0, 0, w, h)
                    wv.translationX = -4000f
                    runCatching {
                        act?.addContentView(wv, ViewGroup.LayoutParams(w, h))
                    }

                    handler.postDelayed({ finish(null) }, 24_000L)
                    wv.loadUrl(embed, mapOf("Referer" to "$mainUrl/"))
                } catch (e: Exception) {
                    Log.d(TAG, "webview lỗi: ${e.message}")
                    cont.resume(null)
                }
            }
        }
    }

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
