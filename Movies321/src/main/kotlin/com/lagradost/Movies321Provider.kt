package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Bật true để tự động copy log ra Clipboard (tắt thành false khi không cần debug)
private const val AUTO_COPY_LOG = true

// Tùy chọn API key phụ đề (để trống vẫn tự động tải Tiếng Việt + Tiếng Anh từ OpenSubtitles & SubDL + giải nén ZIP/GZ)
private const val OPENSUBTITLES_API_KEY = "OHFlEutMFovpvfcNEgdZ34pPIB2IptP6"
private const val SUBDL_API_KEY = "xHeOWlCfS9o4EKmK7OwbeOBIhfbIHr2Y"

@CloudstreamPlugin
class Movies321Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Movies321Provider())
    }
}

/**
 * Logger tự động copy Clipboard an toàn cho Android 7 (API 24) TV Box RAM 1GB:
 * - Đồng bộ hóa buffer, đẩy việc ghi Clipboard về Main Looper qua Handler(Looper.getMainLooper()).
 * - Debounce 500ms & giới hạn 14KB tránh TransactionTooLargeException và lag UI trên TV 1GB RAM.
 */
private object Movies321Clipboard {
    private const val TAG = "321Movies"
    private const val CLIP_LABEL = "321Movies Log"
    private const val MAX_LINES = 140
    private const val MAX_LINE_LEN = 240
    private const val MAX_CLIP_CHARS = 14_000
    private const val DEBOUNCE_MS = 500L

    private val lock = Any()
    private val buffer = ArrayDeque<String>(MAX_LINES + 4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var flushScheduled = false

    private val flushRunnable = Runnable {
        val text: String
        synchronized(lock) {
            flushScheduled = false
            if (!AUTO_COPY_LOG || buffer.isEmpty()) return@Runnable
            text = buildBoundedLocked()
        }
        writeClipboardOnMain(text)
    }

    fun reset() {
        synchronized(lock) {
            mainHandler.removeCallbacks(flushRunnable)
            flushScheduled = false
            buffer.clear()
        }
    }

    fun log(tag: String, msg: String) {
        val safe = if (msg.length > MAX_LINE_LEN) msg.take(MAX_LINE_LEN) + "…" else msg
        val line = "[$tag] $safe"
        Log.d(TAG, line)
        if (!AUTO_COPY_LOG) return
        synchronized(lock) {
            if (buffer.size >= MAX_LINES) buffer.removeAt(0)
            buffer.addLast(line)
            if (!flushScheduled) {
                flushScheduled = true
                mainHandler.postDelayed(flushRunnable, DEBOUNCE_MS)
            }
        }
    }

    fun flushNow() {
        if (!AUTO_COPY_LOG) return
        val text: String
        synchronized(lock) {
            mainHandler.removeCallbacks(flushRunnable)
            flushScheduled = false
            if (buffer.isEmpty()) return
            text = buildBoundedLocked()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            writeClipboardOnMain(text)
        } else {
            mainHandler.post { writeClipboardOnMain(text) }
        }
    }

    private fun buildBoundedLocked(): String {
        val lines = buffer.toList()
        var start = 0
        var total = 0
        for (i in lines.indices.reversed()) {
            val len = lines[i].length + 1
            if (total + len > MAX_CLIP_CHARS) {
                start = i + 1
                break
            }
            total += len
        }
        val sb = StringBuilder(total.coerceAtLeast(256))
        for (i in start until lines.size) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(lines[i])
        }
        return sb.toString()
    }

    private fun writeClipboardOnMain(text: String) {
        val act = CommonActivity.activity ?: return
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, text))
        } catch (t: Throwable) {
            Log.e(TAG, "Clipboard err: ${t.message}")
        }
    }
}

/**
 * Server HTTP nội bộ siêu nhẹ trên 127.0.0.1 để phát phụ đề đã giải nén từ .zip/.gz/.rar:
 * - Giúp CloudStream 4.5.2 & ExoPlayer tải trực tiếp file .srt/.vtt/.ass UTF-8 (tránh lỗi scheme file://).
 * - Bộ đệm LRU tối đa 16 mục (~2MB) đảm bảo không tràn RAM trên TV Android 7 1GB RAM.
 */
private object Movies321SubServer {
    private const val MAX_CACHE_ITEMS = 16
    private val lock = Any()
    private val counter = AtomicInteger(0)
    private val cache = object : LinkedHashMap<String, Pair<String, ByteArray>>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, ByteArray>>?): Boolean {
            return size > MAX_CACHE_ITEMS
        }
    }

    @Volatile
    private var boundPort: Int = -1

    private fun ensureStarted(): Int {
        if (boundPort > 0) return boundPort
        synchronized(lock) {
            if (boundPort > 0) return boundPort
            val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            boundPort = server.localPort
            val worker = Thread {
                while (true) {
                    val sock = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    handleSocket(sock)
                }
            }
            worker.name = "Movies321-SubServer"
            worker.isDaemon = true
            worker.start()
            return boundPort
        }
    }

    private fun handleSocket(sock: Socket) {
        try {
            sock.soTimeout = 3000
            val reader = sock.getInputStream().bufferedReader(Charsets.UTF_8)
            val reqLine = reader.readLine() ?: return
            var headerLines = 0
            while (headerLines < 32) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                headerLines++
            }
            val parts = reqLine.split(' ')
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val path = parts.getOrNull(1)?.substringBefore('?') ?: ""
            val key = path.substringAfterLast('/')
            val entry = synchronized(lock) { cache[key] }

            val out = sock.getOutputStream()
            if (entry == null) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            } else {
                val (mime, bytes) = entry
                val hdr = "HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n"
                out.write(hdr.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write(bytes)
            }
            out.flush()
        } catch (_: Throwable) {
        } finally {
            try { sock.close() } catch (_: Throwable) {}
        }
    }

    fun hostSubtitle(ext: String, utf8Bytes: ByteArray): String? {
        return try {
            val port = ensureStarted()
            val cleanExt = ext.lowercase().let { if (it in listOf("srt", "vtt", "ass", "ssa")) it else "srt" }
            val mime = when (cleanExt) {
                "vtt" -> "text/vtt; charset=utf-8"
                "ass", "ssa" -> "text/x-ssa; charset=utf-8"
                else -> "application/x-subrip; charset=utf-8"
            }
            val key = "sub_${counter.incrementAndGet()}.$cleanExt"
            synchronized(lock) { cache[key] = mime to utf8Bytes }
            "http://127.0.0.1:$port/sub/$key"
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * Bộ tìm & giải nén phụ đề song song cho Movies321 (v14):
 * - Đúng định mức: 02 Tiếng Việt + 01 Tiếng Anh từ OpenSubtitles; 02 Tiếng Việt + 01 Tiếng Anh từ SubDL.
 * - Tự động giải nén ZIP (SubDL), GZIP (OpenSubtitles), chuẩn hóa UTF-8 (hỗ trợ cả Windows-1258/UTF-16),
 *   lọc bỏ RAR/7z nén không đọc được trực tiếp để tự chuyển sang file kế tiếp.
 */
private object Movies321Subtitles {
    private const val MAX_ARCHIVE_BYTES = 2_097_152
    private const val TARGET_OS_VI = 2
    private const val TARGET_OS_EN = 1
    private const val TARGET_SD_VI = 2
    private const val TARGET_SD_EN = 1
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    data class SubCandidate(
        val langCode: String,
        val label: String,
        val url: String,
        val source: String,
        val score: Int
    )

    fun submitAll(
        pool: ExecutorService,
        client: OkHttpClient,
        imdbId: String?,
        tmdbId: String?,
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<Future<List<SubCandidate>>> {
        val cleanImdb = imdbId?.takeIf { it.startsWith("tt") }
        Movies321Clipboard.log(
            "SUBS",
            "Tìm phụ đề (2 VI + 1 EN OpenSubs, 2 VI + 1 EN SubDL) imdb=$cleanImdb title=$title S${season ?: "-"}E${episode ?: "-"}"
        )
        return listOf(
            pool.submit(Callable<List<SubCandidate>> {
                fromOpenSubtitlesQuota(client, cleanImdb, tmdbId, title, season, episode, "vie", TARGET_OS_VI)
            }),
            pool.submit(Callable<List<SubCandidate>> {
                fromOpenSubtitlesQuota(client, cleanImdb, tmdbId, title, season, episode, "eng", TARGET_OS_EN)
            }),
            pool.submit(Callable<List<SubCandidate>> {
                fromSubDLQuota(client, cleanImdb, tmdbId, title, year, season, episode)
            })
        )
    }

    fun collectAndSort(futures: List<Future<List<SubCandidate>>>): List<SubCandidate> {
        val raw = ArrayList<SubCandidate>()
        val deadline = System.currentTimeMillis() + 8500L
        for (f in futures) {
            val rem = (deadline - System.currentTimeMillis()).coerceAtLeast(250L)
            val part = runCatching { f.get(rem, TimeUnit.MILLISECONDS) }.getOrNull()
            if (part != null) raw.addAll(part)
        }
        val seen = HashSet<String>(raw.size * 2)
        val sorted = raw
            .filter { it.url.startsWith("http") && seen.add(it.url.substringBefore("#")) }
            .sortedWith(
                compareBy<SubCandidate>(
                    { langRank(it.langCode) },
                    { -it.score },
                    { it.source }
                )
            )

        val osVi = sorted.count { it.source == "OpenSubs" && it.langCode == "vi" }
        val osEn = sorted.count { it.source == "OpenSubs" && it.langCode == "en" }
        val sdVi = sorted.count { it.source == "SubDL" && it.langCode == "vi" }
        val sdEn = sorted.count { it.source == "SubDL" && it.langCode == "en" }
        Movies321Clipboard.log(
            "SUBS",
            "Đã giải nén & nạp ${sorted.size} phụ đề (OpenSubs: VI=$osVi EN=$osEn | SubDL: VI=$sdVi EN=$sdEn)"
        )
        return sorted
    }

    fun isVietnameseLabel(raw: String): Boolean {
        val s = raw.trim().lowercase()
        return s == "vi" || s == "vie" || s.contains("viet") || s.contains("tiếng việt") || s.contains("tieng viet")
    }

    private fun langRank(code: String): Int = when (normalizeLang(code)) {
        "vi" -> 0
        "en" -> 1
        else -> 2
    }

    private fun normalizeLang(raw: String): String {
        val s = raw.trim().lowercase()
        return when {
            s == "vi" || s == "vie" || s.contains("viet") -> "vi"
            s == "en" || s == "eng" || s.contains("english") -> "en"
            else -> s.take(2)
        }
    }

    private fun httpGetText(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", BROWSER_UA)
            headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun httpGetBytes(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): ByteArray? {
        return try {
            val rb = Request.Builder().url(url).header("User-Agent", BROWSER_UA)
            headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val ins = r.body?.byteStream() ?: return@use null
                readBoundedBytes(ins, MAX_ARCHIVE_BYTES)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readBoundedBytes(ins: InputStream, limit: Int): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val n = ins.read(buf, 0, minOf(buf.size, limit - total))
            if (n <= 0) break
            bos.write(buf, 0, n)
            total += n
        }
        return bos.toByteArray()
    }

    private fun decodeTextBytes(raw: ByteArray): String {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return String(raw, 3, raw.size - 3, Charsets.UTF_8)
        }
        if (raw.size >= 2 && raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16LE)
        }
        if (raw.size >= 2 && raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()) {
            return String(raw, 2, raw.size - 2, Charsets.UTF_16BE)
        }
        try {
            val dec = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return dec.decode(ByteBuffer.wrap(raw)).toString()
        } catch (_: Throwable) {
        }
        return try {
            String(raw, Charset.forName("windows-1258"))
        } catch (_: Throwable) {
            String(raw, Charsets.ISO_8859_1)
        }
    }

    private fun looksLikeSubtitle(text: String): Boolean {
        val t = text.trimStart()
        if (t.isEmpty()) return false
        if (t.startsWith("<html", true) || t.startsWith("<!doctype", true) || t.startsWith("{")) return false
        return t.contains("-->") || t.contains("[Script Info]", true) || t.startsWith("WEBVTT", true)
    }

    private fun detectExt(text: String, fileName: String = ""): String {
        val lower = fileName.lowercase()
        val head = text.trimStart()
        return when {
            head.startsWith("WEBVTT", true) || lower.endsWith(".vtt") -> "vtt"
            head.contains("[Script Info]", true) || lower.endsWith(".ass") -> "ass"
            lower.endsWith(".ssa") -> "ssa"
            else -> "srt"
        }
    }

    private fun extractAndHostSubtitle(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        season: Int?,
        episode: Int?,
        sourceTag: String
    ): String? {
        val raw = httpGetBytes(client, url, headers) ?: return null
        if (raw.size < 16) return null

        // 1. Giải nén ZIP (SubDL / gói phụ đề)
        if (raw.size >= 4 && raw[0] == 0x50.toByte() && raw[1] == 0x4B.toByte()) {
            var bestScore = -1
            var bestExt = "srt"
            var bestText: String? = null
            try {
                ZipInputStream(ByteArrayInputStream(raw)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val name = entry.name.lowercase()
                        if (name.contains("__macosx") || name.contains("sample")) continue
                        if (!name.endsWith(".srt") && !name.endsWith(".vtt") && !name.endsWith(".ass") && !name.endsWith(".ssa")) continue
                        val entryBytes = readBoundedBytes(zis, MAX_ARCHIVE_BYTES)
                        val text = decodeTextBytes(entryBytes)
                        if (!looksLikeSubtitle(text)) continue
                        var score = when {
                            name.endsWith(".srt") -> 40
                            name.endsWith(".vtt") -> 35
                            else -> 25
                        }
                        if (episode != null) {
                            val ep2 = episode.toString().padStart(2, '0')
                            val s2 = (season ?: 1).toString().padStart(2, '0')
                            if (Regex("(s0?$s2.?e0?$ep2|\\b${season ?: 1}x0?$ep2\\b|\\be$ep2\\b)").containsMatchIn(name)) {
                                score += 200
                            }
                        }
                        score += minOf(entryBytes.size / 1024, 150)
                        if (score > bestScore) {
                            bestScore = score
                            bestExt = detectExt(text, name)
                            bestText = text
                        }
                    }
                }
            } catch (_: Throwable) {
                return null
            }
            val finalSub = bestText ?: return null
            return Movies321SubServer.hostSubtitle(bestExt, finalSub.toByteArray(Charsets.UTF_8))
        }

        // 2. Giải nén GZIP (OpenSubtitles .gz)
        if (raw.size >= 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte()) {
            return try {
                val unzipped = GZIPInputStream(ByteArrayInputStream(raw)).use { readBoundedBytes(it, MAX_ARCHIVE_BYTES) }
                val text = decodeTextBytes(unzipped)
                if (!looksLikeSubtitle(text)) return null
                Movies321SubServer.hostSubtitle(detectExt(text), text.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                null
            }
        }

        // 3. Phát hiện file nén RAR / 7z để bỏ qua an toàn & chuyển sang file kế tiếp
        val isRar = raw.size >= 4 && raw[0] == 0x52.toByte() && raw[1] == 0x61.toByte() && raw[2] == 0x72.toByte() && raw[3] == 0x21.toByte()
        val is7z = raw.size >= 6 && raw[0] == 0x37.toByte() && raw[1] == 0x7A.toByte() && raw[2] == 0xBC.toByte() && raw[3] == 0xAF.toByte()
        if (isRar || is7z) {
            Movies321Clipboard.log("SUBS", "$sourceTag bỏ qua file nén ${if (isRar) "RAR" else "7z"}, tìm bản khác")
            return null
        }

        // 4. File phụ đề văn bản thuần (.srt/.vtt/.ass)
        val text = decodeTextBytes(raw)
        if (!looksLikeSubtitle(text)) return null
        return Movies321SubServer.hostSubtitle(detectExt(text), text.toByteArray(Charsets.UTF_8))
    }

    private fun fromOpenSubtitlesQuota(
        client: OkHttpClient,
        imdbId: String?,
        tmdbId: String?,
        title: String?,
        season: Int?,
        episode: Int?,
        osLang: String,
        quota: Int
    ): List<SubCandidate> {
        val norm = if (osLang == "vie") "vi" else "en"
        val labelBase = if (norm == "vi") "Tiếng Việt" else "English"
        val out = ArrayList<SubCandidate>(quota)

        val imdbDigits = imdbId?.removePrefix("tt")?.trim()?.takeIf { it.all { ch -> ch.isDigit() } }
        if (!imdbDigits.isNullOrBlank()) {
            val restUrls = if (season != null && episode != null) {
                listOf(
                    "https://rest.opensubtitles.org/search/episode-$episode/imdbid-$imdbDigits/season-$season/sublanguageid-$osLang",
                    "https://rest.opensubtitles.org/search/imdbid-$imdbDigits/sublanguageid-$osLang"
                )
            } else {
                listOf("https://rest.opensubtitles.org/search/imdbid-$imdbDigits/sublanguageid-$osLang")
            }

            for (restUrl in restUrls) {
                if (out.size >= quota) break
                val text = httpGetText(
                    client,
                    restUrl,
                    mapOf("User-Agent" to "TemporaryUserAgent", "X-User-Agent" to "TemporaryUserAgent")
                ) ?: continue
                if (!text.trimStart().startsWith("[")) continue
                val arr = runCatching { JSONArray(text) }.getOrNull() ?: continue
                if (arr.length() == 0) continue

                val items = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                    .sortedByDescending { it.optString("SubDownloadsCnt").toIntOrNull() ?: 0 }

                for (item in items) {
                    if (out.size >= quota) break
                    val fmt = item.optString("SubFormat").lowercase()
                    if (fmt.isNotBlank() && fmt !in listOf("srt", "vtt", "ass", "ssa")) continue
                    val idFile = item.optString("IDSubtitleFile")
                    val gzLink = item.optString("SubDownloadLink")
                    val stremioUtf8 = if (idFile.isNotBlank()) {
                        "https://subs5.strem.io/en/download/subencoding-stremio-utf8/src-api/file/$idFile"
                    } else ""

                    val hosted = (if (stremioUtf8.isNotBlank()) extractAndHostSubtitle(client, stremioUtf8, emptyMap(), season, episode, "OpenSubs") else null)
                        ?: (if (gzLink.startsWith("http")) extractAndHostSubtitle(client, gzLink, emptyMap(), season, episode, "OpenSubs") else null)
                        ?: continue

                    val idx = out.size + 1
                    out.add(
                        SubCandidate(
                            langCode = norm,
                            label = "$labelBase #$idx [OpenSubs]",
                            url = hosted,
                            source = "OpenSubs",
                            score = if (norm == "vi") (100 - idx) else (78 - idx)
                        )
                    )
                }
            }
        }

        // Fallback sang OpenSubtitles Stremio v3 nếu chưa đủ quota
        if (out.size < quota && !imdbId.isNullOrBlank()) {
            val key = if (season != null && episode != null) "$imdbId:$season:$episode" else imdbId
            val kind = if (season != null && episode != null) "series" else "movie"
            val text = httpGetText(client, "https://opensubtitles-v3.strem.io/subtitles/$kind/$key.json")
            val arr = runCatching { JSONObject(text.orEmpty()).optJSONArray("subtitles") }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    if (out.size >= quota) break
                    val obj = arr.optJSONObject(i) ?: continue
                    if (normalizeLang(obj.optString("lang")) != norm) continue
                    val subUrl = obj.optString("url")
                    if (!subUrl.startsWith("http")) continue
                    val hosted = extractAndHostSubtitle(client, subUrl, emptyMap(), season, episode, "OpenSubs-v3") ?: continue
                    val idx = out.size + 1
                    out.add(
                        SubCandidate(
                            langCode = norm,
                            label = "$labelBase #$idx [OpenSubs]",
                            url = hosted,
                            source = "OpenSubs",
                            score = if (norm == "vi") (96 - idx) else (74 - idx)
                        )
                    )
                }
            }
        }

        if (out.isEmpty()) {
            Movies321Clipboard.log("SUBS", "OpenSubs ($osLang): 0 bản phù hợp (tmdb=$tmdbId title=$title)")
        }
        return out
    }

    private fun fromSubDLQuota(
        client: OkHttpClient,
        imdbId: String?,
        tmdbId: String?,
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<SubCandidate> {
        val viUrls = ArrayList<String>()
        val enUrls = ArrayList<String>()

        // 1. Nếu có API Key SubDL thì ưu tiên API chính thức trước
        if (SUBDL_API_KEY.isNotBlank()) {
            val q = buildString {
                append("languages=VI,EN&subs_per_page=18&api_key=$SUBDL_API_KEY")
                if (!imdbId.isNullOrBlank()) append("&imdb_id=$imdbId")
                else if (!tmdbId.isNullOrBlank()) append("&tmdb_id=$tmdbId")
                else if (!title.isNullOrBlank()) append("&film_name=${URLEncoder.encode(title, "UTF-8")}")
                if (year != null) append("&year=$year")
                if (season != null) append("&season_number=$season")
                if (episode != null) append("&episode_number=$episode")
            }
            val apiJson = httpGetText(client, "https://api.subdl.com/api/v1/subtitles?$q")
            val arr = runCatching { JSONObject(apiJson.orEmpty()).optJSONArray("subtitles") }.getOrNull()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val norm = normalizeLang(o.optString("lang"))
                    val path = o.optString("url")
                    if (path.isBlank()) continue
                    val full = if (path.startsWith("http")) path else "https://dl.subdl.com$path"
                    if (norm == "vi") viUrls.add(full) else if (norm == "en") enUrls.add(full)
                }
            }
        }

        // 2. Tìm trực tiếp qua subdl.com (không cần API key) để lấy cả Tiếng Việt & Tiếng Anh
        if ((viUrls.isEmpty() || enUrls.isEmpty()) && !title.isNullOrBlank()) {
            val isTv = season != null
            val moviePath = findSubDLMoviePath(client, title, year, isTv)
            if (!moviePath.isNullOrBlank()) {
                val targetPath = if (isTv && season != null) {
                    findSubDLSeasonPath(client, moviePath, season) ?: moviePath
                } else moviePath
                if (viUrls.isEmpty()) viUrls.addAll(scrapeSubDLLangLinks(client, "https://subdl.com$targetPath/vietnamese", episode))
                if (enUrls.isEmpty()) enUrls.addAll(scrapeSubDLLangLinks(client, "https://subdl.com$targetPath/english", episode))
            }
        }

        val out = ArrayList<SubCandidate>(TARGET_SD_VI + TARGET_SD_EN)
        val subdlHeaders = mapOf("Referer" to "https://subdl.com/")

        var viPicked = 0
        for (dl in viUrls.distinct().take(6)) {
            if (viPicked >= TARGET_SD_VI) break
            val hosted = extractAndHostSubtitle(client, dl, subdlHeaders, season, episode, "SubDL") ?: continue
            viPicked++
            out.add(SubCandidate("vi", "Tiếng Việt #$viPicked [SubDL]", hosted, "SubDL", 92 - viPicked))
        }

        var enPicked = 0
        for (dl in enUrls.distinct().take(4)) {
            if (enPicked >= TARGET_SD_EN) break
            val hosted = extractAndHostSubtitle(client, dl, subdlHeaders, season, episode, "SubDL") ?: continue
            enPicked++
            out.add(SubCandidate("en", "English #$enPicked [SubDL]", hosted, "SubDL", 72 - enPicked))
        }

        if (viPicked == 0 && enPicked == 0) {
            Movies321Clipboard.log("SUBS", "SubDL: không giải nén được bản nào cho title=$title")
        }
        return out
    }

    private fun findSubDLMoviePath(client: OkHttpClient, title: String, year: Int?, isTv: Boolean): String? {
        val candidates = listOf(title.trim(), title.substringBefore(":").trim()).distinct().filter { it.isNotBlank() }
        for (term in candidates) {
            val q = URLEncoder.encode(term, "UTF-8").replace("+", "%20")
            val html = httpGetText(client, "https://subdl.com/search/$q") ?: continue
            val cardRegex = Regex("<a\\s+href=\"(/subtitle/sd\\d+/[a-z0-9-]+)\"[^>]*>([\\s\\S]{0,1600}?)</a>", RegexOption.IGNORE_CASE)
            var bestPath: String? = null
            var bestScore = -1
            for (m in cardRegex.findAll(html)) {
                val path = m.groupValues[1]
                val card = m.groupValues[2]
                var score = 10
                if (isTv && card.contains(">tv<", true)) score += 50
                if (!isTv && card.contains(">movie<", true)) score += 50
                if (year != null && card.contains("($year)")) score += 40
                if (card.contains(">${term}", true)) score += 20
                if (score > bestScore) {
                    bestScore = score
                    bestPath = path
                }
            }
            if (bestPath != null) return bestPath
        }
        return null
    }

    private fun findSubDLSeasonPath(client: OkHttpClient, moviePath: String, season: Int): String? {
        val html = httpGetText(client, "https://subdl.com$moviePath") ?: return null
        val ordinals = listOf(
            "", "first-season", "second-season", "third-season", "fourth-season", "fifth-season",
            "sixth-season", "seventh-season", "eighth-season", "ninth-season", "tenth-season"
        )
        val ord = ordinals.getOrNull(season)
        val escaped = Regex.escape(moviePath)
        val links = Regex("($escaped/[^\"'<>\\s]+)").findAll(html).map { it.groupValues[1] }.distinct().toList()
        if (ord != null) {
            links.firstOrNull { it.endsWith("/$ord", true) }?.let { return it }
        }
        return links.firstOrNull { it.endsWith("/season-$season", true) || it.contains("-$season", true) }
    }

    private fun scrapeSubDLLangLinks(client: OkHttpClient, pageUrl: String, episode: Int?): List<String> {
        val html = httpGetText(client, pageUrl) ?: return emptyList()
        val matches = Regex("href=\"(https://dl\\.subdl\\.com/subtitle/[^\"'\\s]+)\"").findAll(html).toList()
        val scored = ArrayList<Pair<Int, String>>(matches.size)
        val epTag = episode?.let { "e" + it.toString().padStart(2, '0') }
        for (m in matches) {
            val dl = m.groupValues[1]
            val ctx = html.substring((m.range.first - 500).coerceAtLeast(0), m.range.first).lowercase()
            var score = 10
            if (epTag != null && (ctx.contains(epTag) || ctx.contains("complete"))) score += 50
            if (ctx.contains("hearing impaired") || ctx.contains("sdh")) score -= 5
            scored.add(score to dl)
        }
        return scored.sortedByDescending { it.first }.map { it.second }
    }
}

/**
 * Bộ giải DNS IPv4-ưu-tiên + DoH (1.1.1.1 & 8.8.8.8) cho Android 7 TV Box:
 * - Tránh treo kết nối do IPv6 lỗi trên TV Box / mạng nhà mạng VN.
 * - Tự động chuyển sang DoH nếu DNS hệ thống bị chặn hoặc không phân giải được domain.
 * - dohClient chỉ gọi IP thuần (1.1.1.1 / 8.8.8.8) nên không bao giờ lặp đệ quy.
 */
private object Movies321Dns {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()
    private const val TTL_MS = 300_000L

    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    val ipv4SystemFirst: Dns = Dns { hostname ->
        lookup(hostname, preferDohFirst = false)
    }

    val dohFirst: Dns = Dns { hostname ->
        lookup(hostname, preferDohFirst = true)
    }

    private fun isIpLiteral(host: String): Boolean {
        return host.all { it.isDigit() || it == '.' } || host.contains(':')
    }

    private fun isUsableIpv4(addr: InetAddress): Boolean {
        return addr is Inet4Address && !addr.isLoopbackAddress && !addr.isAnyLocalAddress
    }

    private fun lookup(hostname: String, preferDohFirst: Boolean): List<InetAddress> {
        if (isIpLiteral(hostname)) return Dns.SYSTEM.lookup(hostname)

        val now = System.currentTimeMillis()
        if (!preferDohFirst) {
            cache[hostname]?.let { (ts, list) ->
                if (now - ts < TTL_MS && list.isNotEmpty()) return list
            }
        }

        if (preferDohFirst) {
            val doh = queryDoh(hostname)
            if (doh.isNotEmpty()) {
                cache[hostname] = now to doh
                return doh
            }
        }

        val sysAll = runCatching { Dns.SYSTEM.lookup(hostname) }.getOrNull().orEmpty()
        val sysV4 = sysAll.filter { isUsableIpv4(it) }
        if (sysV4.isNotEmpty() && !preferDohFirst) {
            cache[hostname] = now to sysV4
            return sysV4
        }

        if (!preferDohFirst) {
            val doh = queryDoh(hostname)
            if (doh.isNotEmpty()) {
                cache[hostname] = now to doh
                return doh
            }
        }

        if (sysV4.isNotEmpty()) return sysV4
        if (sysAll.isNotEmpty()) return sysAll
        throw UnknownHostException("Không phân giải được DNS cho $hostname")
    }

    private fun queryDoh(hostname: String): List<InetAddress> {
        val encoded = URLEncoder.encode(hostname, "UTF-8")
        val endpoints = listOf(
            "https://1.1.1.1/dns-query?name=$encoded&type=A",
            "https://8.8.8.8/resolve?name=$encoded&type=A"
        )
        for (ep in endpoints) {
            val addrs = runCatching {
                val req = Request.Builder()
                    .url(ep)
                    .header("Accept", "application/dns-json")
                    .build()
                dohClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val ans = json.optJSONArray("Answer") ?: return@use emptyList()
                    val out = ArrayList<InetAddress>(ans.length())
                    for (i in 0 until ans.length()) {
                        val item = ans.optJSONObject(i) ?: continue
                        if (item.optInt("type", 0) != 1) continue
                        val ip = item.optString("data").trim()
                        if (ip.isEmpty()) continue
                        val addr = InetAddress.getByName(ip)
                        if (isUsableIpv4(addr)) out.add(addr)
                    }
                    out
                }
            }.getOrNull().orEmpty()
            if (addrs.isNotEmpty()) return addrs
        }
        return emptyList()
    }
}

/**
 * Movies321 (v14 - phụ đề 2 VI + 1 EN OpenSubs/SubDL + tự giải nén ZIP/GZ + sửa lỗi kết nối 321movies.xyz):
 * - Chuẩn thuần OkHttp + ExecutorService -> biên dịch sạch 100% trên repo noname (không dùng thư viện coroutines ngoài).
 * - Tải song song đúng định mức: 02 Tiếng Việt + 01 Tiếng Anh từ OpenSubtitles (rest.opensubtitles.org), 02 Tiếng Việt + 01 Tiếng Anh từ SubDL (tự giải nén .zip/.gz qua 127.0.0.1).
 * - Dùng SubtitleFile(label, url) tương thích 100% CloudStream 4.5.2, timeout 45s + DoH 1.1.1.1 + 3 luồng cho TV Android 7 RAM 1GB.
 */
class Movies321Provider : MainAPI() {
    override var mainUrl = "https://321movies.xyz"
    override var name = "321Movies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val usesWebView = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val playerHosts = listOf(
        "https://321movies.xyz"
    )
    private val img = "https://image.tmdb.org/t/p/"
    private val tmdbToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"

    private val playerKey =
        "j7wYkYhVgQn5x2L6k2M8hVQfD4zN3bP1aR7uT0cXyE6dZX4sWAd87JKMN8HHGG654GVCFRLMNBOPUY7LK"
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val preferred = listOf(
        "rivestream", "cinesrc", "bcine", "pstream",
        "streamaggregator", "peestream", "movy", "frame", "vidgod"
    )
    private val maxStreams = 20

    override val mainPage = mainPageOf(
        "/trending/movie/week" to "Phim lẻ thịnh hành",
        "/trending/tv/week" to "Phim bộ thịnh hành",
        "/movie/popular" to "Phim lẻ phổ biến",
        "/tv/popular" to "Phim bộ phổ biến",
        "/movie/top_rated" to "Phim lẻ đánh giá cao",
        "/tv/top_rated" to "Phim bộ đánh giá cao"
    )

    private val tmdbHosts = listOf("https://api.themoviedb.org/3", "https://api.tmdb.org/3")
    private var tmdbPrefer = 0

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        val headers = mapOf("Authorization" to "Bearer $tmdbToken", "Accept" to "application/json")
        for (round in 0 until 2) {
            for (k in tmdbHosts.indices) {
                val hi = (tmdbPrefer + k) % tmdbHosts.size
                val url = "${tmdbHosts[hi]}$path${sep}language=en-US"
                val resp = runCatching { app.get(url, headers = headers, timeout = 15L) }.getOrNull() ?: continue
                val body = runCatching { resp.text }.getOrDefault("")
                val obj = runCatching { JSONObject(body) }.getOrNull()
                if (obj != null && !obj.has("status_code")) {
                    tmdbPrefer = hi
                    return obj
                }
                if (obj != null && obj.optInt("status_code") == 34) return null
            }
        }
        return null
    }

    private fun image(path: String?, size: String): String? {
        if (path.isNullOrBlank() || path == "null") return null
        val tmdbImg = img + size + (if (path.startsWith("/")) path else "/$path")
        return "https://wsrv.nl/?url=" + URLEncoder.encode(tmdbImg, "UTF-8") + "&output=webp"
    }

    private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()
    private fun JSONObject.str(key: String): String? = optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    private fun toSearch(o: JSONObject, forcedTv: Boolean?): SearchResponse? {
        val id = o.optLong("id", 0L)
        if (id == 0L || o.optBoolean("adult", false)) return null
        if (o.optString("media_type") == "person") return null
        val tv = forcedTv ?: (o.optString("media_type") == "tv" || o.has("first_air_date"))
        val type = if (tv) TvType.TvSeries else TvType.Movie
        val title = o.str("title") ?: o.str("name") ?: o.str("original_title") ?: o.str("original_name") ?: return null
        val poster = image(o.str("poster_path"), "w342") ?: image(o.str("backdrop_path"), "w500")
        val path = if (tv) "tv" else "movie"
        val date = if (tv) o.str("first_air_date") else o.str("release_date")
        return newMovieSearchResponse(title, "$mainUrl/$path/$id", type) {
            this.posterUrl = poster
            this.year = yearOf(date)
        }
    }

    private fun parseList(arr: JSONArray?, forcedTv: Boolean?): List<SearchResponse> {
        if (arr == null) return emptyList()
        val out = ArrayList<SearchResponse>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            toSearch(o, forcedTv)?.let { out.add(it) }
        }
        return out
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Movies321Clipboard.reset()
        val res = tmdbGet("${request.data}?page=$page")
        val forcedTv = request.data.startsWith("/tv") || request.data.contains("/tv/")
        val items = parseList(res?.optJSONArray("results"), forcedTv)
        Movies321Clipboard.log("HOME", "${request.name} p=$page -> ${items.size} items")
        val total = res?.optInt("total_pages", 1) ?: 1
        return newHomePageResponse(request.name, items, hasNext = page < total && page < 20)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()
        val res = tmdbGet("/search/multi?query=$q&include_adult=false&page=1")
            ?: tmdbGet("/search/movie?query=$q&include_adult=false&page=1")
        return parseList(res?.optJSONArray("results"), null)
    }

    private data class Ref(val tv: Boolean, val id: String, val season: Int?, val episode: Int?)

    private fun refOf(url: String): Ref? {
        val m = Regex("/(movie|tv|series)/(\\d+)(?:/(?:season/)?(\\d+))?(?:/(?:episode/)?(\\d+))?", RegexOption.IGNORE_CASE)
            .find(url) ?: return null
        val kind = m.groupValues[1].lowercase()
        return Ref(kind != "movie", m.groupValues[2], m.groupValues[3].toIntOrNull(), m.groupValues[4].toIntOrNull())
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = refOf(url) ?: return null
        val path = if (ref.tv) "/tv/${ref.id}?append_to_response=credits,external_ids"
        else "/movie/${ref.id}?append_to_response=credits,external_ids"
        val d = tmdbGet(path) ?: return null
        if (d.optLong("id", 0L) == 0L) return null

        val title = d.str("title") ?: d.str("name") ?: return null
        val kind = if (ref.tv) "tv" else "movie"
        val pageUrl = "$mainUrl/$kind/${d.optLong("id")}"
        val poster = image(d.str("poster_path"), "w342") ?: image(d.str("backdrop_path"), "w500")
        val backdrop = image(d.str("backdrop_path") ?: d.str("poster_path"), "w780")
        val year = yearOf(if (ref.tv) d.str("first_air_date") else d.str("release_date"))
        val imdbId = d.optJSONObject("external_ids")?.str("imdb_id")

        val tags = mutableListOf<String>()
        d.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) g.optJSONObject(i)?.str("name")?.let { tags.add(it) }
        }
        val plot = d.str("overview")
        val runtime = d.optInt("runtime", 0).takeIf { it > 0 }
            ?: d.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }

        if (!ref.tv) {
            val payload = JSONObject()
                .put("url", pageUrl)
                .put("tmdbId", ref.id)
                .put("imdbId", imdbId ?: "")
                .put("title", title)
                .put("year", year ?: 0)
                .toString()
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, payload) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = runtime
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = d.optJSONArray("seasons")
        if (seasons != null) {
            var fetched = 0
            for (i in 0 until seasons.length()) {
                val s = seasons.optJSONObject(i) ?: continue
                val sn = s.optInt("season_number", 0)
                if (sn <= 0) continue
                if (ref.season != null && sn != ref.season) continue
                if (++fetched > 25) break
                val sd = tmdbGet("/tv/${ref.id}/season/$sn") ?: continue
                val eps = sd.optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val e = eps.optJSONObject(j) ?: continue
                    val en = e.optInt("episode_number", 0)
                    if (en <= 0) continue
                    val epPayload = JSONObject()
                        .put("url", "$pageUrl/$sn/$en")
                        .put("tmdbId", ref.id)
                        .put("imdbId", imdbId ?: "")
                        .put("title", title)
                        .put("year", year ?: 0)
                        .put("season", sn)
                        .put("episode", en)
                        .toString()
                    episodes.add(
                        newEpisode(epPayload) {
                            this.name = e.str("name") ?: "Episode $en"
                            this.season = sn
                            this.episode = en
                            this.description = e.str("overview")
                            this.posterUrl = image(e.str("still_path"), "w300") ?: poster
                        }
                    )
                }
            }
        }
        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private fun decodeFile(s: String): String {
        if (!s.startsWith("enc:")) return s
        return runCatching {
            var b64 = s.substring(4).replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size < 9) return@runCatching ""
            val key = playerKey.toByteArray(Charsets.ISO_8859_1)
            val out = StringBuilder(raw.size)
            for (i in 8 until raw.size) {
                val salt = raw[(i - 8) % 8].toInt() and 0xFF
                val k = key[(i - 8 + salt) % key.size].toInt() and 0xFF
                out.append(((raw[i].toInt() and 0xFF) xor k).toChar())
            }
            val decoded = out.toString()
            if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else ""
        }.getOrDefault("")
    }

    private data class Cand(
        val url: String,
        val family: String,
        val label: String,
        val declared: String,
        val rank: Int
    )

    private data class Verdict(
        val state: String,
        val m3u8: Boolean,
        val hasSeparateAudio: Boolean = false,
        val headers: Map<String, String>? = null,
        val note: String = "",
        val url: String? = null
    )

    private fun siteHeaders(host: String = mainUrl): Map<String, String> = mapOf(
        "User-Agent" to ua,
        "Referer" to "$host/",
        "Origin" to host
    )

    private fun buildPlaylistClients(): List<Pair<String, OkHttpClient>> {
        val primary = app.baseClient.newBuilder()
            .dns(Movies321Dns.ipv4SystemFirst)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val fallbackBuilder = OkHttpClient.Builder()
            .dns(Movies321Dns.dohFirst)
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(50, TimeUnit.SECONDS)
            .followRedirects(true)

        // Android 7.0 (API 24) ROM cũ đôi khi thiếu GTS/Cloudflare Root CA -> chỉ bật trust fallback ở lượt #2
        runCatching {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslCtx = SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            fallbackBuilder.sslSocketFactory(sslCtx.socketFactory, trustAll)
            fallbackBuilder.hostnameVerifier(HostnameVerifier { _, _ -> true })
        }

        return listOf(
            "IPv4-45s" to primary,
            "DoH-HTTP1.1" to fallbackBuilder.build()
        )
    }

    private fun fetchPlaylistJson(ref: Ref): Triple<String, JSONObject, Boolean>? {
        var q = "type=${if (ref.tv) "tv" else "movie"}&id=${ref.id}"
        if (ref.tv) q += "&season=${ref.season ?: 1}&episode=${ref.episode ?: 1}"

        val clients = buildPlaylistClients()
        for (host in playerHosts) {
            val url = "$host/api/player/vixsrc-playlist?$q"
            val headers = siteHeaders(host) + ("Accept" to "application/json")
            for (idx in clients.indices) {
                val attempt = idx + 1
                val (mode, client) = clients[idx]
                try {
                    val rb = Request.Builder().url(url)
                    headers.forEach { (k, v) -> rb.header(k, v) }
                    client.newCall(rb.build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        val cacheSt = resp.header("x-playlist-cache") ?: "-"
                        Movies321Clipboard.log(
                            "PLAYER",
                            "$host #$attempt ($mode) HTTP ${resp.code} len=${body.length} cache=$cacheSt"
                        )
                        val trimmed = body.trimStart()
                        if (trimmed.startsWith("<")) {
                            Movies321Clipboard.log("PLAYER", "$host #$attempt trả HTML, thử chế độ kế tiếp")
                            return@use
                        }
                        val j = runCatching { JSONObject(trimmed) }.getOrNull()
                        if (j != null && (j.optJSONArray("playlist")?.length() ?: 0) > 0) {
                            mainUrl = host
                            return Triple(host, j, idx > 0)
                        }
                    }
                } catch (e: Throwable) {
                    val detail = e.message?.take(100) ?: "no-msg"
                    Movies321Clipboard.log(
                        "PLAYER",
                        "$host #$attempt ($mode) lỗi: ${e.javaClass.simpleName}: $detail"
                    )
                }
            }
        }
        return null
    }

    private fun getBytes(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        range: String?,
        limit: Long
    ): Pair<Int, String> {
        return try {
            val rb = Request.Builder().url(url)
            headers.forEach { (k, v) -> rb.header(k, v) }
            rb.header("Accept", "*/*")
            if (range != null) rb.header("Range", range)
            client.newCall(rb.build()).execute().use { r ->
                val body = runCatching { r.peekBody(limit).string() }.getOrDefault("")
                r.code to body
            }
        } catch (_: UnknownHostException) {
            -1 to "dns"
        } catch (e: Exception) {
            0 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { URI(base).resolve(ref).toString() }.getOrDefault(ref)
    }

    private fun chainCheck(client: OkHttpClient, url: String, headers: Map<String, String>): Verdict {
        val (c1, b1) = getBytes(client, url, headers, null, 131_072)
        if (c1 == -1) return Verdict("dead", false, note = "DNS")
        if (c1 != 200 && c1 != 206) {
            val st = if (c1 == 401 || c1 == 403 || c1 == 429 || c1 == 0) "unknown" else "dead"
            return Verdict(st, false, note = "HTTP $c1")
        }
        val head = b1.replace("\uFEFF", "").trimStart()
        if (!head.startsWith("#EXTM3U")) {
            if (Regex("^<(!doctype|html|\\?xml)", RegexOption.IGNORE_CASE).containsMatchIn(head)) {
                return if (head.contains("<MPD", true)) Verdict("ok", false, false, headers, "mpd", url)
                else Verdict("dead", false, note = "HTML")
            }
            return if (head.isNotEmpty()) Verdict("ok", false, false, headers, "direct", url)
            else Verdict("unknown", false, note = "empty")
        }
        val hasSeparateAudio = head.contains("#EXT-X-MEDIA:TYPE=AUDIO", ignoreCase = true)
        var text = head
        var base = url
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            val idx = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val v = lines.drop(idx + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                ?: return Verdict("unknown", true, hasSeparateAudio, note = "no variant")
            base = resolveUrl(url, v)
            val (c2, b2) = getBytes(client, base, headers, null, 131_072)
            if (c2 != 200 && c2 != 206) return Verdict("unknown", true, hasSeparateAudio, note = "var HTTP $c2")
            text = b2.trimStart()
        }
        val seg = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return Verdict("unknown", true, hasSeparateAudio, note = "no seg")
        val (c3, _) = getBytes(client, resolveUrl(base, seg), headers, "bytes=0-1023", 1024)
        return if (c3 == 200 || c3 == 206) Verdict("ok", true, hasSeparateAudio, headers, "seg HTTP $c3", url)
        else Verdict("unknown", true, hasSeparateAudio, note = "seg HTTP $c3")
    }

    private fun unwrapLevels(url: String): List<String> {
        val list = mutableListOf(url)
        var cur = url
        for (i in 0 until 4) {
            val raw = Regex("[?&]url=([^&]+)").find(cur)?.groupValues?.get(1) ?: break
            val decoded = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: break
            if (!decoded.startsWith("http")) break
            list.add(decoded)
            cur = decoded
        }
        return list
    }

    private fun embeddedHeaders(url: String): Map<String, String>? = runCatching {
        val raw = Regex("[?&]headers=([^&]+)").find(url)?.groupValues?.get(1) ?: return@runCatching null
        val j = JSONObject(URLDecoder.decode(raw, "UTF-8"))
        val m = HashMap<String, String>()
        m["User-Agent"] = ua
        j.optString("Referer", "").takeIf { it.isNotBlank() }?.let { m["Referer"] = it }
        j.optString("Origin", "").takeIf { it.isNotBlank() }?.let { m["Origin"] = it }
        m
    }.getOrNull()

    private fun probeOne(client: OkHttpClient, rawUrl: String, activeHost: String): Verdict {
        val levels = unwrapLevels(rawUrl)
        val emb = embeddedHeaders(rawUrl)
        val siteH = siteHeaders(activeHost)
        val bareH = mapOf("User-Agent" to ua)

        // Ưu tiên kiểm tra link gốc ở tầng sâu nhất trước để bỏ qua proxy 403
        val deepest = levels.last()
        var bestFallback: Verdict? = null

        val deepHeaders = listOfNotNull(bareH, emb).distinctBy { it["Referer"] ?: "" }
        for (hs in deepHeaders) {
            val v = chainCheck(client, deepest, hs)
            if (v.state == "ok") return v
            if (bestFallback == null || (bestFallback.state == "dead" && v.state == "unknown")) {
                bestFallback = v
            }
            // Nếu host gốc đã trả HTML/403 Cloudflare hoặc DNS chết thì không thử lặp header vô ích
            if (v.note == "HTML" || v.note == "DNS") break
        }

        // Chỉ thử lại proxy ngoài một lần với siteHeaders nếu nó khác link gốc
        if (levels.size > 1 && rawUrl != deepest) {
            val v = chainCheck(client, rawUrl, siteH)
            if (v.state == "ok") return v
            if (bestFallback == null || (bestFallback.state == "dead" && v.state == "unknown")) {
                bestFallback = v
            }
        }
        return bestFallback ?: Verdict("dead", false, note = "no route")
    }

    private fun heightOf(label: String): Int = when {
        Regex("4k|2160", RegexOption.IGNORE_CASE).containsMatchIn(label) -> 2160
        else -> Regex("(1080|720|480|360)").find(label)?.value?.toIntOrNull() ?: 0
    }

    private fun qualityValue(h: Int): Int = when (h) {
        2160 -> Qualities.P2160.value
        1080 -> Qualities.P1080.value
        720 -> Qualities.P720.value
        480 -> Qualities.P480.value
        360 -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private data class ParsedData(
        val url: String,
        val tmdbId: String?,
        val imdbId: String?,
        val title: String?,
        val year: Int?,
        val season: Int?,
        val episode: Int?
    )

    private fun parseData(data: String): ParsedData {
        if (data.trimStart().startsWith("{")) {
            val j = runCatching { JSONObject(data) }.getOrNull()
            if (j != null) {
                return ParsedData(
                    url = j.optString("url", data),
                    tmdbId = j.optString("tmdbId").takeIf { it.isNotBlank() },
                    imdbId = j.optString("imdbId").takeIf { it.isNotBlank() },
                    title = j.optString("title").takeIf { it.isNotBlank() },
                    year = j.optInt("year", 0).takeIf { it > 1900 },
                    season = j.optInt("season", 0).takeIf { it > 0 },
                    episode = j.optInt("episode", 0).takeIf { it > 0 }
                )
            }
        }
        return ParsedData(data, null, null, null, null, null, null)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Movies321Clipboard.reset()
        Movies321Clipboard.log("LINKS", "data=$data")

        val parsed = parseData(data)
        val ref = refOf(parsed.url)
        if (ref == null) {
            Movies321Clipboard.log("LINKS", "URL không hợp lệ: ${parsed.url}")
            Movies321Clipboard.flushNow()
            return false
        }

        // Bổ sung metadata TMDB nếu gọi trực tiếp từ URL cũ trong lịch sử
        var imdbId = parsed.imdbId
        var title = parsed.title
        var year = parsed.year
        if (imdbId.isNullOrBlank() || title.isNullOrBlank()) {
            val meta = tmdbGet("/${if (ref.tv) "tv" else "movie"}/${ref.id}?append_to_response=external_ids")
            if (meta != null) {
                imdbId = imdbId ?: meta.optJSONObject("external_ids")?.str("imdb_id")
                title = title ?: meta.str("title") ?: meta.str("name")
                year = year ?: yearOf(if (ref.tv) meta.str("first_air_date") else meta.str("release_date"))
            }
        }

        var found = false
        val pool = Executors.newFixedThreadPool(3) // Tối ưu TV Quad-Core RAM 1GB
        val subClient = app.baseClient.newBuilder()
            .dns(Movies321Dns.ipv4SystemFirst)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        try {
            // 1. Chạy song song tìm & giải nén phụ đề (2 VI + 1 EN OpenSubs, 2 VI + 1 EN SubDL)
            val subFutures = Movies321Subtitles.submitAll(
                pool = pool,
                client = subClient,
                imdbId = imdbId,
                tmdbId = ref.id,
                title = title,
                year = year,
                season = ref.season ?: parsed.season,
                episode = ref.episode ?: parsed.episode
            )

            // 2. Lấy playlist từ 321movies.xyz (tự động thử IPv4-45s rồi DoH 1.1.1.1 + HTTP/1.1)
            val res = fetchPlaylistJson(ref)
            val embeddedTracks = ArrayList<Pair<String, String>>()
            val cands = mutableListOf<Cand>()
            var activeHost = mainUrl
            var usedDohFallback = false

            if (res == null) {
                Movies321Clipboard.log("LINKS", "PLAYER_OFFLINE trên tất cả host")
            } else {
                activeHost = res.first
                val payload = res.second
                usedDohFallback = res.third
                val seen = HashSet<String>()
                val groups = payload.optJSONArray("playlist") ?: JSONArray()
                for (gi in 0 until groups.length()) {
                    val grp = groups.optJSONObject(gi) ?: continue
                    val tracks = grp.optJSONArray("tracks")
                    if (tracks != null) {
                        for (ti in 0 until tracks.length()) {
                            val t = tracks.optJSONObject(ti) ?: continue
                            val subUrl = decodeFile(t.optString("file", ""))
                            val subLabel = t.optString("label", "Subtitle")
                            if (subUrl.startsWith("http")) {
                                embeddedTracks.add(subLabel to subUrl)
                            }
                        }
                    }

                    val srcs = grp.optJSONArray("sources") ?: continue
                    for (si in 0 until srcs.length()) {
                        val s = srcs.optJSONObject(si) ?: continue
                        val real = decodeFile(s.optString("file", ""))
                        if (!real.startsWith("http") || !seen.add(real)) continue
                        val fam = s.optString("provider", "other").removePrefix("sourcepack-")
                        cands.add(
                            Cand(
                                url = real,
                                family = fam,
                                label = s.optString("label", ""),
                                declared = s.optString("type", ""),
                                rank = preferred.indexOf(fam).let { if (it < 0) 50 else it }
                            )
                        )
                    }
                }
                Movies321Clipboard.log("LINKS", "Giải mã được ${cands.size} nguồn từ $activeHost")
            }

            val rankedCands = cands
                .sortedBy { it.rank }
                .distinctBy { unwrapLevels(it.url).last() }
                .take(12)

            val probeClient = app.baseClient.newBuilder()
                .dns(if (usedDohFallback) Movies321Dns.dohFirst else Movies321Dns.ipv4SystemFirst)
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(6, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()

            // 3. Dùng ExecutorCompletionService để không bị nghẽn sau các server chết ở đầu danh sách
            val ecs = ExecutorCompletionService<Pair<Cand, Verdict>>(pool)
            for (c in rankedCands) {
                ecs.submit(Callable<Pair<Cand, Verdict>> {
                    c to probeOne(probeClient, c.url, activeHost)
                })
            }

            // Phát phụ đề theo thứ tự: Web Tiếng Việt -> Phụ đề ngoài Tiếng Việt -> Web Tiếng Anh -> Phụ đề ngoài Tiếng Anh
            val webVi = embeddedTracks.filter { Movies321Subtitles.isVietnameseLabel(it.first) }
            val webOther = embeddedTracks.filter { !Movies321Subtitles.isVietnameseLabel(it.first) }
            val sortedSubs = Movies321Subtitles.collectAndSort(subFutures)
            val extVi = sortedSubs.filter { it.langCode == "vi" }
            val extEn = sortedSubs.filter { it.langCode != "vi" }

            for ((subLabel, subUrl) in webVi) {
                runCatching { subtitleCallback(SubtitleFile(subLabel, subUrl)) }
            }
            for (sub in extVi) {
                runCatching { subtitleCallback(SubtitleFile(sub.label, sub.url)) }
            }
            for ((subLabel, subUrl) in webOther) {
                runCatching { subtitleCallback(SubtitleFile(subLabel, subUrl)) }
            }
            for (sub in extEn) {
                runCatching { subtitleCallback(SubtitleFile(sub.label, sub.url)) }
            }

            // 4. Thu kết quả probe theo thứ tự xong trước (dừng sớm khi đủ 5 nguồn sống hoặc hết 8.5s)
            if (rankedCands.isNotEmpty()) {
                val probeStart = System.currentTimeMillis()
                val probeDeadline = probeStart + 8_500L
                val results = ArrayList<Pair<Cand, Verdict>>(rankedCands.size)
                var okCount = 0

                for (i in rankedCands.indices) {
                    val rem = probeDeadline - System.currentTimeMillis()
                    if (rem <= 0L) break
                    val fut = runCatching { ecs.poll(rem, TimeUnit.MILLISECONDS) }.getOrNull() ?: break
                    val pair = runCatching { fut.get() }.getOrNull() ?: continue
                    results.add(pair)
                    if (pair.second.state == "ok") {
                        okCount++
                        if (okCount >= 5) break
                    }
                }

                val ok = results.filter { it.second.state == "ok" }
                    .sortedBy { it.first.rank }
                val unk = results.filter { it.second.state == "unknown" }
                val deadCount = results.count { it.second.state == "dead" }
                Movies321Clipboard.log(
                    "PROBE",
                    "Xong ${results.size}/${rankedCands.size} trong ${System.currentTimeMillis() - probeStart}ms (ok=${ok.size}, unk=${unk.size}, dead=$deadCount)"
                )

                val picked: List<Pair<Cand, Verdict>> = when {
                    ok.isNotEmpty() -> ok
                    unk.isNotEmpty() -> unk.take(4)
                    else -> rankedCands.take(4).map { c ->
                        val deep = unwrapLevels(c.url).last()
                        val hs = embeddedHeaders(c.url) ?: mapOf("User-Agent" to ua)
                        c to Verdict(
                            state = "unknown",
                            m3u8 = c.declared.equals("hls", true) || deep.contains(".m3u8", true),
                            headers = hs,
                            note = "unverified-fallback",
                            url = deep
                        )
                    }
                }

                val names = HashMap<String, Int>()
                var emitted = 0
                var m3u8Expanded = 0

                for ((c, v) in picked) {
                    if (emitted >= maxStreams) break
                    val fam = c.family.replaceFirstChar { it.uppercase() }
                    val cleanLabel = c.label
                        .replace(Regex("\\bauto\\b", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("\\s*\\|\\s*"), " · ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                        .ifBlank { fam }

                    val targetUrl = v.url ?: c.url
                    val isM3u8 = if (v.state == "ok") v.m3u8 else (c.declared.equals("hls", true) || targetUrl.contains(".m3u8", true))
                    val hs = v.headers ?: siteHeaders(activeHost)
                    val isUnverified = v.state != "ok"

                    var disp = cleanLabel + if (isUnverified) " (chưa xác minh)" else ""
                    val cnt = (names[disp] ?: 0) + 1
                    names[disp] = cnt
                    if (cnt > 1) disp += " #$cnt"

                    // Phát Master M3U8 trước (đảm bảo âm thanh hoạt động nếu HLS v7 tách audio riêng)
                    try {
                        callback(
                            newExtractorLink(
                                source = fam,
                                name = disp,
                                url = targetUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = hs["Referer"] ?: ""
                                this.headers = hs
                                this.quality = if (isUnverified) 100
                                else heightOf(c.label).let { if (it > 0) qualityValue(it) else Qualities.P720.value }
                            }
                        )
                        emitted++
                        found = true
                    } catch (t: Throwable) {
                        Movies321Clipboard.log("EMIT", "$fam err: ${t.javaClass.simpleName}: ${t.message?.take(90)}")
                    }

                    // Nếu là M3U8 gộp âm thanh, tách thêm từng chất lượng 1080p/720p/480p (giới hạn 2 lần để không trễ)
                    if (v.state == "ok" && isM3u8 && !v.hasSeparateAudio && m3u8Expanded < 2 && emitted < maxStreams) {
                        m3u8Expanded++
                        val variants = runCatching {
                            M3u8Helper.generateM3u8(
                                source = fam,
                                streamUrl = targetUrl,
                                referer = hs["Referer"] ?: "",
                                headers = hs
                            )
                        }.getOrDefault(emptyList())
                        for (variant in variants) {
                            if (emitted >= maxStreams) break
                            if (variant.url != targetUrl) {
                                callback(variant)
                                emitted++
                            }
                        }
                    }
                }
                Movies321Clipboard.log("LINKS", "Đã trả về $emitted link phát (verified=${ok.size})")
            }
        } catch (t: Throwable) {
            Movies321Clipboard.log("LINKS", "Lỗi loadLinks: ${t.javaClass.simpleName}: ${t.message?.take(120)}")
        } finally {
            pool.shutdownNow()
            Movies321Clipboard.flushNow()
        }

        return found
    }
}
