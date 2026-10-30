package com.lagradost

import android.content.Context
import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class CineJoyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineJoyProvider())
    }
}

class CineJoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "CineJoy"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ──────────────────────────────────────────────────────────────────────
    // CONFIG
    // ──────────────────────────────────────────────────────────────────────
    private val mapper = jacksonObjectMapper()
    private fun log(tag: String, msg: String) = println("[CineJoy][$tag] $msg")
    private fun logErr(tag: String, msg: String, e: Throwable? = null) {
        println("[CineJoy][$tag][ERR] $msg${if (e != null) " | ${e.message}" else ""}")
    }

    // TMDB keys (from CineJoy's own JS bundle - both are live)
    private val tmdbKey = "8476a7ab80ad76f0936744df0430e67c"
    private val tmdbFallbackKey = "439c478a771f35c05022f9feabcca01c"

    // OMDb (optional fallback - leave empty to skip)
    private val omdbApiKey = ""

    // Image proxy
    private val imgProxy = "https://wsrv.nl/?url="

    // TMDB direct domains
    private val tmdbDirectDomains = listOf(
        "https://api.tmdb.org",
        "https://api.themoviedb.org",
        "https://tmdb.demojameson.de5.net",
        "https://daili.smdhb.com",
        "https://vidora-tmdb.wwmm.date",
    )

    // CORS proxies
    private val tmdbCorsProxies: List<(String) -> String> = listOf(
        { url -> "https://cors.eu.org/$url" },
        { url -> "https://api.codetabs.com/v1/proxy/?quest=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://api.allorigins.win/raw?url=${URLEncoder.encode(url, "UTF-8")}" },
        { url -> "https://cinejoytv.fun/relay?url=${URLEncoder.encode(url, "UTF-8")}" },
    )

    // Stream providers
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
    // TMDB (multi-domain + CORS)
    // ========================================================================
    private suspend fun tmdbGet(path: String, useFallback: Boolean = false): JsonNode? {
        val key = if (useFallback) tmdbFallbackKey else tmdbKey
        val sep = if (path.contains("?")) "&" else "?"
        val fullPath = "$path${sep}api_key=$key"

        for (base in tmdbDirectDomains) {
            try {
                val text = app.get(
                    "$base$fullPath",
                    headers = mapOf("Accept" to "application/json", "User-Agent" to userAgent),
                    referer = null
                ).text
                if (text.isBlank() || !text.trimStart().startsWith("{")) {
                    log("tmdb", "$base -> non-JSON")
                    continue
                }
                val j = mapper.readTree(text)
                val status = j.get("status_code")?.asInt()
                if (status != null && status != 0) {
                    log("tmdb", "$base -> status $status")
                    if (!useFallback && (status == 7 || status == 3)) return tmdbGet(path, true)
                    continue
                }
                log("tmdb", "$base -> OK")
                return j
            } catch (e: Exception) {
                log("tmdb", "$base -> ${e.message}")
            }
        }

        val targetUrl = "https://api.themoviedb.org/3$fullPath"
        for (buildProxy in tmdbCorsProxies) {
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
                log("tmdb-cors", "OK")
                return j
            } catch (e: Exception) {
                log("tmdb-cors", "failed: ${e.message}")
            }
        }

        if (!useFallback) return tmdbGet(path, true)
        return null
    }

    private fun JsonNode.s(k: String): String? =
        get(k)?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.takeIf { it.isNotBlank() }

    private fun proxyImg(originalUrl: String): String =
        "$imgProxy${URLEncoder.encode(originalUrl, "UTF-8")}"

    /** Build TMDB image URL proxied via wsrv.nl. Falls back to OMDb if no path. */
    private fun poster(path: String?, size: String = "w500", imdbId: String? = null): String? {
        if (path != null) {
            return proxyImg("https://image.tmdb.org/t/p/$size$path")
        }
        // OMDb fallback (only when TMDB has no poster and key configured)
        if (!imdbId.isNullOrBlank() && imdbId.startsWith("tt") &&
            omdbApiKey.isNotBlank() && omdbApiKey != "YOUR_OMDB_API_KEY") {
            return "https://img.omdbapi.com/?apikey=$omdbApiKey&i=$imdbId"
        }
        return null
    }

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
        // TMDB response sometimes includes imdb_id (only if append_to_response=external_ids, else null)
        val imdbId = r.s("imdb_id")
        val posterUrl = poster(r.s("poster_path"), "w500", imdbId)
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
        val items = mutableListOf<SearchResponse>()
        val json = tmdbGet("${request.data}?page=$page")
        val results = json?.get("results")
        if (results != null && results.isArray) {
            for (r in results) tmdbToSearch(r)?.let { items.add(it) }
        }
        log("main", "${request.name}: ${items.size} items")
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
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
        log("search", "query=$query: ${out.size} items")
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
        val p = parseUrl(url) ?: return null

        return if (p.type == "movie") {
            val d = tmdbGet("/movie/${p.id}") ?: return null
            val title = d.s("title") ?: "Unknown"
            val posterUrl = poster(d.s("poster_path"), "w500", d.s("imdb_id"))
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
            val posterUrl = poster(d.s("poster_path"), "w500", d.s("imdb_id"))
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
    // VIDLOVE (7 variants)
    // ========================================================================
    private data class StreamResult(
        val url: String,
        val label: String,
        val subs: List<Pair<String, String>> = emptyList(),
    )

    private suspend fun vidloveStream(
        type: String, id: Int, season: Int, episode: Int, suffix: String
    ): StreamResult? {
        val path = if (type == "tv") {
            "/tv?id=$id&season=$season&episode=$episode&mode=json$suffix"
        } else {
            "/movie?id=$id&mode=json$suffix"
        }
        return try {
            val text = app.get(
                "$vidloveBase$path",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to vidloveReferer,
                    "Accept" to "application/json",
                )
            ).text
            if (text.isBlank()) return null
            val json = mapper.readTree(text)
            val src = json.get("source") ?: return null
            val url = src.s("url") ?: return null
            if (!url.startsWith("http")) return null

            val manifest = src.s("manifest")
            val quality = parseQuality(manifest)

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
            logErr("vidlove", "stream $suffix failed", e)
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
    // VIDROCK (AES-GCM)
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
            val ivB64 = std.substring(0, 16)
            val dataB64 = std.substring(16)
            val iv = Base64.decode(ivB64, Base64.DEFAULT)
            val data = Base64.decode(dataB64, Base64.DEFAULT)
            val key = hexToBytes(vidrockKeyHex)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            logErr("vidrock", "decrypt failed", e)
            null
        }
    }

    private suspend fun vidrockStreams(
        type: String, id: Int, season: Int, episode: Int
    ): List<StreamResult> {
        val path = if (type == "tv") "tv/$id/$season/$episode" else "movie/$id"
        return try {
            val text = app.get(
                "$vidrockBase/api/$path",
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "$vidrockBase/",
                    "Accept" to "application/json",
                )
            ).text
            if (text.isBlank()) return emptyList()
            val json = mapper.readTree(text)
            val out = mutableListOf<StreamResult>()
            json.fieldNames().forEach { name ->
                val s = json.get(name) ?: return@forEach
                if (!s.isObject) return@forEach
                val enc = s.s("url") ?: return@forEach
                val dec = vidrockDecrypt(enc) ?: return@forEach
                if (!dec.startsWith("http")) return@forEach
                val lang = s.s("language") ?: ""
                val label = name + (if (lang.isNotBlank()) " ($lang)" else "")
                out.add(StreamResult(dec, label))
            }
            out
        } catch (e: Exception) {
            logErr("vidrock", "streams failed", e)
            emptyList()
        }
    }

    // ========================================================================
    // LOAD LINKS
    // ========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val p = parseUrl(data) ?: return false
        var found = false

        // VidLove: 7 variants
        val vlVariants = listOf(
            "" to "Nebula",
            "&sources=warden" to "Solara",
            "&sources=moviebox" to "Lisbon 4K",
            "&sources=vidapi" to "Athens",
            "&sources=moviebox2" to "MovieBox 2",
            "&sources=cinefreak" to "PEKKA",
            "&sources=ipcloud" to "IPCloud",
        )
        for ((suffix, label) in vlVariants) {
            val s = vidloveStream(p.type, p.id, p.s, p.e, suffix) ?: continue
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "CineJoy $label - ${s.label}",
                    url = s.url,
                    type = if (s.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = vidloveReferer
                    this.quality = Qualities.Unknown.value
                }
            )
            for ((lang, subUrl) in s.subs) {
                subtitleCallback.invoke(newSubtitleFile(url = subUrl, lang = lang))
            }
            found = true
        }

        // VidRock
        val vr = vidrockStreams(p.type, p.id, p.s, p.e)
        for (s in vr) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "CineJoy ${s.label}",
                    url = s.url,
                    type = if (s.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "$vidrockBase/"
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }

        log("links", "found=$found (vidlove variants tried=7, vidrock=${vr.size})")
        return found
    }
}
