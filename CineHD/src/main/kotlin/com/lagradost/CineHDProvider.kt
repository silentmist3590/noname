package com.lagradost

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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

@CloudstreamPlugin
class CineHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineHDProvider())
    }
}

/**
 * CloudStream 3 Provider cho CineHD (cinehd.vc)
 * Bản cập nhật v2.1.0 - Khắc phục triệt để hiện tượng "Phát sai phim"
 */
class CineHDProvider : MainAPI() {
    override var mainUrl = "https://cinehd.vc"
    override var name = "CineHD"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)


    private inner class Dbg(private val tag: String) {
        fun log(message: String) {
            android.util.Log.d("CineHD", "[$tag] $message")
        }

        fun err(message: String, error: Throwable?) {
            android.util.Log.e("CineHD", "[$tag] $message: ${error?.message}", error)
        }
    }

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    
    // TMDB Hosts chống chặn DNS mạng Việt Nam (::1)
    private val tmdbHosts = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private var tmdbPrefer = 0

    // TMDB API Keys dự phòng
    private val tmdbKeys = listOf(
        "439c478a771f35c05022f9feabcca01c",
        "8476a7ab80ad76f0936744df0430e67c",
        "15d2ea6d0dc1d476efbca3eba2b9bbfb"
    )

    private val tmdbBearerToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"
    // API key mặc định được chuyển từ Vidup; thay giá trị này nếu bạn dùng key SubDL riêng.
    private val defaultSubdlApiKey = "xHeOWlCfS9o4EKmK7OwbeOBIhfbIHr2Y"
    // Máy chủ phát
    private val vidrockBase = "https://vidrock.net"
    private val vidrockKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private val nxBase = "https://nxsha.space"
    private val nxPass = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"

    // Bỏ qua các host lưu trữ file rác/chậm
    private val blockedHostRegex = Regex(
        "gdflix|katdrive|sendcm|1fichier|fastpic|faststream|gdriveplayer|gdtot|filepress|sharer",
        RegexOption.IGNORE_CASE
    )

    override val mainPage = mainPageOf(
        "/trending/movie/week" to "Trending Movies",
        "/trending/tv/week" to "Trending TV Shows",
        "/movie/now_playing" to "In Theaters",
        "/movie/popular" to "Popular Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/popular" to "Popular Series",
        "/tv/top_rated" to "Top Rated Series"
    )

    // =========================================================================
    // TMDB Network & Catalog
    // =========================================================================

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"

        for (k in tmdbHosts.indices) {
            val hi = (tmdbPrefer + k) % tmdbHosts.size
            val host = tmdbHosts[hi]

            for (apiKey in tmdbKeys) {
                val url = "$host$path${sep}api_key=$apiKey"
                val res = runCatching {
                    app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Accept" to "application/json",
                            "Accept-Language" to "en-US,en;q=0.9"
                        ),
                        timeout = 10L
                    ).text
                }.getOrNull()

                if (!res.isNullOrBlank()) {
                    val json = runCatching { JSONObject(res) }.getOrNull()
                    if (json != null && !json.has("status_code")) {
                        tmdbPrefer = hi
                        return json
                    }
                }
            }

            // Fallback Token
            val bearerUrl = "$host$path"
            val resBearer = runCatching {
                app.get(
                    bearerUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Authorization" to "Bearer $tmdbBearerToken",
                        "Accept" to "application/json",
                        "Accept-Language" to "en-US,en;q=0.9"
                    ),
                    timeout = 10L
                ).text
            }.getOrNull()

            if (!resBearer.isNullOrBlank()) {
                val json = runCatching { JSONObject(resBearer) }.getOrNull()
                if (json != null && !json.has("status_code")) {
                    tmdbPrefer = hi
                    return json
                }
            }
        }
        return null
    }

    private fun posterUrl(path: String?, size: String = "w500"): String {
        if (path.isNullOrBlank()) return ""
        val clean = path.removePrefix("/")
        val tmdbImg = "https://image.tmdb.org/t/p/$size/$clean"
        return "https://wsrv.nl/?url=${URLEncoder.encode(tmdbImg, "UTF-8")}&w=500&output=webp"
    }

    private fun parseYear(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        val m = Regex("^(\\d{4})").find(date) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun toSearchResponse(obj: JSONObject): SearchResponse? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null

        val mediaType = obj.optString("media_type")
        val isTv = if (mediaType.isNotBlank()) {
            mediaType == "tv"
        } else {
            obj.has("first_air_date") || obj.has("name")
        }

        val type = if (isTv) "tv" else "movie"
        val title = obj.optString(if (isTv) "name" else "title", "").trim()
        if (title.isBlank()) return null

        val poster = posterUrl(obj.optString("poster_path", ""))
        val dateStr = obj.optString(if (isTv) "first_air_date" else "release_date", "")
        val year = parseYear(dateStr)

        val linkData = JSONObject().apply {
            put("type", type)
            put("id", id)
            put("title", title)
            put("year", year ?: 0)
        }.toString()

        return if (isTv) {
            newTvSeriesSearchResponse(title, linkData, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, linkData, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "${request.data}${if (request.data.contains("?")) "&" else "?"}page=$page"
        val json = tmdbGet(pageUrl) ?: return newHomePageResponse(request.name, emptyList())

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val itemObj = results.optJSONObject(i) ?: continue
            val item = toSearchResponse(itemObj) ?: continue
            items.add(item)
        }

        val hasNext = page < json.optInt("total_pages", 1)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val json = tmdbGet("/search/multi?query=$encoded&include_adult=false&page=1") ?: return emptyList()

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val item = toSearchResponse(obj) ?: continue
            items.add(item)
        }
        return items
    }

    // =========================================================================
    // Load Details (Movie / TV Series)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        val parsed = parseDataUrl(url) ?: throw ErrorLoadingException("Invalid CineHD URL: $url")
        val id = parsed.optInt("id")
        val isTv = parsed.optString("type") == "tv"

        if (!isTv) {
            val json = tmdbGet("/movie/$id?append_to_response=credits,external_ids")
                ?: throw ErrorLoadingException("Movie details not found for TMDB id: $id")

            val title = json.optString("title", parsed.optString("title", "Unknown"))
            val poster = posterUrl(json.optString("poster_path", ""))
            val backdrop = posterUrl(json.optString("backdrop_path", ""), "w780")
            val year = parseYear(json.optString("release_date", ""))
            val overview = json.optString("overview", "")
            val runtime = json.optInt("runtime", 0).takeIf { it > 0 }
            val imdbId = json.optString("imdb_id", "").ifBlank {
                json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
            }
            val genres = (0 until (json.optJSONArray("genres")?.length() ?: 0)).mapNotNull {
                json.optJSONArray("genres")?.optJSONObject(it)?.optString("name")
            }

            val linkData = JSONObject().apply {
                put("type", "movie")
                put("id", id)
                put("title", title)
                put("year", year ?: 0)
                put("imdb_id", imdbId)
            }.toString()

            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.duration = runtime
                this.tags = genres
            }
        } else {
            val json = tmdbGet("/tv/$id?append_to_response=credits,external_ids")
                ?: throw ErrorLoadingException("TV Series details not found for TMDB id: $id")

            val title = json.optString("name", parsed.optString("title", "Unknown"))
            val poster = posterUrl(json.optString("poster_path", ""))
            val backdrop = posterUrl(json.optString("backdrop_path", ""), "w780")
            val year = parseYear(json.optString("first_air_date", ""))
            val overview = json.optString("overview", "")
            val status = if (json.optString("status") == "Ended") ShowStatus.Completed else ShowStatus.Ongoing
            val imdbId = json.optString("imdb_id", "").ifBlank {
                json.optJSONObject("external_ids")?.optString("imdb_id", "") ?: ""
            }
            val genres = (0 until (json.optJSONArray("genres")?.length() ?: 0)).mapNotNull {
                json.optJSONArray("genres")?.optJSONObject(it)?.optString("name")
            }

            val seasonsJson = json.optJSONArray("seasons") ?: JSONArray()
            val seasonNums = mutableListOf<Int>()
            for (i in 0 until seasonsJson.length()) {
                val s = seasonsJson.optJSONObject(i) ?: continue
                val sn = s.optInt("season_number", -1)
                val ec = s.optInt("episode_count", 0)
                if (sn > 0 && ec > 0) seasonNums.add(sn)
            }
            seasonNums.sort()
            val episodes = mutableListOf<Episode>()
            for (sNum in seasonNums) {
                val sJson = tmdbGet("/tv/$id/season/$sNum")
                val epArray = sJson?.optJSONArray("episodes") ?: JSONArray()
                for (ei in 0 until epArray.length()) {
                    val ep = epArray.optJSONObject(ei) ?: continue
                    val epNum = ep.optInt("episode_number", ei + 1)
                    val epName = ep.optString("name", "Episode $epNum")
                    val still = posterUrl(ep.optString("still_path", ""))
                    val epPlot = ep.optString("overview", "")

                    val epData = JSONObject().apply {
                        put("type", "tv")
                        put("id", id)
                        put("s", sNum)
                        put("e", epNum)
                        put("title", title)
                        put("year", year ?: 0)
                        put("imdb_id", imdbId)
                    }.toString()

                    episodes.add(newEpisode(epData) {
                        this.name = epName
                        this.season = sNum
                        this.episode = epNum
                        this.posterUrl = still
                        this.description = epPlot
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.showStatus = status
                this.tags = genres
            }
        }
    }

    private fun parseDataUrl(url: String): JSONObject? {
        return runCatching {
            if (url.startsWith("{")) JSONObject(url)
            else {
                val m = Regex("/(movie|tv|series)/(\\d+)(?:/(\\d+)/(\\d+))?").find(url)
                if (m != null) {
                    val type = if (m.groupValues[1] == "series") "tv" else m.groupValues[1]
                    val id = m.groupValues[2].toInt()
                    JSONObject().apply {
                        put("type", type)
                        put("id", id)
                        if (m.groupValues[3].isNotBlank()) put("s", m.groupValues[3].toInt())
                        if (m.groupValues[4].isNotBlank()) put("e", m.groupValues[4].toInt())
                    }
                } else null
            }
        }.getOrNull()
    }

    // =========================================================================
    // Bộ lọc chống phát sai phim nghiêm ngặt (Strict Title Matching Filter)
    // =========================================================================

    private fun isTitleMatching(url: String, label: String, expectedTitle: String): Boolean {
        if (expectedTitle.isBlank()) return true

        val decodedUrl = runCatching { URLDecoder.decode(url, "UTF-8").lowercase() }.getOrDefault(url.lowercase())
        val combined = "$decodedUrl ${label.lowercase()}"

        val isDirectHlsStream = decodedUrl.contains("/hls/") || decodedUrl.contains("/stream2/") ||
                decodedUrl.contains("kkphimplayer") || decodedUrl.contains("phim1280") ||
                decodedUrl.contains("opstream") || decodedUrl.contains("nxstreamhub")

        val hasFileSignature = decodedUrl.contains(".mkv") || decodedUrl.contains(".mp4") ||
                decodedUrl.contains("/movies/") || decodedUrl.contains("zipdisk") ||
                decodedUrl.contains("pixeldrain") || combined.contains("bluray")

        if (hasFileSignature && !isDirectHlsStream) {
            val stopWords = setOf(
                "the", "a", "an", "and", "of", "in", "on", "at", "to", "for", "with",
                "part", "movie", "full", "hd", "bluray", "web", "dl", "1080p", "720p", "4k"
            )
            val titleWords = expectedTitle.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 2 && it !in stopWords }

            if (titleWords.isNotEmpty()) {
                val matchesWord = titleWords.any { word ->
                    Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(combined) || combined.contains(word)
                }
                if (!matchesWord) {
                    return false
                }
            }
        }
        return true
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
            val ua: String
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
            ua: String
        ): String? {
            val localPort = ensureStarted()
            if (localPort <= 0) return null
            if (entries.size > 200) entries.clear()
            val token = sequence.incrementAndGet().toString()
            entries[token] = Entry(zipUrl, isTv, season, episode, ua)
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
                mapOf("User-Agent" to entry.ua, "Referer" to "https://subdl.com/", "Accept" to "*/*")
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
            val text = app.get(url, headers = mapOf("User-Agent" to ua), timeout = 8L).text.trim()
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
                headers = mapOf("User-Agent" to ua, "Accept" to "application/json"),
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
                        zipUrl, ref.type == "tv", ref.season, ref.episode, ua
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
 
    /** Nạp phụ đề từ JSON data mà CineHD gắn vào movie/episode trong load(). */
    private suspend fun loadSubtitlesFromData(
        parsed: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
        d: Dbg
    ): Int {
        val type = if (parsed.optString("type").equals("tv", ignoreCase = true)) "tv" else "movie"
        var tmdbId = parsed.optInt("id", 0)
        val season = parsed.optInt("s", 0).takeIf { it > 0 }
        val episode = parsed.optInt("e", 0).takeIf { it > 0 }
        val title = parsed.optString("title", "").trim()
        val year = parsed.optInt("year", 0).takeIf { it > 0 }
        var imdbId = extractImdbId(parsed.optString("imdb_id", ""))

        // Lấy IMDb ID trực tiếp từ TMDB trước; không cần tìm lại phim nếu đã có TMDB ID.
        if (imdbId.isBlank() && tmdbId > 0) {
            val details = tmdbGet("/$type/$tmdbId?append_to_response=external_ids")
            imdbId = extractImdbId(
                details?.optString("imdb_id", "").orEmpty().ifBlank {
                    details?.optJSONObject("external_ids")?.optString("imdb_id", "").orEmpty()
                }
            )
        }

        // Fallback: tìm theo tên/năm khi payload cũ không có TMDB ID.
        if (tmdbId <= 0 && title.isNotBlank()) {
            val identity = resolveSubtitleIdentity(title, year, type)
            if (identity != null) {
                tmdbId = identity.tmdbId
                if (imdbId.isBlank()) imdbId = identity.imdbId
                d.log("TMDB match: id=$tmdbId IMDb=${imdbId.ifBlank { "không có" }}")
            }
        }

        if (imdbId.isBlank() && tmdbId <= 0) {
            d.log("Bỏ qua phụ đề: không có IMDb ID hoặc TMDB ID (title='$title')")
            return 0
        }

        val ref = SubtitleMediaRef(type, tmdbId, season, episode)
        return fetchSubtitleList(ref, imdbId, subtitleCallback, d)
    }

    // =========================================================================
    // Link Extraction: Ưu tiên nguồn chuẩn xác 100%, không phát sai phim
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseDataUrl(data) ?: return false
        val d = Dbg("loadLinks")
        try {
            loadSubtitlesFromData(parsed, subtitleCallback, d)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            d.err("Không tải được danh sách phụ đề", e)
        }

        val type = parsed.optString("type", "movie")
        val id = parsed.optInt("id", 0)
        val title = parsed.optString("title", "")
        if (id <= 0) return false
        val s = parsed.optInt("s", 1)
        val e = parsed.optInt("e", 1)

        var totalLinks = 0
        val seenUrls = HashSet<String>()

        // ---------------------------------------------------------------------
        // 1. NxSha API: CHỈ DÙNG CÁC NGUỒN CHUẨN XÁC CAO (LOẠI BỎ PHIM RÁC ẤN ĐỘ)
        // ---------------------------------------------------------------------
        runCatching {
            val nxReq = JSONObject().apply {
                put("tmdbId", id.toString())
                put("imdb_id", "")
                put("type", type)
                put("season", if (type == "tv") s else 0)
                put("episode", if (type == "tv") e else 0)
            }
            val encodedQuery = nxEncode(nxReq)
            val srvResp = app.get(
                "$nxBase/api/servers?q=$encodedQuery",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$nxBase/",
                    "Accept" to "application/json"
                ),
                timeout = 10L
            ).text

            val srvHash = JSONObject(srvResp).optString("_hash", "")
            if (srvHash.isNotBlank()) {
                val srvDec = nxDecode(srvHash)
                val servers = srvDec.optJSONArray("servers") ?: JSONArray()

                // THỨ TỰ ƯU TIÊN SERVER AN TOÀN:
                val safeScrapersPriority = listOf(
                    "ophim",        // #1: Vietsub Full HD chuẩn 100%
                    "movone",       // #2: Phim gốc 1080p chuẩn
                    "vixolity",     // #3: HLS tốc độ cao
                    "vidapi",       // #4: HLS ổn định
                    "streamflix",   // #5: StreamFlix
                    "rive-citadel", // #6: Citadel
                    "rive-primevids", // #7: PrimeVids
                    "castle",       // #8: CastVid
                    "purstream"     // #9: PureWave
                )

                val orderedServers = mutableListOf<Pair<String, String>>()
                for (targetScraper in safeScrapersPriority) {
                    for (si in 0 until servers.length()) {
                        val sObj = servers.optJSONObject(si) ?: continue
                        val scraper = sObj.optString("scraper", "")
                        val sName = sObj.optString("name", scraper)
                        if (scraper.equals(targetScraper, ignoreCase = true) || scraper.contains(targetScraper, ignoreCase = true)) {
                            orderedServers.add(sName to scraper)
                            break
                        }
                    }
                }

                for ((sName, scraper) in orderedServers) {
                    runCatching {
                        val srcReq = JSONObject().apply {
                            put("ex_lang", true)
                            put("provider", scraper)
                            put("tmdbId", id.toString())
                            put("imdb_id", "")
                            put("type", type)
                            put("season", if (type == "tv") s else 0)
                            put("episode", if (type == "tv") e else 0)
                        }
                        val srcQ = nxEncode(srcReq)
                        val srcResp = app.get(
                            "$nxBase/api/sources?q=$srcQ",
                            headers = mapOf(
                                "User-Agent" to ua,
                                "Referer" to "$nxBase/"
                            ),
                            timeout = 6L
                        ).text

                        val srcHash = JSONObject(srcResp).optString("_hash", "")
                        if (srcHash.isNotBlank()) {
                            val srcDec = nxDecode(srcHash)
                            val sources = srcDec.optJSONArray("sources") ?: JSONArray()
                            for (j in 0 until sources.length()) {
                                val item = sources.optJSONObject(j) ?: continue
                                val sUrl = item.optString("url", "")
                                if (!sUrl.startsWith("http") || blockedHostRegex.containsMatchIn(sUrl)) continue

                                val rawLabel = item.optString("label", item.optString("quality", "1080p"))

                                // KIỂM TRA CHỐNG PHÁT SAI PHIM:
                                if (!isTitleMatching(sUrl, rawLabel, title)) {
                                    continue
                                }

                                if (seenUrls.add(sUrl)) {
                                    val isHls = sUrl.contains(".m3u8") || sUrl.contains("/hls/")
                                    val displayName = when {
                                        scraper.contains("ophim") -> "CineHD • Vietsub ($sName)"
                                        scraper.contains("movone") -> "CineHD • Original 1080p ($rawLabel)"
                                        else -> "CineHD • $sName"
                                    }

                                    callback(
                                        newExtractorLink(
                                            name = "CineHD",
                                            source = displayName,
                                            url = sUrl,
                                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "$nxBase/"
                                            this.quality = if (rawLabel.contains("4k", true)) Qualities.P2160.value else Qualities.P1080.value
                                            this.headers = mapOf(
                                                "User-Agent" to ua,
                                                "Referer" to "$nxBase/"
                                            )
                                        }
                                    )
                                    totalLinks++
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure { ex ->
            android.util.Log.w("CineHD", "NxSha load error: ${ex.message}")
        }

        // ---------------------------------------------------------------------
        // 2. VidRock API (Giải mã AES-256-GCM 12-byte IV khớp 100% TMDB ID)
        // ---------------------------------------------------------------------
        runCatching {
            val vrPath = if (type == "tv") "tv/$id/$s/$e" else "movie/$id"
            val vrUrl = "$vidrockBase/api/$vrPath"
            val vrResp = app.get(
                vrUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$vidrockBase/",
                    "Accept" to "application/json"
                ),
                timeout = 8L
            ).text

            val vrJson = JSONObject(vrResp)
            val keys = vrJson.keys()
            while (keys.hasNext()) {
                val serverName = keys.next()
                val serverObj = vrJson.optJSONObject(serverName) ?: continue
                val token = serverObj.optString("url", "")
                if (token.isBlank()) continue

                val decryptedUrl = decryptVidRockGcm(token)
                if (decryptedUrl.startsWith("http") && !blockedHostRegex.containsMatchIn(decryptedUrl)) {
                    val lang = serverObj.optString("language", "")
                    val label = "CineHD Rock $serverName" + (if (lang.isNotBlank()) " - $lang" else "")

                    if (decryptedUrl.contains("streamrk.site/playlist")) {
                        extractStreamRkMp4s(decryptedUrl, label, callback)
                    } else if (seenUrls.add(decryptedUrl)) {
                        callback(
                            newExtractorLink(
                                name = "CineHD",
                                source = "$label (HLS)",
                                url = decryptedUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$vidrockBase/"
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "User-Agent" to ua,
                                    "Referer" to "$vidrockBase/"
                                )
                            }
                        )
                        totalLinks++
                    }
                }
            }
        }.onFailure { ex ->
            android.util.Log.w("CineHD", "VidRock error: ${ex.message}")
        }

        return totalLinks > 0
    }

    private suspend fun extractStreamRkMp4s(
        playlistUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val res = app.get(
                playlistUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$vidrockBase/"
                ),
                timeout = 10L
            ).text

            val arr = JSONArray(res)
            for (i in 0 until minOf(2, arr.length())) {
                val obj = arr.optJSONObject(i) ?: continue
                val u = obj.optString("url", "")
                val resQuality = obj.optInt("resolution", 1080)
                if (u.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            name = "CineHD",
                            source = "$label • MP4 ${resQuality}p",
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$vidrockBase/"
                            this.quality = if (resQuality >= 1080) Qualities.P1080.value else Qualities.P720.value
                            this.headers = mapOf(
                                "User-Agent" to ua,
                                "Referer" to "$vidrockBase/"
                            )
                        }
                    )
                }
            }
        }
    }

    // =========================================================================
    // VidRock Cryptography: AES-256-GCM (12-byte IV + Ciphertext + 16-byte Tag)
    // =========================================================================

    private fun decryptVidRockGcm(token: String): String {
        return runCatching {
            val raw = Base64.decode(token.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            if (raw.size < 28) return ""

            val iv = raw.copyOfRange(0, 12)
            val cipherAndTag = raw.copyOfRange(12, raw.size)

            val keyBytes = vidrockKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(cipherAndTag)
            String(decryptedBytes, Charsets.UTF_8)
        }.getOrDefault("")
    }

    // =========================================================================
    // NxSha Cryptography: OpenSSL EVP AES-256-CBC ("Salted__" prefix)
    // =========================================================================

    private fun evpBytesToKey(password: String, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        val d = mutableListOf<Byte>()
        var dI = ByteArray(0)

        while (d.size < keyLen + ivLen) {
            md5.reset()
            md5.update(dI)
            md5.update(passBytes)
            md5.update(salt)
            dI = md5.digest()
            for (b in dI) d.add(b)
        }

        val allBytes = d.toByteArray()
        val key = allBytes.copyOfRange(0, keyLen)
        val iv = allBytes.copyOfRange(keyLen, keyLen + ivLen)
        return Pair(key, iv)
    }

    private fun nxEncode(obj: JSONObject): String {
        return runCatching {
            val salt = ByteArray(8)
            SecureRandom().nextBytes(salt)

            val (key, iv) = evpBytesToKey(nxPass, salt, 32, 16)

           val payload = JSONObject(obj.toString()).apply {
                            put("_req_ts", System.currentTimeMillis())
                            put("_req_salt", java.util.UUID.randomUUID().toString().take(8))
                        }.toString()

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

            val prefix = "Salted__".toByteArray(Charsets.UTF_8)
            val combined = prefix + salt + encrypted
            Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrDefault("")
    }

    private fun nxDecode(b64url: String): JSONObject {
        return runCatching {
            val raw = Base64.decode(b64url.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            if (raw.size <= 16) return JSONObject()

            val prefix = String(raw.copyOfRange(0, 8), Charsets.UTF_8)
            if (prefix != "Salted__") return JSONObject()

            val salt = raw.copyOfRange(8, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)

            val (key, iv) = evpBytesToKey(nxPass, salt, 32, 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)

            JSONObject(jsonStr).apply {
                remove("_req_ts")
                remove("_req_salt")
            }
        }.getOrDefault(JSONObject())
    }
}
