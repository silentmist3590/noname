package com.cinefreak

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URI

class CineFreakProvider : MainAPI() {
    override var mainUrl = "https://cinefreak.ch"
    override var name = "CineFreak"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)
    // CloudStream mainPageOf expects URL first, category name second.
    // If the order is reversed, request.data becomes text such as "Hindi Movies",
    // which is not a valid URL and triggers "Expected URL scheme http or https".
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/hindi-movies/" to "Hindi Movies",
        "$mainUrl/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/english-movies/" to "English Movies",
        "$mainUrl/dual-audio/" to "Dual Audio",
        "$mainUrl/k-drama/" to "K-Drama",
        "$mainUrl/web-series/" to "Web Series"
    )

    /** Convert a site-provided URL to an absolute HTTP(S) URL, rejecting empty/invalid values. */
    private fun absolute(url: String?): String {
        val raw = url?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val candidate = when {
            raw.startsWith("//") -> "https:$raw"
            raw.matches(Regex("(?i)^https?://.*")) -> raw
            else -> runCatching { URI(mainUrl + "/").resolve(URI(raw)).toString() }.getOrDefault("")
        }
        return candidate.takeIf {
            runCatching {
                val uri = URI(it)
                (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }.orEmpty()
    }

    private fun safePageUrl(raw: String?, page: Int): String? {
        // Do not resolve arbitrary text like "Hindi Movies" into a URL. MainPageRequest.data
        // must already be an absolute HTTP(S) URL supplied by mainPageOf(url to name).
        val base = raw?.trim().orEmpty()
        if (!base.matches(Regex("(?i)^https?://[^\\s]+$"))) return null
        val valid = runCatching {
            val uri = URI(base)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
        if (!valid) return null
        val pageUrl = if (page <= 1) base else base.trimEnd('/') + "/page/$page/"
        return pageUrl.takeIf { it.matches(Regex("(?i)^https?://[^\\s]+$")) }
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
        val poster = item.thumb?.let(::absolute)?.takeIf { it.isNotBlank() }
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
            val poster = item.i?.takeIf { it.isNotBlank() }?.let(::absolute)?.takeIf { it.isNotBlank() }
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
            if (href.isBlank() || !href.startsWith("http", true)) continue
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
                if (href.isBlank() || !href.startsWith("http", true)) continue
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
        val url = safePageUrl(request.data, page)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
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
                val links = extractCloudLinks(row.outerHtml(), url)
                episodes.add(newEpisode("${epName}|||${links.joinToString("|")}|||$url") {
                    name = epName
                    this.season = season
                    episode = ep
                })
            }
        }
        if (episodes.isEmpty()) {
            // SkyStream finds CineCloud/NeoDrive buttons by URL pattern, not only button text.
            val links = extractCloudLinks(response.text, url)
            val packed = links.joinToString("|")
            episodes.add(newEpisode("$title|||$packed|||$url") { name = title; season = 1; episode = 1 })
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

    /** Return the CineCloud/NeoDrive file-page URLs used by CineFreak's current player. */
    private fun extractCloudLinks(html: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(html, pageUrl)
        // The site includes hidden decoy links; don't use those as playback sources.
        doc.select("div[aria-hidden=true]").remove()
        return doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { absolute(a.attr("href")) }
            val label = (a.text() + " " + a.attr("aria-label") + " " + a.className()).lowercase()
            val cloudHost = runCatching { java.net.URI(href).host.orEmpty() }.getOrDefault("")
            val cloudPath = runCatching { java.net.URI(href).path.orEmpty() }.getOrDefault("")
            if ((cloudHost.contains("cinecloud") || cloudHost.contains("neodrive")) &&
                Regex("/(f|x|w|d|gp)/[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE).containsMatchIn(cloudPath) &&
                !Regex("telegram|facebook|whatsapp|tutorial|privacy|terms", RegexOption.IGNORE_CASE).containsMatchIn("$href $label")) href else null
        }.distinct()
    }

    private fun absoluteFrom(base: String, value: String): String = when {
        value.startsWith("http://", true) || value.startsWith("https://", true) -> value
        value.startsWith("//") -> "https:$value"
        value.startsWith("/") -> runCatching { java.net.URI(base).let { "${it.scheme}://${it.host}$value" } }.getOrDefault(value)
        else -> value
    }

    private fun directQuality(url: String): Int = when {
        Regex("(?i)(2160|4k)").containsMatchIn(url) -> 2160
        Regex("(?i)1080").containsMatchIn(url) -> 1080
        Regex("(?i)720").containsMatchIn(url) -> 720
        Regex("(?i)480").containsMatchIn(url) -> 480
        else -> Qualities.Unknown.value
    }

    private suspend fun emitDirect(url: String, referer: String, label: String, callback: (ExtractorLink) -> Unit): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        val type = when {
            url.contains(".m3u8", true) -> ExtractorLinkType.M3U8
            else -> ExtractorLinkType.VIDEO
        }
        callback(newExtractorLink(source = name, name = label, url = url, type = type) {
            this.referer = referer
            this.quality = directQuality(url)
            this.headers = mapOf("Referer" to referer, "User-Agent" to userAgent)
        })
        return true
    }

    /** Resolve a CineCloud/NeoDrive file page into the server buttons or a direct media URL. */
    private suspend fun resolveCloudPage(
        inputUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileUrl = inputUrl.replace(Regex("/(x|w|d|gp)/", RegexOption.IGNORE_CASE), "/f/").substringBefore('#').substringBefore('?')
        val response = try {
            app.get(fileUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent))
        } catch (_: Exception) { return false }
        val html = response.text
        // A Turnstile interstitial has no playable server list. Do not mistake challenge links for media.
        if (html.contains("cf-turnstile", true) && !html.contains("server-btn", true) && !html.contains("fsl-btn", true)) return false
        val doc = Jsoup.parse(html, fileUrl)
        val serverLinks = doc.select("a[href]").mapNotNull { a ->
            val href = a.absUrl("href").ifBlank { absoluteFrom(fileUrl, a.attr("href")) }
            val label = (a.text() + " " + a.attr("aria-label") + " " + a.className()).trim()
            val low = label.lowercase()
            if (href.isBlank() || href == fileUrl) null
            else if (low.contains("telegram") || low.contains("facebook") || low.contains("tutorial") || low.contains("privacy")) null
            else if (low.contains("fast cloud") || low.contains("instant") || low.contains("resumable") || low.contains("stream online") ||
                low.contains("watch online") || a.className().contains("fsl-btn", true) ||
                Regex("/(x|w|d|gp|f)/[A-Za-z0-9_-]+", RegexOption.IGNORE_CASE).containsMatchIn(href)) href to label
            else if (Regex("(?i)\\.(m3u8|mp4|mkv|webm)(?:[?#].*)?$").containsMatchIn(href)) href to label
            else null
        }.distinctBy { it.first }

        var found = false
        for ((target, label) in serverLinks.take(12)) {
            try {
                if (Regex("(?i)\\.(m3u8|mp4|mkv|webm)(?:[?#].*)?$").containsMatchIn(target) ||
                    target.contains("googleusercontent.com", true) || target.contains("workers.dev", true) ||
                    target.contains("r2.dev", true) || target.contains("pixeldrain", true)) {
                    if (target.contains("pixeldrain", true)) {
                        val id = Regex("pixeldrain\\.[a-z]+/(?:u|l|api/file)/([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(target)?.groupValues?.get(1)
                        val direct = if (id != null) "https://pixeldrain.com/api/file/$id?download" else target
                        found = emitDirect(direct, fileUrl, "CineFreak · ${label.ifBlank { "Direct" }}", callback) || found
                    } else found = emitDirect(target, fileUrl, "CineFreak · ${label.ifBlank { "Direct" }}", callback) || found
                    continue
                }
                // Stream Online pages can carry the final URL in iframe?id=...
                val inter = app.get(target, headers = mapOf("Referer" to fileUrl, "User-Agent" to userAgent))
                val id = Regex("[?&]id=([^&#]+)", RegexOption.IGNORE_CASE).find(target)?.groupValues?.get(1)
                val decodedId = id?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() }
                val candidates = buildList {
                    if (decodedId?.startsWith("http", true) == true) add(decodedId)
                    val playerDoc = Jsoup.parse(inter.text, target)
                    playerDoc.select("video[src], source[src]").forEach { add(it.absUrl("src")) }
                    Regex("(?i)[\"'](?:file|src|source|url|hls|videoUrl)[\"']\\s*[:=]\\s*[\"']([^\"']+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\"']*)?)[\"']")
                        .findAll(inter.text.replace("\\\\/", "/")).forEach { add(absoluteFrom(target, it.groupValues[1])) }
                    Regex("https?://[^\\s\"'<>]+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\\s\"'<>]*)?", RegexOption.IGNORE_CASE)
                        .findAll(inter.text).forEach { add(it.value) }
                }.filter { it.startsWith("http", true) }.distinct()
                for (candidate in candidates) found = emitDirect(candidate, target, "CineFreak · $label", callback) || found
                if (!found) {
                    loadExtractor(target, fileUrl, subtitleCallback) { extracted ->
                        found = true
                        callback(extracted)
                    }
                }
            } catch (_: Exception) { /* Continue to other server buttons. */ }
        }
        return found
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|||", limit = 3)
        var pageUrl = parts.getOrNull(2).orEmpty().takeIf { it.startsWith("http", true) }
        val rawLinks = parts.getOrNull(1).orEmpty().split('|').filter { it.startsWith("http://") || it.startsWith("https://") }
        val links = LinkedHashSet<String>()
        links.addAll(rawLinks)
        // Re-read the page: current CineFreak pages may add player buttons through markup that the
        // old text-only selector missed, and URLs can change between catalog and playback.
        if (pageUrl == null && parts.firstOrNull().orEmpty().startsWith("http", true)) pageUrl = parts.first()
        if (pageUrl != null) {
            try {
                val page = app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent))
                links.addAll(extractCloudLinks(page.text, pageUrl))
                // Direct video URLs embedded in the player markup.
                val doc = Jsoup.parse(page.text, pageUrl)
                doc.select("video[src], source[src]").forEach { el ->
                    val src = el.absUrl("src")
                    if (src.startsWith("http", true)) links.add(src)
                }
                Regex("(?i)[\"'](?:file|src|source|url|hls|videoUrl)[\"']\\s*[:=]\\s*[\"']([^\"']+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\"']*)?)[\"']")
                    .findAll(page.text.replace("\\\\/", "/")).forEach { links.add(absoluteFrom(pageUrl, it.groupValues[1])) }
            } catch (_: Exception) { }
        }

        var found = false
        val seen = mutableSetOf<String>()
        for ((index, link) in links.withIndex()) {
            if (!seen.add(link)) continue
            try {
                if (Regex("(?i)\\.(m3u8|mp4|mkv|webm|mpd)(?:[?#].*)?$").containsMatchIn(link) ||
                    link.contains("googleusercontent.com", true) || link.contains("workers.dev", true) ||
                    link.contains("r2.dev", true) || link.contains("r2.cloudflarestorage.com", true) || link.contains("pixeldrain", true)) {
                    found = emitDirect(link, pageUrl ?: "$mainUrl/", "CineFreak ${index + 1}", callback) || found
                } else if (link.contains("cinecloud", true) || link.contains("neodrive", true)) {
                    found = resolveCloudPage(link, subtitleCallback, callback) || found
                } else {
                    loadExtractor(link, pageUrl ?: "$mainUrl/", subtitleCallback) { extracted ->
                        found = true
                        callback(extracted)
                    }
                }
            } catch (_: Exception) { }
        }

        // SkyStream's second route uses PLAY_API with an episode source code. Try the non-challenge
        // request path when present; if the site requires Turnstile, CloudStream must report no link
        // rather than pretending the API returned a playable URL.
        if (!found && pageUrl != null) {
            try {
                val page = app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent))
                val api = Regex("PLAY_API\\s*=\\s*[\"']([^\"']+)[\"']").find(page.text)?.groupValues?.get(1)?.replace("\\\\/", "/")
                val ep = Regex("(?:sources\\s*[:=]\\s*[\"'])([^\"']+)[\"']").find(page.text)?.groupValues?.get(1)
                    ?: if (page.text.contains("PLAY_API")) "m" else null
                if (!api.isNullOrBlank() && !ep.isNullOrBlank()) {
                    val result = app.post(api, headers = mapOf(
                        "Referer" to pageUrl, "Origin" to mainUrl, "Accept" to "application/json",
                        "Content-Type" to "application/x-www-form-urlencoded", "User-Agent" to userAgent
                    ), data = mapOf("op" to "play", "ep" to ep))
                    val json = parseJsonOrNull<PlayerJson>(result.text)
                    val target = json?.url?.takeIf { json.state == "ready" && it.startsWith("http", true) }
                    if (target != null) {
                        if (Regex("(?i)\\.(m3u8|mp4|mkv|webm)(?:[?#].*)?$").containsMatchIn(target)) {
                            found = emitDirect(target, pageUrl, "CineFreak Player", callback) || found
                        } else {
                            val player = app.get(target, headers = mapOf("Referer" to pageUrl, "User-Agent" to userAgent))
                            val pd = Jsoup.parse(player.text, target)
                            val media = LinkedHashSet<String>()
                            pd.select("video[src], source[src]").forEach { media.add(it.absUrl("src")) }
                            Regex("(?i)[\"'](?:file|src|source|url|hls|videoUrl)[\"']\\s*[:=]\\s*[\"']([^\"']+\\.(?:m3u8|mp4|mkv|webm)(?:\\?[^\"']*)?)[\"']")
                                .findAll(player.text.replace("\\\\/", "/")).forEach { media.add(absoluteFrom(target, it.groupValues[1])) }
                            for (m in media.filter { it.startsWith("http", true) }) found = emitDirect(m, target, "CineFreak Player", callback) || found
                        }
                    }
                }
            } catch (_: Exception) { }
        }
        return found
    }

    private data class PlayerJson(val state: String? = null, val url: String? = null, val message: String? = null)
    private data class SearchKeyJson(val k: String? = null)
    private data class SearchJson(val results: List<SearchItem> = emptyList(), val renew: Boolean? = null)
    private data class SearchItem(val t: String? = null, val l: String? = null, val i: String? = null, val c: String? = null)
    private data class IndexJson(val hits: List<IndexHit> = emptyList(), val found: Int? = null)
    private data class IndexHit(val document: IndexDocument? = null)
    private data class IndexDocument(val title: String? = null, val slug: String? = null, val thumb: String? = null, val cats: List<String> = emptyList())
}
