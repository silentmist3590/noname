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

    // Fallback key. If it rotates, scrapeKeyFromBundle() finds the current one.
    private val vidoraKeyFallback = "e716f19ab4d25edc5247239a8f3494f8"

    // Key cache
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
    //          KEY SCRAPING  (tự động lấy x-player-key hiện tại)
    // ================================================================

    private suspend fun getCurrentVidoraKey(): String {
        val now = System.currentTimeMillis()
        cachedKey?.let { if (now - cachedKeyAt < 3600_000L) return it }

        val scraped = scrapeKeyFromBundle()
        if (scraped != null && scraped.length >= 16) {
            DebugLog.log("key", "scraped OK: $scraped")
            cachedKey = scraped
            cachedKeyAt = now
            return scraped
        }
        DebugLog.log("key", "scrape failed - using fallback")
        return vidoraKeyFallback
    }

    private suspend fun scrapeKeyFromBundle(): String? {
        // Step 1: fetch home page HTML
        val html = try {
            app.get("$mainUrl/", referer = "$mainUrl/").text
        } catch (e: Exception) {
            DebugLog.log("key", "fetch home failed: ${e.message}")
            return null
        }
        if (html.isBlank()) return null

        // Step 2: find script srcs
        val scriptSrcs = Regex("""<script[^>]+src=["']([^"']+\.js[^"']*)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        // Step 3: also look for modulepreload / dynamic import hints
        val moreSrcs = Regex("""["'](/?assets/[A-Za-z0-9_\-]+\.js)["']""")
            .findAll(html)
            .map { it.groupValues[1] }
            .toList()

        val allSrcs = (scriptSrcs + moreSrcs).distinct()
        DebugLog.log("key", "found ${allSrcs.size} JS: ${allSrcs.take(6)}")

        // Step 4: fetch each JS, search for the key
        for (raw in allSrcs) {
            val url = when {
                raw.startsWith("http") -> raw
                raw.startsWith("/")    -> "$mainUrl$raw"
                else                   -> "$mainUrl/$raw"
            }
            try {
                val js = app.get(url, referer = "$mainUrl/").text
                if (js.isBlank()) continue
                DebugLog.log("key", "loaded $url (${js.length}b)")

                // Pattern A: x-player-key:"literal"
                Regex("""["']x-player-key["']\s*:\s*["'`]([^"'`]{8,})["'`]""")
                    .find(js)?.let { return it.groupValues[1] }

                // Pattern B: x-player-key:varName
                Regex("""["']x-player-key["']\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""")
                    .find(js)?.let { m ->
                        val vn = Regex.escape(m.groupValues[1])
                        Regex("""(?:var|let|const)\s+$vn\s*=\s*["'`]([^"'`]{8,})["'`]""")
                            .find(js)?.let { return it.groupValues[1] }
                    }

                // Pattern C: PLAYER_KEY:"literal" or X-PLAYER-KEY:"literal"
                Regex("""(?:PLAYER_KEY|X_PLAYER_KEY|playerKey|player_key)["']?\s*[:=]\s*["'`]([^"'`]{8,})["'`]""")
                    .find(js)?.let { return it.groupValues[1] }

                // Pattern D: any 32-hex string used in an Authorization-ish context
                if (js.contains("vidora") || js.contains("x-player-key")) {
                    Regex("""["'`]([a-f0-9]{24,64})["'`]""")
                        .findAll(js)
                        .map { it.groupValues[1] }
                        .forEach { candidate ->
                            if (candidate != tmdbKey && candidate.length == 32) {
                                DebugLog.log("key", "candidate from $url: $candidate")
                            }
                        }
                }
            } catch (e: Exception) {
                DebugLog.log("key", "fetch $url failed: ${e.message}")
            }
        }
        return null
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

    // Auto-play script
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
              var player = document.querySelector('media-player, #player, .player, [class*="player"]');
              if (player && n > 1) {
                var r = player.getBoundingClientRect();
                var x = r.left + r.width / 2;
                var y = r.top + r.height / 2;
                ['pointerdown','pointerup','click'].forEach(function(type){
                  try {
                    player.dispatchEvent(new MouseEvent(type, {bubbles: true, cancelable: true, clientX: x, clientY: y}));
                  } catch (e) {}
                });
              }
            } catch (e) {}
            if (n > 25) clearInterval(t);
          }, 1000);
        })();
    """.trimIndent()

    // Hook script that RETURNS accumulated state to scriptCallback
    // (CloudStream WebViewResolver picks up the return value)
    private val hookScriptTemplate = """
        (function(){
          if (!window.__CS_HOOK) window.__CS_HOOK = {fetch: [], xhr: [], keys: [], storage: []};
          var H = window.__CS_HOOK;
          function add(tag, data) {
            try {
              H[tag].push(data);
              if (H[tag].length > 8) H[tag].shift();
            } catch (e) {}
          }
          function matchUrl(u) {
            return /vidora|m3u8|hls|workers\.dev|\/api\//i.test(u || '');
          }

          if (!window.__CS_HOOK_DONE) {
            window.__CS_HOOK_DONE = true;

            // Hook fetch
            try {
              var of_ = window.fetch;
              if (of_) {
                window.fetch = function(input, init) {
                  try {
                    var u = typeof input === 'string' ? input : (input && input.url || '');
                    if (matchUrl(u)) {
                      var hd = {};
                      if (init && init.headers) {
                        if (typeof init.headers.forEach === 'function') {
                          init.headers.forEach(function(v, k){ hd[k] = v; });
                        } else if (typeof init.headers === 'object') {
                          hd = Object.assign({}, init.headers);
                        }
                      }
                      add('fetch', {url: u, method: (init && init.method) || 'GET', h: hd});
                    }
                  } catch (e) {}
                  return of_.apply(this, arguments);
                };
              }
            } catch (e) {}

            // Hook XHR
            try {
              var p = XMLHttpRequest.prototype;
              var oO = p.open, oS = p.setRequestHeader, oN = p.send;
              p.open = function(m, u){ try { this.__u = u; this.__h = {}; this.__m = m; } catch (e) {} return oO.apply(this, arguments); };
              p.setRequestHeader = function(k, v){ try { if (this.__h) this.__h[k] = v; } catch (e) {} return oS.apply(this, arguments); };
              p.send = function(b){
                try {
                  if (matchUrl(this.__u)) add('xhr', {url: this.__u, m: this.__m, h: this.__h || {}});
                } catch (e) {}
                return oN.apply(this, arguments);
              };
            } catch (e) {}

            // Storage scan
            try {
              function scan(store, name) {
                for (var i = 0; i < store.length; i++) {
                  var k = store.key(i);
                  if (/key|token|auth|player|stream/i.test(k || '')) {
                    add('storage', {s: name, k: k, v: String(store.getItem(k)).slice(0, 80)});
                  }
                }
              }
              scan(window.localStorage, 'L');
              scan(window.sessionStorage, 'S');
            } catch (e) {}

            // PROBE: call vidora API from page context using KEY placeholder
            // If placeholder works, we immediately know real network behavior.
            try {
              var k = '__PLAYER_KEY__';
              if (k && k.length > 8 && k !== '__PLAYER_KEY__') {
                fetch('/api/vidora/v1/movie/1', {headers: {'x-player-key': k}})
                  .then(function(r){ return r.text(); })
                  .then(function(t){ add('keys', {probe: 'ok', status: 'auth-check', body: t.slice(0, 200)}); })
                  .catch(function(e){ add('keys', {probe: 'fail', err: String(e)}); });
              }
            } catch (e) {}
          }

          return JSON.stringify({
            f: H.fetch.slice(-3),
            x: H.xhr.slice(-3),
            k: H.keys.slice(-3),
            s: H.storage.slice(-3)
          });
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

        // -------- STEP 1: scrape current key + call API directly --------
        val currentKey = getCurrentVidoraKey()
        DebugLog.log("key", "using key: $currentKey")

        val apiUrl = vidoraUrl(data)
        if (apiUrl != null) {
            try {
                val headers = apiBaseHeaders.toMutableMap()
                headers["x-player-key"] = currentKey
                val body = app.get(apiUrl, headers = headers, referer = "$mainUrl/").text
                DebugLog.log("vidora", "GET $apiUrl -> ${body.length}b: ${body.take(700)}")

                if (body.trimStart().startsWith("{")) {
                    val json = mapper.readTree(body)
                    if (json.get("result")?.asBoolean() == true) {
                        val sources = json.get("sources")
                        if (sources != null && sources.isArray) {
                            for (src in sources) {
                                val u = src.str("url") ?: continue
                                if (!u.contains(".m3u8")) continue
                                callback.invoke(
                                    newExtractorLink(
                                        source = name, name = name, url = u,
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
                        if (found) return true
                    } else {
                        val err = json.get("error")?.asText() ?: "unknown"
                        DebugLog.error("vidora", "auth failed ($err) with key=$currentKey")
                    }
                }
            } catch (e: Exception) {
                DebugLog.error("vidora", "API error", e)
            }
        }

        // -------- STEP 2: WebView hook (returns data via scriptCallback) --------
        DebugLog.log("webview", "=== WebView hook mode ===")

        // Inject currentKey into the probe
        val hookScript = hookScriptTemplate.replace("__PLAYER_KEY__", currentKey)

        try {
            val resolver = WebViewResolver(
                interceptUrl   = Regex("(?i)(\\.m3u8|/hls2/|workers\\.dev|/master|/cdn/)"),
                useOkhttp      = false,
                script         = autoPlayScript + "\n" + hookScript,
                scriptCallback = { result ->
                    if (!result.isNullOrBlank() && result.length > 4) {
                        DebugLog.log("hook", "state=$result")
                    }
                },
                timeout        = 30_000L
            )
            val resp = app.get(data, referer = "$mainUrl/", interceptor = resolver)
            val capturedUrl = resp.url
            DebugLog.log("webview", "captured: $capturedUrl")

            if (capturedUrl.contains(".m3u8")) {
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
                found = true
            }
        } catch (e: Exception) {
            DebugLog.log("webview", "error: ${e.message}")
        }

        if (!found) {
            DebugLog.error(
                "links",
                "No link. Check [key] log lines to see which key was used " +
                "and [hook] to see WebView-captured headers."
            )
        }
        return found
    }
}
