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
 * - Phát: moviesapi.to chỉ có trang embed (iframe) -> dùng WebViewResolver để bắt link .m3u8
 *     movie: https://moviesapi.to/movie/{id}
 *     tv   : https://moviesapi.to/tv/{id}/{season}/{episode}
 *   {id} = TMDB id hoặc IMDb id (tt1234567)
 */
class MoviesApiProvider : MainAPI() {
    override var mainUrl = "https://moviesapi.to"
    override var name = "MoviesAPI"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper()
    private val tmdbImg = "https://image.tmdb.org/t/p"

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
        mapper.readTree(
            app.get(url, headers = mapOf("Accept" to "application/json"), referer = referer).text
        )
    } catch (e: Exception) {
        e.printStackTrace()
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
        val poster = img(str("poster", "poster_path", "poster_url", "image", "cover"))
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
        val list = getJson(url)?.items().orEmpty().mapNotNull { it.toSearch(isTv) }
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
        return eps.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    override suspend fun load(url: String): LoadResponse? {
        val m = Regex("""/(movie|tv)/([^/?#]+)""").find(url) ?: return null
        val isTv = m.groupValues[1] == "tv"
        val rawId = m.groupValues[2]
        // Hỗ trợ cả dạng hyphen /tv/66732-5-3 -> lấy phần id
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

        val title = node?.str("title", "name", "original_title") ?: id
        val poster = img(node?.str("poster", "poster_path", "poster_url", "image", "cover"))
        val backdrop = img(node?.str("backdrop", "backdrop_path", "banner"), "w1280")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data = trang embed. Mở trong WebView ẩn và bắt request .m3u8 do player gọi.
        val resolver = WebViewResolver(
            interceptUrl = Regex("""\.m3u8"""),
            useOkhttp = false,
            timeout = 30_000L
        )
        val stream = app.get(data, referer = "$mainUrl/", interceptor = resolver).url
        if (!stream.contains(".m3u8")) return false

        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = stream,
            referer = data
        ).forEach(callback)
        return true
    }
}
