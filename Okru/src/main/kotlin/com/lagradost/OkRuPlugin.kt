package com.lagradost

import android.content.Context
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

@CloudstreamPlugin
class OkRuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OkRuProvider())
    }
}

class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OK.ru"
    override var lang = "vi" // Mặc định tiếng Việt
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    private val mapper = ObjectMapper()

    override val mainPage = mainPageOf(
        "https://ok.ru/video/top" to "Video Thịnh Hành",
        "https://ok.ru/video/my" to "Video Mới"
    )

    // =========================================================================
    // TRÍCH XUẤT TRANG CHỦ
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}?st.mode=Movie&page=$page"
        val document = app.get(url).document

        val items = document.select("div.video-card, div.video-card__wrapper").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val href = aTag.attr("href").takeIf { it.contains("/video/") } ?: return null
        val title = this.selectFirst(".video-card_n, .video-card__title")?.text() ?: return null
        val poster = this.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() } ?: 
                     this.selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    // =========================================================================
    // TÌM KIẾM
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // Sử dụng API tìm kiếm của OK.ru
        val url = "https://ok.ru/dk?st.cmd=searchResult&st.mode=Movie&st.query=$encodedQuery"
        val document = app.get(url).document

        val results = document.select("div.video-card, div.video-card__wrapper, .search-result-card").mapNotNull { it.toSearchResult() }
        
        // Nếu selector trang chủ không khớp với trang search, thử selector khác
        if (results.isEmpty()) {
            return document.select("div[data-module='VideoCard']").mapNotNull { it.toSearchResult() }
        }
        return results
    }

    // =========================================================================
    // LOAD CHI TIẾT PHIM
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: document.selectFirst("title")?.text()?.replace(" | OK.RU", "")
            ?: "Unknown Video"

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        
        var description: String? = null
        var duration = 0

        // Trích xuất duration và mô tả từ data-options (chứa JSON của player)
        val dataOptions = document.selectFirst("div[data-module='OKVideo']")?.attr("data-options")
        if (dataOptions != null) {
            try {
                // Decode HTML entities (&quot; -> ")
                val decodedJson = dataOptions.replace("&quot;", "\"")
                val node = mapper.readTree(decodedJson)
                val movie = node.get("flashvars")?.get("metadata")?.get("movie")
                
                if (movie != null) {
                    duration = movie.get("duration")?.asInt() ?: 0
                    description = movie.get("description")?.asText()
                }
            } catch (e: Exception) {
                // Bỏ qua lỗi parse JSON, vẫn tiếp tục lấy link stream
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
            this.duration = duration
        }
    }

    // =========================================================================
    // LOAD LINKS (Phát video)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // 1. Tìm thẻ div chứa cấu hình player
        val playerDiv = document.selectFirst("div[data-module='OKVideo']") 
        val dataOptions = playerDiv?.attr("data-options")

        if (dataOptions.isNullOrBlank()) {
            return false
        }

        var found = false
        try {
            // 2. Giải mã HTML Entities (&quot;) để lấy JSON chuẩn
            val decodedJson = dataOptions.replace("&quot;", "\"")
            val rootNode = mapper.readTree(decodedJson)
            val flashvars = rootNode.get("flashvars")

            if (flashvars != null) {
                // 3. Ưu tiên lấy link HLS (m3u8)
                val hlsUrl = flashvars.get("hlsManifestUrl")?.asText()
                if (!hlsUrl.isNullOrBlank()) {
                    val cleanHlsUrl = hlsUrl.replace("\\u0026", "&") // Xử lý escape của JSON
                    callback.invoke(
                        newExtractorLink(
                            source = "OK.ru",
                            name = "OK.ru HLS",
                            url = cleanHlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://ok.ru/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    found = true
                }

                // 4. Dự phòng: Lấy danh sách link MP4 (nếu không có HLS)
                val videosArray = flashvars.get("videos")
                if (videosArray != null && videosArray.isArray) {
                    for (videoNode in videosArray) {
                        val name = videoNode.get("name")?.asText() ?: "unknown"
                        val url = videoNode.get("url")?.asText() ?: continue
                        val cleanUrl = url.replace("\\u0026", "&")

                        val quality = when (name.lowercase()) {
                            "mobile" -> Qualities.P144.value
                            "lowest" -> Qualities.P240.value
                            "low" -> Qualities.P360.value
                            "sd" -> Qualities.P480.value
                            "hd" -> Qualities.P720.value
                            "full" -> Qualities.P1080.value
                            else -> Qualities.Unknown.value
                        }

                        callback.invoke(
                            newExtractorLink(
                                source = "OK.ru",
                                name = "OK.ru $name",
                                url = cleanUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://ok.ru/"
                                this.quality = quality
                            }
                        )
                        found = true
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return found
    }
}
