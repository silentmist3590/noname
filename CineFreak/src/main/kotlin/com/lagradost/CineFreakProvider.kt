package com.cinefreak

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CineFreakProvider : MainAPI() {
    override var mainUrl = "https://cinefreak.ch"
    override var name = "CineFreak"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Cartoon)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Releases",
        "$mainUrl/hindi-movies/" to "Hindi Movies",
        "$mainUrl/hindi-dubbed-movies/" to "Hindi Dubbed",
        "$mainUrl/english-movies/" to "English Movies",
        "$mainUrl/dual-audio/" to "Dual Audio",
        "$mainUrl/k-drama/" to "K-Drama",
        "$mainUrl/web-series/" to "Web Series"
    )

    private val mapper = jacksonObjectMapper()
    private val indexApi = "https://search.yagaverse.net/api/search"
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private inline fun <reified T> parseJsonOrNull(text: String): T? =
        runCatching { mapper.readValue<T>(text) }.getOrNull()

    private fun absolute(rawValue: String?, base: String = "$mainUrl/"): String {
        val raw = rawValue?.trim().orEmpty()
        if (raw.isBlank()) return ""
        val candidate = when {
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            else -> runCatching { URI(base).resolve(URI(raw)).toString() }.getOrDefault("")
        }
        return candidate.takeIf {
            runCatching {
                val u = URI(it)
                (u.scheme.equals("http", true) || u.scheme.equals("https", true)) && !u.host.isNullOrBlank()
            }.getOrDefault(false)
        }.orEmpty()
    }

    private fun cleanTitle(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()
        .replace(Regex("\\s*[|–-]\\s*CineFreak.*$", RegexOption.IGNORE_CASE), "")

    private fun isSeries(text: String, url: String = ""): Boolean =
        Regex("web.?series|series|season|episode|k-drama|tv show|anime", RegexOption.IGNORE_CASE)
            .containsMatchIn("$text $url")

    private fun indexUrl(slug: String): String {
        val clean = slug.trim()
        return if (clean.startsWith("http://") || clean.startsWith("https://")) clean
        else "$mainUrl/${clean.trim('/')}/"
    }

    private data class SearchKeyJson(val k: String? = null)
    private data class SearchJson(val results: List<SearchItem> = emptyList())
    private data class SearchItem(val t: String? = null, val l: String? = null, val i: String? = null, val c: String? = null)
    private data class IndexJson(val hits: List<IndexHit> = emptyList())
    private data class IndexHit(val document: IndexDocument? = null)
    private data class IndexDocument(
        val title: String? = null,
        val slug: String? = null,
        val thumb: String? = null,
        val cats: List<String> = emptyList()
    )

    private fun indexItems(json: IndexJson?): List<SearchResponse> =
        json?.hits.orEmpty().mapNotNull { hit ->
            val item = hit.document ?: return@mapNotNull null
            val slug = item.slug?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = cleanTitle(item.title.orEmpty()).ifBlank {
                slug.substringAfterLast('/').replace('-', ' ')
            }
            val url = indexUrl(slug)
            val poster = absolute(item.thumb)
            if (isSeries("$title ${item.cats.orEmpty().joinToString(" ")}", slug)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster.ifBlank { null } }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster.ifBlank { null } }
            }
        }.distinctBy { it.url }

    private suspend fun indexSearch(query: String, page: Int = 1): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val text = app.get(
            "$indexApi?q=$q&pg=$page",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "application/json",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )
        ).text
        return indexItems(parseJsonOrNull<IndexJson>(text))
    }

    private suspend fun searchSiteApi(query: String, page: Int = 1): List<SearchResponse> {
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "application/json",
            "Referer" to "$mainUrl/fast-search/"
        )
        val key = runCatching {
            parseJsonOrNull<SearchKeyJson>(
                app.post(
                    "$mainUrl/search-api.php",
                    headers = headers + ("Origin" to mainUrl),
                    data = mapOf("st" to "1")
                ).text
            )?.k.orEmpty()
        }.getOrDefault("")

        val result = app.get(
            "$mainUrl/search-api.php?q=${URLEncoder.encode(query, "UTF-8")}&pg=$page",
            headers = headers + ("X-CF-ST" to key)
        ).text.let { parseJsonOrNull<SearchJson>(it) }

        return result?.results.orEmpty().mapNotNull { item ->
            val slug = item.l?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = cleanTitle(item.t.orEmpty()).ifBlank {
                slug.substringAfterLast('/').replace('-', ' ')
            }
            val url = indexUrl(slug)
            val poster = absolute(item.i)
            if (isSeries("$title ${item.c.orEmpty()}", slug)) {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) { posterUrl = poster.ifBlank { null } }
            } else {
                newMovieSearchResponse(title, url, TvType.Movie) { posterUrl = poster.ifBlank { null } }
            }
        }.distinctBy { it.url }
    }

    private fun cards(html: String, pageUrl: String): List<SearchResponse> {
        val doc = Jsoup.parse(html, pageUrl)
        val out = LinkedHashMap<String, SearchResponse>()
        val candidates = doc.select("article, .post, .movie-item, .item, .post-item, .latest-release, .result-item")
        for (card in candidates) {
            val a = card.selectFirst("a[href]") ?: continue
            val href = absolute(a.attr("href"), pageUrl)
            if (href.isBlank() || !href.startsWith(mainUrl) || href == mainUrl ||
                href.contains("/category/") || href.contains("/tag/")) continue
            val titleNode = card.selectFirst("h1,h2,h3,h4,.title,.entry-title,[title]")
            val title = cleanTitle(titleNode?.text()?.takeIf { it.isNotBlank() }
                ?: a.attr("title").ifBlank { a.text() })
            if (title.length < 2) continue
            val poster = card.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
            }?.let { absolute(it, pageUrl) }?.ifBlank { null }
            val response = if (isSeries(title, href)) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            }
            out.putIfAbsent(href, response)
        }

        if (out.isEmpty()) {
            for (a in doc.select("a[href]:has(img)")) {
                val href = absolute(a.attr("href"), pageUrl)
                if (href.isBlank() || !href.startsWith(mainUrl) || href == mainUrl ||
                    href.contains("/category/") || href.contains("/tag/")) continue
                val img = a.selectFirst("img")
                val title = cleanTitle(a.attr("title").ifBlank {
                    img?.attr("alt").orEmpty()
                }.ifBlank { a.text() })
                if (title.length < 2) continue
                val poster = img?.let { it.attr("data-src").ifBlank { it.attr("src") } }
                    ?.let { absolute(it, pageUrl) }?.ifBlank { null }
                val response = if (isSeries(title, href)) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
                }
                out.putIfAbsent(href, response)
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trim()
        if (!base.matches(Regex("(?i)^https?://[^\\s]+$"))) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        val pageUrl = if (page <= 1) base else base.trimEnd('/') + "/page/$page/"
        val response = app.get(pageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
        var items = cards(response.text, pageUrl)
        if (items.isEmpty()) {
            items = runCatching {
                val indexed = indexSearch("*", page)
                when {
                    request.name.equals("Latest Releases", true) -> indexed
                    request.name.contains("Hindi Dubbed", true) -> indexed.filter { it.name.contains("hindi", true) }
                    request.name.contains("Hindi", true) -> indexed.filter { it.name.contains("hindi", true) }
                    request.name.contains("English", true) -> indexed.filter { it.name.contains("english", true) }
                    request.name.contains("Dual", true) -> indexed.filter { it.name.contains("dual", true) }
                    request.name.contains("K-Drama", true) -> indexed.filter { isSeries(it.name, it.url) || it.name.contains("Korean", true) }
                    request.name.contains("Series", true) -> indexed.filter { isSeries(it.name, it.url) }
                    else -> indexed
                }
            }.getOrDefault(emptyList())
        }
        val hasNext = items.isNotEmpty() &&
            (response.text.contains("rel=\"next\"", true) ||
                response.text.contains("/page/${page + 1}/") || items.size >= 20)
        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        runCatching { searchSiteApi(query) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        runCatching { indexSearch(query) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        val html = app.get(
            "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}",
            headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")
        ).text
        return cards(html, mainUrl)
    }

    private fun extractCloudLinks(html: String, pageUrl: String): List<String> {
        val doc = Jsoup.parse(html, pageUrl)
        doc.select("div[aria-hidden=true]").remove()
        val results = LinkedHashSet<String>()

        for (a in doc.select("a[href]")) {
            val href = absolute(a.attr("href"), pageUrl)
            if (href.isBlank()) continue
            val label = (
                a.text() + " " + a.attr("aria-label") + " " + a.attr("title") +
                    " " + a.className() + " " + a.id()
                ).lowercase()
            if (Regex("telegram|facebook|whatsapp|tutorial|privacy|terms", RegexOption.IGNORE_CASE)
                    .containsMatchIn("$href $label")) continue

            val host = runCatching { URI(href).host.orEmpty().lowercase() }.getOrDefault("")
            val path = runCatching { URI(href).path.orEmpty() }.getOrDefault("")
            val knownCloud = host.contains("cinecloud") || host.contains("neodrive")
            val cloudRoute = Regex("""/(f|x|w|d|gp|fc|fastdl)/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE)
                .containsMatchIn(path)
            val playerButton = Regex(
                "fast.?cloud|instant|resumable|stream.?online|watch.?online|fsl-btn|server-btn|download.?now|instant-download",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(label)
            val directMedia = Regex("""(?i)\.(m3u8|mp4|mkv|webm|mpd)(?:[?#].*)?$""")
                .containsMatchIn(href)

            if (directMedia || (knownCloud && cloudRoute) || playerButton) results.add(href)
        }
        return results.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(url, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
        val doc = Jsoup.parse(response.text, url)
        val title = cleanTitle(
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("h1.entry-title, h1, .post-title")?.text().orEmpty()
        )
        if (title.isBlank()) return null

        val poster = doc.selectFirst(
            "article img[src], .post img[src], .entry-content img[src], img[src*=image.tmdb.org], img[src*=cineimg.xyz]"
        )?.let {
            it.absUrl("src").ifBlank { it.absUrl("data-src") }.ifBlank { absolute(it.attr("data-src"), url) }
        }?.ifBlank { null } ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { !it.contains("admin-ajax") }?.let { absolute(it, url) }

        val description = doc.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
            ?: doc.selectFirst(".entry-content, .description, .summary")?.text()
        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(title)?.value?.toIntOrNull()
        val tv = isSeries(title, url) || doc.select(".cft-it[data-k]").isNotEmpty()
        val episodes = mutableListOf<Episode>()

        if (tv) {
            for (row in doc.select(".cft-it[data-k]")) {
                val key = row.attr("data-k")
                val match = Regex("""[ep]s(\d+)e(\d+)(?:-(\d+))?""", RegexOption.IGNORE_CASE).find(key) ?: continue
                val season = match.groupValues[1].toIntOrNull() ?: 1
                val ep = match.groupValues[2].toIntOrNull() ?: continue
                val epName = row.selectFirst(".cft-info b")?.text()?.ifBlank { null } ?: "Episode $ep"
                val links = extractCloudLinks(row.outerHtml(), url)
                episodes.add(newEpisode("$epName|||${links.joinToString("|")}|||$url") {
                    name = epName
                    this.season = season
                    episode = ep
                })
            }
        }

        if (episodes.isEmpty()) {
            val links = extractCloudLinks(response.text, url)
            episodes.add(newEpisode("$title|||${links.joinToString("|")}|||$url") {
                name = title
                season = 1
                episode = 1
            })
        }

        return if (tv) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
                this.year = year
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                posterUrl = poster
                plot = description
                this.year = year
            }
        }
    }

    private fun decodeB64Url(value: String): ByteArray {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }

    private fun decryptVidzer(encrypted: String, qk: String): String {
        val key = decodeB64Url(qk.reversed())
        val input = decodeB64Url(encrypted)
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key size" }
        require(input.size > 12) { "Encrypted Vidzer payload too short" }
        val iv = input.copyOfRange(0, 12)
        val ciphertext = input.copyOfRange(12, input.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }

    private fun isCineCloudFile(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.host.orEmpty().contains("cinecloud", true) &&
            uri.path.orEmpty().matches(Regex("""/f/[A-Za-z0-9_-]+/?"""))
    }.getOrDefault(false)

    /**
     * Attempts to resolve CineCloud /f/... to Vidzer and decrypt its /q/... response.
     * The site's actual response schema can change; failures return false and allow the
     * existing generic extractor route to run.
     */
    private suspend fun resolveVidzer(
        inputUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileUrl = inputUrl.substringBefore('#')
        try {
            val page = app.get(fileUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/"))
            if (page.code !in 200..299) return false
            val html = page.text
            if (html.contains("cf-turnstile", true) || html.contains("Just a moment...", true)) return false

            val doc = Jsoup.parse(html, fileUrl)
            val csrf = sequenceOf(
                doc.selectFirst("meta[name=csrf-token]")?.attr("content"),
                doc.selectFirst("meta[name=_token]")?.attr("content"),
                doc.selectFirst("input[name=_token]")?.attr("value"),
                Regex("""["']X-CSRF-TOKEN["']\s*:\s*["']([^"']+)["']""").find(html)?.groupValues?.getOrNull(1)
            ).firstOrNull { !it.isNullOrBlank() }.orEmpty()
            if (csrf.isBlank()) {
                Log.w("CineFreak", "Vidzer: CSRF token not found")
                return false
            }

            val origin = runCatching { URI(fileUrl).let { "${it.scheme}://${it.host}" } }.getOrDefault(mainUrl)
            val play = app.post(
                fileUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to fileUrl,
                    "Origin" to origin,
                    "Accept" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-CSRF-TOKEN" to csrf
                ),
                data = mapOf("a" to "play", "X-CSRF-TOKEN" to csrf)
            )
            if (play.code !in 200..299) return false

            val playText = play.text.replace("\\/", "/")
            val vidzerUrl = Regex(
                """https?://(?:www\.)?vidzer\.me/w/[A-Za-z0-9_-]+(?:\?[^\s"'<>]*)?""",
                RegexOption.IGNORE_CASE
            ).find(playText)?.value ?: findUrlNode(runCatching { mapper.readTree(playText) }.getOrNull())
            if (vidzerUrl.isNullOrBlank()) {
                Log.w("CineFreak", "Vidzer URL not found in CineCloud play response")
                return false
            }

            val playerUrl = absolute(vidzerUrl, fileUrl)
            val player = app.get(
                playerUrl,
                headers = mapOf("User-Agent" to userAgent, "Referer" to fileUrl)
            )
            if (player.code !in 200..299) return false

            val playerHtml = player.text
            val qk = Regex("""\bqk\s*:\s*["']([^"']+)["']""").find(playerHtml)?.groupValues?.getOrNull(1)
                ?: Regex("""["']qk["']\s*:\s*["']([^"']+)["']""").find(playerHtml)?.groupValues?.getOrNull(1)
            val apiPath = Regex("""\bapiUrl\s*:\s*["']([^"']+)["']""").find(playerHtml)?.groupValues?.getOrNull(1)
                ?: Regex("""["']apiUrl["']\s*:\s*["']([^"']+)["']""").find(playerHtml)?.groupValues?.getOrNull(1)
            if (qk.isNullOrBlank() || apiPath.isNullOrBlank()) {
                Log.w("CineFreak", "Vidzer qk/apiUrl missing")
                return false
            }

            val apiUrl = absolute(apiPath, playerUrl)
            val api = app.get(
                apiUrl,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to playerUrl,
                    "Accept" to "application/json"
                )
            )
            if (api.code !in 200..299) return false

            val raw = api.text.trim()
            val payload = if (raw.startsWith("{")) raw else decryptVidzer(raw.substringAfterLast('.', raw), qk)
            val root = mapper.readTree(payload)
            val videoUrl = root.path("videoUrl").asText("").ifBlank {
                if (root.path("video").isTextual) root.path("video").asText("") else root.path("video").path("url").asText("")
            }
            if (!videoUrl.startsWith("http://", true) && !videoUrl.startsWith("https://", true)) return false

            val resolutionNodes = root.path("resolutions")
            var quality = 0
            for (i in 0 until resolutionNodes.size()) {
                val item = resolutionNodes[i]
                if (item.path("url").asText() == videoUrl) {
                    quality = Regex("""\d+""").find(item.path("quality").asText(""))?.value?.toIntOrNull() ?: 0
                    break
                }
            }

            val subtitles = root.path("subtitleTracks")
            for (i in 0 until subtitles.size()) {
                val item = subtitles[i]
                val subtitleUrl = absolute(item.path("url").asText(""), playerUrl)
                if (subtitleUrl.isNotBlank()) {
                    subtitleCallback(SubtitleFile(item.path("language").asText("en"), subtitleUrl))
                }
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = "Vidzer${if (quality > 0) " ${quality}p" else ""}",
                    url = videoUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = playerUrl
                    this.quality = if (quality > 0) quality else Qualities.Unknown.value
                    this.headers = mapOf("Referer" to playerUrl, "User-Agent" to userAgent)
                }
            )
            Log.d("CineFreak", "Vidzer resolved (quality=$quality)")
            return true
        } catch (e: Exception) {
            Log.e("CineFreak", "Vidzer resolution failed", e)
            return false
        }
    }

    private fun findUrlNode(node: JsonNode?): String? {
        if (node == null) return null
        if (node.isTextual) {
            val value = node.asText()
            if (value.startsWith("http", true) && value.contains("vidzer.me/w/", true)) return value
        }
        if (node.isObject) {
            val fields = node.fields()
            while (fields.hasNext()) {
                val found = findUrlNode(fields.next().value)
                if (found != null) return found
            }
        }
        if (node.isArray) {
            for (child in node) {
                val found = findUrlNode(child)
                if (found != null) return found
            }
        }
        return null
    }

    private suspend fun emitDirect(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return false
        val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(source = name, name = label, url = url, type = type) {
                this.referer = referer
                this.quality = when {
                    Regex("(?i)(2160|4k)").containsMatchIn(url) -> 2160
                    Regex("(?i)1080").containsMatchIn(url) -> 1080
                    Regex("(?i)720").containsMatchIn(url) -> 720
                    Regex("(?i)480").containsMatchIn(url) -> 480
                    else -> Qualities.Unknown.value
                }
                this.headers = mapOf("Referer" to referer, "User-Agent" to userAgent)
            }
        )
        return true
    }

    private suspend fun resolveCloudPage(
        inputUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fileUrl = inputUrl.substringBefore('#')
        val response = runCatching {
            app.get(fileUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent))
        }.getOrNull() ?: return false
        if (response.code !in 200..299) return false
        val html = response.text
        if (html.contains("cf-turnstile", true) || html.contains("Just a moment...", true)) return false

        val doc = Jsoup.parse(html, fileUrl)
        val targets = doc.select("a[href]").mapNotNull { a ->
            val href = absolute(a.attr("href"), fileUrl)
            val label = (a.text() + " " + a.attr("aria-label") + " " + a.className()).lowercase()
            if (href.isBlank() || href == fileUrl) null
            else if (Regex("telegram|facebook|tutorial|privacy", RegexOption.IGNORE_CASE).containsMatchIn(label)) null
            else if (
                Regex("fast cloud|instant|resumable|stream online|watch online|download now", RegexOption.IGNORE_CASE).containsMatchIn(label) ||
                Regex("""/(x|w|d|gp|f|fc|fastdl)/[A-Za-z0-9_-]+""", RegexOption.IGNORE_CASE).containsMatchIn(href) ||
                Regex("""(?i)\.(m3u8|mp4|mkv|webm)(?:[?#].*)?$""").containsMatchIn(href)
            ) href to label else null
        }.distinctBy { it.first }.take(12)

        var found = false
        for ((target, label) in targets) {
            try {
                if (Regex("""(?i)\.(m3u8|mp4|mkv|webm)(?:[?#].*)?$""").containsMatchIn(target) ||
                    target.contains("r2.dev", true) || target.contains("workers.dev", true) ||
                    target.contains("googleusercontent.com", true)) {
                    found = emitDirect(target, fileUrl, "CineFreak · ${label.ifBlank { "Direct" }}", callback) || found
                } else {
                    loadExtractor(target, fileUrl, subtitleCallback) { extracted ->
                        callback(extracted)
                        found = true
                    }
                }
            } catch (e: Exception) {
                Log.e("CineFreak", "Cloud extractor failed: $target", e)
            }
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
        val rawLinks = parts.getOrNull(1).orEmpty().split('|')
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
        val links = LinkedHashSet<String>()
        links.addAll(rawLinks)

        if (pageUrl == null && parts.firstOrNull().orEmpty().startsWith("http", true)) {
            pageUrl = parts.first()
        }
        if (pageUrl != null) {
            runCatching {
                val page = app.get(pageUrl, headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to userAgent))
                links.addAll(extractCloudLinks(page.text, pageUrl))
                val doc = Jsoup.parse(page.text, pageUrl)
                doc.select("video[src], source[src]").forEach { el ->
                    val src = el.absUrl("src")
                    if (src.startsWith("http", true)) links.add(src)
                }
                Regex("""(?i)["'](?:file|src|source|url|hls|videoUrl)["']\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4|mkv|webm)(?:\?[^"']*)?)["']""")
                    .findAll(page.text.replace("\\/", "/"))
                    .forEach { links.add(absolute(it.groupValues[1], pageUrl)) }
            }.onFailure { Log.e("CineFreak", "Failed to reread page", it) }
        }

        var found = false
        val seen = mutableSetOf<String>()
        for (link in links) {
            if (!seen.add(link)) continue
            try {
                if (isCineCloudFile(link)) {
                    Log.d("CineFreak", "Trying Vidzer resolver: $link")

                    val resolved = resolveVidzer(link, subtitleCallback, callback)
                    Log.d("CineFreak", "Vidzer resolver result: $resolved")

                    found = resolved || found

                    if (!resolved) {
                        Log.d("CineFreak", "Trying generic cloud resolver: $link")
                        val cloudResolved = resolveCloudPage(link, subtitleCallback, callback)
                        Log.d("CineFreak", "Generic cloud resolver result: $cloudResolved")
                        found = cloudResolved || found
                    }
                } else if (
                    Regex("""(?i)\.(m3u8|mp4|mkv|webm|mpd)(?:[?#].*)?$""").containsMatchIn(link) ||
                    link.contains("googleusercontent.com", true) || link.contains("workers.dev", true) ||
                    link.contains("r2.dev", true) || link.contains("r2.cloudflarestorage.com", true) ||
                    link.contains("pixeldrain", true)
                ) {
                    found = emitDirect(link, pageUrl ?: "$mainUrl/", "CineFreak", callback) || found
                } else if (link.contains("cinecloud", true) || link.contains("neodrive", true)) {
                    found = resolveCloudPage(link, subtitleCallback, callback) || found
                } else {
                    loadExtractor(link, pageUrl ?: "$mainUrl/", subtitleCallback) { extracted ->
                        callback(extracted)
                        found = true
                    }
                }
            } catch (e: Exception) {
                Log.e("CineFreak", "Failed candidate: $link", e)
            }
        }
        Log.d("CineFreak", "Page URL: $pageUrl")
        Log.d("CineFreak", "Total candidate links: ${links.size}")

        links.forEachIndexed { index, link ->
            Log.d("CineFreak", "Candidate[$index]: $link")
        }
        return found
    }
}
