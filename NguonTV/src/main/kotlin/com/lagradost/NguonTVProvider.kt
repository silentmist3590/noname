package com.nguontv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder
import java.util.Locale

class NguonTVProvider : MainAPI() {
    override var mainUrl = "https://nguontv.life"
    override var name = "NguonTV"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "User-Agent" to ua,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

    // Cookie phiên của site. Site trả trang "NP Checker" (đặt cookie rồi tự chuyển về trang thật
    // sau 1 giây) cho client chưa có cookie, nên phải giữ cookie và gọi lại như trình duyệt.
    private var sessionCookies: Map<String, String> = emptyMap()

    private suspend fun fetch(url: String, extra: Map<String, String> = emptyMap()) = run {
        val h = headers + extra
        var r = app.get(url, headers = h, cookies = sessionCookies)
        var tries = 0
        while (tries < 2 && r.text.contains("NP Checker")) {
            sessionCookies = sessionCookies + r.cookies
            Thread.sleep(1100)
            r = app.get(url, headers = h, cookies = sessionCookies)
            tries++
        }
        if (r.cookies.isNotEmpty()) sessionCookies = sessionCookies + r.cookies
        r
    }

    private suspend fun postForm(url: String, data: Map<String, String>, extra: Map<String, String> = emptyMap()) = run {
        if (sessionCookies.isEmpty()) fetch("$mainUrl/")
        app.post(url, data = data, headers = headers + extra, cookies = sessionCookies)
    }

    private data class FilmItem(val title: String, val url: String, val poster: String?)

    private fun absolute(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "$mainUrl/${url.trimStart('/')}"
        }
    }

    private fun decodeHtml(value: String): String = Jsoup.parse(value).text().trim()

    /**
     * Lấy danh sách phim ở trang chủ NguonTV để hiển thị trong Home của CloudStream.
     * NguonTV hiện cung cấp các mục phim ngay trên trang chủ; chưa giả định URL phân trang
     * vì cấu trúc phân trang chưa được xác nhận từ HTML/HAR đã cung cấp.
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList(), hasNext = false)

        // Do not send the AJAX header on normal page requests.
        val res = try {
            fetch("$mainUrl/")
        } catch (e: Exception) {
            return debugHome("lỗi tải: ${e.javaClass.simpleName} ${e.message?.take(80)}")
        }
        val doc = res.document
        val items = doc.select(".categories-file-index .item-file-index, .item-file-index").mapNotNull { item ->
            val link = item.selectFirst(".info-item-file-index h3 a[href*='-f']")
                ?: item.selectFirst(".img-item-file-index a[href*='-f']")
                ?: item.selectFirst("a[href*='-f']")
                ?: return@mapNotNull null

            val href = link.attr("abs:href").ifBlank { absolute(link.attr("href")) ?: "" }
            if (href.isBlank() || !Regex("-f\\d+\\.html", RegexOption.IGNORE_CASE).containsMatchIn(href)) {
                return@mapNotNull null
            }

            val title = link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val image = item.selectFirst(".img-item-file-index img, img.hover-img")
            val poster = image?.let {
                absolute(it.attr("data-src").ifBlank { it.attr("data-original").ifBlank { it.attr("src") } })
            }
            FilmItem(title, href, poster)
        }.distinctBy { it.url }

        val responses = items.map { item ->
            newMovieSearchResponse(item.title, item.url, TvType.Movie) {
                posterUrl = item.poster
            }
        }

        if (responses.isEmpty()) {
            return debugHome("Bấm vào đây xem chẩn đoán v7 (code=${res.code} len=${res.text.length} title=${doc.title()})")
        }

        val homePage = mutableListOf<HomePageList>()
        if (responses.isNotEmpty()) homePage.add(HomePageList("Phim Hot / Phim mới", responses))
        // TẠM THỜI: thẻ chẩn đoán các bước lấy link phát
        homePage.add(
            HomePageList(
                "DEBUG",
                listOf(newMovieSearchResponse("Chẩn đoán phát v7", "$mainUrl/__debug", TvType.Movie))
            )
        )
        return newHomePageResponse(homePage, hasNext = false)
    }

    // Hiện thông tin chẩn đoán thành các thẻ phim ngay ở trang chủ
    private fun debugHome(vararg lines: String): HomePageResponse {
        val cards = lines.mapIndexed { i, t ->
            newMovieSearchResponse(t.replace("<", "‹").replace(">", "›"), "$mainUrl/__debug$i", TvType.Movie)
        }
        return newHomePageResponse(listOf(HomePageList("DEBUG NguonTV", cards)), hasNext = false)
    }

    private suspend fun searchItems(query: String): List<FilmItem> {
        if (query.isBlank()) return emptyList()

        val response = postForm(
            "$mainUrl/tim-kiem-a.html",
            mapOf("q" to query.trim(), "t" to "film"),
            mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "X-Requested-With" to "XMLHttpRequest"
            )
        ).text

        // Endpoint normally returns {"code":200,"html":"..."}.
        // Fall back to parsing raw HTML if a proxy/server returns the fragment directly.
        val html = try {
            val json = JSONObject(response)
            if (json.optInt("code", 200) != 200) return emptyList()
            json.optString("html", "")
        } catch (_: Exception) {
            response
        }
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parse(html, "$mainUrl/")
        // The HAR response contains <li class="result-item"><a href="/...-f123.html">.
        // Parse anchors directly instead of relying on a particular list-item class.
        val items = doc.select("a[href]").mapNotNull { link ->
            val rawHref = link.attr("href").trim()
            val href = link.attr("abs:href").ifBlank { absolute(rawHref) ?: "" }
            if (href.isBlank() ||
                !Regex("-f\\d+\\.html(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(href)
            ) return@mapNotNull null

            val title = link.attr("title").ifBlank { link.text().trim() }
            if (title.isBlank()) return@mapNotNull null

            val image = link.selectFirst("img") ?: link.parent()?.selectFirst("img")
            val poster = image?.let {
                val src = it.attr("data-src").ifBlank {
                    it.attr("data-original").ifBlank { it.attr("src") }
                }
                absolute(src)
            }
            FilmItem(title, href.substringBefore('#'), poster)
        }.distinctBy { it.url }

        return items
    }

    override suspend fun search(query: String): List<SearchResponse> = searchItems(query).map { item ->
        newMovieSearchResponse(item.title, item.url, TvType.Movie) {
            this.posterUrl = item.poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query) ?: emptyList()

    private fun parseTitle(doc: Document, fallback: String): String {
        return doc.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.title().substringBefore(" - NguồnTV").trim().ifBlank { fallback }
    }

    private fun episodeNumber(url: String): Int? = Regex("-(\\d+)-e\\d+\\.html", RegexOption.IGNORE_CASE)
        .find(url)?.groupValues?.getOrNull(1)?.toIntOrNull()

    // Trang chẩn đoán: chạy từng bước lấy link phát trên một phim thật và in kết quả
    private suspend fun debugLoad(): LoadResponse {
        fun esc(t: String) = t.replace("<", "‹").replace(">", "›").replace("\n", "<br>")
        fun head(t: String?, n: Int) = (t ?: "null").replace(Regex("\\s+"), " ").take(n)
        val sb = StringBuilder()
        try {
            val home = fetch("$mainUrl/")
            val filmUrl = home.document.select("a[href]").map { it.attr("abs:href") }
                .firstOrNull { Regex("-f\\d+\\.html", RegexOption.IGNORE_CASE).containsMatchIn(it) && !it.contains("/xem-phim/") }
            sb.append("[1] trang chủ code=${home.code} len=${home.text.length}\nphim thử=$filmUrl\n\n")
            if (filmUrl == null) throw Exception("không có phim để thử")

            val page = fetch(filmUrl)
            val pageDoc = page.document
            val epLinks = pageDoc.select("a[href]").mapNotNull { a ->
                val href = a.attr("abs:href")
                if (episodeNumber(href) != null) href else null
            }.distinct()
            val iframes = pageDoc.select("iframe[src]").map { it.attr("abs:src") }
            sb.append("[2] trang phim code=${page.code} len=${page.text.length} tập=${epLinks.size} iframe=${iframes.joinToString(" ; ").take(150)}\n")
            val playerPage = epLinks.firstOrNull() ?: toPlayerUrl(filmUrl)
            sb.append("playerPage=$playerPage\n")

            val state1 = extractPlayerState(pageDoc, filmUrl)
            sb.append("[3] state(trang phim)=${state1?.let { "fid=${it.fid} key=${it.key.take(20)}" } ?: "NULL"}\n")

            // Trang phát của tập: có thể chứa fid/key/embed thay vì trang chi tiết phim
            val pp = fetch(playerPage, mapOf("Referer" to filmUrl))
            val ppText = pp.text.replace("\\/", "/")
            val state2 = extractPlayerState(pp.document, playerPage)
            sb.append("[3b] GET trang phát code=${pp.code} len=${ppText.length}\n")
            sb.append("state=${state2?.let { "fid=${it.fid} time=${it.time} key=${it.key.take(20)}" } ?: "NULL"} embed=${findEmbedUrl(ppText)} hls=${findHls(ppText)}\n")
            val ifr = pp.document.select("iframe[src]").map { it.attr("abs:src") }
            sb.append("iframe=${ifr.joinToString(" ; ").take(200)}\n")
            val scripts = pp.document.select("script[src]").map { it.attr("abs:src") }.filter { it.contains("nguontv") }
            sb.append("script=${scripts.joinToString(" ; ").take(300)}\n")
            Regex("(?i)grab\\.nguontv|\\.m3u8|\\.post\\(|\\\$\\.ajax|fetch\\(|\\bkey\\b|\\bfid\\b|embed")
                .findAll(ppText).take(10).forEach {
                    val x = maxOf(0, it.range.first - 90)
                    val y = minOf(ppText.length, it.range.last + 130)
                    sb.append("> ").append(head(ppText.substring(x, y), 220)).append("\n")
                }
            sb.append("\n")
            val state = state2 ?: state1 ?: throw Exception("không tách được fid/time/key")

            val pr = postForm(
                playerPage,
                mapOf(
                    "fid" to state.fid, "time" to state.time, "key" to state.key,
                    "loadTime" to "0", "indexL" to "0", "indexSL" to "0"
                ),
                xhrHeaders(playerPage)
            )
            val prText = pr.text
            val embed = findEmbedUrl(prText)
            sb.append("[4] POST player code=${pr.code} len=${prText.length} embed=$embed\n")
            sb.append("đầu nội dung: ${head(prText, 500)}\n\n")
            if (embed == null) throw Exception("không có link embed")

            val eh = app.get(embed, headers = headers + ("Referer" to playerPage))
            val ehText = eh.text
            val hls = findHls(ehText)
            sb.append("[5] embed code=${eh.code} len=${ehText.length} hls=$hls\n")
            sb.append("đầu nội dung: ${head(ehText, 400)}\n")
        } catch (e: Exception) {
            sb.append("DỪNG: ${e.javaClass.simpleName}: ${e.message?.take(150)}\n")
        }
        return newMovieLoadResponse("DEBUG phát v7", "$mainUrl/__debug", TvType.Movie, "debug") {
            this.plot = esc(sb.toString())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("__debug")) return debugLoad()
        val pageUrl = absolute(url) ?: return null
        val doc = fetch(pageUrl).document
        val title = parseTitle(doc, doc.selectFirst("meta[property=og:title]")?.attr("content") ?: "NguonTV")
        val poster = absolute(
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".film-poster img, .movie-poster img, .detail-poster img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }
        )
        val description = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".film-content, .description, .movie-description")?.text()
        val episodeLinks = doc.select("a[href]").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { absolute(a.attr("href")) ?: "" }
            val num = episodeNumber(href) ?: return@mapNotNull null
            val label = a.text().trim().ifBlank { "Tập $num" }
            Triple(num, href, label)
        }.distinctBy { it.second }.sortedBy { it.first }

        val isSeries = episodeLinks.isNotEmpty() || doc.select(".list-episode, .episode-list, .episodes-list, [class*=episode]").isNotEmpty()
        if (isSeries && episodeLinks.isNotEmpty()) {
            val episodes = episodeLinks.map { (num, href, label) ->
                // Preserve both the public detail page (for its dynamic key) and the episode POST URL.
                newEpisode("$pageUrl||$href") {
                    name = label
                    season = 1
                    episode = num
                }
            }
            return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        // The site's film detail URL is /slug-f123.html; its player endpoint is /xem-phim/slug-f123.html.
        return newMovieLoadResponse(title, pageUrl, TvType.Movie, "$pageUrl||${toPlayerUrl(pageUrl)}") {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private fun toPlayerUrl(url: String): String {
        if (url.contains("/xem-phim/")) return url
        val path = url.substringAfter(mainUrl).trimStart('/')
        return "$mainUrl/xem-phim/$path"
    }

    private data class PlayerState(val fid: String, val time: String, val key: String)

    // Trang phát của tập có đoạn JS: npPhim = { key: 'xxx', ... } và
    // $.ajax({ data: { 'fid':74165, 'time':1792322811, 'key': this.getKey(), ... } })
    private fun extractPlayerState(doc: Document, pageUrl: String): PlayerState? {
        val source = doc.html()
        val fid = Regex("['\"]fid['\"]\\s*[:=]\\s*['\"]?(\\d+)", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
            ?: Regex("-f(\\d+)(?:-|\\.html)").find(pageUrl)?.groupValues?.get(1)
            ?: return null
        val time = Regex("['\"]time['\"]\\s*[:=]\\s*['\"]?(\\d{9,})", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
            ?: (System.currentTimeMillis() / 1000L).toString()
        // Chỉ nhận dạng  key: 'xxx'  (dấu hai chấm). Không nhận jwplayer.key="..." (khóa bản quyền JW Player).
        val key = Regex("(?<![.\\w])['\"]?key['\"]?\\s*:\\s*['\"]([A-Za-z0-9+/=_-]{4,})['\"]")
            .find(source)?.groupValues?.get(1)
            ?: return null
        return PlayerState(fid, time, key)
    }

    // Header giống lời gọi $.ajax của trang phát
    private fun xhrHeaders(referer: String) = mapOf(
        "X-Requested-With" to "XMLHttpRequest",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Referer" to referer
    )

    private fun findEmbedUrl(html: String): String? {
        val decoded = html.replace("\\/", "/").replace("\\u0026", "&")
        return Regex("https?://grab\\.nguontv\\.life/embed/[^\\\"'<>\\s]+", RegexOption.IGNORE_CASE)
            .find(decoded)?.value?.replace("&amp;", "&")
    }

    private fun findHls(html: String): String? {
        val decoded = html.replace("\\/", "/").replace("\\u0026", "&")
        return Regex("https?://[^\\\"'<>\\s]+\\.m3u8(?:\\?[^\\\"'<>\\s]*)?", RegexOption.IGNORE_CASE)
            .find(decoded)?.value?.replace("&amp;", "&")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("||", limit = 2)
        val basePage = absolute(parts.first()) ?: return false
        val playerPage = absolute(parts.getOrNull(1)) ?: toPlayerUrl(basePage)
        return try {
            val pageDoc = fetch(basePage).document
            val playerDoc = runCatching { fetch(playerPage, mapOf("Referer" to basePage)).document }.getOrNull()
            val state = playerDoc?.let { extractPlayerState(it, playerPage) }
                ?: extractPlayerState(pageDoc, basePage)
            if (state != null) {
                val playerResponse = postForm(
                    playerPage,
                    mapOf(
                        "fid" to state.fid,
                        "time" to state.time,
                        "key" to state.key,
                        "loadTime" to "0",
                        "indexL" to "0",
                        "indexSL" to "0"
                    ),
                    xhrHeaders(playerPage)
                ).text
                val embed = findEmbedUrl(playerResponse)
                if (embed != null) {
                    val embedHtml = app.get(embed, headers = headers + ("Referer" to playerPage)).text
                    val hls = findHls(embedHtml)
                    if (hls != null) {
                        callback(
                            newExtractorLink(name, "$name HLS", hls, ExtractorLinkType.M3U8) {
                                referer = embed
                                this.headers = headers
                                quality = Qualities.Unknown.value
                            }
                        )
                        return true
                    }
                    // Let CloudStream's common extractors handle the embed if the playlist is assembled by JavaScript.
                    loadExtractor(embed, playerPage, subtitleCallback, callback)
                    return true
                }
            }
            // Fallback: some film pages expose a player iframe directly in their HTML.
            val iframe = pageDoc.selectFirst("iframe[src*=grab.nguontv.life], iframe[src*=m3u8]")?.attr("abs:src")
            if (!iframe.isNullOrBlank()) {
                val iframeHtml = app.get(iframe, headers = headers + ("Referer" to playerPage)).text
                val hls = findHls(iframeHtml)
                if (hls != null) callback(
                    newExtractorLink(name, "$name HLS", hls, ExtractorLinkType.M3U8) {
                        referer = iframe
                        this.headers = headers
                        quality = Qualities.Unknown.value
                    }
                )
                else loadExtractor(iframe, playerPage, subtitleCallback, callback)
                return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
