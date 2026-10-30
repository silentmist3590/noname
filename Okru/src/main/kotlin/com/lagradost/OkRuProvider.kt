package com.lagradost.okru

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject

class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OkRu"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasQuickSearch = true

    override suspend fun search(query: String): List<SearchResponse>? {
        val searchUrl = "$mainUrl/search/video?st.query=$query"
        val document = app.get(searchUrl).document
        val results = mutableListOf<SearchResponse>()

        document.select("div.video-card, div[data-video]").forEach { element ->
            val title = element.selectFirst("a.video-card_lk, a[data-video]")?.text()
                ?: element.selectFirst("div.video-card_n")?.text()
                ?: return@forEach
            val href = element.selectFirst("a.video-card_lk, a[data-video]")?.attr("href")
                ?: return@forEach
            if (!href.contains("/video/")) return@forEach
            val poster = element.selectFirst("img")?.attr("src")

            results.add(
                newMovieSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = poster
                }
            )
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = extractVideoId(url) ?: return null
        return newMovieLoadResponse("Video $videoId", url, TvType.Movie, url) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = extractVideoId(data) ?: return false
        val apiUrl = "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId"

        val response = app.get(
            apiUrl,
            headers = mapOf(
                "Referer" to "https://ok.ru/videoembed/$videoId",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )
        ).text

        val jsonString = extractJsonFromResponse(response) ?: return false

        try {
            val json = JSONObject(jsonString)
            val videos = json.optJSONArray("videos") ?: return false

            for (i in 0 until videos.length()) {
                val video = videos.getJSONObject(i)
                val videoUrl = video.optString("url")
                val videoName = video.optString("name")

                if (videoUrl.isNullOrEmpty()) continue

                val quality = when {
                    videoName.contains("mobile", true) -> Qualities.Unknown.value
                    videoName.contains("lowest", true) -> Qualities.P240.value
                    videoName.contains("low", true) -> Qualities.P360.value
                    videoName.contains("sd", true) -> Qualities.P480.value
                    videoName.contains("hd", true) -> Qualities.P720.value
                    videoName.contains("full", true) -> Qualities.P1080.value
                    else -> Qualities.Unknown.value
                }

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = "https://ok.ru/videoembed/$videoId"
                    }
                )
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun extractVideoId(url: String): String? {
        return Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)
    }

    private fun extractJsonFromResponse(response: String): String? {
        val start = response.indexOf('{')
        val end = response.lastIndexOf('}')
        if (start == -1 || end == -1 || start >= end) return null
        return response.substring(start, end + 1)
    }
}
