package com.lagradost

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

/**
 * Plugin cho https://moviesapi.to
 *
 * - Danh sách/tìm kiếm: /api/discover/{movies|tv|views}
 * - Phát: trang embed gọi /api/vidora/v1/movie/{id}  (xem logcat) -> thử gọi thẳng API này,
 *   nếu không ra link thì mở trang embed bằng WebView, tự bấm play và bắt request .m3u8.
 *
 * QUAN TRỌNG: API /api/vidora/v1/* BẮT BUỘC phải có header `x-player-key`
 * (xem bundle JS của moviesapi.to). Thiếu header này API trả `result: false`,
 * `sources: []` → không có m3u8. M3u8 CDN cũng cần `Origin` + `Referer`.
 */
class MoviesApiProvider : MainAPI() {
    override var mainUrl = "https://moviesapi.to"
    override var name = "MoviesAPI"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val imgExt = Regex("""\.(jpe?g|png|webp|avif)(\?.*)?$""", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- headers

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36 Edg/156.0.0.0"

    /** Key bắt buộc cho API vidora (trích từ bundle JS của moviesapi.to). */
    private val vidoraKey = "e716f19ab4d25edc5247239a8f3494f8"

    /** Headers cho request JSON (API discover + API vidora). */
    private val apiHeaders = mapOf(
        "User-Agent"      to userAgent,
        "Accept"          to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin"          to mainUrl,
        "Referer"         to "$mainUrl/",
        "x-player-key"    to vidoraKey,
    )

    /** Headers cho CDN (workers.dev) — m3u8 + segment. */
    private val streamHeaders = mapOf(
        "User-Agent"      to userAgent,
        "Accept"          to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Origin"          to mainUrl,
        "Referer"         to "$mainUrl/",
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/discover/views?type=movie&views=day" to "Trending Movies (Today)",
        "$mainUrl/api/discover/views?type=tv&views=day" to "Trending TV (Today)",
        "$mainUrl/api/discover/views?type=movie&views=week" to "Trending Movies (Week)",
        "$mainUrl/api/discover/movies?ordering=created_at&direction=desc" to "Newly Added Movies",
        "$mainUrl/api/discover/tv?ordering=last_upload_date&direction=desc" to "Recently Updated TV",
        "$mainUrl/api/discover/movies?ordering=views&direction=desc" to "Most Viewed Movies",
        "$mainUrl/api/discover/tv?ordering=views&direction=desc" to "Most Viewed TV",
    )

    // ------------------------------------------------------------------ helpers

    private suspend fun getJson(url: String, referer: String? = "$mainUrl/"): JsonNode? = try {
        val text = app.get(
            url,
            headers = mapOf(
                "Accept"       to "application/json, text/plain, */*",
                "User-Agent"   to userAgent,
                "Origin"       to mainUrl,
                "x-player-key" to vidoraKey,
            ),
            referer = referer
        ).text
        DebugLog.log("http", "GET $url -> ${text.length}b: ${text.take(500)}")
        mapper.readTree(text)
    } catch (e: Exception) {
        DebugLog.error("http", "GET $url thất bại", e)
        null
    }

    /** Lấy mảng item từ nhiều dạng response: [..] | {data:[..]} | {data:{data:[..]}} | {results:[..]} */
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

    /**
     * Tự dò ảnh trong JSON mà không cần biết tên field:
     * ưu tiên key chứa "poster"/"thumb"/"cover"/"image", chấp nhận giá trị có đuôi ảnh.
     * Với backdrop thì ưu tiên key chứa "backdrop"/"banner"/"background".
     */
    private fun JsonNode.findImage(backdrop: Boolean = false, depth: Int = 0): String? {
        if (!isObject || depth > 2) return null
        fun isBack(k: String) = k.contains("backdrop") || k.contains("banner") || k.contains("background")
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
        // chế độ poster: không thấy key rõ ràng -> lấy bất kỳ chuỗi nào có đuôi ảnh (trừ backdrop)
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

    private fun JsonNode.toSearch(isTv: Boolean): SearchResponse? {
        val id = mediaId() ?: return null
        val title = str("title", "name", "original_title") ?: return null
        val poster = img(findImage())
        val year = (str("year") ?: str("release_date", "first_air_date", "released"))
            ?.take(4)?.toIntOrNull()
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

    // ------------------------------------------------------------------ main page / search

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep = if (request.data.contains("?")) "&" else "?"
        val url = "${request.data}${sep}page=$page&resultsPerPage=30"
        val isTv = request.data.contains("/discover/tv") || request.data.contains("type=tv")
        val raw = getJson(url)?.items().orEmpty()
        val list = raw.mapNotNull { it.toSearch(isTv) }

        if (raw.isEmpty()) {
            DebugLog.error("main", "Không đọc được danh sách từ $url")
        } else if (list.isEmpty()) {
            DebugLog.error("main", "Có ${raw.size} item nhưng thiếu tmdb_id/imdb_id. Item đầu: ${raw.first()}")
        } else if (list.all { it.posterUrl == null }) {
            DebugLog.error("main", "Không tìm thấy poster. Item đầu: ${raw.first()}")
        }
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 3) return emptyList() // API yêu cầu tối thiểu 3 ký tự
        val q = URLEncoder.encode(query, "UTF-8")
        val movies = getJson("$mainUrl/api/discover/movies?query=$q&resultsPerPage=30")
            ?.items().orEmpty().mapNotNull { it.toSearch(false) }
        val shows = getJson("$mainUrl/api/discover/tv?query=$q&resultsPerPage=30")
            ?.items().orEmpty().mapNotNull { it.toSearch(true) }
        return movies + shows
    }

    // ------------------------------------------------------------------ load

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

        // 1) Nếu API trả sẵn "seasons"
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

        // 2) Fallback: Cinemeta (Stremio) — cần IMDb id
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
        if (eps.isEmpty()) DebugLog.error("load", "Không dựng được danh sách tập cho id=$id (imdb=$imdbId)")
        return eps.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun load(url: String): LoadResponse? {
        val m = Regex("""/(movie|tv)/([^/?#]+)""").find(url) ?: return null
        val isTv = m.groupValues[1] == "tv"
        val rawId = m.groupValues[2]
        val id = if (isTv) rawId.substringBefore("-") else rawId
        val isImdb = id.startsWith("tt")

        // Tên tham số theo tài liệu: movies -> imdb_id / tmdbid ; tv -> imdbid / tmdbid
        val param = when {
            isImdb && isTv -> "imdbid"
            isImdb -> "imdb_id"
            else -> "tmdbid"
        }
        val endpoint = if (isTv) "tv" else "movies"
        val node = getJson("$mainUrl/api/discover/$endpoint?$param=$id")?.items()?.firstOrNull()
        if (node == null) DebugLog.error("load", "Không có dữ liệu chi tiết cho $endpoint $param=$id")

        val title = node?.str("title", "name", "original_title") ?: id
        val poster = img(node?.findImage())
        val backdrop = img(node?.findImage(backdrop = true), "w1280")
        val plot = node?.str("overview", "plot", "description", "synopsis")
        val year = node?.let { (it.str("year") ?: it.str("release_date", "first_air_date"))?.take(4)?.toIntOrNull() }
        val imdbId = node?.str("imdb_id", "imdbid", "imdbId") ?: id.takeIf { isImdb }
        val tagList = node?.get("genres")?.let { g ->
            if (g.isArray) g.mapNotNull { if (it.isTextual) it.asText() else it.str("name", "title") }
            else g.asText().split(",").map { it.trim() }.filter { it.isNotEmpty() }
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

    // ------------------------------------------------------------------ links

    /** Trang embed -> endpoint API mà player của moviesapi.to gọi (thấy trong logcat). */
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

    // Tự bấm play (trong WebView không có thao tác người dùng nên player không tự chạy)
    private val autoPlayScript = """
        (function(){
          var n = 0;
          var t = setInterval(function(){
            n++;
            try {
              var p = document.querySelector('media-player');
              if (p && p.play) { p.muted = true; p.play(); }
              var v = document.querySelector('video');
              if (v) { v.muted = true; var r = v.play(); if (r && r.catch) r.catch(function(){}); }
              var b = document.querySelector('media-play-button, [aria-label="Play"], [data-play]');
              if (b && n % 3 == 0) b.click();
            } catch (e) {}
            if (n > 25) clearInterval(t);
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

        // ------------------------------------------------------------------
        // 1) Gọi thẳng API vidora — PHẢI có x-player-key
        // ------------------------------------------------------------------
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
                    DebugLog.error(
                        "vidora",
                        "result != true (kiểm tra x-player-key). Body: ${body.take(300)}"
                    )
                }

                val sources = json.get("sources")
                if (sources != null && sources.isArray) {
                    for (src in sources) {
                        val url = src.str("url") ?: continue
                        if (!url.contains(".m3u8")) continue

                        // Xác minh m3u8 fetch được bằng headers đầy đủ
                        val ok = try {
                            val probe = app.get(
                                url,
                                headers = streamHeaders,
                                referer = "$mainUrl/"
                            ).text
                            probe.trimStart().startsWith("#EXTM3U")
                        } catch (e: Exception) {
                            DebugLog.error("m3u8-probe", "probe fail: ${e.message}")
                            false
                        }

                        if (ok) {
                            // Thử M3u8Helper để tách các quality (nếu hỗ trợ headers)
                            val links = try {
                                M3u8Helper.generateM3u8(
                                    source = name,
                                    streamUrl = url,
                                    referer = "$mainUrl/"
                                )
                            } catch (_: Exception) { emptyList() }

                            if (links.isNotEmpty()) {
                                links.forEach { callback(it) }
                            } else {
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
                            }
                            found = true
                        } else {
                            DebugLog.error("m3u8-probe", "m3u8 không fetch được với headers hiện tại")
                        }
                    }
                }

                // ---- Phụ đề đính kèm (sources[0].tracks) ----
                val tracks = sources?.firstOrNull()?.get("tracks")
                if (tracks != null && tracks.isArray) {
                    for (t in tracks) {
                        val fileNode = t.get("file")
                        if (fileNode == null || fileNode.isNull) continue
                        val file  = fileNode.asText()
                        val label = t.str("label") ?: "Unknown"
                        val lang  = t.str("language") ?: ""
                        subtitleCallback.invoke(newSubtitleFile(lang, file, label))
                    }
                }
            } catch (e: Exception) {
                DebugLog.error("vidora", "API error", e)
            }
        }

        if (found) return true

        // ------------------------------------------------------------------
        // 2) Fallback: WebView + tự bấm play, bắt request .m3u8
        // ------------------------------------------------------------------
        try {
            val resolver = WebViewResolver(
                interceptUrl   = Regex(""".m3u8"""),
                useOkhttp      = false,
                script         = autoPlayScript,
                scriptCallback = { },
                timeout        = 25_000L
            )
            val stream = app.get(data, referer = "$mainUrl/", interceptor = resolver).url
            DebugLog.log("webview", "kết quả: $stream")
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
            DebugLog.log("webview", "lỗi: $e")
        }

        if (!found) {
            DebugLog.error("links", "Không tìm thấy link phát cho $data")
        }
        return found
    }
}
