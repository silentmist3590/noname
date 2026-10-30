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
                    // SỬA LỖI TẠI ĐÂY: Dùng HomePageList thay vì newHomePageList
                    pages.add(HomePageList(label, results))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return if (pages.isEmpty()) null else newHomePageResponse(pages)
    }

    // ... (phần còn lại của file giữ nguyên như phiên bản trước)
    // Bao gồm: search, searchInternal, load, loadLinks, mapQuality,
    // extractVideoId, extractJson
}
