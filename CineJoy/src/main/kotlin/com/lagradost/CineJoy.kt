package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

class CineJoy : MainAPI() {
    override var mainUrl = "https://cinejoy.to"
    private val backupUrl = "https://cinejoy.net"
    
    override var name = "CineJoy"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasMainPage = true

    data class CineJoyResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("message") val message: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("status") val status: Boolean?
    )

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/home?page=$page", headers = baseHeaders).document
        val home = document.select("div.item-movie").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.title")?.text() ?: return null
        
        // Kiểm tra phần tử thẻ <a> không null để lấy href dạng String non-null
        val aTag = this.selectFirst("a") ?: return null
        val href = fixUrl(aTag.attr("href"))
        
        // Xử lý poster an toàn không bị lỗi String?
        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() } ?: imgTag?.attr("src") ?: ""
        val posterUrl = if (rawPoster.isNotBlank()) fixUrl(rawPoster) else null
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document
        val title = document.selectFirst("h1.movie-title")?.text() ?: "Không rõ"
        val description = document.selectFirst("div.description")?.text()
        
        // Xử lý poster trang chi tiết an toàn
        val posterElement = document.selectFirst("img.poster")
        val posterSrc = posterElement?.attr("src") ?: ""
        val poster = if (posterSrc.isNotBlank()) fixUrl(posterSrc) else null
        
        val episodes = document.select("ul.list-episode li").mapIndexed { index, element ->
            val link = element.selectFirst("a")?.attr("href") ?: ""
            newEpisode(link) {
                this.name = "Tập ${index + 1}"
                this.episode = index + 1
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, episodes) {
            this.plot = description
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        suspend fun fetchStream(targetUrl: String, serverName: String): Boolean {
            return try {
                val response = app.get(targetUrl, headers = baseHeaders)
                val apiResponse = response.parsedSafe<CineJoyResponse>()

                if (apiResponse?.code == 2004) {
                    return false
                }

                val streamUrl = apiResponse?.url ?: targetUrl
                if (streamUrl.isBlank()) return false

                callback.invoke(
                    newExtractorLink(
                        source = serverName,
                        name = serverName,
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.P1080.value
                    }
                )
                true
            } catch (e: Exception) {
                false
            }
        }

        var isSuccess = fetchStream(data, "CineJoy - Server chính")

        if (!isSuccess) {
            val backupDataUrl = data.replace(mainUrl, backupUrl)
            isSuccess = fetchStream(backupDataUrl, "CineJoy - Server dự phòng")
        }

        return isSuccess
    }
}
