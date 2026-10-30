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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// ============================================================================
// LOG BUFFER + AUTO-COPY CLIPBOARD
// ============================================================================
object CJLog {
    private const val MAX_LINES = 300
    private const val RATE_LIMIT_MS = 2000L

    private val buffer = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var lastCopyAt = 0L
    @Volatile private var ctx: Context? = null

    fun attachContext(c: Context) { ctx = c.applicationContext }

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
        val c = ctx ?: run {
            println("[CineJoy][clipboard][ERR] no context attached")
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - lastCopyAt < RATE_LIMIT_MS) {
            println("[CineJoy][clipboard] rate limited")
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

            Toast.makeText(
                c,
                "⚠ CineJoy log copied (${buffer.size} lines) — paste into chat",
                Toast.LENGTH_LONG
            ).show()

            println("[CineJoy][clipboard] copied ${buffer.size} lines (reason: $reason)")
        } catch (e: Throwable) {
            println("[CineJoy][clipboard][ERR] failed: ${e.message}")
        }
    }
}

// ============================================================================
// PLUGIN ENTRY
// ============================================================================
@CloudstreamPlugin
class CineJoyPlugin : Plugin() {
    override fun load(context: Context) {
        CJLog.attachContext(context)
        registerMainAPI(CineJoyProvider())
    }
}

// ============================================================================
// PROVIDER
// ============================================================================
class CineJoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "CineJoy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()

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

    private val corsProxies: List<(String) -> String> = listOf(
        { url -> "https://cinejoytv.fun/relay?url=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://cors.eu.org/$url" },
        { url -> "https://api.codetabs.com/v1/proxy/?quest=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://api.allorigins.win/raw?url=${URLEncoder.encode(url, "UTF-8")}" },
    )

    private val vidloveBase = "https://api.vidlove.cc"
    private val vidloveReferer = "https://player.vidlove.cc/"
    private val vidrockBase = "https://vidrock.net"
    private val vidrockKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

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

    // ========================================================================
    // GENERIC CORS FETCH (used by TMDB, VidLove, VidRock)
    // ========================================================================
    private suspend fun corsFetch(url: String, referer: String?, tag: String): String? {
        // Strategy 1: direct
        try {
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Accept" to "application/json, text/plain, */*",
            )
            if (referer != null) headers["Referer"] = referer
            val text = app.get(url, headers = headers, referer = referer).text
            if (text.isNotBlank()) {
                CJLog.log(tag, "direct OK (${text.length}b)")
                return text
            }
        } catch (e: Exception) {
            CJLog.log(tag, "direct fail: ${e.message}")
        }

        // Strategy 2: CORS proxies
        for ((i, buildProxy) in corsProxies.withIndex()) {
            try {
                val text = app.get(
                    buildProxy(url),
                    headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json, text/plain, */*"),
                ).text
                if (text.isNotBlank()) {
                    CJLog.log(tag, "proxy#$i OK (${text.length}b)")
                    return text
                }
            } catch (e: Exception) {
                CJLog.log(tag, "proxy#$i fail: ${e.message}")
            }
        }
        CJLog.logErr(tag, "ALL SOURCES FAILED for ${url.take(100)}")
        return null
    }

    // ========================================================================
    // TMDB
    // ========================================================================
    private suspend fun tmdbGet(path: String, useFallback: Boolean = false): JsonNode? {
        val key = if (useFallback) tmdbFallbackKey else tmdbKey
        val normalizedPath = when {
            path.startsWith("/3/") || path.startsWith("/3?") -> path
            else -> "/3$path"
        }
        val sep = if (normalizedPath.contains("?")) "&" else "?"
        val fullPath = "$normalizedPath${sep}api_key=$key"

        // Direct domains
        for (base in tmdbDirectDomains) {
            try {
                val text = app.get(
                    "$base$fullPath",
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent),
                    referer = null
                ).text
                if (text.isBlank() || !text.trimStart().startsWith("{")) continue
                val j = mapper.readTree(text)
                val status = j.get("status_code")?.asInt()
                if (status != null && status != 0) {
                    if (!useFallback && (status == 7 || status == 3)) return tmdbGet(path, true)
                    continue
                }
                return j
            } catch (_: Exception) { }
        }

        // CORS
        val targetUrl = "https://api.themoviedb.org$fullPath"
        for (buildProxy in corsProxies) {
            try {
                val text = app.get(
                    buildProxy(targetUrl),
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent),
                    referer = null
                ).text
                if (text.isBlank() || !text.trimStart().startsWith("{")) continue
                val j = mapper.readTree(text)
                val status = j.get("status_code")?.asInt()
                if (status != null && status != 0) continue
                return j
            } catch (_: Exception) { }
        }

        if (!useFallback) return tmdbGet(path, true)
        CJLog.logErr("tmdb", "ALL FAILED for $path")
        return null
    }

    private fun JsonNode.s(k: String): String? =
        get(k)?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.takeIf { it.isNotBlank() }

    private fun proxyImg(originalUrl: String): String =
        "$imgProxy${URLEncoder.encode(originalUrl, "UTF-8")}"

    private fun poster(path: String?, size: String = "w500"): String? =
        if (path != null) proxyImg("https://image.tmdb.org/t/p/$size$path") else null

    private fun yearOf(date: String?): Int? =
        if (date == null || date.length < 4) null else date.take(4).toIntOrNull()

    private fun mediaTypeOf(r: JsonNode): String {
        val mt = r.s("media_type")
        if (mt == "movie" || mt == "tv") return mt
        return if (r.get("first_air_date") != null || r.get("name") != null) "tv" else "movie"
    }

    private fun scoreFrom10(r: JsonNode): Score? {
        val v = r.get("vote_average")?.asDouble() ?: return null
        if (v <= 0) return null
        return Score.from10(v)
    }

    private fun tmdbToSearch(r: JsonNode): SearchResponse? {
        val id = r.get("id")?.asInt() ?: return null
        val type = mediaTypeOf(r)
        val title = r.s("title") ?: r.s("name") ?: return null
        val url = """{"type":"$type","id":$id}"""
        val posterUrl = poster(r.s("poster_path"))
        val year = yearOf(r.s("release_date") ?: r.s("first_air_date"))
        val score = scoreFrom10(r)

        return if (type == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        CJLog.clear()
        val items = mutableListOf<SearchResponse>()
        val json = tmdbGet("${request.data}?page=$page")
        val results = json?.get("results")
        if (results != null && results.isArray) {
            for (r in results) tmdbToSearch(r)?.let { items.add(it) }
        }
        CJLog.log("main", "${request.name}: ${items.size} items")

        if (items.isEmpty()) {
            CJLog.logErr("main", "EMPTY RESULT for ${request.name}")
            CJLog.copyToClipboard("getMainPage empty for ${request.name}")
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Manual trigger
        if (query.trim().equals("!log", ignoreCase = true)) {
            CJLog.copyToClipboard("manual trigger via search", force = true)
            return emptyList()
        }

        CJLog.clear()
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val json = tmdbGet("/search/multi?query=$q&page=1&include_adult=false")
        val results = json?.get("results") ?: return emptyList()
        val out = mutableListOf<SearchResponse>()
        for (r in results) {
            val mt = r.s("media_type")
            if (mt != "movie" && mt != "tv") continue
            tmdbToSearch(r)?.let { out.add(it) }
        }
        CJLog.log("search", "query=$query: ${out.size} items")
        return out
    }

    // ========================================================================
    // LOAD
    // ========================================================================
    private data class ParsedUrl(val type: String, val id: Int, val s: Int, val e: Int)

    private fun parseUrl(url: String): ParsedUrl? {
        return try {
            val json = mapper.readTree(url)
            val type = json.s("type") ?: return null
            val id = json.get("id")?.asInt() ?: return null
            val s = json.get("s")?.asInt() ?: 1
            val e = json.get("e")?.asInt() ?: 1
            ParsedUrl(type, id, s, e)
        } catch (e: Exception) { null }
    }

    private fun fixEpisode(e: Int): Int =
        if (e >= 800) (e % 100) + 1 + ((e / 100) - 8) * 2 else e

    override suspend fun load(url: String): LoadResponse? {
        CJLog.clear()
        val p = parseUrl(url) ?: return null
        CJLog.log("load", "type=${p.type} id=${p.id}")

        return if (p.type == "movie") {
            val d = tmdbGet("/movie/${p.id}") ?: return null
            val title = d.s("title") ?: "Unknown"
            val posterUrl = poster(d.s("poster_path"))
            val backdrop = poster(d.s("backdrop_path"), "w780")
            val description = d.s("overview")
            val year = yearOf(d.s("release_date"))
            val score = scoreFrom10(d)
            val runtime = d.get("runtime")?.asInt()?.takeIf { it > 0 }
            val tags = d.get("genres")?.mapNotNull { it.s("name") }

            newMovieLoadResponse(title, url, TvType.Movie, """{"type":"movie","id":${p.id}}""") {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = description
                this.score = score
                this.duration = runtime
                this.tags = tags
            }
        } else {
            val d = tmdbGet("/tv/${p.id}") ?: return null
            val title = d.s("name") ?: "Unknown"
            val posterUrl = poster(d.s("poster_path"))
            val backdrop = poster(d.s("backdrop_path"), "w780")
            val description = d.s("overview")
            val year = yearOf(d.s("first_air_date"))
            val score = scoreFrom10(d)
            val tags = d.get("genres")?.mapNotNull { it.s("name") }

            val episodes = mutableListOf<Episode>()
            val seasons = d.get("seasons")
            if (seasons != null && seasons.isArray) {
                for (s in seasons) {
                    val sn = s.get("season_number")?.asInt() ?: continue
                    if (sn <= 0) continue
                    val epCount = s.get("episode_count")?.asInt() ?: 0
                    if (epCount <= 0) continue
                    val seasonJson = tmdbGet("/tv/${p.id}/season/$sn")
                    val eps = seasonJson?.get("episodes")
                    if (eps != null && eps.isArray) {
                        for (ep in eps) {
                            val en = ep.get("episode_number")?.asInt() ?: continue
                            val fixed = fixEpisode(en)
                            episodes.add(newEpisode("""{"type":"tv","id":${p.id},"s":$sn,"e":$fixed}""") {
                                this.name = ep.s("name") ?: "S${sn}E$fixed"
                                this.season = sn
                                this.episode = fixed
                                this.posterUrl = poster(ep.s("still_path"))
                                this.description = ep.s("overview")
                                this.score = scoreFrom10(ep)
                            })
                        }
                    } else {
                        for (en in 1..epCount) {
                            episodes.add(newEpisode("""{"type":"tv","id":${p.id},"s":$sn,"e":$en}""") {
                                this.name = "S${sn}E$en"
                                this.season = sn
                                this.episode = en
                            })
                        }
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = description
                this.score = score
                this.tags = tags
            }
        }
    }

    // ========================================================================
    // VIDLOVE — uses corsFetch (handles DNS-blocked api.vidlove.cc)
    // ========================================================================
    private data class StreamResult(
        val url: String,
        val label: String,
        val subs: List<Pair<String, String>> = emptyList(),
    )

    private suspend fun vidloveStream(
        type: String, id: Int, season: Int, episode: Int, suffix: String, variantLabel: String
    ): StreamResult? {
        val path = if (type == "tv") {
            "/tv?id=$id&season=$season&episode=$episode&mode=json$suffix"
        } else {
            "/movie?id=$id&mode=json$suffix"
        }
        val text = corsFetch("$vidloveBase$path", vidloveReferer, "vidlove-$variantLabel") ?: return null

        return try {
            if (text.trimStart().startsWith("<")) {
                CJLog.logErr("vidlove-$variantLabel", "returned HTML (Cloudflare block): ${text.take(80)}")
                return null
            }
            val json = mapper.readTree(text)

            // Detect app-level error codes (e.g. 2004)
            val code = json.get("code")?.asInt()
            if (code != null && code != 0 && code != 200) {
                CJLog.log("vidlove-$variantLabel", "error code=$code msg=${json.get("message")?.asText()}")
                return null
            }
            val errMsg = json.get("error")?.asText()
            if (!errMsg.isNullOrBlank()) {
                CJLog.log("vidlove-$variantLabel", "error field: $errMsg")
                return null
            }

            val src = json.get("source") ?: run {
                CJLog.log("vidlove-$variantLabel", "no 'source' field. Keys: ${json.fieldNames().asSequence().toList()}")
                return null
            }
            val url = src.s("url") ?: run {
                CJLog.log("vidlove-$variantLabel", "no 'url' in source")
                return null
            }
            if (!url.startsWith("http")) return null

            val quality = parseQuality(src.s("manifest"))
            val subs = mutableListOf<Pair<String, String>>()
            val subsArr = json.get("subtitles")
            if (subsArr != null && subsArr.isArray) {
                for (sub in subsArr) {
                    val file = sub.s("file") ?: sub.s("url") ?: continue
                    val lang = sub.s("language") ?: sub.s("label") ?: "en"
                    subs.add(lang to file)
                }
            }
            StreamResult(url, quality, subs)
        } catch (e: Exception) {
            CJLog.logErr("vidlove-$variantLabel", "parse fail", e)
            null
        }
    }

    private fun parseQuality(manifest: String?): String {
        if (manifest == null) return "Auto"
        val regex = Regex("RESOLUTION=(\\d+)x(\\d+)")
        var maxH = 0
        for (m in regex.findAll(manifest)) {
            val h = m.groupValues[2].toIntOrNull() ?: continue
            if (h > maxH) maxH = h
        }
        return when {
            maxH >= 2160 -> "2160p"
            maxH >= 1080 -> "1080p"
            maxH >= 720 -> "720p"
            maxH >= 480 -> "480p"
            maxH > 0 -> "${maxH}p"
            else -> "Auto"
        }
    }

    // ========================================================================
    // VIDROCK — uses corsFetch for API
    // ========================================================================
    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((hex[i * 2].digitToInt(16) shl 4) or hex[i * 2 + 1].digitToInt(16)).toByte()
        }

    private fun b64UrlToStd(s: String): String {
        var r = s.replace('-', '+').replace('_', '/')
        while (r.length % 4 != 0) r += "="
        return r
    }

    private fun vidrockDecrypt(token: String): String? {
        return try {
            val std = b64UrlToStd(token)
            if (std.length < 20) return null
            val iv = Base64.decode(std.substring(0, 16), Base64.DEFAULT)
            val data = Base64.decode(std.substring(16), Base64.DEFAULT)
            val key = hexToBytes(vidrockKeyHex)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            CJLog.logErr("vidrock", "decrypt failed", e)
            null
        }
    }

    private suspend fun vidrockStreams(
        type: String, id: Int, season: Int, episode: Int
    ): List<StreamResult> {
        val path = if (type == "tv") "tv/$id/$season/$episode" else "movie/$id"
        val text = corsFetch("$vidrockBase/api/$path", "$vidrockBase/", "vidrock-api") ?: return emptyList()

        return try {
            if (text.trimStart().startsWith("<")) {
                CJLog.logErr("vidrock", "returned HTML: ${text.take(80)}")
                return emptyList()
            }
            val json = mapper.readTree(text)
            val out = mutableListOf<StreamResult>()

            json.fieldNames().forEach { name ->
                val s = json.get(name) ?: return@forEach
                if (!s.isObject) return@forEach
                val enc = s.s("url")
                if (enc.isNullOrBlank()) {
                    CJLog.log("vidrock", "$name: empty url (dead source)")
                    return@forEach
                }
                val dec = vidrockDecrypt(enc) ?: return@forEach
                if (!dec.startsWith("http")) return@forEach
                val lang = s.s("language") ?: ""
                val label = name + (if (lang.isNotBlank()) " ($lang)" else "")
                out.add(StreamResult(dec, label))
            }
            out
        } catch (e: Exception) {
            CJLog.logErr("vidrock", "parse fail", e)
            emptyList()
        }
    }

    // ========================================================================
    // M3U8 VALIDATION
    // ========================================================================
    private suspend fun isValidM3u8(url: String, referer: String, tag: String): Boolean {
        return try {
            val text = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to referer,
                    "Accept" to "*/*",
                    "Range" to "bytes=0-2047",
                )
            ).text
            val ok = text.trimStart().startsWith("#EXTM3U")
            if (!ok) {
                val preview = text.take(120).replace("\n", " ").replace("\r", "")
                CJLog.log("$tag-probe", "NOT m3u8 (${text.length}b): $preview")
            }
            ok
        } catch (e: Exception) {
            CJLog.log("$tag-probe", "fail: ${e.message}")
            false
        }
    }

    // ========================================================================
    // LOAD LINKS — only emit VERIFIED streams, trigger clipboard on 0
    // ========================================================================
    private data class Candidate(
        val label: String,
        val url: String,
        val referer: String,
        val headers: Map<String, String>,
        val verified: Boolean,
        val subs: List<Pair<String, String>>,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        CJLog.clear()
        CJLog.log("loadLinks", "data=$data")

        val p = parseUrl(data)
        if (p == null) {
            CJLog.logErr("loadLinks", "parseUrl failed")
            CJLog.copyToClipboard("parseUrl failed for $data")
            return false
        }
        CJLog.log("loadLinks", "type=${p.type} id=${p.id} s=${p.s} e=${p.e}")

        val candidates = mutableListOf<Candidate>()

        // ── VidLove: 7 variants ──
        val vlVariants = listOf(
            "" to "Nebula",
            "&sources=warden" to "Solara",
            "&sources=moviebox" to "Lisbon",
            "&sources=vidapi" to "Athens",
            "&sources=moviebox2" to "MovieBox2",
            "&sources=cinefreak" to "PEKKA",
            "&sources=ipcloud" to "IPCloud",
        )
        for ((suffix, label) in vlVariants) {
            val s = vidloveStream(p.type, p.id, p.s, p.e, suffix, label) ?: continue
            val verified = isValidM3u8(s.url, vidloveReferer, "vidlove-$label")
            candidates.add(
                Candidate(
                    label = "CineJoy $label - ${s.label}",
                    url = s.url,
                    referer = vidloveReferer,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to vidloveReferer,
                        "Origin" to "https://player.vidlove.cc",
                    ),
                    verified = verified,
                    subs = s.subs,
                )
            )
        }

        // ── VidRock ──
        val vr = vidrockStreams(p.type, p.id, p.s, p.e)
        for (s in vr) {
            val verified = isValidM3u8(s.url, "$vidrockBase/", "vidrock-${s.label}")
            candidates.add(
                Candidate(
                    label = "CineJoy ${s.label}",
                    url = s.url,
                    referer = "$vidrockBase/",
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$vidrockBase/",
                    ),
                    verified = verified,
                    subs = emptyList(),
                )
            )
        }

        val verifiedList = candidates.filter { it.verified }
        CJLog.log("loadLinks", "candidates=${candidates.size} verified=${verifiedList.size}")

        // Log all rejected candidates for debug
        for (c in candidates.filter { !it.verified }) {
            CJLog.logErr("loadLinks", "REJECTED: ${c.label} -> ${c.url.take(80)}")
        }

        // ── Emit only verified streams ──
        var found = false
        for (c in verifiedList) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = c.label,
                    url = c.url,
                    type = ExtractorLinkType.M3U8,
                ) {
                    this.referer = c.referer
                    this.quality = Qualities.Unknown.value
                    this.headers = c.headers
                }
            )
            for ((lang, subUrl) in c.subs) {
                subtitleCallback.invoke(newSubtitleFile(url = subUrl, lang = lang))
            }
            found = true
        }

        if (!found) {
            // Trigger clipboard dump for user to report
            val reason = when {
                candidates.isEmpty() -> "0 candidates (all APIs failed — check [vidlove-*]/[vidrock-*] in log)"
                else -> "0 verified / ${candidates.size} unverified (all URLs returned dead/HTML)"
            }
            CJLog.logErr("loadLinks", "FAILED: $reason")
            CJLog.copyToClipboard("no verified streams — $reason")
        } else {
            CJLog.log("loadLinks", "SUCCESS: emitting ${verifiedList.size} verified streams")
        }

        return found
    }
}
