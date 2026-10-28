package com.lagradost

import com.lagradost.extractors.NxshaExtractor
import com.lagradost.extractors.VidLoveExtractor
import com.lagradost.extractors.VidRockExtractor
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray

class CineHDProvider : MainAPI() {
    override var mainUrl = "https://cinehd.vc"
    override var name = "CineHD"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    companion object {
        const val TMDB_API = "https://api.themoviedb.org/3"
        const val TMDB_KEY = "439c478a771f35c05022f9feabcca01c"
        const val TMDB_IMG = "https://image.tmdb.org/t/p"

        val DEFAULT_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        )
    }

    override val mainPage = mainPageOf(
        "trending/movie/week" to "Trending Movies",
        "trending/tv/week" to "Trending TV Shows",
        "movie/now_playing" to "In Theaters",
        "movie/top_rated" to "Top Rated Movies"
    )

    private fun getPoster(path: String?, size: String = "w500"): String {
        return if (!path.isNullOrBlank()) "$TMDB_IMG/$size$path" else ""
    }

    private fun getYear(dateStr: String?): Int? {
        return dateStr?.takeIf { it.length >= 4 }?.substring(0, 4)?.toIntOrNull()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$TMDB_API/${request.data}?api_key=$TMDB_KEY&page=$page"
        val response = app.get(url, headers = DEFAULT_HEADERS).text
        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: JSONArray()
        
        val items = ArrayList<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optInt("id")
            if (id <= 0) continue

            val isTv = request.data.contains("/tv") || item.has("first_air_date") || item.has("name")
            val title = item.optString("title").ifEmpty { item.optString("name") }
            val posterPath = item.optString("poster_path")
            val releaseDate = item.optString("release_date").ifEmpty { item.optString("first_air_date") }

            val dataPayload = JSONObject().apply {
                put("id", id)
                put("type", if (isTv) "tv" else "movie")
                put("title", title)
            }.toString()

            if (isTv) {
                items.add(
                    newTvSeriesSearchResponse(title, dataPayload, TvType.TvSeries) {
                        this.posterUrl = getPoster(posterPath)
                        this.year = getYear(releaseDate)
                    }
                )
            } else {
                items.add(
                    newMovieSearchResponse(title, dataPayload, TvType.Movie) {
                        this.posterUrl = getPoster(posterPath)
                        this.year = getYear(releaseDate)
                    }
                )
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$TMDB_API/search/multi?api_key=$TMDB_KEY&query=${query.trim()}&page=1&include_adult=false"
        val response = app.get(url, headers = DEFAULT_HEADERS).text
        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: JSONArray()

        val list = ArrayList<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = item.optInt("id")
            if (id <= 0) continue

            val isTv = mediaType == "tv"
            val title = item.optString("title").ifEmpty { item.optString("name") }
            val posterPath = item.optString("poster_path")
            val releaseDate = item.optString("release_date").ifEmpty { item.optString("first_air_date") }

            val dataPayload = JSONObject().apply {
                put("id", id)
                put("type", mediaType)
                put("title", title)
            }.toString()

            if (isTv) {
                list.add(
                    newTvSeriesSearchResponse(title, dataPayload, TvType.TvSeries) {
                        this.posterUrl = getPoster(posterPath)
                        this.year = getYear(releaseDate)
                    }
                )
            } else {
                list.add(
                    newMovieSearchResponse(title, dataPayload, TvType.Movie) {
                        this.posterUrl = getPoster(posterPath)
                        this.year = getYear(releaseDate)
                    }
                )
            }
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        val parsed = JSONObject(url)
        val id = parsed.getInt("id")
        val type = parsed.getString("type")

        if (type == "movie") {
            val res = app.get("$TMDB_API/movie/$id?api_key=$TMDB_KEY&append_to_response=credits,videos", headers = DEFAULT_HEADERS).text
            val d = JSONObject(res)

            val title = d.optString("title", "Unknown")
            val posterUrl = getPoster(d.optString("poster_path"))
            val backdropUrl = getPoster(d.optString("backdrop_path"), "w780")
            val plot = d.optString("overview")
            val year = getYear(d.optString("release_date"))
            val voteAverage = d.optDouble("vote_average", 0.0)
            val duration = d.optInt("runtime", 0)

            val tags = ArrayList<String>()
            val genres = d.optJSONArray("genres")
            if (genres != null) {
                for (i in 0 until genres.length()) {
                    genres.optJSONObject(i)?.optString("name")?.let { tags.add(it) }
                }
            }

            val streamData = JSONObject().apply {
                put("id", id)
                put("type", "movie")
                put("title", title)
            }.toString()

            return newMovieLoadResponse(title, url, TvType.Movie, streamData) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.score = Score.from10(voteAverage)
                this.duration = duration
                this.tags = tags
            }
        } else {
            val res = app.get("$TMDB_API/tv/$id?api_key=$TMDB_KEY&append_to_response=credits,videos", headers = DEFAULT_HEADERS).text
            val d = JSONObject(res)

            val title = d.optString("name", "Unknown")
            val posterUrl = getPoster(d.optString("poster_path"))
            val backdropUrl = getPoster(d.optString("backdrop_path"), "w780")
            val plot = d.optString("overview")
            val year = getYear(d.optString("first_air_date"))
            val voteAverage = d.optDouble("vote_average", 0.0)

            val tags = ArrayList<String>()
            val genres = d.optJSONArray("genres")
            if (genres != null) {
                for (i in 0 until genres.length()) {
                    genres.optJSONObject(i)?.optString("name")?.let { tags.add(it) }
                }
            }

            val seasons = d.optJSONArray("seasons") ?: JSONArray()
            val episodes = ArrayList<Episode>()

            for (i in 0 until seasons.length()) {
                val sObj = seasons.optJSONObject(i) ?: continue
                val sNum = sObj.optInt("season_number", 0)
                val epCount = sObj.optInt("episode_count", 0)
                if (sNum <= 0 || epCount <= 0) continue

                val sRes = try {
                    app.get("$TMDB_API/tv/$id/season/$sNum?api_key=$TMDB_KEY", headers = DEFAULT_HEADERS).text
                } catch (e: Exception) {
                    null
                } ?: continue

                val sJson = JSONObject(sRes)
                val epArray = sJson.optJSONArray("episodes") ?: continue
                for (j in 0 until epArray.length()) {
                    val epObj = epArray.optJSONObject(j) ?: continue
                    val epNum = epObj.optInt("episode_number", j + 1)
                    val epName = epObj.optString("name").ifEmpty { "Episode $epNum" }
                    val epStill = epObj.optString("still_path")
                    val epPlot = epObj.optString("overview")

                    val epData = JSONObject().apply {
                        put("id", id)
                        put("type", "tv")
                        put("s", sNum)
                        put("e", epNum)
                        put("title", title)
                    }.toString()

                    episodes.add(
                        newEpisode(epData) {
                            this.name = epName
                            this.season = sNum
                            this.episode = epNum
                            this.posterUrl = getPoster(epStill)
                            this.description = epPlot
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.backgroundPosterUrl = backdropUrl
                this.plot = plot
                this.year = year
                this.score = Score.from10(voteAverage)
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val payload = JSONObject(data)
        val tmdbId = payload.getInt("id")
        val isTv = payload.optString("type") == "tv"
        val season = if (isTv) payload.optInt("s", 1) else 0
        val episode = if (isTv) payload.optInt("e", 1) else 0

        try {
            VidRockExtractor.extract(tmdbId, isTv, season, episode, callback, subtitleCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            NxshaExtractor.extract(tmdbId, isTv, season, episode, callback, subtitleCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            VidLoveExtractor.extract(tmdbId, isTv, season, episode, callback, subtitleCallback)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }
}
