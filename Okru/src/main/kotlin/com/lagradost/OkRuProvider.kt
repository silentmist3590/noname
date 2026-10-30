package com.okru

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
    override val hasMainPage = true

    /**
     * Trang chủ của plugin — hiển thị danh sách video gợi ý để người dùng
     * có thể chọn OkRu làm nguồn cung cấp chính trên màn hình Home.
     *
     * Vì ok.ru không có API công khai cho "video mới nhất" mà không cần đăng nhập,
     * ta dùng trang tìm kiếm với một số từ khóa phổ biến để tạo các mục gợi ý.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val pages = mutableListOf<HomePageList>()

        // Một số query mặc định để tạo các mục trên trang chủ
        val defaultQueries = listOf(
            "phim" to "Phim",
            "nhạc" to "Nhạc",
            "hài" to "Hài",
            "trailer" to "Trailer"
        )

        for ((query, label) in defaultQueries) {
            try {
                val items = searchInternal(query)
                if (items.isNotEmpty()) {
                    pages.add(newHomePageList(label, items))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return if (pages.isEmpty()) null else newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return searchInternal(query)
    }

    /**
     * Hàm dùng chung cho cả search và getMainPage
     */
    private suspend fun searchInternal(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search/video?st.query=$encodedQuery"

        val document = try {
            app.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36",
                    "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                )
            ).document
        } catch (e: Exception) {
            e.printStackTrace()
            return results
        }

        // Selector cho kết quả tìm kiếm video của ok.ru
        // Nếu ok.ru thay đổi giao diện, cần cập nhật các selector này
        val cards = document.select("div.video-card, div.video-card_v2, a.video-card_lk, div[data-video]")

        cards.forEach { element ->
            val linkEl = if (element.tagName() == "a") element
                         else element.selectFirst("a.video-card_lk, a[href*=/video/]")
                         ?: element.selectFirst("a")

            val href = linkEl?.attr("href") ?: return@forEach
            if (!href.contains("/video/")) return@forEach

            val title = linkEl.text().trim().ifEmpty {
                element.selectFirst("div.video-card_n, div.video-card__title, span.video-card_n")?.text()?.trim()
                    ?: return@forEach
            }

            val poster = element.selectFirst("img")?.attr("src")
                ?: element.selectFirst("img")?.attr("data-src")

            results.add(
                newMovieSearchResponse(title, fixUrl(href)) {
                    this.posterUrl = poster
                }
            )
        }

        // Loại bỏ trùng lặp theo URL
        return results.distinctBy { it.url }
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

        val response = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to "https://ok.ru/videoembed/$videoId",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                )
            ).text
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

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
