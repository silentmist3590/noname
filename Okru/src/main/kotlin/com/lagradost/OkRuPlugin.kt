package com.okru

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLEncoder

// =====================================================================
// ENTRY POINT — CloudStream tìm lớp này qua annotation
// =====================================================================
@CloudstreamPlugin
class OkRuPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(OkRuProvider())
    }
}

// =====================================================================
// PROVIDER — Toàn bộ logic
// =====================================================================
class OkRuProvider : MainAPI() {
    override var mainUrl = "https://ok.ru"
    override var name = "OkRu"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi"
    override val hasQuickSearch = true
    override val hasMainPage = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // =================================================================
    // TRANG CHỦ
    // =================================================================
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
                val results = searchInternal(query, 1)
                if (results.isNotEmpty()) pages.add(HomePageList(label, results))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return if (pages.isEmpty()) null else newHomePageResponse(pages)
    }

    // =================================================================
    // TÌM KIẾM
    // =================================================================
    override suspend fun search(query: String): List<SearchResponse>? =
        searchInternal(query, 1)

    private suspend fun searchInternal(query: String, page: Int): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val urls = listOf(
            "$mainUrl/search/video?st.query=$encoded&st.page=$page",
            "$mainUrl/search?st.query=$encoded&st.mode=Video&st.page=$page"
        )
        var doc: Document? = null
        for (url in urls) {
            try {
                val d = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                    )
                ).document
                if (d.select("a[href*=/video/]").isNotEmpty()) { doc = d; break }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        if (doc == null) return emptyList()

        val results = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()
        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").trim()
            val m = Regex("""^/video/(\d+)""").find(href) ?: return@forEach
            val id = m.groupValues[1]
            if (!seen.add(id)) return@forEach
            val title = a.attr("data-video-title").trim()
                .ifEmpty { a.attr("title").trim() }
                .ifEmpty { a.text().trim() }
                .ifEmpty { a.selectFirst("div.video-card_n, span.video-card_n")?.text()?.trim() ?: "" }
            if (title.isBlank()) return@forEach
            val poster = a.selectFirst("img")
                ?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
                ?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            results.add(
                newMovieSearchResponse(title, "$mainUrl/video/$id", TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }
        return results
    }

    // =================================================================
    // LOAD CHI TIẾT — Dùng og: meta tag (nhẹ, không cần parse data-options)
    // =================================================================
    override suspend fun load(url: String): LoadResponse? {
        val videoId = extractVideoId(url) ?: return null
        var title = "Video $videoId"
        var poster: String? = null
        try {
            val doc = app.get(
                "$mainUrl/video/$videoId",
                headers = mapOf("User-Agent" to userAgent, "Referer" to mainUrl)
            ).document
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }?.let { poster = it }
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "load failed: ${e.message}")
        }
        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
        }
    }

    // =================================================================
    // LẤY LINK STREAM — HLS là chính, MP4 là fallback
    // =================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = if (data.matches(Regex("""\d+"""))) data else extractVideoId(data)
        if (videoId.isNullOrBlank()) return false
        val referer = "$mainUrl/video/$videoId"

        try {
            // Tải HTML thô (không parse) để regex được chính xác
            val html = app.get(
                referer,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "vi,en;q=0.9,ru;q=0.8"
                )
            ).text

            val doc = Jsoup.parse(html)
            val (metadata, hlsFromRegex) = extractMetadata(doc, html)
            android.util.Log.d("OkRuDebug",
                "metadata=${metadata != null}, hlsFromRegex=${hlsFromRegex != null}")

            var success = false

            // === 1. HLS (ưu tiên tuyệt đối — đã kiểm chứng HTTP 200 với 6 chất lượng) ===
            val hlsUrl = metadata?.optString("hlsManifestUrl")?.takeIf { it.isNotBlank() }
                ?: hlsFromRegex
            if (!hlsUrl.isNullOrBlank()) {
                callback(
                    newExtractorLink(name, "HLS (tự động)", hlsUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.referer = referer
                    }
                )
                success = true
                android.util.Log.d("OkRuDebug", "Emitted HLS: ${hlsUrl.take(80)}...")
            }

            // === 2. MP4 theo chất lượng (fallback nếu HLS fail hoặc user muốn tải) ===
            metadata?.optJSONArray("videos")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i) ?: continue
                    val url = v.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val label = v.optString("name", "")
                    callback(
                        newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
                            this.quality = mapQuality(label)
                            this.referer = referer
                        }
                    )
                    success = true
                }
            }

            if (!success) android.util.Log.e("OkRuDebug", "No playable links found")
            return success
        } catch (e: Exception) {
            android.util.Log.e("OkRuDebug", "loadLinks failed", e)
        }
        return false
    }

    // =================================================================
    // TRÍCH XUẤT METADATA — 3 phương pháp độc lập
    // =================================================================
    private fun extractMetadata(doc: Document, html: String): Pair<JSONObject?, String?> {

        // === Phương pháp 1: data-options JSON (chính xác nhất) ===
        for (el in doc.select("[data-options]")) {
            val raw = el.attr("data-options")
            if (!raw.contains("flashvars") && !raw.contains("metadata")) continue
            try {
                val unescaped = Parser.unescapeEntities(raw, true)
                val outer = JSONObject(unescaped)
                val metaStr = outer.optJSONObject("flashvars")
                    ?.optString("metadata")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val metadata = JSONObject(metaStr)
                val hls = metadata.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
                android.util.Log.d("OkRuDebug", "Method 1 (data-options) OK")
                return Pair(metadata, hls)
            } catch (e: Exception) {
                android.util.Log.e("OkRuDebug", "Method 1 failed: ${e.message}")
            }
        }

        // === Phương pháp 2: script tag OKVideo.start({...}) ===
        for (script in doc.select("script")) {
            val data = script.data()
            val m = Regex("""OKVideo\.start\((\{.+?\})\)""").find(data) ?: continue
            try {
                val outer = JSONObject(m.groupValues[1])
                val metaStr = outer.optJSONObject("flashvars")
                    ?.optString("metadata")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val metadata = JSONObject(metaStr)
                val hls = metadata.optString("hlsManifestUrl").takeIf { it.isNotBlank() }
                android.util.Log.d("OkRuDebug", "Method 2 (OKVideo.start) OK")
                return Pair(metadata, hls)
            } catch (e: Exception) {
                // ignore
            }
        }

        // === Phương pháp 3: regex trực tiếp trên HTML đã unescape ===
        // Đây là phương pháp dự phòng cuối cùng nếu JSON parse thất bại.
        try {
            val unescaped = Parser.unescapeEntities(html, true)
            val m = Regex("""https://[^"'\s<>]+\.m3u8[^"'\s<>]*""").find(unescaped)
            if (m != null) {
                android.util.Log.d("OkRuDebug", "Method 3 (regex m3u8) OK")
                return Pair(null, m.value)
            }
        } catch (e: Exception) {
            // ignore
        }

        android.util.Log.e("OkRuDebug", "All extraction methods failed")
        return Pair(null, null)
    }

    // =================================================================
    // HELPERS
    // =================================================================
    private fun mapQuality(label: String): Int = when {
        label.equals("mobile", true) -> Qualities.P144.value
        label.equals("lowest", true) -> Qualities.P240.value
        label.equals("low", true) -> Qualities.P360.value
        label.equals("sd", true) -> Qualities.P480.value
        label.equals("hd", true) -> Qualities.P720.value
        label.equals("full", true) -> Qualities.P1080.value
        else -> Qualities.Unknown.value
    }

    private fun extractVideoId(url: String): String? =
        Regex("""/video/(\d+)""").find(url)?.groupValues?.get(1)
}
