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
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// Bật true để tự động copy log ra Clipboard (tắt thành false khi không cần debug)
private const val AUTO_COPY_LOG = true

// Tùy chọn API key phụ đề (để trống vẫn tự động dùng các nguồn Keyless: OpenSubtitles v3, Wyzie, SubDL, Subsource)
private const val OPENSUBTITLES_API_KEY = ""
private const val SUBDL_API_KEY = ""

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
 * Bộ tìm phụ đề song song cho Movies321 (CloudStream 4.5.2, chuẩn thuần OkHttp + Executor):
 * - Chạy song song qua ExecutorService giới hạn luồng & OkHttpClient (4.5s timeout/nguồn).
 * - Hỗ trợ OpenSubtitles (Stremio v3 keyless + API v1), SubDL, Wyzie Subs, Subsource.
 * - Sắp xếp ưu tiên tuyệt đối: Tiếng Việt (vi) -> Tiếng Anh (en) -> Ngôn ngữ khác.
 */
private object Movies321Subtitles {
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
            "Tìm phụ đề song song imdb=$cleanImdb tmdb=$tmdbId title=$title S${season ?: "-"}E${episode ?: "-"}"
        )
        return listOf(
            pool.submit(Callable<List<SubCandidate>> { fromOpenSubtitlesStremio(client, cleanImdb, season, episode) }),
            pool.submit(Callable<List<SubCandidate>> { fromOpenSubtitlesOfficial(client, tmdbId, title, season, episode) }),
            pool.submit(Callable<List<SubCandidate>> { fromSubDL(client, cleanImdb, tmdbId, title, year, season, episode) }),
            pool.submit(Callable<List<SubCandidate>> { fromWyzie(client, cleanImdb ?: tmdbId, season, episode) }),
            pool.submit(Callable<List<SubCandidate>> { fromSubsource(client, title, year, season, episode) })
        )
    }

    fun collectAndSort(futures: List<Future<List<SubCandidate>>>): List<SubCandidate> {
        val raw = ArrayList<SubCandidate>()
        val deadline = System.currentTimeMillis() + 6000L
        for (f in futures) {
            val rem = (deadline - System.currentTimeMillis()).coerceAtLeast(150L)
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
            .take(18)

        Movies321Clipboard.log(
            "SUBS",
            "Tìm thấy ${sorted.size} phụ đề (VI=${sorted.count { it.langCode == "vi" }}, EN=${sorted.count { it.langCode == "en" }})"
        )
        return sorted
    }

    private fun httpGet(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val rb = Request.Builder().url(url).header("Accept", "application/json")
            headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun httpPostJson(
        client: OkHttpClient,
        url: String,
        jsonBody: String,
        headers: Map<String, String> = emptyMap()
    ): String? {
        return try {
            val body = jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType())
            val rb = Request.Builder().url(url).post(body).header("Accept", "application/json")
            headers.forEach { (k, v) -> rb.header(k, v) }
            client.newCall(rb.build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
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

    private fun formatLabel(code: String, source: String, tag: String? = null): String {
        val norm = normalizeLang(code)
        val base = when (norm) {
            "vi" -> "Tiếng Việt"
            "en" -> "English"
            else -> SubtitleHelper.fromTwoLettersToLanguage(norm) ?: norm.uppercase()
        }
        return if (!tag.isNullOrBlank()) "$base [$source · $tag]" else "$base [$source]"
    }

    private fun fromOpenSubtitlesStremio(
        client: OkHttpClient,
        imdbId: String?,
        season: Int?,
        episode: Int?
    ): List<SubCandidate> {
        if (imdbId.isNullOrBlank()) return emptyList()
        val key = if (season != null && episode != null) "$imdbId:$season:$episode" else imdbId
        val kind = if (season != null && episode != null) "series" else "movie"
        return try {
            val text = httpGet(client, "https://opensubtitles-v3.strem.io/subtitles/$kind/$key.json")
                ?: return emptyList()
            val arr = JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
            val out = ArrayList<SubCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val norm = normalizeLang(obj.optString("lang"))
                if (norm != "vi" && norm != "en") continue
                val url = obj.optString("url")
                if (!url.startsWith("http")) continue
                out.add(SubCandidate(norm, formatLabel(norm, "OpenSubs"), url, "OpenSubs-v3", if (norm == "vi") 95 else 75))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fromOpenSubtitlesOfficial(
        client: OkHttpClient,
        tmdbId: String?,
        title: String?,
        season: Int?,
        episode: Int?
    ): List<SubCandidate> {
        if (OPENSUBTITLES_API_KEY.isBlank()) return emptyList()
        val q = buildString {
            append("languages=vi,en")
            if (!tmdbId.isNullOrBlank()) append("&tmdb_id=$tmdbId")
            else if (!title.isNullOrBlank()) append("&query=${URLEncoder.encode(title, "UTF-8")}")
            if (season != null && episode != null) append("&season_number=$season&episode_number=$episode")
        }
        return try {
            val headers = mapOf("Api-Key" to OPENSUBTITLES_API_KEY, "User-Agent" to "Movies321 v9")
            val text = httpGet(client, "https://api.opensubtitles.com/api/v1/subtitles?$q", headers)
                ?: return emptyList()
            val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
            val out = ArrayList<SubCandidate>()
            var viCount = 0
            var enCount = 0
            for (i in 0 until arr.length()) {
                val attrs = arr.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val norm = normalizeLang(attrs.optString("language"))
                if (norm == "vi" && viCount >= 3) continue
                if (norm == "en" && enCount >= 2) continue
                if (norm != "vi" && norm != "en") continue
                val fileId = attrs.optJSONArray("files")?.optJSONObject(0)?.optInt("file_id") ?: continue
                val dlBody = JSONObject().put("file_id", fileId).toString()
                val dlRes = httpPostJson(client, "https://api.opensubtitles.com/api/v1/download", dlBody, headers)
                    ?: continue
                val link = JSONObject(dlRes).optString("link")
                if (!link.startsWith("http")) continue
                if (norm == "vi") viCount++ else enCount++
                out.add(SubCandidate(norm, formatLabel(norm, "OpenSubsAPI"), link, "OpenSubs-API", 98))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fromSubDL(
        client: OkHttpClient,
        imdbId: String?,
        tmdbId: String?,
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<SubCandidate> {
        val q = buildString {
            append("languages=VI,EN&subs_per_page=10")
            if (SUBDL_API_KEY.isNotBlank()) append("&api_key=$SUBDL_API_KEY")
            if (!imdbId.isNullOrBlank()) append("&imdb_id=$imdbId")
            else if (!tmdbId.isNullOrBlank()) append("&tmdb_id=$tmdbId")
            else if (!title.isNullOrBlank()) append("&film_name=${URLEncoder.encode(title, "UTF-8")}")
            if (year != null) append("&year=$year")
            if (season != null) append("&season_number=$season")
            if (episode != null) append("&episode_number=$episode")
        }
        return try {
            val text = httpGet(client, "https://api.subdl.com/api/v1/subtitles?$q") ?: return emptyList()
            val arr = JSONObject(text).optJSONArray("subtitles") ?: return emptyList()
            val out = ArrayList<SubCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val norm = normalizeLang(o.optString("lang"))
                if (norm != "vi" && norm != "en") continue
                val path = o.optString("url")
                if (path.isBlank()) continue
                val full = if (path.startsWith("http")) path else "https://dl.subdl.com$path"
                out.add(SubCandidate(norm, formatLabel(norm, "SubDL", o.optString("release_name").take(16)), full, "SubDL", if (norm == "vi") 92 else 72))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fromWyzie(
        client: OkHttpClient,
        id: String?,
        season: Int?,
        episode: Int?
    ): List<SubCandidate> {
        if (id.isNullOrBlank()) return emptyList()
        val url = buildString {
            append("https://sub.wyzie.ru/search?id=$id")
            if (season != null && episode != null) append("&season=$season&episode=$episode")
        }
        return try {
            val text = httpGet(client, url) ?: return emptyList()
            val arr = JSONArray(text)
            val out = ArrayList<SubCandidate>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val norm = normalizeLang(o.optString("language"))
                if (norm != "vi" && norm != "en") continue
                val subUrl = o.optString("url")
                if (!subUrl.startsWith("http")) continue
                out.add(SubCandidate(norm, formatLabel(norm, "Wyzie"), subUrl, "Wyzie", if (norm == "vi") 90 else 70))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun fromSubsource(
        client: OkHttpClient,
        title: String?,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<SubCandidate> {
        if (title.isNullOrBlank()) return emptyList()
        return try {
            val searchBody = JSONObject().put("query", title).toString()
            val searchRes = httpPostJson(client, "https://api.subsource.net/api/searchMovie", searchBody)
                ?: return emptyList()
            val found = JSONObject(searchRes).optJSONArray("found") ?: return emptyList()
            val match = (0 until found.length())
                .mapNotNull { found.optJSONObject(it) }
                .firstOrNull { year == null || it.optInt("releaseYear") == year }
                ?: found.optJSONObject(0)
                ?: return emptyList()
            val linkName = match.optString("linkName").takeIf { it.isNotBlank() } ?: return emptyList()
            val detailJson = JSONObject()
                .put("movieName", linkName)
                .put("langs", JSONArray(listOf("vietnamese", "english")))
            if (season != null) detailJson.put("season", "season-$season")
            val detailRes = httpPostJson(client, "https://api.subsource.net/api/getMovie", detailJson.toString())
                ?: return emptyList()
            val subs = JSONObject(detailRes).optJSONArray("subs") ?: return emptyList()
            val out = ArrayList<SubCandidate>()
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val norm = normalizeLang(s.optString("lang"))
                if (norm != "vi" && norm != "en") continue
                val subId = s.optString("subId")
                if (subId.isBlank()) continue
                if (episode != null) {
                    val rel = s.optString("releaseName").lowercase()
                    val epTag = "e${episode.toString().padStart(2, '0')}"
                    if (!rel.contains(epTag) && !rel.contains("complete")) continue
                }
                out.add(SubCandidate(norm, formatLabel(norm, "Subsource"), "https://api.subsource.net/api/downloadSub/$subId", "Subsource", if (norm == "vi") 88 else 68))
            }
            out
        } catch (_: Throwable) {
            emptyList()
        }
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
 * Movies321 (v10 - sửa lỗi kết nối 321movies.xyz trên Android TV 7 / nhà mạng VN):
 * - Chuẩn thuần OkHttp + ExecutorService -> biên dịch sạch 100% trên repo noname (không dùng thư viện ngoài).
 * - Tăng timeout lên 45s (vì 321movies.xyz khi cache-miss cần ~25-35s để scrape nguồn), kèm fallback DoH 1.1.1.1 + HTTP/1.1 + TLS Android 7.
 * - Unwrap nhiều lớp proxy (piracya.workers.dev -> corsflix -> origin) để phát đa server & đa chất lượng M3U8.
 * - Giới hạn tối đa 3 luồng (Executors.newFixedThreadPool(3)) + buffer 128KB tối ưu TV Android 7 RAM 1GB.
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
            .callTimeout(4500L, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()

        try {
            // 1. Chạy song song tìm phụ đề (OpenSubtitles, SubDL, Wyzie, Subsource) trong khi đang lấy playlist
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

            for ((subLabel, subUrl) in embeddedTracks) {
                runCatching { subtitleCallback(SubtitleFile(subLabel, subUrl)) }
            }
            val sortedSubs = Movies321Subtitles.collectAndSort(subFutures)
            for (sub in sortedSubs) {
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
