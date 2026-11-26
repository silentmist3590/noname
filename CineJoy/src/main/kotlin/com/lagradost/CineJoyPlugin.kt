package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Base64
import android.widget.Toast
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
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

// ============================================================================
// LOG + CLIPBOARD
// ============================================================================
object CJLog {
    private const val MAX_LINES = 350
    private const val RATE_LIMIT_MS = 1500L

    private val buffer = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var lastCopyAt = 0L
    @Volatile private var ctx: Context? = null

    fun attachContext(c: Context) {
        ctx = c.applicationContext
        println("[CineJoy][clipboard] ctx attached=${ctx != null}")
    }

    private fun getCtx(): Context? {
        ctx?.let { return it }
        // Fallback: reflection on App.getAppContext()
        return try {
            val c = Class.forName("com.lagradost.cloudstream3.App")
                .getMethod("getAppContext").invoke(null) as? Context
            c?.applicationContext
        } catch (_: Throwable) { null }
    }

    @Synchronized fun clear() { buffer.clear() }

    @Synchronized
    fun log(tag: String, msg: String) {
        val line = "${fmt.format(Date())} [$tag] $msg"
        buffer.add(line)
        if (buffer.size > MAX_LINES) buffer.removeAt(0)
        println("[CineJoy] $msg")
    }

    @Synchronized
    fun logErr(tag: String, msg: String, e: Throwable? = null) {
        val detail = if (e != null) "$msg | ${e.javaClass.simpleName}: ${e.message}" else msg
        val line = "${fmt.format(Date())} [$tag][ERR] $detail"
        buffer.add(line)
        if (buffer.size > MAX_LINES) buffer.removeAt(0)
        println("[CineJoy][ERR] $detail")
    }

    @Synchronized
    fun copyToClipboard(reason: String, force: Boolean = false) {
        println("[CineJoy][clipboard] called reason=$reason force=$force hasCtx=${getCtx() != null} lines=${buffer.size}")
        val c = getCtx() ?: run {
            println("[CineJoy][clipboard][ERR] no context")
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastCopyAt < RATE_LIMIT_MS) {
            println("[CineJoy][clipboard] rate limited (${now - lastCopyAt}ms < ${RATE_LIMIT_MS}ms)")
            return
        }
        lastCopyAt = now
        try {
            val header = buildString {
                appendLine("=== CineJoy Debug Log ===")
                appendLine("Reason: $reason")
                appendLine("Time:   ${Date()}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
                appendLine("Lines:  ${buffer.size}")
                appendLine("--- log ---")
            }
            val fullText = header + buffer.joinToString("\n")

            val cm = c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CineJoy log", fullText))

            Toast.makeText(c, "⚠ CineJoy log copied (${buffer.size} lines) — paste in chat", Toast.LENGTH_LONG).show()
            println("[CineJoy][clipboard] SUCCESS copied ${buffer.size} lines")
        } catch (e: Throwable) {
            println("[CineJoy][clipboard][ERR] failed: ${e.message}")
        }
    }
}

// ============================================================================
// PLUGIN
// ============================================================================
@CloudstreamPlugin
class CineJoyPlugin : Plugin() {
    override fun load(context: Context) {
        CJLog.attachContext(context)
        registerMainAPI(CineJoyProvider())
    }
}

class CineJoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "CineJoy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()

    // ── Config ──
    private val tmdbKey = "8476a7ab80ad76f0936744df0430e67c"
    private val tmdbFallbackKey = "439c478a771f35c05022f9feabcca01c"
    private val imgProxy = "https://wsrv.nl/?url="

    private val tmdbDirectDomains = listOf(
        "https://api.tmdb.org",
        "https://api.themoviedb.org",
        "https://tmdb.demojameson.de5.net",
        "https://daili.smdhb.com",
        "https://vidora-tmdb.wwmm.date",
    )

    private val tmdbProxies: List<(String) -> String> = listOf(
        { url -> "https://cinejoytv.fun/relay?url=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://api.codetabs.com/v1/proxy/?quest=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://api.allorigins.win/raw?url=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://cors.eu.org/$url" },
        { url -> "https://corsproxy.io/?url=${URLEncoder.encode(url, "UTF-8")}" },
    )

    private val apiProxies: List<(String) -> String> = listOf(
        { url -> "https://api.codetabs.com/v1/proxy/?quest=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://api.allorigins.win/raw?url=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://cors.eu.org/$url" },
        { url -> "https://corsproxy.io/?url=${URLEncoder.encode(url, "UTF-8")}" },
    )
    // API key mặc định được chuyển từ Vidup; thay giá trị này nếu bạn dùng key SubDL riêng.
    private val defaultSubdlApiKey = "xHeOWlCfS9o4EKmK7OwbeOBIhfbIHr2Y"
    private val vidloveBase = "https://api.vidlove.cc"
    private val vidloveReferer = "https://player.vidlove.cc/"
    private val vidrockBase = "https://vidrock.net"
    private val vidrockKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    // NxSha aggregator
    private val nxBase = "https://nxsha.space"
    private val nxPass = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"
    private val nxProviders = setOf(
        "rive-citadel", "mhbox", "awsind", "nitro", "watchout", "castle",
        "vidapi", "hdhub4u", "stvv", "holly", "bkl-blast", "rive-primevids",
        "streamflix", "rive-flowcast", "rive-quasar", "ophm", "rive-guru",
    )
    private val nxPreferred = listOf("nitro", "mhbox", "vidapi", "rive-guru", "rive-citadel", "castle")
    private val nxPretty = mapOf(
        "nitro" to "Nitro", "mhbox" to "MhPly", "rive-citadel" to "Citadel",
        "awsind" to "AwsPly", "watchout" to "Watchout", "castle" to "CastVid",
        "vidapi" to "VidPi", "hdhub4u" to "HDHub", "stvv" to "Stvvid",
        "holly" to "Lolly", "bkl-blast" to "MbBlast", "rive-primevids" to "Prvibd",
        "streamflix" to "StremFx", "rive-flowcast" to "River", "rive-quasar" to "Kutti",
        "ophm" to "Ophm", "rive-guru" to "Gbru",
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "/trending/movie/week" to "Trending Movies",
        "/trending/tv/week" to "Trending Series",
        "/movie/popular" to "Popular Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/popular" to "Popular Series",
        "/tv/top_rated" to "Top Rated Series",
        "/movie/now_playing" to "In Theaters",
    )

    // ================================================================
    // GENERIC FETCH WITH PROXY FALLBACK
    // ================================================================
    private suspend fun fetchWithFallback(
        url: String,
        tag: String,
        proxyList: List<(String) -> String>,
    ): String? {
        suspend fun tryGet(u: String): String? = try {
            val text = app.get(
                u,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/plain, */*",
                    "Accept-Language" to "en-US,en;q=0.9",
                )
            ).text
            if (text.isBlank()) null
            else {
                val t = text.trimStart()
                if (t.startsWith("<") || t.startsWith("Forbidden") || t.startsWith("Access Denied") || t.startsWith("Not Found")) null
                else text
            }
        } catch (e: Exception) {
            CJLog.log(tag, "tryGet fail: ${e.message}")
            null
        }

        tryGet(url)?.let { CJLog.log(tag, "direct OK (${it.length}b)"); return it }
        for ((i, build) in proxyList.withIndex()) {
            tryGet(build(url))?.let {
                CJLog.log(tag, "proxy#$i OK (${it.length}b)")
                return it
            }
        }
        CJLog.logErr(tag, "ALL FAILED for ${url.take(80)}")
        return null
    }

    // ================================================================
    // TMDB
    // ================================================================
    private suspend fun tmdbGet(path: String, useFallback: Boolean = false): JsonNode? {
        val key = if (useFallback) tmdbFallbackKey else tmdbKey
        val normalized = if (path.startsWith("/3/") || path.startsWith("/3?")) path else "/3$path"
        val sep = if (normalized.contains("?")) "&" else "?"
        val full = "$normalized${sep}api_key=$key"

        for (base in tmdbDirectDomains) {
            try {
                val text = app.get(
                    "$base$full",
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent),
                ).text
                if (text.isBlank() || !text.trimStart().startsWith("{")) continue
                val j = mapper.readTree(text)
                val st = j.get("status_code")?.asInt()
                if (st != null && st != 0) {
                    if (!useFallback && (st == 7 || st == 3)) return tmdbGet(path, true)
                    continue
                }
                return j
            } catch (_: Exception) { }
        }

        val target = "https://api.themoviedb.org$full"
        for ((i, build) in tmdbProxies.withIndex()) {
            try {
                val text = app.get(
                    build(target),
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent),
                ).text
                if (text.isBlank() || !text.trimStart().startsWith("{")) continue
                val j = mapper.readTree(text)
                val st = j.get("status_code")?.asInt()
                if (st != null && st != 0) continue
                CJLog.log("tmdb-proxy", "proxy#$i OK")
                return j
            } catch (_: Exception) { }
        }

        if (!useFallback) return tmdbGet(path, true)
        CJLog.logErr("tmdb", "ALL FAILED for $path")
        return null
    }

    private fun JsonNode.s(k: String): String? =
        get(k)?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.takeIf { it.isNotBlank() }

    private fun proxyImg(u: String): String = "$imgProxy${URLEncoder.encode(u, "UTF-8")}"
    private fun poster(path: String?, size: String = "w500"): String? =
        if (path != null) proxyImg("https://image.tmdb.org/t/p/$size$path") else null

    private fun yearOf(d: String?): Int? = if (d == null || d.length < 4) null else d.take(4).toIntOrNull()

    private fun mediaType(r: JsonNode): String {
        val mt = r.s("media_type")
        if (mt == "movie" || mt == "tv") return mt
        return if (r.get("first_air_date") != null || r.get("name") != null) "tv" else "movie"
    }

    private fun tmdbToSearch(r: JsonNode): SearchResponse? {
        val id = r.get("id")?.asInt() ?: return null
        val type = mediaType(r)
        val title = r.s("title") ?: r.s("name") ?: return null
        val url = """{"type":"$type","id":$id}"""
        val posterUrl = poster(r.s("poster_path"))
        val year = yearOf(r.s("release_date") ?: r.s("first_air_date"))
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl; this.year = year
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl; this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        CJLog.clear()
        val items = mutableListOf<SearchResponse>()
        val json = tmdbGet("${request.data}?page=$page")
        json?.get("results")?.let { if (it.isArray) for (r in it) tmdbToSearch(r)?.let { s -> items.add(s) } }
        CJLog.log("main", "${request.name}: ${items.size} items")
        if (items.isEmpty()) {
            CJLog.logErr("main", "EMPTY ${request.name}")
            CJLog.copyToClipboard("getMainPage empty ${request.name}", force = true)
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.trim().equals("!log", ignoreCase = true)) {
            CJLog.copyToClipboard("manual trigger", force = true)
            return emptyList()
        }
        CJLog.clear()
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val json = tmdbGet("/search/multi?query=$q&page=1&include_adult=false") ?: return emptyList()
        val results = json.get("results") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        if (results.isArray) for (r in results) {
            if (r.s("media_type") !in listOf("movie", "tv")) continue
            tmdbToSearch(r)?.let { out.add(it) }
        }
        CJLog.log("search", "query=$query: ${out.size} items")
        return out
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
    // ================================================================
    // LOAD
    // ================================================================
    private data class ParsedUrl(val type: String, val id: Int, val s: Int, val e: Int)

    private fun parseUrl(url: String): ParsedUrl? = try {
        val j = mapper.readTree(url)
        val type = j.s("type") ?: return null
        val id = j.get("id")?.asInt() ?: return null
        ParsedUrl(type, id, j.get("s")?.asInt() ?: 1, j.get("e")?.asInt() ?: 1)
    } catch (_: Exception) { null }

    private fun fixEp(e: Int): Int = if (e >= 800) (e % 100) + 1 + ((e / 100) - 8) * 2 else e

    override suspend fun load(url: String): LoadResponse? {
        CJLog.clear()
        val p = parseUrl(url) ?: return null
        CJLog.log("load", "type=${p.type} id=${p.id}")

        return if (p.type == "movie") {
            val d = tmdbGet("/movie/${p.id}") ?: return null
            newMovieLoadResponse(d.s("title") ?: "Unknown", url, TvType.Movie, """{"type":"movie","id":${p.id}}""") {
                this.posterUrl = poster(d.s("poster_path"))
                this.backgroundPosterUrl = poster(d.s("backdrop_path"), "w780")
                this.year = yearOf(d.s("release_date"))
                this.plot = d.s("overview")
                this.duration = d.get("runtime")?.asInt()?.takeIf { it > 0 }
                this.tags = d.get("genres")?.mapNotNull { it.s("name") }
            }
        } else {
            val d = tmdbGet("/tv/${p.id}") ?: return null
            val episodes = mutableListOf<Episode>()
            d.get("seasons")?.let { seasons ->
                if (seasons.isArray) for (s in seasons) {
                    val sn = s.get("season_number")?.asInt() ?: continue
                    if (sn <= 0) continue
                    val cnt = s.get("episode_count")?.asInt() ?: 0
                    if (cnt <= 0) continue
                    val sj = tmdbGet("/tv/${p.id}/season/$sn")
                    val eps = sj?.get("episodes")
                    if (eps != null && eps.isArray) {
                        for (ep in eps) {
                            val en = ep.get("episode_number")?.asInt() ?: continue
                            val fixed = fixEp(en)
                            episodes.add(newEpisode("""{"type":"tv","id":${p.id},"s":$sn,"e":$fixed}""") {
                                this.name = ep.s("name") ?: "S${sn}E$fixed"
                                this.season = sn
                                this.episode = fixed
                                this.posterUrl = poster(ep.s("still_path"))
                                this.description = ep.s("overview")
                            })
                        }
                    } else for (en in 1..cnt) episodes.add(newEpisode("""{"type":"tv","id":${p.id},"s":$sn,"e":$en}""") {
                        this.name = "S${sn}E$en"; this.season = sn; this.episode = en
                    })
                }
            }
            newTvSeriesLoadResponse(d.s("name") ?: "Unknown", url, TvType.TvSeries, episodes) {
                this.posterUrl = poster(d.s("poster_path"))
                this.backgroundPosterUrl = poster(d.s("backdrop_path"), "w780")
                this.year = yearOf(d.s("first_air_date"))
                this.plot = d.s("overview")
                this.tags = d.get("genres")?.mapNotNull { it.s("name") }
            }
        }
    }

    // ================================================================
    // VIDLOVE
    // ================================================================
    private data class Stream(val url: String, val label: String, val subs: List<Pair<String, String>> = emptyList())

    private suspend fun vidloveStream(
        type: String, id: Int, s: Int, e: Int, suffix: String, vLabel: String,
    ): Stream? {
        val path = if (type == "tv") "/tv?id=$id&season=$s&episode=$e&mode=json$suffix"
                   else "/movie?id=$id&mode=json$suffix"
        val text = fetchWithFallback("$vidloveBase$path", "vidlove-$vLabel", apiProxies) ?: return null
        return try {
            val j = mapper.readTree(text)
            val code = j.get("code")?.asInt()
            if (code != null && code != 0 && code != 200) {
                CJLog.log("vidlove-$vLabel", "code=$code msg=${j.get("message")?.asText()}")
                return null
            }
            val src = j.get("source") ?: return null
            val url = src.s("url") ?: return null
            if (!url.startsWith("http")) return null
            val quality = parseQuality(src.s("manifest"))
            val subs = mutableListOf<Pair<String, String>>()
            j.get("subtitles")?.let { if (it.isArray) for (sub in it) {
                val f = sub.s("file") ?: sub.s("url") ?: continue
                subs.add((sub.s("language") ?: sub.s("label") ?: "en") to f)
            } }
            Stream(url, quality, subs)
        } catch (e: Exception) {
            CJLog.logErr("vidlove-$vLabel", "parse fail", e)
            null
        }
    }

    private fun parseQuality(m: String?): String {
        if (m == null) return "Auto"
        var max = 0
        Regex("RESOLUTION=(\\d+)x(\\d+)").findAll(m).forEach {
            it.groupValues[2].toIntOrNull()?.let { h -> if (h > max) max = h }
        }
        return when {
            max >= 2160 -> "2160p"; max >= 1080 -> "1080p"
            max >= 720 -> "720p"; max >= 480 -> "480p"
            max > 0 -> "${max}p"; else -> "Auto"
        }
    }

    // ================================================================
    // VIDROCK
    // ================================================================
    private fun hexBytes(h: String): ByteArray =
        ByteArray(h.length / 2) { i -> ((h[i * 2].digitToInt(16) shl 4) or h[i * 2 + 1].digitToInt(16)).toByte() }

    private fun b64Url(s: String): String {
        var r = s.replace('-', '+').replace('_', '/')
        while (r.length % 4 != 0) r += "="
        return r
    }

    private fun vidrockDec(token: String): String? = try {
        val std = b64Url(token)
        if (std.length < 20) null else {
            val iv = Base64.decode(std.substring(0, 16), Base64.DEFAULT)
            val data = Base64.decode(std.substring(16), Base64.DEFAULT)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(hexBytes(vidrockKeyHex), "AES"), GCMParameterSpec(128, iv))
            String(c.doFinal(data), Charsets.UTF_8)
        }
    } catch (e: Exception) { CJLog.logErr("vidrock", "dec fail", e); null }

    private suspend fun vidrockStreams(p: ParsedUrl): List<Stream> {
        val path = if (p.type == "tv") "tv/${p.id}/${p.s}/${p.e}" else "movie/${p.id}"
        val text = fetchWithFallback("$vidrockBase/api/$path", "vidrock", apiProxies) ?: return emptyList()
        return try {
            val j = mapper.readTree(text)
            val out = mutableListOf<Stream>()
            j.fieldNames().forEach { name ->
                val s = j.get(name) ?: return@forEach
                if (!s.isObject) return@forEach
                val enc = s.s("url")
                if (enc.isNullOrBlank()) {
                    CJLog.log("vidrock", "$name: empty")
                    return@forEach
                }
                val dec = vidrockDec(enc) ?: return@forEach
                if (!dec.startsWith("http")) return@forEach
                val lang = s.s("language") ?: ""
                out.add(Stream(dec, name + (if (lang.isNotBlank()) " ($lang)" else "")))
            }
            out
        } catch (e: Exception) { CJLog.logErr("vidrock", "parse", e); emptyList() }
    }

    // ================================================================
    // NXSHA  (AES-256-CBC with OpenSSL EVP_BytesToKey)
    // ================================================================
    private fun nxEvp(pass: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        val out = mutableListOf<Byte>()
        var prev = ByteArray(0)
        while (out.size < keyLen + ivLen) {
            md.reset()
            md.update(prev)
            md.update(pass)
            md.update(salt)
            prev = md.digest()
            prev.forEach { out.add(it) }
        }
        val arr = out.toByteArray()
        return arr.sliceArray(0 until keyLen) to arr.sliceArray(keyLen until keyLen + ivLen)
    }

    private fun nxEncode(obj: JSONObject): String {
        val salt = ByteArray(8).also { java.util.Random().nextBytes(it) }
        obj.put("_req_ts", System.currentTimeMillis())
        obj.put("_req_salt", Integer.toHexString((Math.random() * Int.MAX_VALUE).toInt()))
        val payload = obj.toString().toByteArray(Charsets.UTF_8)
        val (key, iv) = nxEvp(nxPass.toByteArray(Charsets.UTF_8), salt, 32, 16)
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val enc = c.doFinal(payload)
        val combined = "Salted__".toByteArray(Charsets.UTF_8) + salt + enc
        return Base64.encodeToString(combined, Base64.NO_WRAP or Base64.URL_SAFE).trimEnd('=')
    }

    private fun nxDecode(b64url: String): JSONObject? = try {
        var std = b64url.replace('-', '+').replace('_', '/')
        while (std.length % 4 != 0) std += "="
        val combined = Base64.decode(std, Base64.DEFAULT)
        if (combined.size < 17) null else {
            val salt = combined.sliceArray(8 until 16)
            val enc = combined.sliceArray(16 until combined.size)
            val (key, iv) = nxEvp(nxPass.toByteArray(Charsets.UTF_8), salt, 32, 16)
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val dec = c.doFinal(enc)
            val j = JSONObject(String(dec, Charsets.UTF_8))
            j.remove("_req_ts"); j.remove("_req_salt")
            j
        }
    } catch (e: Exception) {
        CJLog.logErr("nxsha", "decode fail: ${e.message}")
        null
    }

    private suspend fun nxshaSources(p: ParsedUrl): List<Stream> {
        val out = mutableListOf<Stream>()
        try {
            val q1 = nxEncode(JSONObject().apply {
                put("tmdbId", p.id.toString())
                put("imdb_id", "")
                put("type", p.type)
                put("season", p.s)
                put("episode", p.e)
            })
            val r1 = fetchWithFallback("$nxBase/api/servers?q=$q1", "nxsha-servers", apiProxies) ?: return out
            val r1Json = JSONObject(r1)
            val hash = r1Json.optString("_hash")
            if (hash.isBlank()) { CJLog.log("nxsha", "no hash: ${r1.take(80)}"); return out }
            val decoded = nxDecode(hash) ?: return out
            val serversArr = decoded.optJSONArray("servers") ?: return out

            val providers = mutableListOf<Pair<JSONObject, String>>()
            for (i in 0 until serversArr.length()) {
                val srv = serversArr.optJSONObject(i) ?: continue
                val scraper = srv.optString("scraper")
                if (scraper in nxProviders) providers.add(srv to scraper)
            }
            providers.sortBy { (_, sc) -> nxPreferred.indexOf(sc).let { if (it < 0) 999 else it } }
            CJLog.log("nxsha", "${providers.size} providers (of ${serversArr.length()})")

            for ((srv, scraper) in providers.take(5)) {
                try {
                    val q2 = nxEncode(JSONObject().apply {
                        put("ex_lang", true)
                        put("provider", scraper)
                        put("tmdbId", p.id.toString())
                        put("imdb_id", "")
                        put("type", p.type)
                        put("season", p.s)
                        put("episode", p.e)
                    })
                    val r2 = fetchWithFallback("$nxBase/api/sources?q=$q2", "nxsha-$scraper", apiProxies) ?: continue
                    val r2Json = JSONObject(r2)
                    val h2 = r2Json.optString("_hash")
                    if (h2.isBlank()) continue
                    val dec2 = nxDecode(h2) ?: continue
                    val srcArr = dec2.optJSONArray("sources") ?: continue
                    for (j in 0 until srcArr.length()) {
                        val src = srcArr.optJSONObject(j) ?: continue
                        val url = src.optString("url")
                        if (url.isBlank() || !url.startsWith("http")) continue
                        if (src.optString("type") == "embed") continue
                        val name = nxPretty[scraper] ?: scraper
                        val label = src.optString("label").ifBlank { src.optString("quality") }.ifBlank { "multi" }
                        out.add(Stream(url, "NxSha $name - $label"))
                    }
                } catch (e: Exception) { CJLog.log("nxsha-$scraper", "fail: ${e.message}") }
            }
        } catch (e: Exception) { CJLog.logErr("nxsha", "sources", e) }
        return out
    }

    // ================================================================
    // M3U8 VALIDATION (browser-like headers)
    // ================================================================
    private suspend fun isValidM3u8(url: String, referer: String, tag: String): Boolean = try {
        val baseOrigin = try { java.net.URI(referer).let { "${it.scheme}://${it.host}" } } catch (_: Exception) { referer }
        val text = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to referer,
                "Origin" to baseOrigin,
                "Accept" to "*/*",
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept-Encoding" to "identity",
                "Range" to "bytes=0-2047",
                "Connection" to "keep-alive",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
            )
        ).text
        val ok = text.trimStart().startsWith("#EXTM3U")
        if (!ok) CJLog.log("$tag-probe", "NOT m3u8 (${text.length}b): ${text.take(80).replace("\n", " ")}")
        ok
    } catch (e: Exception) {
        CJLog.log("$tag-probe", "fail: ${e.message}")
        false
    }

    // ================================================================
    // LOAD LINKS
    // ================================================================
    private data class Cand(
        val label: String, val url: String, val referer: String,
        val headers: Map<String, String>, val verified: Boolean,
        val subs: List<Pair<String, String>>,
    )

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        CJLog.clear()
        CJLog.log("loadLinks", "data=$data")

        val p = parseUrl(data)
        if (p == null) {
            CJLog.logErr("loadLinks", "parse fail")
            CJLog.copyToClipboard("parseUrl fail", force = true)
            return false
        }
        CJLog.log("loadLinks", "type=${p.type} id=${p.id} s=${p.s} e=${p.e}")

        val cands = mutableListOf<Cand>()

        // ── VidLove: 3 variants (reduced from 7 to avoid proxy rate limit) ──
        val vlVariants = listOf(
            "" to "Nebula",
            "&sources=moviebox" to "Lisbon",
            "&sources=warden" to "Solara",
        )
        for ((suffix, lbl) in vlVariants) {
            val s = vidloveStream(p.type, p.id, p.s, p.e, suffix, lbl) ?: continue
            val ok = isValidM3u8(s.url, vidloveReferer, "vl-$lbl")
            cands.add(Cand(
                label = "CineJoy $lbl - ${s.label}", url = s.url,
                referer = vidloveReferer,
                headers = mapOf("User-Agent" to userAgent, "Referer" to vidloveReferer, "Origin" to "https://player.vidlove.cc"),
                verified = ok, subs = s.subs,
            ))
        }

        // ── VidRock ──
        for (s in vidrockStreams(p)) {
            val ok = isValidM3u8(s.url, "$vidrockBase/", "vr-${s.label}")
            cands.add(Cand(
                label = "CineJoy ${s.label}", url = s.url,
                referer = "$vidrockBase/",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$vidrockBase/",
                    "Origin" to vidrockBase,
                ),
                verified = ok, subs = emptyList(),
            ))
        }

        // ── NxSha ──
        for (s in nxshaSources(p)) {
            val ok = isValidM3u8(s.url, "$nxBase/", "nx-${s.label.take(20)}")
            cands.add(Cand(
                label = s.label, url = s.url,
                referer = "$nxBase/",
                headers = mapOf("User-Agent" to userAgent, "Referer" to "$nxBase/", "Origin" to nxBase),
                verified = ok, subs = s.subs,
            ))
        }

        val verified = cands.filter { it.verified }
        CJLog.log("loadLinks", "candidates=${cands.size} verified=${verified.size}")

        for (c in cands.filter { !it.verified }) {
            CJLog.logErr("loadLinks", "REJECTED ${c.label} -> ${c.url.take(80)}")
        }

        var found = false
        for (c in verified) {
            callback.invoke(newExtractorLink(name, c.label, c.url, ExtractorLinkType.M3U8) {
                this.referer = c.referer
                this.quality = Qualities.Unknown.value
                this.headers = c.headers
            })
            for ((lang, sub) in c.subs) subtitleCallback.invoke(newSubtitleFile(url = sub, lang = lang))
            found = true
        }

        if (!found) {
            val reason = if (cands.isEmpty()) "0 candidates (all APIs failed)" else "0 verified / ${cands.size} unverified"
            CJLog.logErr("loadLinks", "FAILED: $reason")
            CJLog.copyToClipboard("no verified — $reason", force = true)
        } else {
            CJLog.log("loadLinks", "SUCCESS: emitted ${verified.size} streams")
        }
        return found
    }
}
