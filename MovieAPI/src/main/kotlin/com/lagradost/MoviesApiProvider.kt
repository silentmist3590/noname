package com.lagradost

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

class MoviesApiProvider : MainAPI() {
    override var mainUrl = "https://moviesapi.to"
    override var name = "MoviesAPI"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()
    private val tmdbImg = "https://image.tmdb.org/t/p"

    // TMDB public API key (used for poster/backdrop lookup)
    private val tmdbKey = "e716f19ab4d25edc5247239a8f3494f8"

    private val imgExt = Regex("\\.(?:jpe?g|png|webp|avif)(?:\\?.*)?\\z", RegexOption.IGNORE_CASE)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0"

    // x-player-key used by the vidora API.
    // This value rotates on the live site. If you get {"error":"Unauthorized"},
    // open https://moviesapi.to/movie/1291595 in Chrome, F12 -> Network ->
    // filter "vidora" -> copy request header "x-player-key" and paste below.
    private val vidoraKey = "e716f19ab4d25edc5247239a8f3494f8"

    private val apiHeaders: Map<String, String> by lazy {
        mapOf(
            "User-Agent"      to userAgent,
            "Accept"          to "application/json",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin"          to mainUrl,
            "Referer"         to "$mainUrl/",
            "x-player-key"    to vidoraKey,
        )
    }

    private val streamHeaders: Map<String, String> by lazy {
        mapOf(
            "User-Agent"      to userAgent,
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin"          to mainUrl,
            "Referer"         to "$mainUrl/",
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/discover/views?type=movie&views=day" to "Trending Movies Today",
        "$mainUrl/api/discover/views?type=tv&views=day" to "Trending TV Today",
        "$mainUrl/api/discover/views?type=movie&views=week" to "Trending Movies Week",
        "$mainUrl/api/discover/movies?ordering=created_at&direction=desc" to "Newly Added Movies",
        "$mainUrl/api/discover/tv?ordering=last_upload_date&direction=desc" to "Recently Updated TV",
        "$mainUrl/api/discover/movies?ordering=views&direction=desc" to "Most Viewed Movies",
        "$mainUrl/api/discover/tv?ordering=views&direction=desc" to "Most Viewed TV",
    )

    // ---------------------------------------------------------------- helpers

    private suspend fun getJson(url: String, referer: String? = "$mainUrl/"): JsonNode? = try {
        val text = app.get(
            url,
            headers = mapOf(
                "Accept"     to "application/json",
                "User-Agent" to userAgent,
                "Origin"     to mainUrl,
            ),
            referer = referer
        ).text
        DebugLog.log("http", "GET $url -> ${text.length}b: ${text.take(300)}")
        if (text.isBlank()) null else mapper.readTree(text)
    } catch (e: Exception) {
        DebugLog.error("http", "GET $url failed", e)
        null
    }

    private fun JsonNode.items(): List<JsonNode> {
        if (isArray) return toList()
        for (k in listOf("data", "results", "items", "movies", "shows", "tv", "list")) {
            val n = get(k) ?: continue
            if (n.isArray) return n.toList()
            if (n.isObject) {
                val r = n.items()
                if (r.isNotEmpty()) return r
            }
        }
        return emptyList()
    }

    private fun JsonNode.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            get(k)?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.takeIf { it.isNotBlank() }
        }

    private fun JsonNode.findImage(backdrop: Boolean = false, depth: Int = 0): String? {
        if (!isObject || depth > 2) return null
        fun isBack(k: String) =
            k.contains("backdrop") || k.contains("banner") || k.contains("background")
        val keys = fieldNames().asSequence().toList().sortedBy { k ->
            val l = k.lowercase()
            when {
                backdrop && isBack(l) -> 0
                !backdrop && l.contains("poster") -> 0
                !backdrop && (l.contains("thumb") || l.contains("cover") || l == "image" || l.contains("img")) -> 1
                else -> 2
            }
        }
        for (k in keys) {
            val l = k.lowercase()
            if (!backdrop && isBack(l)) continue
            if (backdrop && !isBack(l)) continue
            val v = get(k) ?: continue
            if (v.isTextual) {
                val s = v.asText()
                if (s.isNotBlank() && (imgExt.containsMatchIn(s) || l.contains("poster") || l.contains("backdrop"))) return s
            } else if (v.isObject) {
                v.findImage(false, depth + 1)?.let { return it }
            }
        }
        if (!backdrop) {
            for (k in keys) {
                if (isBack(k.lowercase())) continue
                val v = get(k) ?: continue
                if (v.isTextual && imgExt.containsMatchIn(v.asText())) return v.asText()
            }
        }
        return null
    }

    private fun img(p: String?, size: String = "w500"): String? = when {
        p.isNullOrBlank() -> null
        p.startsWith("http") -> p
        p.startsWith("//") -> "https:$p"
        p.startsWith("/") -> "$tmdbImg/$size$p"
        else -> "$mainUrl/$p"
    }

    private fun JsonNode.mediaId(): String? =
        str("tmdb_id", "tmdbid", "tmdbId", "tmdb") ?: str("imdb_id", "imdbid", "imdbId", "imdb")

    private fun JsonNode.tmdbId(): String? =
        str("tmdb_id", "tmdbid", "tmdbId", "tmdb")

    private fun JsonNode.imdbId(): String? =
        str("imdb_id", "imdbid", "imdbId", "imdb")

    private fun JsonNode.title(): String? =
        str("title", "name", "original_title", "orig_title")

    // -------------------------------------------------- TMDB poster fallback

    private suspend fun tmdbPoster(
        tmdbid: String?, imdbId: String?, isTv: Boolean
    ): Pair<String?, String?> {
        try {
            // Preferred: use tmdbid directly (single call)
            if (tmdbid != null) {
                val type = if (isTv) "tv" else "movie"
                val info = app.get(
                    "https://api.themoviedb.org/3/$type/$tmdbid?api_key=$tmdbKey"
                ).text
                if (info.isNotBlank()) {
                    val j = mapper.readTree(info)
                    val pp = j.get("poster_path")?.asText()
                        ?.takeIf { it != "null" && it.isNotBlank() }
                    val bp = j.get("backdrop_path")?.asText()
                        ?.takeIf { it != "null" && it.isNotBlank() }
                    if (pp != null || bp != null) {
                        return (pp?.let { "$tmdbImg/w500$it" })
                            to (bp?.let { "$tmdbImg/w1280$it" })
                    }
                }
            }
            // Fallback: find by imdb_id
            if (imdbId != null && imdbId.startsWith("tt")) {
                val find = app.get(
                    "https://api.themoviedb.org/3/find/$imdbId?api_key=$tmdbKey&external_source=imdb_id"
                ).text
                if (find.isNotBlank()) {
                    val j = mapper.readTree(find)
                    val arr = if (isTv) j.get("tv_results") else j.get("movie_results")
                    if (arr != null && arr.isArray && arr.size() > 0) {
                        val first = arr.get(0)
                        val pp = first.get("poster_path")?.asText()
                            ?.takeIf { it != "null" && it.isNotBlank() }
                        val bp = first.get("backdrop_path")?.asText()
                            ?.takeIf { it != "null" && it.isNotBlank() }
                        return (pp?.let { "$tmdbImg/w500$it" })
                            to (bp?.let { "$tmdbImg/w1280$it" })
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("tmdb", "poster lookup failed: ${e.message}")
        }
        return null to null
    }

    // -------------------------------------------------- list entry (suspend)

    private suspend fun JsonNode.toSearch(isTv: Boolean): SearchResponse? {
        val id = mediaId() ?: return null
        val title = title() ?: return null

        var poster = img(findImage())
        val year = (str("year") ?: str("release_date", "first_air_date", "released"))
            ?.take(4)?.toIntOrNull()

        // List API returns no poster - fetch from TMDB
        if (poster == null) {
            val (tp, _) = tmdbPoster(tmdbId(), imdbId(), isTv)
            poster = tp
        }

        return if (isTv) {
            newTvSeriesSearchResponse(title, "$mainUrl/tv/$id", TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, "$mainUrl/movie/$id", TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // -------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${sep}page=$page&resultsPerPage=30"
        val isTv = request.data.contains("/discover/tv") || request.data.contains("type=tv")
        val raw = getJson(url)?.items().orEmpty()

        // Parallel poster lookup via TMDB for each item
        val list: List<SearchResponse> = coroutineScope {
            raw.map { item -> async { item.toSearch(isTv) } }
                .awaitAll()
                .filterNotNull()
        }

        if (raw.isEmpty()) {
            DebugLog.error("main", "Empty response from $url")
        } else if (list.isEmpty()) {
            DebugLog.error("main", "Got ${raw.size} items but couldn't parse. First: ${raw.first()}")
        }
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList()
        val q = URLEncoder.encode(query, "UTF-8")
        val movies = getJson("$mainUrl/api/discover/movies?query=$q&resultsPerPage=30")
            ?.items().orEmpty().mapNotNull { it.toSearch(false) }
        val shows = getJson("$mainUrl/api/discover/tv?query=$q&resultsPerPage=30")
            ?.items().orEmpty().mapNotNull { it.toSearch(true) }
        return movies + shows
    }

    // -------------------------------------------------- load

    private fun mkEpisode(
        id: String, season: Int, episode: Int,
        name: String? = null, desc: String? = null, poster: String? = null
    ): Episode = newEpisode("$mainUrl/tv/$id/$season/$episode") {
        this.season = season
        this.episode = episode
        this.name = name
        this.description = desc
        this.posterUrl = poster
    }

    private suspend fun buildEpisodes(id: String, node: JsonNode?, imdbId: String?): List<Episode> {
        val eps = mutableListOf<Episode>()

        val seasons = node?.get("seasons")
        if (seasons != null && seasons.isArray) {
            for (s in seasons) {
                val sn = s.str("season_number", "season", "number")?.toIntOrNull() ?: continue
                if (sn == 0) continue
                val inner = s.get("episodes")
                if (inner != null && inner.isArray && inner.size() > 0) {
                    for (e in inner) {
                        val en = e.str("episode_number", "episode", "number")?.toIntOrNull() ?: continue
                        eps += mkEpisode(
                            id, sn, en,
                            e.str("name", "title"), e.str("overview"),
                            img(e.str("still_path", "still", "image"), "w300")
                        )
                    }
                } else {
                    val count = s.str("episode_count", "episodes_count", "episodes")?.toIntOrNull() ?: 0
                    for (en in 1..count) eps += mkEpisode(id, sn, en)
                }
            }
        }

        if (eps.isEmpty() && imdbId != null) {
            val videos = getJson(
                "https://v3-cinemeta.strem.io/meta/series/$imdbId.json", referer = null
            )?.get("meta")?.get("videos")
            if (videos != null && videos.isArray) {
                for (v in videos) {
                    val sn = v.get("season")?.asInt() ?: continue
                    val en = v.get("episode")?.asInt() ?: continue
                    if (sn == 0) continue
                    eps += mkEpisode(
                        id, sn, en,
                        v.str("name", "title"), v.str("overview", "description"),
                        v.str("thumbnail")
                    )
                }
            }
        }
        return eps.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun load(url: String): LoadResponse? {
        val m = Regex("""/(movie|tv)/([^/?#]+)""").find(url) ?: return null
        val isTv = m.groupValues[1] == "tv"
        val rawId = m.groupValues[2]
        val id = if (isTv) rawId.substringBefore("-") else rawId
        val isImdb = id.startsWith("tt")

        val param = when {
            isImdb && isTv -> "imdbid"
            isImdb -> "imdb_id"
            else -> "tmdbid"
        }
        val endpoint = if (isTv) "tv" else "movies"
        val node = getJson("$mainUrl/api/discover/$endpoint?$param=$id")?.items()?.firstOrNull()
        if (node == null) DebugLog.error("load", "No detail data for $endpoint $param=$id")

        val title = node?.title() ?: id
        var poster = img(node?.findImage())
        var backdrop = img(node?.findImage(backdrop = true), "w1280")
        val plot = node?.str("overview", "plot", "description", "synopsis")
        val year = node?.let {
            (it.str("year") ?: it.str("release_date", "first_air_date"))
                ?.take(4)?.toIntOrNull()
        }
        val imdbId = node?.imdbId() ?: id.takeIf { isImdb }
        val tmdbid = node?.tmdbId() ?: if (!isImdb) id else null
        val tagList = node?.get("genres")?.let { g ->
            if (g.isArray) g.mapNotNull { if (it.isTextual) it.asText() else it.str("name", "title") }
            else g.asText().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }

        // Fallback to TMDB for poster/backdrop if missing
        if (poster == null || backdrop == null) {
            val (tp, tb) = tmdbPoster(tmdbid, imdbId, isTv)
            if (poster == null) poster = tp
            if (backdrop == null) backdrop = tb
        }

        return if (isTv) {
            val episodes = buildEpisodes(id, node, imdbId)
            newTvSeriesLoadResponse(title, "$mainUrl/tv/$id", TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tagList
            }
        } else {
            val embed = "$mainUrl/movie/$id"
            newMovieLoadResponse(title, embed, TvType.Movie, embed) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tagList
            }
        }
    }

    // -------------------------------------------------- loadLinks

    private fun vidoraUrl(embed: String): String? {
        val m = Regex("""/(movie|tv)/([^/?#]+)(?:/(\d+)/(\d+))?""").find(embed) ?: return null
        val type = m.groupValues[1]
        val id = m.groupValues[2]
        return when {
            type == "movie" -> "$mainUrl/api/vidora/v1/movie/$id"
            m.groupValues[3].isNotEmpty() ->
                "$mainUrl/api/vidora/v1/tv/$id/${m.groupValues[3]}/${m.groupValues[4]}"
            else -> null
        }
    }

    private val autoPlayScript = """
        (function(){
          var n = 0;
          var t = setInterval(function(){
            n++;
            try {
              document.querySelectorAll('video, audio').forEach(function(el){
                el.muted = true;
                var p = el.play();
                if (p && p.catch) p.catch(function(){});
              });
              var bs = document.querySelectorAll('button, [role="button"], [data-play], media-play-button');
              bs.forEach(function(b){
                var a = (b.getAttribute('aria-label') || '').toLowerCase();
                var t = (b.textContent || '').trim().toLowerCase();
                if (a.indexOf('play') >= 0 || t === 'play' || b.hasAttribute('data-play')) {
                  try { b.click(); } catch (e) {}
                }
              });
            } catch (e) {}
            if (n > 20) clearInterval(t);
          }, 1000);
        })();
    """.trimIndent()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DebugLog.log("links", "loadLinks data=$data")
        var found = false

        val apiUrl = vidoraUrl(data)
        if (apiUrl != null) {
            try {
                val body = app.get(
                    apiUrl,
                    headers = apiHeaders,
                    referer = "$mainUrl/"
                ).text
                DebugLog.log("vidora", "GET $apiUrl -> ${body.length}b: ${body.take(800)}")

                val json = mapper.readTree(body)

                if (json.get("result")?.asBoolean() != true) {
                    val err = json.get("error")?.asText() ?: "unknown"
                    DebugLog.error(
                        "vidora",
                        "API auth failed ($err). The x-player-key has likely rotated. " +
                        "Fix: open https://moviesapi.to/movie/1291595 in Chrome, F12 -> " +
                        "Network -> filter 'vidora' -> copy request header 'x-player-key' " +
                        "-> update vidoraKey constant. Body: ${body.take(200)}"
                    )
                }

                val sources = json.get("sources")
                if (sources != null && sources.isArray) {
                    for (src in sources) {
                        val url = src.str("url") ?: continue
                        if (!url.contains(".m3u8")) continue

                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name   = name,
                                url    = url,
                                type   = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = streamHeaders
                            }
                        )
                        found = true
                    }
                }

                val tracks = sources?.firstOrNull()?.get("tracks")
                if (tracks != null && tracks.isArray) {
                    for (t in tracks) {
                        val fileNode = t.get("file") ?: continue
                        if (fileNode.isNull) continue
                        val file = fileNode.asText()
                        if (file.isBlank()) continue
                        val lang = t.str("language") ?: "unknown"
                        subtitleCallback.invoke(newSubtitleFile(url = file, lang = lang))
                    }
                }
            } catch (e: Exception) {
                DebugLog.error("vidora", "API error", e)
            }
        }

        if (found) return true

        // Fallback: WebView to capture m3u8
        try {
            val resolver = WebViewResolver(
                interceptUrl   = Regex("\\.m3u8"),
                useOkhttp      = false,
                script         = autoPlayScript,
                scriptCallback = { },
                timeout        = 30_000L
            )
            val stream = app.get(data, referer = "$mainUrl/", interceptor = resolver).url
            DebugLog.log("webview", "result: $stream")
            if (stream.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name   = name,
                        url    = stream,
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
                found = true
            }
        } catch (e: Exception) {
            DebugLog.log("webview", "error: $e")
        }

        if (!found) {
            DebugLog.error("links", "No playable link for $data")
        }
        return found
    }
}
