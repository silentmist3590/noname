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
        "Accept-Language" to "vi-VN,vi;q=0.9,en;q=0.8",
        "Origin" to mainUrl,
        "Referer" to "$mainUrl/"
    )

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

    private suspend fun searchItems(query: String): List<FilmItem> {
        val response = app.post(
            "$mainUrl/tim-kiem-a.html",
            data = mapOf("q" to query, "t" to "film"),
            headers = headers
        ).text
        val json = JSONObject(response)
        val html = json.optString("html", "")
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, mainUrl)
        return doc.select(".result-item a[href], a[href*='-f']").mapNotNull { a ->
            val href = a.attr("abs:href").ifBlank { absolute(a.attr("href")) ?: "" }
            if (!href.contains(Regex("-f\\d+\\.html"))) return@mapNotNull null
            val title = a.attr("title").ifBlank { a.text().trim() }
            if (title.isBlank()) return@mapNotNull null
            val img = a.selectFirst("img")
            val poster = absolute(img?.let { it.attr("data-src").ifBlank { it.attr("src") } })
            FilmItem(title, href, poster)
        }.distinctBy { it.url }
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

    override suspend fun load(url: String): LoadResponse? {
        val pageUrl = absolute(url) ?: return null
        val doc = app.get(pageUrl, headers = headers).document
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

    private fun extractPlayerState(doc: Document, pageUrl: String): PlayerState? {
        val source = doc.html()
        val fid = Regex("(?:fid|film_id|filmId)\\s*[:=]\\s*['\"]?(\\d+)", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
            ?: Regex("-f(\\d+)\\.html").find(pageUrl)?.groupValues?.get(1)
            ?: return null
        val time = Regex("(?:time|loadTime)\\s*[:=]\\s*['\"]?(\\d{9,})", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
            ?: (System.currentTimeMillis() / 1000L).toString()
        val key = Regex("(?:key|film_key)\\s*[:=]\\s*['\"]([A-Za-z0-9+/=_-]{4,})['\"]", RegexOption.IGNORE_CASE)
            .find(source)?.groupValues?.get(1)
            ?: Regex("['\"]key['\"]\\s*:\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
                .find(source)?.groupValues?.get(1)
            ?: return null
        return PlayerState(fid, time, key)
    }

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
            val pageDoc = app.get(basePage, headers = headers).document
            val state = extractPlayerState(pageDoc, basePage)
            if (state != null) {
                val playerResponse = app.post(
                    playerPage,
                    data = mapOf(
                        "fid" to state.fid,
                        "time" to state.time,
                        "key" to state.key,
                        "loadTime" to "0",
                        "indexL" to "0",
                        "indexSL" to "0"
                    ),
                    headers = headers
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
