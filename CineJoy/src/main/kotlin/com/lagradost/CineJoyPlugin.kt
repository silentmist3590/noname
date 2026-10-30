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

    private fun score10(r: JsonNode): Score? {
        val v = r.get("vote_average")?.asDouble() ?: return null
        return if (v <= 0) null else Score.from10(v)
    }

    private fun tmdbToSearch(r: JsonNode): SearchResponse? {
        val id = r.get("id")?.asInt() ?: return null
        val type = mediaType(r)
        val title = r.s("title") ?: r.s("name") ?: return null
        val url = """{"type":"$type","id":$id}"""
        val posterUrl = poster(r.s("poster_path"))
        val year = yearOf(r.s("release_date") ?: r.s("first_air_date"))
        val score = score10(r)
        return if (type == "tv") {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = posterUrl; this.year = year; this.score = score
            }
        } else {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = posterUrl; this.year = year; this.score = score
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
                this.score = score10(d)
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
                                this.score = score10(ep)
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
                this.score = score10(d)
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
