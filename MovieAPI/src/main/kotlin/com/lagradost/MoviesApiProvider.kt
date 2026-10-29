package com.lagradost

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class MoviesApiProvider : MainAPI() {
    override var mainUrl = "https://moviesapi.to"
    override var name = "MoviesAPI"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val tmdbKey = "e716f19ab4d25edc5247239a8f3494f8"

    private val imgExt = Regex("\\.(?:jpe?g|png|webp|avif)(?:\\?.*)?\\z", RegexOption.IGNORE_CASE)

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0"

    // Working key captured from live WebView (2026-10-29). If it rotates,
    // the WebView hook below will capture the new one automatically.
    private val vidoraKeyFallback = "3a67e8866ae1d2bb9e81fe7f73315a56eb3bdf5e3e755c7554c8be6910aa6b13"

    @Volatile private var cachedKey: String? = null
    @Volatile private var cachedKeyAt: Long = 0L

    private val apiBaseHeaders: Map<String, String> by lazy {
        mapOf(
            "User-Agent"      to userAgent,
            "Accept"          to "application/json, text/plain, */*",
            "Accept-Language" to "en-US,en;q=0.9",
            "Origin"          to mainUrl,
            "Referer"         to "$mainUrl/",
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

    private fun JsonNode.tmdbId(): String? = str("tmdb_id", "tmdbid", "tmdbId", "tmdb")
    private fun JsonNode.imdbId(): String? = str("imdb_id", "imdbid", "imdbId", "imdb")
    private fun JsonNode.title(): String? = str("title", "name", "original_title", "orig_title")

    private suspend fun tmdbPoster(
        tmdbid: String?, imdbId: String?, isTv: Boolean
    ): Pair<String?, String?> {
        try {
            if (tmdbid != null) {
                val type = if (isTv) "tv" else "movie"
                val info = app.get("https://api.themoviedb.org/3/$type/$tmdbid?api_key=$tmdbKey").text
                if (info.isNotBlank()) {
                    val j = mapper.readTree(info)
                    val pp = j.get("poster_path")?.asText()?.takeIf { it != "null" && it.isNotBlank() }
                    val bp = j.get("backdrop_path")?.asText()?.takeIf { it != "null" && it.isNotBlank() }
                    if (pp != null || bp != null) {
                        return Pair(pp?.let { "$tmdbImg/w500$it" }, bp?.let { "$tmdbImg/w1280$it" })
                    }
                }
            }
            if (imdbId != null && imdbId.startsWith("tt")) {
                val find = app.get(
                    "https://api.themoviedb.org/3/find/$imdbId?api_key=$tmdbKey&external_source=imdb_id"
                ).text
                if (find.isNotBlank()) {
                    val j = mapper.readTree(find)
                    val arr = if (isTv) j.get("tv_results") else j.get("movie_results")
                    if (arr != null && arr.isArray && arr.size() > 0) {
                        val first = arr.get(0)
                        val pp = first.get("poster_path")?.asText()?.takeIf { it != "null" && it.isNotBlank() }
                        val bp = first.get("backdrop_path")?.asText()?.takeIf { it != "null" && it.isNotBlank() }
                        return Pair(pp?.let { "$tmdbImg/w500$it" }, bp?.let { "$tmdbImg/w1280$it" })
                    }
                }
            }
        } catch (e: Exception) {
            DebugLog.log("tmdb", "poster lookup failed: ${e.message}")
        }
        return Pair(null, null)
    }

    private suspend fun JsonNode.toSearch(isTv: Boolean): SearchResponse? {
        val id = mediaId() ?: return null
        val title = title() ?: return null
        var poster = img(findImage())
        val year = (str("year") ?: str("release_date", "first_air_date", "released"))?.take(4)?.toIntOrNull()
        if (poster == null) poster = tmdbPoster(tmdbId(), imdbId(), isTv).first
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${sep}page=$page&resultsPerPage=30"
        val isTv = request.data.contains("/discover/tv") || request.data.contains("type=tv")
        val raw = getJson(url)?.items().orEmpty()
        val list = raw.mapNotNull { it.toSearch(isTv) }
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

        val title = node?.title() ?: id
        var poster = img(node?.findImage())
        var backdrop = img(node?.findImage(backdrop = true), "w1280")
        val plot = node?.str("overview", "plot", "description", "synopsis")
        val year = node?.let {
            (it.str("year") ?: it.str("release_date", "first_air_date"))?.take(4)?.toIntOrNull()
        }
        val imdbId = node?.imdbId() ?: id.takeIf { isImdb }
        val tmdbid = node?.tmdbId() ?: if (!isImdb) id else null
        val tagList = node?.get("genres")?.let { g ->
            if (g.isArray) g.mapNotNull { if (it.isTextual) it.asText() else it.str("name", "title") }
            else g.asText().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (poster == null || backdrop == null) {
            val p = tmdbPoster(tmdbid, imdbId, isTv)
            if (poster == null) poster = p.first
            if (backdrop == null) backdrop = p.second
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

    // ================================================================
    //                          LOAD LINKS
    // ================================================================

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

    private fun cacheKey(key: String) {
        cachedKey = key
        cachedKeyAt = System.currentTimeMillis()
        DebugLog.log("key", "cached for 1h: $key")
    }

    private fun currentKey(): String {
        val c = cachedKey
        if (c != null && System.currentTimeMillis() - cachedKeyAt < 3600_000L) return c
        return vidoraKeyFallback
    }

    /** Try the API with the given key. Returns true if at least one m3u8 was emitted. */
    private suspend fun tryApiCall(
        key: String,
        embed: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val apiUrl = vidoraUrl(embed) ?: return false
        return try {
            val headers = apiBaseHeaders.toMutableMap()
            headers["x-player-key"] = key
            val body = app.get(apiUrl, headers = headers, referer = "$mainUrl/").text
            DebugLog.log("vidora", "GET $apiUrl (key=${key.take(8)}..) -> ${body.length}b: ${body.take(500)}")

            if (!body.trimStart().startsWith("{")) return false
            val json = mapper.readTree(body)
            if (json.get("result")?.asBoolean() != true) {
                val err = json.get("error")?.asText() ?: "unknown"
                DebugLog.log("vidora", "auth failed: $err")
                return false
            }

            var found = false
            val sources = json.get("sources")
            if (sources != null && sources.isArray) {
                for (src in sources) {
                    val url = src.str("url") ?: continue
                    if (!url.contains(".m3u8")) continue
                    callback.invoke(
                        newExtractorLink(
                            source = name, name = name, url = url,
                            type = ExtractorLinkType.M3U8
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
                    val fn = t.get("file") ?: continue
                    if (fn.isNull) continue
                    val f = fn.asText()
                    if (f.isBlank()) continue
                    val lang = t.str("language") ?: "unknown"
                    subtitleCallback.invoke(newSubtitleFile(url = f, lang = lang))
                }
            }
            found
        } catch (e: Exception) {
            DebugLog.log("vidora", "call failed: ${e.message}")
            false
        }
    }

    // Auto-play script that aggressively tries to start the player
    private val autoPlayScript = """
        (function(){
          var n = 0;
          var t = setInterval(function(){
            n++;
            try {
              document.querySelectorAll('video, audio').forEach(function(el){
                el.muted = true;
                var pr = el.play();
                if (pr && pr.catch) pr.catch(function(){});
              });
              var sel = 'media-play-button, [data-part="play-button"], [data-play], ' +
                        '.play-button, .vjs-big-play-button, .jw-icon-playback, ' +
                        '.plyr__control--overlaid, button[aria-label*="lay"]';
              document.querySelectorAll(sel).forEach(function(b){
                try { b.click(); } catch (e) {}
              });
              var p = document.querySelector('media-player, #player, .player, [class*="player"]');
              if (p && n > 1) {
                var r = p.getBoundingClientRect();
                var x = r.left + r.width / 2;
                var y = r.top + r.height / 2;
                ['pointerdown','pointerup','click'].forEach(function(type){
                  try {
                    p.dispatchEvent(new MouseEvent(type, {bubbles: true, cancelable: true, clientX: x, clientY: y}));
                  } catch (e) {}
                });
              }
            } catch (e) {}
            if (n > 25) clearInterval(t);
          }, 1000);
        })();
    """.trimIndent()

    // Hook script: captures x-player-key from the site's own fetch() calls.
    // Returns JSON to scriptCallback. Once captured, key is cached by the plugin.
    private val hookScript = """
        (function(){
          if (window.__CS_KEY && window.__CS_KEY.length > 16) {
            return JSON.stringify({key: window.__CS_KEY});
          }
          if (!window.__CS_HOOKED) {
            window.__CS_HOOKED = true;
            try {
              var of = window.fetch;
              window.fetch = function(input, init) {
                try {
                  if (init && init.headers) {
                    var h = init.headers, found = null;
                    if (typeof h.forEach === 'function') {
                      h.forEach(function(v, k){
                        if (String(k).toLowerCase() === 'x-player-key') found = String(v);
                      });
                    } else {
                      for (var k in h) {
                        if (k.toLowerCase() === 'x-player-key') found = String(h[k]);
                      }
                    }
                    if (found && found.length > 16) window.__CS_KEY = found;
                  }
                } catch (e) {}
                return of.apply(this, arguments);
              };
            } catch (e) {}
          }
          return JSON.stringify({waiting: true});
        })();
    """.trimIndent()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        DebugLog.log("links", "loadLinks data=$data")

        // ---- FAST PATH: try current key directly ----
        val keyNow = currentKey()
        DebugLog.log("key", "fast path key: ${keyNow.take(8)}..")
        if (tryApiCall(keyNow, data, subtitleCallback, callback)) {
            DebugLog.log("key", "fast path OK")
            return true
        }

        // ---- SLOW PATH: WebView to capture live key ----
        DebugLog.log("webview", "=== opening WebView to capture live key ===")
        var capturedKey: String? = null

        try {
            val resolver = WebViewResolver(
                // Match the API call so WebView closes right after the site
                // fires the request that carries x-player-key in its headers.
                interceptUrl   = Regex("(?i)(/api/vidora/v1/(movie|tv)/|/api/vidora/v1/ice|workers\\.dev|\\.m3u8)"),
                useOkhttp      = false,
                script         = autoPlayScript + "\n" + hookScript,
                scriptCallback = { result ->
                    if (!result.isNullOrBlank() && capturedKey == null) {
                        try {
                            val j = mapper.readTree(result)
                            val k = j.get("key")?.asText()
                            if (k != null && k.length > 16) {
                                DebugLog.log("key", "captured live key: $k")
                                capturedKey = k
                                cacheKey(k)
                            }
                        } catch (_: Exception) { }
                    }
                },
                timeout        = 20_000L
            )
            val resp = app.get(data, referer = "$mainUrl/", interceptor = resolver)
            val capturedUrl = resp.url
            DebugLog.log("webview", "captured: $capturedUrl")

            // If WebView captured an m3u8 directly, emit it
            if (capturedUrl.contains(".m3u8")) {
                DebugLog.log("webview", "using captured m3u8: $capturedUrl")
                callback.invoke(
                    newExtractorLink(
                        source = name, name = name, url = capturedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = streamHeaders
                    }
                )
                return true
            }
        } catch (e: Exception) {
            DebugLog.log("webview", "error: ${e.message}")
        }

        // ---- RETRY with captured key ----
        if (capturedKey != null) {
            DebugLog.log("key", "retrying API with captured key")
            if (tryApiCall(capturedKey!!, data, subtitleCallback, callback)) {
                DebugLog.log("key", "retry OK - key saved for next 1h")
                return true
            }
        }

        DebugLog.error("links", "No playable link for $data")
        return false
    }
}
