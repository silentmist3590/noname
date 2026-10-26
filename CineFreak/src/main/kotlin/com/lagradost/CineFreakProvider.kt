package com.cinefreak

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.Jsoup
import java.net.URLEncoder

class CineFreakProvider : MainAPI() {
    override var mainUrl = "https://cinefreak.ch"
    override var name = "CineFreak"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    override val mainPage = mainPageOf(
        "Latest Releases" to "$mainUrl/",
        "Hindi Movies" to "$mainUrl/hindi-movies/",
        "Hindi Dubbed" to "$mainUrl/hindi-dubbed-movies/",
        "English Movies" to "$mainUrl/english-movies/",
        "Dual Audio" to "$mainUrl/dual-audio/",
        "K-Drama" to "$mainUrl/k-drama/",
        "Web Series" to "$mainUrl/web-series/"
    )

    private fun absolute(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> mainUrl + url
        else -> "$mainUrl/$url"
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s+"), " ").trim()
        .replace(Regex("\\s*[|–-]\\s*CineFreak.*$", RegexOption.IGNORE_CASE), "")

    private fun isSeries(text: String, url: String = ""): Boolean =
        Regex("web.?series|series|season|episode|k-drama|tv show|anime", RegexOption.IGNORE_CASE)
            .containsMatchIn("$text $url")

    private val jsonMapper = jacksonObjectMapper()
    private inline fun <reified T> parseJsonOrNull(text: String): T? =
        runCatching { jsonMapper.readValue<T>(text) }.getOrNull()

    private val indexApi = "https://search.yagaverse.net/api/search"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private fun indexUrl(slug: String): String {
        val clean = slug.trim().trim('/')
        return if (clean.startsWith("http://") || clean.startsWith("https://")) clean
        else "$mainUrl/$clean/"
    }

    private fun indexItems(json: IndexJson?): List<SearchResponse> = json?.hits.orEmpty().mapNotNull { hit ->
        val item = hit.document ?: return@mapNotNull null
        val slug = item.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val title = cleanTitle(item.title.orEmpty()).ifBlank { slug.substringAfterLast('/').replace('-', ' ') }
        val url = indexUrl(slug)
        val poster = item.thumb?.let(::absolute)
        val cats = item.cats.orEmpty().joinToString(" ")
        if (isSeries("$title $cats", slug)) newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
        else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
    }.distinctBy { it.url }

    private suspend fun searchSiteApi(query: String, page: Int = 1): List<SearchResponse> {
        val headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json", "Referer" to "$mainUrl/fast-search/")
        val key = try {
            parseJsonOrNull<SearchKeyJson>(app.post("$mainUrl/search-api.php", headers = headers + mapOf("Origin" to mainUrl),
                data = mapOf("st" to "1")).text)?.k.orEmpty()
        } catch (_: Exception) { "" }
        val result = app.get(
            "$mainUrl/search-api.php?q=${URLEncoder.encode(query, "UTF-8")}&pg=$page",
            headers = headers + mapOf("X-CF-ST" to key)
        ).text.let { parseJsonOrNull<SearchJson>(it) }
        return result?.results.orEmpty().mapNotNull { item ->
            val slug = item.l?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = cleanTitle(item.t.orEmpty()).ifBlank { slug.substringAfterLast('/').replace('-', ' ') }
            val url = indexUrl(slug)
            val poster = item.i?.takeIf { it.isNotBlank() }?.let(::absolute)
            if (isSeries("$title ${item.c.orEmpty()}", slug)) newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster }
            else newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster }
        }.distinctBy { it.url }
    }

    private suspend fun indexSearch(query: String, page: Int = 1): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = app.get("$indexApi?q=$q&pg=$page", headers = mapOf(
            "User-Agent" to userAgent, "Accept" to "application/json", "Origin" to mainUrl, "Referer" to "$mainUrl/"
        )).text.let { parseJsonOrNull<IndexJson>(it) }
        return indexItems(json)
    }

    private fun cards(html: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, mainUrl)
        val out = LinkedHashMap<String, SearchResponse>()
        val candidates = doc.select("article, .post, .movie-item, .item, .post-item, .latest-release, .result-item")
        for (card in candidates) {
            val a = card.selectFirst("a[href]") ?: continue
            val href = absolute(a.attr("href"))
            if (!href.startsWith(mainUrl) || href == mainUrl || href.contains("/category/") || href.contains("/tag/")) continue
            val titleNode = card.selectFirst("h1,h2,h3,h4,.title,.entry-title,[title]")
            val title = cleanTitle(titleNode?.text()?.takeIf { it.isNotBlank() } ?: a.attr("title").ifBlank { a.text() })
            if (title.length < 2) continue
            val poster = card.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            }?.let(::absolute)
            val tv = isSeries(title, href)
            out.putIfAbsent(href, if (tv) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
                else newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster })
        }
        // Fallback for themes where cards are plain links with images.
        if (out.isEmpty()) {
            for (a in doc.select("a[href]:has(img)")) {
                val href = absolute(a.attr("href"))
                if (!href.startsWith(mainUrl) || href == mainUrl || href.contains("/category/") || href.contains("/tag/")) continue
                val title = cleanTitle(a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }.ifBlank { a.text() })
                if (title.length < 2) continue
                val poster = a.selectFirst("img")?.let { it.attr("data-src").ifBlank { it.attr("src") } }?.let(::absolute)
                val response = if (isSeries(title, href)) newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
                    else newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
                out.putIfAbsent(href, response)
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.let { base -> if (page <= 1) base else base.trimEnd('/') + "/page/$page/" }
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
        var items = cards(response.text)
        if (items.isEmpty()) {
            items = try {
                val indexed = indexSearch("*", page)
                if (request.name.equals("Latest Releases", true)) indexed
                else indexed.filter { item ->
                    val title = item.name.lowercase()
                    val category = request.name.lowercase()
                    when {
                        category.contains("hindi dubbed") -> title.contains("hindi dubbed") || title.contains("hindi")
                        category.contains("hindi") -> title.contains("hindi")
                        category.contains("english") -> title.contains("english")
                        category.contains("dual") -> title.contains("dual audio") || title.contains("dual")
                        category.contains("k-drama") -> title.contains("k-drama") || title.contains("korean")
                        category.contains("series") -> isSeries(item.name, item.url)
                        else -> true
                    }
                }
            } catch (_: Exception) { emptyList() }
        }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && (response.text.contains("rel=\"next\"", true) || response.text.contains("/page/${page + 1}/") || items.size >= 20))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        try { searchSiteApi(query).takeIf { it.isNotEmpty() }?.let { return it } } catch (_: Exception) { }
        try { indexSearch(query).takeIf { it.isNotEmpty() }?.let { return it } } catch (_: Exception) { }
        val html = app.get("$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}", headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")).text
        return cards(html)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = mapOf("Referer" to "$mainUrl/"))
        val doc = Jsoup.parse(response.text, url)
        val title = cleanTitle(doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?: doc.selectFirst("h1.entry-title, h1, .post-title")?.text().orEmpty())
        if (title.isBlank()) return null
        val poster = doc.selectFirst("article img[src], .post img[src], .entry-content img[src], img[src*=image.tmdb.org], img[src*=cineimg.xyz]")?.let {
            it.absUrl("src").ifBlank { it.absUrl("data-src") }.ifBlank { it.attr("data-src").let(::absolute) }
        } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { !it.contains("admin-ajax") }?.let(::absolute)
        val description = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".entry-content, .description, .summary")?.text()
        val year = Regex("\\b(19\\d{2}|20\\d{2})\\b").find(title)?.value?.toIntOrNull()
        val tv = isSeries(title, url) || doc.select(".cft-it[data-k]").isNotEmpty()
        val episodes = mutableListOf<Episode>()

        if (tv) {
            val rows = doc.select(".cft-it[data-k]")
            for (row in rows) {
                val key = row.attr("data-k")
                val match = Regex("[ep]s(\\d+)e(\\d+)(?:-(\\d+))?", RegexOption.IGNORE_CASE).find(key) ?: continue
                val season = match.groupValues[1].toIntOrNull() ?: 1
                val ep = match.groupValues[2].toIntOrNull() ?: continue
                val epName = row.selectFirst(".cft-info b")?.text()?.ifBlank { null } ?: "Episode $ep"
                val links = row.select("a[href]").map { it.absUrl("href") }.filter { it.isNotBlank() && it.matches(Regex("https?://.*", RegexOption.IGNORE_CASE)) }
                episodes.add(newEpisode("${epName}|||${links.joinToString("|")}") {
                    name = epName
                    this.season = season
                    episode = ep
                })
            }
        }
        if (episodes.isEmpty()) {
            val links = doc.select("a[href]").map { a -> a.absUrl("href") to a.text().trim() }
                .filter { (href, text) -> href.isNotBlank() && (text.contains("watch", true) || text.contains("instant download", true) || text.contains("download", true) || Regex("cinecloud|neodrive", RegexOption.IGNORE_CASE).containsMatchIn(href)) }
                .distinctBy { it.first }
            val packed = links.joinToString("|") { it.first }
            episodes.add(newEpisode("$title|||$packed") { name = title; season = 1; episode = 1 })
        }

        return if (tv) newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            plot = description
            this.year = year
        } else newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
            posterUrl = poster
            plot = description
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|||", limit = 2)
        val rawLinks = parts.getOrNull(1).orEmpty().split('|').filter { it.startsWith("http://") || it.startsWith("https://") }
        var found = false
        for ((index, link) in rawLinks.withIndex()) {
            try {
                if (Regex("\\.(m3u8|mp4|webm)(?:[?#].*)?$", RegexOption.IGNORE_CASE).containsMatchIn(link)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "CineFreak ${index + 1}",
                            url = link,
                            type = if (link.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Regex("(?i)(2160|4k)").find(link)?.let { Qualities.P2160.value }
                                ?: Regex("(?i)1080").find(link)?.let { Qualities.P1080.value }
                                ?: Regex("(?i)720").find(link)?.let { Qualities.P720.value }
                                ?: Regex("(?i)480").find(link)?.let { Qualities.P480.value }
                                ?: Qualities.Unknown.value
                            this.headers = mapOf("Referer" to "$mainUrl/")
                        }
                    )
                    found = true
                } else {
                    // Delegate supported third-party hosts to CloudStream's normal extractor registry.
                    loadExtractor(link, mainUrl, subtitleCallback, callback)
                    found = true
                }
            } catch (_: Exception) { /* try the remaining links */ }
        }
        return found
    }

    private data class SearchKeyJson(val k: String? = null)
    private data class SearchJson(val results: List<SearchItem> = emptyList(), val renew: Boolean? = null)
    private data class SearchItem(val t: String? = null, val l: String? = null, val i: String? = null, val c: String? = null)
    private data class IndexJson(val hits: List<IndexHit> = emptyList(), val found: Int? = null)
    private data class IndexHit(val document: IndexDocument? = null)
    private data class IndexDocument(val title: String? = null, val slug: String? = null, val thumb: String? = null, val cats: List<String> = emptyList())
}
