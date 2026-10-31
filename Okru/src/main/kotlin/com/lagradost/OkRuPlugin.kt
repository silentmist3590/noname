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
    // TRANG CHỦ & TÌM KIẾM (Giữ nguyên)
    // =====================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val queries = listOf("phim" to "Phim", "nhạc" to "Nhạc", "hài" to "Hài", "trailer" to "Trailer", "hoạt hình" to "Hoạt hình")
        val pages = mutableListOf<HomePageList>()
        for ((query, label) in queries) {
            try {
                val results = searchInternal(query, page = 1)
                if (results.isNotEmpty()) pages.add(HomePageList(label, results))
            } catch (e: Exception) { e.printStackTrace() }
        }
        return if (pages.isEmpty()) null else newHomePageResponse(pages)
    }

    override suspend fun search(query: String): List<SearchResponse>? = searchInternal(query, page = 1)

    private suspend fun searchInternal(query: String, page: Int = 1): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrls = listOf(
            "$mainUrl/search/video?st.query=$encoded&st.page=$page",
            "$mainUrl/search?st.query=$encoded&st.mode=Video&st.page=$page"
        )
        var document: org.jsoup.nodes.Document? = null
        for (url in searchUrls) {
            try {
                val doc = app.get(url, headers = mapOf("User-Agent" to userAgent, "Accept-Language" to "vi,en;q=0.9,ru;q=0.8")).document
                if (doc.select("a[href*=/video/]").isNotEmpty()) { document = doc; break }
            } catch (e: Exception) { e.printStackTrace() }
        }
        if (document == null) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val seenUrls = mutableSetOf<String>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.attr("href").trim()
            val match = Regex("""^/video/(\d+)""").find(href) ?: return@forEach
            val videoId = match.groupValues[1]
            if (!seenUrls.add(videoId)) return@forEach
            val title = anchor.attr("data-video-title").trim().ifEmpty { anchor.attr("title").trim() }.ifEmpty { anchor.text().trim() }.ifEmpty { anchor.selectFirst("div.video-card_n, span.video-card_n")?.text()?.trim() ?: "" }
            if (title.isBlank()) return@forEach
            val poster = anchor.selectFirst("img")?.let { img -> img.attr("src").ifEmpty { img.attr("data-src") } }?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            results.add(newMovieSearchResponse(title, "$mainUrl/video/$videoId", TvType.Movie) { this.posterUrl = poster })
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
            val embedUrl = "$mainUrl/videoembed/$videoId"
            val document = app.get(embedUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)).document
            val playerJson = extractPlayerJson(document)
            if (playerJson != null) {
                val movie = playerJson.optJSONObject("flashvars")?.optString("metadata")?.let { JSONObject(it) }?.optJSONObject("movie")
                title = movie?.optString("title")?.ifBlank { null } ?: title
                poster = movie?.optString("poster")?.ifBlank { null }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) { this.posterUrl = poster }
    }

    // =====================================================================
    // LẤY LINK STREAM (Phương pháp mới: data-options)
    // =====================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = if (data.matches(Regex("""\d+"""))) data else extractVideoId(data)
        if (videoId.isNullOrBlank()) return false

        val embedUrl = "$mainUrl/videoembed/$videoId"

        try {
            // BƯỚC 1: Fetch trang embed
            val document = app.get(
                embedUrl,
                headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl, "Accept-Language" to "vi,en;q=0.9,ru;q=0.8")
            ).document

            // BƯỚC 2: Trích xuất JSON từ thuộc tính data-options
            val playerJson = extractPlayerJson(document) ?: run {
                android.util.Log.e("OkRuDebug", "Could not find data-options JSON")
                return false
            }

            // BƯỚC 3: Lấy metadata từ flashvars
            val flashvars = playerJson.optJSONObject("flashvars")
            val metadataStr = flashvars?.optString("metadata")
            if (metadataStr.isNullOrBlank()) {
                android.util.Log.e("OkRuDebug", "No flashvars.metadata found")
                return false
            }
            val metadata = JSONObject(metadataStr)

            var success = false

            // CÁCH 1: videos array
            val videos = metadata.optJSONArray("videos")
            if (videos != null && videos.length() > 0) {
                for (i in 0 until videos.length()) {
                    val v = videos.optJSONObject(i) ?: continue
                    val videoUrl = v.optString("url").ifBlank { null } ?: continue
                    val label = v.optString("name", "")
                    val quality = mapQuality(label)
                    val type = if (videoUrl.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    callback(newExtractorLink(name, name, videoUrl, type) { this.quality = quality; this.referer = embedUrl })
                    success = true
                }
            }

            // CÁCH 2: HLS Manifest
            if (!success) {
                val hlsUrl = metadata.optString("hlsManifestUrl").ifBlank { null }
                    ?: metadata.optString("ondemandHls").ifBlank { null }
                if (!hlsUrl.isNullOrBlank()) {
                    callback(newExtractorLink(name, name, hlsUrl, ExtractorLinkType.M3U8) { this.referer = embedUrl; this.quality = Qualities.Unknown.value })
                    success = true
                }
            }

            return success
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "Error in loadLinks", e)
        }
        return false
    }

    // =====================================================================
    // HELPERS
    // =====================================================================

    /**
     * Tìm thẻ div có thuộc tính data-options chứa videoId và parse JSON từ đó.
     */
    private fun extractPlayerJson(document: org.jsoup.nodes.Document): JSONObject? {
        // Thử tìm trong tất cả các thẻ div có data-options
        document.select("div[data-options]").forEach { div ->
            val dataOptions = div.attr("data-options")
            if (dataOptions.contains("flashvars") && dataOptions.contains("metadata")) {
                try {
                    // data-options có thể chứa HTML entities, cần unescape
                    val unescaped = org.jsoup.parser.Parser.unescapeEntities(dataOptions, true)
                    return JSONObject(unescaped)
                } catch (e: Exception) {
                    android.util.Log.e("OkRuDebug", "Failed to parse data-options: ${e.message}")
                }
            }
        }
        // Fallback: thử tìm trong script tag (có thể có OKVideo.start({...}))
        document.select("script").forEach { script ->
            val data = script.data()
            val match = Regex("""OKVideo\.start\((\{.+?\})\)""").find(data) ?: return@forEach
            try {
                return JSONObject(match.groupValues[1])
            } catch (e: Exception) { /* ignore */ }
        }
        return null
    }

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

    private fun extractVideoId(url: String): String? = Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)
}
