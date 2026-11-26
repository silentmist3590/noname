package com.hicine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject
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

/**
 * HiCine v1 - port của plugin SkyStream "HiCine" (TJ-Plugins).
 *
 * API: https://api.hicine.sbs/api/<collection>?offset=&limit=
 * Link: worker  /api/links?vcloud=..  ->  {tokens:{fsl:{ts,sig},..}}  ->  <worker>/go?type=..&vcloud=..&ts=..&sig=..
 * (player tự theo 302 tới file thật). Mọi lỗi được copy log vào clipboard.
 */
class HiCineProvider : MainAPI() {
    override var mainUrl = "https://api.hicine.sbs"
    override var name = "HiCine"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        const val VERSION = 1
        const val UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val TMDB_KEY = "e716f19ab4d25edc5247239a8f3494f8"
        const val SUBDL_API_KEY = "xHeOWlCfS9o4EKmK7OwbeOBIhfbIHr2Y"
        const val PAGE = 18
        val SKIP_SERVERS = setOf("ten", "gofile")
        val SERVER_PREF = listOf("fsl", "server1", "fsl2", "pixel")
        val SERVER_LABEL = mapOf(
            "fsl" to "FSL", "fsl2" to "FSL v2", "pixel" to "PixelDrain",
            "server1" to "Server 1", "gofile" to "Gofile", "ten" to "10Gbps"
        )
    }

    private val mapper = jacksonObjectMapper()
    private var lastCopy = 0L

    // ---------------------------------------------------------------- debug
    private inner class Dbg(private val tag: String) {
        private val sb = StringBuilder()
        private val t0 = System.currentTimeMillis()
        fun log(msg: String) {
            val line = "[${System.currentTimeMillis() - t0}ms] $msg"
            synchronized(sb) { sb.append(line).append('\n') }
            Log.d("HiCine", line)
        }

        fun err(msg: String, e: Throwable?) {
            log("LỖI $msg: ${e?.javaClass?.simpleName}: ${e?.message}")
            Log.e("HiCine", msg, e)
        }

        fun copy(reason: String) {
            val now = System.currentTimeMillis()
            if (now - lastCopy < 3000) return
            lastCopy = now
            val text = synchronized(sb) {
                "HiCine v$VERSION | $tag | $reason\n" +
                    "Android ${android.os.Build.VERSION.RELEASE} | ${android.os.Build.MODEL}\n" + sb.toString()
            }
            try {
                val act = CommonActivity.activity ?: return
                act.runOnUiThread {
                    try {
                        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("HiCine log", text))
                        Toast.makeText(act, "HiCine: đã copy log lỗi ($reason)", Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- collections
    private class Col(val path: String, val type: TvType)

    private val cols = mapOf(
        "bolly_movies" to Col("bollywood_movies", TvType.Movie),
        "bolly_series" to Col("bollywood_series", TvType.TvSeries),
        "movies" to Col("hollywood_movies", TvType.Movie),
        "series" to Col("hollywood_series", TvType.TvSeries),
        "anime" to Col("anime", TvType.Anime)
    )
    private val pathToCt: Map<String, String> =
        cols.entries.associate { it.value.path to it.key } + mapOf("movies" to "movies", "series" to "series")

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "recent" to "Latest Uploads",
        "movies" to "Hollywood Movies",
        "series" to "Hollywood Series",
        "bolly_movies" to "Bollywood Movies",
        "bolly_series" to "Bollywood Series",
        "anime" to "Anime"
    )

    // ---------------------------------------------------------------- helpers
    private suspend fun getJson(url: String): JsonNode {
        val r = app.get(url, headers = mapOf("User-Agent" to UA, "Accept" to "application/json, text/plain, */*"))
        if (r.code !in 200..299) throw Exception("HTTP ${r.code} $url")
        return mapper.readTree(r.text)
    }

    private fun JsonNode.s(key: String): String {
        val n = get(key)
        return if (n == null || n.isNull) "" else n.asText("")
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private fun stripTags(h: String) = decode(h.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun yearOf(rec: JsonNode): Int? {
        Regex("\\((19\\d{2}|20\\d{2})\\)").find(rec.s("title"))?.let { return it.groupValues[1].toInt() }
        val y = rec.s("date").take(4).toIntOrNull()
        return if (y != null && y in 1901..2099) y else null
    }

    private fun cleanTitle(t: String) =
        decode(t.trim()).replace(Regex("\\s*\\((19\\d{2}|20\\d{2})\\)\\s*$"), "").trim()

    private fun fixImg(u: String): String {
        val s = u.trim()
        return when {
            s.isEmpty() -> ""
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "https://storage.hicine.sbs$s"
            s.startsWith("http://") -> "https://" + s.substring(7)
            else -> s
        }
    }

    private fun qualityText(t: String): String {
        val m = Regex("\\b(2160p|1440p|1080p|720p|480p|360p)\\b", RegexOption.IGNORE_CASE).find(t)
        if (m != null) return m.groupValues[1].lowercase()
        if (Regex("\\b4k\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "2160p"
        return ""
    }

    private fun qInt(q: String) = q.removeSuffix("p").toIntOrNull() ?: Qualities.Unknown.value

    private fun sizeText(t: String) =
        Regex("([\\d.]+)\\s*(GB|MB)", RegexOption.IGNORE_CASE).find(t)?.let { it.groupValues[1] + it.groupValues[2].uppercase() } ?: ""

    private val langs = listOf(
        "hindi", "punjabi", "urdu", "tamil", "telugu", "marathi", "kannada", "malayalam",
        "bengali", "gujarati", "english", "korean", "japanese", "chinese", "spanish", "arabic"
    )

    private fun langText(t: String): String {
        val s = decode(t.lowercase())
        return langs.filter { s.contains(it) }.joinToString("-")
    }

    private fun langLabel(l: String) = l.split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun langTier(l: String) = when {
        l.isEmpty() -> 3
        l.contains("hindi") -> 0
        l.contains("punjabi") || l.contains("urdu") -> 1
        l.contains("english") -> 4
        else -> 2
    }

    private fun detailUrl(ct: String, id: String) = "$mainUrl/api/${cols[ct]!!.path}/$id"

    private fun ctOf(rec: JsonNode, forced: String?): String {
        if (forced != null && cols.containsKey(forced)) return forced
        val c = rec.s("contentType")
        if (cols.containsKey(c)) return c
        val cats = rec.s("categories")
        return when {
            Regex("anime", RegexOption.IGNORE_CASE).containsMatchIn(cats) -> "anime"
            Regex("bollywood", RegexOption.IGNORE_CASE).containsMatchIn(cats) ->
                if (Regex("series", RegexOption.IGNORE_CASE).containsMatchIn(cats)) "bolly_series" else "bolly_movies"
            Regex("series|korean|k-drama", RegexOption.IGNORE_CASE).containsMatchIn(cats) -> "series"
            else -> "movies"
        }
    }

    private fun toCard(rec: JsonNode, forced: String? = null): SearchResponse? {
        val id = rec.s("record_id")
        if (id.isEmpty()) return null
        val ct = ctOf(rec, forced)
        val type = cols[ct]!!.type
        val title = cleanTitle(rec.s("title"))
        val poster = fixImg(rec.s("featured_image").ifEmpty { rec.s("poster") })
        val url = detailUrl(ct, id)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster.ifEmpty { null }
                this.year = yearOf(rec)
            }
        } else {
            newTvSeriesSearchResponse(title, url, type) {
                this.posterUrl = poster.ifEmpty { null }
                this.year = yearOf(rec)
            }
        }
    }

    private fun arr(d: JsonNode): List<JsonNode> {
        val a = if (d.isArray) d else d.get("data")
        return if (a != null && a.isArray) a.toList() else emptyList()
    }

    // ---------------------------------------------------------------- main page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val dbg = Dbg("getMainPage ${request.name}")
        try {
            val off = (page - 1) * PAGE
            val key = request.data
            val items: List<SearchResponse> = when (key) {
                "trending" -> arr(getJson("$mainUrl/api/trending-paginated?offset=$off&limit=$PAGE")).mapNotNull { toCard(it) }
                "recent" -> {
                    if (page > 1) emptyList()
                    else arr(getJson("$mainUrl/api/recent")).take(PAGE).mapNotNull { toCard(it) }
                }
                else -> arr(getJson("$mainUrl/api/${cols[key]!!.path}?offset=$off&limit=$PAGE")).mapNotNull { toCard(it, key) }
            }
            dbg.log("${request.name} page=$page -> ${items.size}")
            if (items.isEmpty() && page == 1) {
                dbg.copy("trang chủ trống")
            }
            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && key != "recent")
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("getMainPage", e)
            dbg.copy("getMainPage lỗi")
            return null
        }
    }

    // ---------------------------------------------------------------- search
    override suspend fun search(query: String): List<SearchResponse> {
        val dbg = Dbg("search $query")
        try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            fun norm(t: String) = t.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "")
            val words = q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }

            suspend fun fetchQ(t: String): List<JsonNode> = try {
                arr(getJson("$mainUrl/api/search/" + URLEncoder.encode(t, "UTF-8").replace("+", "%20")))
            } catch (e: Throwable) {
                if (e is java.util.concurrent.CancellationException) throw e
                dbg.log("search '$t' lỗi: ${e.message}")
                emptyList()
            }

            val items = fetchQ(q).toMutableList()
            if (items.size < 3 && words.size > 1) {
                val variants = mutableListOf(words.joinToString("-"), words.joinToString(" "))
                words.sortedByDescending { it.length }.take(2).forEach { if (it.length > 2) variants.add(it) }
                for (v in variants.distinct().filter { it != q }) {
                    for (rec in fetchQ(v)) {
                        val t = norm(rec.s("title").ifEmpty { rec.s("name") })
                        if (words.all { t.contains(norm(it)) }) items.add(rec)
                    }
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<SearchResponse>()
            for (rec in items) {
                val c = toCard(rec) ?: continue
                val k = c.name.lowercase() + "|" + (rec.s("record_id"))
                val k2 = c.name.lowercase() + "|" + (yearOf(rec) ?: 0)
                if (!seen.add(k2)) continue
                out.add(c)
            }
            dbg.log("search '$q' -> ${out.size}")
            return out
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("search", e)
            dbg.copy("search lỗi")
            return emptyList()
        }
    }

    // ---------------------------------------------------------------- parse links
    private class Variant(val url: String, val quality: String)
    private class EpInfo(val num: Int, val variants: List<Variant>)

    private fun parseSeasonEpisodes(text: String): List<EpInfo> {
        val out = ArrayList<EpInfo>()
        for (line in decode(text).split(Regex("\\r?\\n"))) {
            val m = Regex("episode\\s*(\\d+)\\s*[:\\-]?\\s*(.*)", RegexOption.IGNORE_CASE).find(line) ?: continue
            val num = m.groupValues[1].toInt()
            val rest = m.groupValues[2]
            val variants = ArrayList<Variant>()
            val re = Regex("(https?://[^\\s,]+?)\\s*,\\s*,?\\s*([^:]*?)(?=\\s*:|$)")
            for (h in re.findAll(rest)) {
                val label = h.groupValues[2].trim()
                variants.add(Variant(h.groupValues[1], qualityText(label).ifEmpty { label.ifEmpty { "auto" } }))
            }
            if (variants.isEmpty()) {
                Regex("https?://[^\\s,]+").findAll(rest).forEach { variants.add(Variant(it.value, "auto")) }
            }
            if (variants.isNotEmpty()) out.add(EpInfo(num, variants))
        }
        return out
    }

    private class Target(val workerUrl: String, val quality: String, val size: String, val lang: String)

    private fun parseMovieLinks(links: String): List<Target> {
        val out = ArrayList<Target>()
        for (line in links.split(Regex("\\r?\\n"))) {
            val m = Regex("https?://[^\\s,]+").find(line) ?: continue
            out.add(Target(m.value, qualityText(line).ifEmpty { "auto" }, sizeText(line), langText(line)))
        }
        return out
    }


    // ---------------------------------------------------------------- subtitles: OpenSubtitles REST + Stremio V3 + SubDL

    private data class SubtitleMediaRef(
        val type: String,
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
        if (listOf(".srt", ".vtt", ".ass", ".ssa").any { lower.contains(it) }) return url
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
            Regex(
                """\b(hindi|english|dubbed|dual audio|multi audio|uncut|full movie|watch online|movie|web series|series|season|complete|anime|cartoon|k drama)\b""",
                RegexOption.IGNORE_CASE
            ),
            " "
        )
        .replace(
            Regex("""\b(s\d{1,2}|e\d{1,3}|480p|720p|1080p|2160p|4k|hdrip|webrip|web-dl|bluray)\b""", RegexOption.IGNORE_CASE),
            " "
        )
        .replace(Regex("""[^a-zA-Z0-9]+"""), " ")
        .trim()
        .lowercase()

    private fun subtitleTitleScore(a: String, b: String): Double {
        val left = normalizeSubtitleTitle(a)
        val right = normalizeSubtitleTitle(b)
        if (left.isBlank() || right.isBlank()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) {
            return minOf(left.length, right.length).toDouble() /
                maxOf(left.length, right.length).toDouble()
        }
        val leftWords = left.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        val rightWords = right.split(Regex("\\s+")).filter { it.length > 1 }.toSet()
        if (leftWords.isEmpty() || rightWords.isEmpty()) return 0.0
        return leftWords.intersect(rightWords).size.toDouble() /
            leftWords.union(rightWords).size.toDouble()
    }

    /**
     * TMDB lookup dùng riêng JsonNode và TMDB key hiện có của HiCine.
     * Không khai báo/ghi đè tmdbGet() đang dùng cho catalog.
     */
    private suspend fun getTmdbSubtitleJson(path: String): JsonNode? {
        val sep = if (path.contains("?")) "&" else "?"
        val requestPath = "$path${sep}api_key=$TMDB_KEY"
        val hosts = listOf("https://api.tmdb.org/3", "https://api.themoviedb.org/3")
        for (host in hosts) {
            val text = runCatching {
                app.get(
                    "$host$requestPath",
                    headers = mapOf(
                        "User-Agent" to UA,
                        "Accept" to "application/json"
                    ),
                    timeout = 10L
                ).text
            }.getOrNull() ?: continue
            if (!text.trimStart().startsWith("{")) continue
            val node = runCatching { mapper.readTree(text) }.getOrNull() ?: continue
            if (node.has("status_code")) continue
            return node
        }
        return null
    }

    /** Tìm TMDB/IMDb ID từ tiêu đề và năm của bản ghi HiCine. */
    private suspend fun resolveSubtitleIdentity(
        title: String,
        year: Int?,
        type: String
    ): SubtitleIdentity? {
        if (title.isBlank()) return null
        val kind = if (type == "tv") "tv" else "movie"
        val query = URLEncoder.encode(title.trim(), "UTF-8")
        val yearParam = year?.let {
            if (kind == "tv") "&first_air_date_year=$it" else "&year=$it"
        }.orEmpty()

        val search = getTmdbSubtitleJson(
            "/search/$kind?query=$query&include_adult=false&page=1$yearParam"
        ) ?: return null
        val results = search.get("results")?.takeIf { it.isArray } ?: return null

        data class Candidate(val id: Int, val score: Double, val year: Int?)
        val matches = mutableListOf<Candidate>()
        for (item in results) {
            val id = item.get("id")?.asInt() ?: continue
            if (id <= 0) continue
            val candidateTitle = item.get(if (kind == "tv") "name" else "title")?.asText().orEmpty()
            val date = item.get(if (kind == "tv") "first_air_date" else "release_date")?.asText().orEmpty()
            val candidateYear = date.take(4).toIntOrNull()
            val score = subtitleTitleScore(title, candidateTitle)
            if (score < 0.62) continue
            if (year != null && candidateYear != null && kotlin.math.abs(year - candidateYear) > 1) continue
            matches.add(Candidate(id, score, candidateYear))
        }

        val sorted = matches.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy {
                if (year == null || it.year == null) 0 else kotlin.math.abs(year - it.year)
            }
        )

        for (candidate in sorted.take(4)) {
            val details = getTmdbSubtitleJson(
                "/$kind/${candidate.id}?append_to_response=external_ids"
            )
            val imdbId = details?.get("imdb_id")?.asText().orEmpty().ifBlank {
                details?.get("external_ids")?.get("imdb_id")?.asText().orEmpty()
            }
            return SubtitleIdentity(candidate.id, imdbId.takeIf { it.startsWith("tt") }.orEmpty())
        }
        return null
    }

    // Localhost proxy: chỉ tải và giải nén ZIP SubDL khi người xem chọn phụ đề.
    private object HiCineSubdlProxy {
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
                val rawPath = parts.getOrNull(1).orEmpty()
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
                mapOf(
                    "User-Agent" to entry.userAgent,
                    "Referer" to "https://subdl.com/",
                    "Accept" to "*/*"
                )
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
                        if (!item.isDirectory && !lower.contains("__macosx/") &&
                            validExts.any { lower.endsWith(it) }
                        ) {
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
                } else {
                    "application/x-subrip; charset=utf-8"
                }
                return mime to best.second
            }

            val head = zipBytes.take(150).toByteArray().toString(Charsets.UTF_8).trimStart()
            if (head.startsWith("<!doctype", true) || head.startsWith("<html", true)) return null
            val mime = if (head.contains("WEBVTT", true)) {
                "text/vtt; charset=utf-8"
            } else {
                "application/x-subrip; charset=utf-8"
            }
            return mime to zipBytes
        }

        private fun matchesEpisodeHint(fileName: String, season: Int?, episode: Int?): Boolean {
            if (season == null || episode == null) return true
            val lower = fileName.lowercase()
            val season2 = season.toString().padStart(2, '0')
            val episode2 = episode.toString().padStart(2, '0')
            return listOf(
                "s${season2}e${episode2}", "s${season}e${episode2}", "s${season2}e${episode}",
                "${season}x${episode2}", "${season2}x${episode2}",
                "episode.${episode2}", "episode_${episode2}", "ep${episode2}"
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
                            current = if (location.startsWith("http", true)) {
                                location
                            } else {
                                URL(URL(current), location).toString()
                            }
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

        return listOf("vie" to "Vietnamese", "eng" to "English").amap { (languageCode, fallback) ->
            val output = mutableListOf<SubtitleCandidate>()
            runCatching {
                val url = if (ref.type == "tv" && ref.season != null && ref.episode != null) {
                    "https://rest.opensubtitles.org/search/episode-${ref.episode}/imdbid-$cleanId/season-${ref.season}/sublanguageid-$languageCode"
                } else {
                    "https://rest.opensubtitles.org/search/imdbid-$cleanId/sublanguageid-$languageCode"
                }
                val text = app.get(url, headers = headers, timeout = 8L).text.trim()
                if (!text.startsWith("[")) return@runCatching
                val array = JSONArray(text)
                val items = (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                    .sortedBy { item ->
                        if (item.optString("SubFormat", "").lowercase() in listOf("srt", "vtt")) 0 else 1
                    }

                for (item in items.take(6)) {
                    val raw = item.optString("SubDownloadLink", "").trim()
                    if (!raw.startsWith("http")) continue
                    val direct = raw
                        .replace("/filead/", "/subencoding-utf8/filead/")
                        .replace("/file/", "/subencoding-utf8/file/")
                        .removeSuffix(".gz")
                    output.add(
                        SubtitleCandidate(
                            "OpenSubtitles",
                            item.optString("LanguageName", fallback).trim(),
                            appendSubExtFragment(direct, item.optString("SubFormat", "srt")),
                            item.optString("MovieReleaseName", "").trim()
                        )
                    )
                }
            }
            output
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
            val text = app.get(url, headers = mapOf("User-Agent" to UA), timeout = 8L).text.trim()
            if (!text.startsWith("{")) return@runCatching
            val array = JSONObject(text).optJSONArray("subtitles") ?: return@runCatching
            val vietnamese = mutableListOf<SubtitleCandidate>()
            val english = mutableListOf<SubtitleCandidate>()

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val subUrl = item.optString("url", "").trim()
                if (!subUrl.startsWith("http")) continue
                val language = item.optString("lang", "").lowercase()
                val release = item.optString("releaseGroup", "").ifBlank {
                    item.optString("movieReleaseName", "")
                }.trim()
                val candidate = SubtitleCandidate(
                    "OpenSubtitles-V3",
                    if (language == "vie" || language == "vi") "Vietnamese" else "English",
                    appendSubExtFragment(subUrl),
                    release
                )
                when (language) {
                    "vie", "vi" -> if (vietnamese.size < 5) vietnamese.add(candidate)
                    "eng", "en" -> if (english.size < 5) english.add(candidate)
                }
            }
            output.addAll(vietnamese)
            output.addAll(english)
        }
        return output
    }

    private suspend fun querySubdl(
        ref: SubtitleMediaRef,
        imdbId: String,
        apiKey: String,
        dbg: Dbg
    ): List<SubtitleCandidate> {
        if (apiKey.isBlank() || (imdbId.isBlank() && ref.tmdbId <= 0)) return emptyList()
        val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
        val idParam = if (imdbId.startsWith("tt")) {
            "imdb_id=${URLEncoder.encode(imdbId, "UTF-8")}"
        } else {
            "tmdb_id=${ref.tmdbId}"
        }
        val queryUrl = if (ref.type == "tv") {
            "https://api.subdl.com/api/v1/subtitles?api_key=$encodedKey&$idParam&type=tv&season_number=${ref.season ?: 1}&episode_number=${ref.episode ?: 1}&languages=VI,EN"
        } else {
            "https://api.subdl.com/api/v1/subtitles?api_key=$encodedKey&$idParam&type=movie&languages=VI,EN"
        }

        val output = mutableListOf<SubtitleCandidate>()
        runCatching {
            val response = app.get(
                queryUrl,
                headers = mapOf("User-Agent" to UA, "Accept" to "application/json"),
                timeout = 10L
            )
            if (response.code !in 200..299) {
                dbg.log("SubDL HTTP ${response.code}: ${response.text.take(120)}")
                return@runCatching
            }
            val json = JSONObject(response.text)
            if (!json.optBoolean("status", false)) {
                dbg.log("SubDL status=false: ${json.optString("error")}")
                return@runCatching
            }
            val subtitles = json.optJSONArray("subtitles") ?: return@runCatching
            val viItems = mutableListOf<JSONObject>()
            val enItems = mutableListOf<JSONObject>()
            for (i in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(i) ?: continue
                val language = item.optString("language", "").lowercase()
                if (language.contains("vietnam") || language == "vi" || language == "vie") {
                    viItems.add(item)
                } else if (language.contains("english") || language == "en" || language == "eng") {
                    enItems.add(item)
                }
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
                    val localUrl = HiCineSubdlProxy.register(
                        zipUrl, ref.type == "tv", ref.season, ref.episode, UA
                    ) ?: continue
                    val release = item.optString("release_name", "").ifBlank {
                        item.optString("name", "")
                    }.trim()
                    output.add(SubtitleCandidate("SubDL", language, localUrl, release))
                }
            }
        }.onFailure { dbg.err("querySubdl", it) }
        return output
    }

    private suspend fun fetchSubtitleList(
        ref: SubtitleMediaRef,
        imdbId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        dbg: Dbg
    ): Int {
        val results = listOf(0, 1, 2).amap { index ->
            when (index) {
                0 -> queryOpenSubtitlesRest(ref, imdbId)
                1 -> queryOpenSubtitlesStremio(ref, imdbId)
                else -> querySubdl(ref, imdbId, SUBDL_API_KEY, dbg)
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
        dbg.log("Đã nạp $count phụ đề OpenSubtitles/SubDL (IMDb=${imdbId.ifBlank { "không có" }}, TMDB=${ref.tmdbId})")
        return count
    }

    /** Tìm và nạp phụ đề theo metadata chi tiết của HiCine. */
    private suspend fun loadSubtitlesForMedia(
        details: JsonNode,
        season: Int,
        episode: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        dbg: Dbg
    ): Int {
        val title = cleanTitle(details.s("title"))
        if (title.isBlank()) {
            dbg.log("Bỏ qua phụ đề: bản ghi HiCine không có tiêu đề")
            return 0
        }
        val isTv = season > 0 && episode > 0
        val type = if (isTv) "tv" else "movie"
        val year = yearOf(details)
        var tmdbId = 0
        var imdbId = extractImdbId(details.toString())

        if (imdbId.isBlank()) {
            val identity = resolveSubtitleIdentity(title, year, type)
            if (identity != null) {
                tmdbId = identity.tmdbId
                imdbId = identity.imdbId
                dbg.log("TMDB match: '$title' -> $tmdbId IMDb=${imdbId.ifBlank { "không có" }}")
            } else {
                dbg.log("Không tìm được TMDB/IMDb ID cho '$title'${year?.let { " ($it)" } ?: ""}")
            }
        }

        if (tmdbId <= 0 && imdbId.isBlank()) return 0
        val ref = SubtitleMediaRef(
            type = type,
            tmdbId = tmdbId,
            season = season.takeIf { isTv },
            episode = episode.takeIf { isTv }
        )
        return fetchSubtitleList(ref, imdbId, subtitleCallback, dbg)
    }

    // ---------------------------------------------------------------- load
    private fun parseUrl(url: String): Triple<String, String, String>? {
        val m = Regex("/api/([a-z_\\-]+)/(\\d+)").find(url) ?: return null
        val ct = pathToCt[m.groupValues[1].lowercase()] ?: return null
        return Triple(ct, m.groupValues[2], detailUrl(ct, m.groupValues[2]))
    }

    private suspend fun tmdb(title: String, year: Int?, tv: Boolean, poster: String): Triple<String, Double?, String?>? {
        return try {
            val kind = if (tv) "tv" else "movie"
            val q = title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
            if (q.isEmpty()) return null
            val d = getJson("https://api.themoviedb.org/3/search/$kind?api_key=$TMDB_KEY&query=" +
                URLEncoder.encode(q, "UTF-8"))
            val list = arr(d.get("results") ?: return null)
            val key = Regex("/([A-Za-z0-9]{20,})\\.(?:webp|jpe?g|png)").find(poster)?.groupValues?.get(1)
            var hit = key?.let { k -> list.firstOrNull { it.s("poster_path").contains(k) } }
            if (hit == null && year != null) {
                hit = list.firstOrNull { (it.s("release_date").ifEmpty { it.s("first_air_date") }).take(4) == year.toString() }
            }
            if (hit == null) return null
            val bd = hit.s("backdrop_path")
            Triple(hit.s("overview"), hit.get("vote_average")?.asDouble(),
                if (bd.isNotEmpty()) "https://image.tmdb.org/t/p/w1280$bd" else null)
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val dbg = Dbg("load")
        try {
            val p = parseUrl(url) ?: throw Exception("URL HiCine không nhận ra: $url")
            val (ct, id, durl) = p
            val det = getJson(durl)
            if (det.has("error")) throw Exception("API: ${det.s("error")}")
            val type = cols[ct]!!.type
            val title = cleanTitle(det.s("title"))
            val poster = fixImg(det.s("featured_image").ifEmpty { det.s("poster") })
            val year = yearOf(det)
            var plot = stripTags(det.s("content").ifEmpty { det.s("excerpt") })
            val tags = det.s("categories").split(",").map { it.trim() }
                .filter { it.isNotEmpty() && !Regex("^(\\d{3,4}p|\\d{4}|featured)$", RegexOption.IGNORE_CASE).matches(it) }
            var score: Double? = null
            var backdrop: String? = null
            if (plot.isEmpty()) {
                val tm = tmdb(title, year, type != TvType.Movie, poster)
                if (tm != null) {
                    plot = tm.first
                    score = tm.second
                    backdrop = tm.third
                }
            }

            // ---- tập phim
            val episodes = ArrayList<Episode>()
            for (s in 1..20) {
                val txt = det.s("season_$s")
                if (txt.isEmpty()) continue
                for (e in parseSeasonEpisodes(txt)) {
                    episodes.add(newEpisode("$durl|||$s|||${e.num}") {
                        this.name = "Episode ${e.num}"
                        this.season = s
                        this.episode = e.num
                        this.posterUrl = poster.ifEmpty { null }
                    })
                }
            }
            dbg.log("load $ct/$id title=$title eps=${episodes.size} movieLinks=${parseMovieLinks(det.s("links")).size}")

            val isSeries = episodes.isNotEmpty() && type != TvType.Movie
            return if (isSeries || (episodes.isNotEmpty() && parseMovieLinks(det.s("links")).isEmpty())) {
                newTvSeriesLoadResponse(title, durl, if (type == TvType.Movie) TvType.TvSeries else type, episodes) {
                    this.posterUrl = poster.ifEmpty { null }
                    this.backgroundPosterUrl = backdrop ?: poster.ifEmpty { null }
                    this.year = year
                    this.plot = plot.ifEmpty { null }
                    this.tags = tags
                }
            } else {
                if (parseMovieLinks(det.s("links")).isEmpty()) {
                    plot = (if (plot.isEmpty()) "" else "$plot\n\n") + "⚠ Chưa có link cho phim này trên HiCine."
                }
                newMovieLoadResponse(title, durl, TvType.Movie, "$durl|||0|||0") {
                    this.posterUrl = poster.ifEmpty { null }
                    this.backgroundPosterUrl = backdrop ?: poster.ifEmpty { null }
                    this.year = year
                    this.plot = plot.ifEmpty { null }
                    this.tags = tags
                }
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("load", e)
            dbg.copy("load lỗi")
            return null
        }
    }

    // ---------------------------------------------------------------- links
    private suspend fun goPixel(goUrl: String, dbg: Dbg): String? {
        return try {
            val r = app.get(goUrl, headers = mapOf("User-Agent" to UA, "Range" to "bytes=0-0"), timeout = 10L)
            val fin = r.okhttpResponse.request.url.toString()
            val m = Regex("^https?://(pixeldrain\\.[a-z]+)/(?:u|api/file)/([A-Za-z0-9]+)").find(fin)
            if (m != null) "https://${m.groupValues[1]}/api/file/${m.groupValues[2]}" else {
                dbg.log("pixel final=${fin.take(80)}")
                null
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.log("pixel lỗi ${e.message}")
            null
        }
    }

    private suspend fun resolve(
        t: Target, dbg: Dbg, allowFallback: Boolean, callback: (ExtractorLink) -> Unit
    ): Int {
        val qi = t.workerUrl.indexOf('?')
        if (qi < 0) return 0
        val base = t.workerUrl.substring(0, qi).trimEnd('/')
        val vcloud = Regex("[?&]vcloud=([^&]+)").find(t.workerUrl)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return 0
        val d = getJson("$base/api/links?vcloud=" + URLEncoder.encode(vcloud, "UTF-8"))
        val tokens = d.get("tokens")
        if (tokens == null || !tokens.isObject || tokens.size() == 0) {
            dbg.log("link chết: ${vcloud.takeLast(14)}")
            return 0
        }
        var keys = tokens.fieldNames().asSequence().toList()
        if (!allowFallback) keys = keys.filter { it !in SKIP_SERVERS }
        keys = keys.sortedBy { val i = SERVER_PREF.indexOf(it); if (i < 0) 99 else i }
        var n = 0
        val size = t.size.ifEmpty { d.s("size") }
        for (k in keys) {
            val tk = tokens.get(k)
            val ts = tk.s("ts")
            val sig = tk.s("sig")
            if (ts.isEmpty() || sig.isEmpty()) continue
            var url = "$base/go?type=${URLEncoder.encode(k, "UTF-8")}&vcloud=${URLEncoder.encode(vcloud, "UTF-8")}" +
                "&ts=${URLEncoder.encode(ts, "UTF-8")}&sig=${URLEncoder.encode(sig, "UTF-8")}"
            if (k == "pixel") {
                url = goPixel(url, dbg) ?: continue
            }
            var label = SERVER_LABEL[k] ?: k.uppercase()
            if (t.lang.isNotEmpty()) label += " • ${langLabel(t.lang)}"
            if (t.quality != "auto" && t.quality.isNotEmpty()) label += " • ${t.quality}"
            if (size.isNotEmpty()) label += " • $size"
            val q = if (t.quality == "auto") Qualities.Unknown.value else qInt(t.quality)
            callback(
                newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
                    this.quality = q
                    this.headers = mapOf("User-Agent" to UA)
                }
            )
            n++
        }
        return n
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dbg = Dbg("loadLinks")
        try {
            val parts = data.split("|||")
            val durl = parts[0]
            val season = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val ep = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val det = getJson(durl)
            // Phụ đề độc lập với link phát; lỗi API phụ đề không được làm hỏng loadLinks.
            runCatching {
                loadSubtitlesForMedia(det, season, ep, subtitleCallback, dbg)
            }.onFailure { error ->
                if (error is java.util.concurrent.CancellationException) throw error
                dbg.err("loadSubtitlesForMedia", error)
            }
            val targets = ArrayList<Target>()
            if (season > 0 && ep > 0) {
                val txt = det.s("season_$season")
                val seasonLang = langText(txt.lineSequence().firstOrNull() ?: "")
                val e = parseSeasonEpisodes(txt).firstOrNull { it.num == ep }
                e?.variants?.forEach { targets.add(Target(it.url, it.quality, "", seasonLang)) }
            } else {
                targets.addAll(parseMovieLinks(det.s("links")))
            }
            dbg.log("targets=${targets.size} data=${data.takeLast(40)}")
            if (targets.isEmpty()) {
                dbg.copy("không có link nào được tải lên")
                return false
            }
            val sorted = targets.sortedWith(
                compareBy<Target> { langTier(it.lang) }.thenByDescending { qInt(it.quality) }
            )
            var total = 0
            for (t in sorted) {
                var got = 0
                for (attempt in 1..2) {
                    try {
                        got = resolve(t, dbg, false, callback)
                        if (got > 0) break
                    } catch (e: Throwable) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        dbg.err("resolve ${t.quality}", e)
                    }
                }
                if (got == 0) {
                    try {
                        got = resolve(t, dbg, true, callback)
                    } catch (e: Throwable) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        dbg.err("resolve-fallback ${t.quality}", e)
                    }
                }
                dbg.log("${t.quality} ${t.lang} -> $got link")
                total += got
            }
            if (total == 0) {
                dbg.copy("không lấy được link phát")
                return false
            }
            return true
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("loadLinks", e)
            dbg.copy("loadLinks lỗi")
            return false
        }
    }
}
