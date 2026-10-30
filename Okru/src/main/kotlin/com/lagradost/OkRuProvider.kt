package com.okru

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URLEncoder

class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OkRu"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasQuickSearch = true
    override val hasMainPage = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // =====================================================================
    // TRANG CHỦ
    // =====================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val queries = listOf(
            "phim" to "Phim",
            "nhạc" to "Nhạc",
            "hài" to "Hài",
            "trailer" to "Trailer",
            "hoạt hình" to "Hoạt hình"
        )

        val pages = mutableListOf<HomePageList>()

        for ((query, label) in queries) {
            try {
                val results = searchInternal(query, page = 1)
                if (results.isNotEmpty()) {
                    pages.add(HomePageList(label, results))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return if (pages.isEmpty()) null else newHomePageResponse(pages)
    }

    // =====================================================================
    // TÌM KIẾM
    // =====================================================================

    override suspend fun search(query: String): List<SearchResponse>? {
        return searchInternal(query, page = 1)
    }

    /**
     * Hàm tìm kiếm nội bộ, được dùng chung cho cả search() và getMainPage().
     * Thử nhiều URL để tăng khả năng thành công khi ok.ru thay đổi cấu trúc.
     */
    private suspend fun searchInternal(query: String, page: Int = 1): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        val searchUrls = listOf(
            "$mainUrl/search/video?st.query=$encoded&st.page=$page",
            "$mainUrl/search?st.query=$encoded&st.mode=Video&st.page=$page",
            "$mainUrl/video/search?st.query=$encoded&st.page=$page"
        )

        var document: org.jsoup.nodes.Document? = null
        for (url in searchUrls) {
            try {
                val doc = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                ).document
                if (doc.select("a[href*=/video/]").isNotEmpty()) {
                    document = doc
                    break
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (document == null) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()

        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val match = Regex("""^/video/(\d+)""").find(href) ?: return@forEach
            val videoId = match.groupValues[1]

            if (!seenUrls.add(videoId)) return@forEach

            val title = anchor.attr("data-video-title").trim().ifEmpty {
                anchor.attr("title").trim()
            }.ifEmpty {
                anchor.text().trim()
            }.ifEmpty {
                anchor.selectFirst("div.video-card_n, span.video-card_n, div.video-card__title")
                    ?.text()?.trim() ?: ""
            }

            if (title.isBlank()) return@forEach

            val poster = anchor.selectFirst("img")?.let { img ->
                img.attr("src").ifEmpty { img.attr("data-src") }
            }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }

            val fullUrl = "$mainUrl/video/$videoId"

            results.add(
                newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }

        return results
    }

    // =====================================================================
    // LOAD CHI TIẾT
    // =====================================================================

    override suspend fun load(url: String): LoadResponse? {
        val videoId = extractVideoId(url) ?: return null

        var title = "Video $videoId"
        var poster: String? = null

        try {
            val apiUrl = "$mainUrl/dk?cmd=videoPlayerMetadata&mid=$videoId"
            val raw = app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/videoembed/$videoId",
                    "User-Agent" to userAgent
                )
            ).text

            val jsonStr = extractJson(raw)
            if (jsonStr != null) {
                val json = JSONObject(jsonStr)
                val meta = json.optJSONObject("movie")
                title = meta?.optString("title")?.ifBlank { null } ?: title
                poster = meta?.optString("poster")?.ifBlank { null }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
        }
    }

    // =====================================================================
    // LẤY LINK STREAM — dùng videoPlayerMetadata (unofficial)
    // =====================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = if (data.matches(Regex("""\d+"""))) data else extractVideoId(data)
        if (videoId.isNullOrBlank()) return false

        val apiUrl = "$mainUrl/dk?cmd=videoPlayerMetadata&mid=$videoId"

        val rawResponse = try {
            app.get(
                apiUrl,
                headers = mapOf(
                    "Referer" to "$mainUrl/videoembed/$videoId",
                    "User-Agent" to userAgent,
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "X-Requested-With" to "XMLHttpRequest"
                )
            ).text
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        val jsonString = extractJson(rawResponse) ?: return false

        try {
            val json = JSONObject(jsonString)
            val videos = json.optJSONArray("videos")

            if (videos == null || videos.length() == 0) {
                val hls = json.optString("hls").ifBlank { null }
                if (!hls.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = hls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/videoembed/$videoId"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
                return false
            }

            for (i in 0 until videos.length()) {
                val v = videos.optJSONObject(i) ?: continue
                val url = v.optString("url").ifBlank { null } ?: continue
                val label = v.optString("name", "")

                val quality = mapQuality(label)
                val type = if (url.contains(".m3u8", ignoreCase = true))
                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url,
                        type = type
                    ) {
                        this.quality = quality
                        this.referer = "$mainUrl/videoembed/$videoId"
                    }
                )
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    private fun mapQuality(label: String): Int = when {
        label.contains("mobile", true) -> Qualities.P144.value
        label.contains("lowest", true) -> Qualities.P240.value
        label.equals("low", true) -> Qualities.P360.value
        label.contains("sd", true) -> Qualities.P480.value
        label.contains("hd", true) -> Qualities.P720.value
        label.contains("full", true) -> Qualities.P1080.value
        label.contains("quad", true) || label.contains("4k", true) -> Qualities.P2160.value
        else -> Qualities.Unknown.value
    }

    private fun extractVideoId(url: String): String? {
        return Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)
    }

    private fun extractJson(response: String): String? {
        val trimmed = response.trim()

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed
        }

        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start == -1 || end == -1 || start >= end) return null

        val candidate = trimmed.substring(start, end + 1)

        return try {
            JSONObject(candidate)
            candidate
        } catch (e: Exception) {
            null
        }
    }
}
