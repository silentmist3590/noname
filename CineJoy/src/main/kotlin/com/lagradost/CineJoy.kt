package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

class CineJoyProvider : MainProvider() {
    override var mainUrl = "https://cinejoy.to" // Domain chính
    private val backupUrl = "https://cinejoy.net" // Domain dự phòng khi mainUrl gặp lỗi 2004
    
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
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = baseHeaders).document
        val title = document.selectFirst("h1.movie-title")?.text() ?: "Không rõ"
        val description = document.selectFirst("div.description")?.text()
        val poster = fixUrl(document.selectFirst("img.poster")?.attr("src"))
        
        val episodes = document.select("ul.list-episode li").mapIndexed { index, element ->
            val link = element.selectFirst("a")?.attr("href") ?: ""
            Episode(link, name = "Tập ${index + 1}")
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
        // Hàm nội bộ hỗ trợ gọi API và kiểm tra mã lỗi
        suspend fun fetchStream(targetUrl: String, serverName: String): Boolean {
            return try {
                val response = app.get(targetUrl, headers = baseHeaders)
                val apiResponse = response.parsedSafe<CineJoyResponse>()

                // Phát hiện lỗi 2004 từ API
                if (apiResponse?.code == 2004) {
                    return false
                }

                val streamUrl = apiResponse?.url ?: targetUrl
                if (streamUrl.isBlank()) return false

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = serverName,
                        url = streamUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.P1080.value,
                        isM3u8 = streamUrl.contains(".m3u8")
                    )
                )
                true
            } catch (e: Exception) {
                false
            }
        }

        // 1. Thử tải link từ Máy chủ chính (Main Server)
        var isSuccess = fetchStream(data, "CineJoy - Server chính")

        // 2. TỰ ĐỘNG CHUYỂN SANG SERVER DỰ PHÒNG nếu máy chủ chính bị lỗi 2004 hoặc không phản hồi
        if (!isSuccess) {
            val backupDataUrl = data.replace(mainUrl, backupUrl)
            isSuccess = fetchStream(backupDataUrl, "CineJoy - Server dự phòng")
        }

        return isSuccess
    }
}
