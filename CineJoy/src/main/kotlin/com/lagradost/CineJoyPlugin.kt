package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================================
// DEBUG HELPER
// Gom log vào buffer + auto-copy clipboard khi lỗi
// ============================================================================
object CineJoyDebug {
    private const val MAX_LINES = 200
    private const val RATE_LIMIT_MS = 3000L
    private const val TAG = "cinejoy"

    private val buffer = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile private var lastCopyAt = 0L

    @Synchronized
    fun log(step: String, msg: String) {
        val line = "${fmt.format(Date())} [$step] $msg"
        buffer.add(line)
        if (buffer.size > MAX_LINES) buffer.removeAt(0)
        DebugLog.log(TAG, msg)
    }

    @Synchronized
    fun logError(step: String, msg: String, e: Throwable? = null) {
        val detail = if (e != null) "$msg | ${e::class.simpleName}: ${e.message}" else msg
        val line = "${fmt.format(Date())} [$step][ERROR] $detail"
        buffer.add(line)
        if (buffer.size > MAX_LINES) buffer.removeAt(0)
        DebugLog.error(TAG, detail, e)
    }

    @Synchronized
    fun clear() {
        buffer.clear()
    }

    @Synchronized
    fun snapshot(): String = buffer.joinToString("\n")

    /**
     * Copy toàn bộ buffer vào clipboard + toast.
     * @param auto true nếu gọi tự động do lỗi (có rate-limit, toast style khác)
     * @param reason lý do ngắn gọn (vd: "loadLinks returned false")
     */
    @Synchronized
    fun dumpToClipboard(auto: Boolean = false, reason: String = "") {
        val now = System.currentTimeMillis()
        if (auto && now - lastCopyAt < RATE_LIMIT_MS) return
        lastCopyAt = now

        try {
            val ctx = getContext() ?: return

            val header = buildString {
                appendLine("=== CineJoy Debug${if (auto) " (AUTO-COPIED ON ERROR)" else ""} ===")
                if (reason.isNotBlank()) appendLine("Reason: $reason")
                appendLine("Time:   ${Date()}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}")
                appendLine("App:    CloudStream")
                appendLine("Plugin: CineJoy v7")
                appendLine("Lines:  ${buffer.size}")
                appendLine("--- log ---")
            }

            val fullText = header + buffer.joinToString("\n")

            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CineJoy Debug", fullText))

            Toast.makeText(
                ctx,
                if (auto) "⚠ CineJoy error — log copied, please share"
                else "CineJoy log copied (${buffer.size} lines)",
                Toast.LENGTH_LONG
            ).show()

            DebugLog.log(TAG, "dumpToClipboard OK (${buffer.size} lines, auto=$auto)")
        } catch (e: Throwable) {
            DebugLog.error(TAG, "dumpToClipboard failed: ${e.message}")
        }
    }

    /**
     * Lấy Context an toàn qua reflection — thử nhiều API của CloudStream.
     */
    private fun getContext(): Context? {
        // 1) App.getAppContext()
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.App")
            val m = cls.getMethod("getAppContext")
            (m.invoke(null) as? Context)?.let { return it }
        } catch (_: Throwable) {}

        // 2) App.appContext (field)
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.App")
            val f = cls.getDeclaredField("appContext")
            f.isAccessible = true
            (f.get(null) as? Context)?.let { return it }
        } catch (_: Throwable) {}

        // 3) CommonActivity.activity (field)
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val f = cls.getDeclaredField("activity")
            f.isAccessible = true
            (f.get(null) as? Context)?.let { return it }
        } catch (_: Throwable) {}

        // 4) CommonActivity.getActivity()
        try {
            val cls = Class.forName("com.lagradost.cloudstream3.CommonActivity")
            val m = cls.getMethod("getActivity")
            (m.invoke(null) as? Context)?.let { return it }
        } catch (_: Throwable) {}

        return null
    }
}

// ============================================================================
// PLUGIN ENTRY POINT
// ============================================================================
@CloudstreamPlugin
class CineJoyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineJoyProvider())
    }
}

// ============================================================================
// PROVIDER
// ============================================================================
class CineJoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "CineJoy"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true

    private val apiInfo = "https://api.wing.st/info?tmdb="
    private val mapper = ObjectMapper()

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer"    to "$mainUrl/",
        "Origin"     to mainUrl,
    )

    data class CineJoyResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("message") val message: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("status") val status: Boolean?,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies",
        "$mainUrl/series" to "TV Series",
    )

    // ========================================================================
    // MAIN PAGE
    // ========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        CineJoyDebug.clear()
        CineJoyDebug.log("getMainPage", "category=${request.name} page=$page")

        val url = if (request.data.contains("{page}")) {
            request.data.replace("{page}", page.toString())
        } else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        }
        CineJoyDebug.log("getMainPage", "url=$url")

        try {
            val doc = app.get(url, headers = baseHeaders, referer = "$mainUrl/").document
            CineJoyDebug.log("getMainPage", "html.size=${doc.outerHtml().length}")

            val items = doc.select("a[href*='/watch/'], a[href*='/movie/'], a[href*='/series/']")
                .mapNotNull { it.parent() }
                .distinct()
                .ifEmpty { doc.select("div.mediaCard, div.item-movie, div.movie-item") }

            CineJoyDebug.log("getMainPage", "found ${items.size} raw items")

            val list = items.mapNotNull { it.toSearchResult() }
            CineJoyDebug.log("getMainPage", "parsed ${list.size} items")

            if (list.isEmpty() && items.isNotEmpty()) {
                CineJoyDebug.logError(
                    "getMainPage",
                    "selector matched ${items.size} nodes but 0 parsed. First HTML: " +
                    items.first().outerHtml().take(400)
                )
            }

            return newHomePageResponse(request.name, list)
        } catch (e: Exception) {
            CineJoyDebug.logError("getMainPage", "exception", e)
            CineJoyDebug.dumpToClipboard(auto = true, reason = "getMainPage exception")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    // ========================================================================
    // SEARCH
    // ========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        // Manual dump trigger — gõ "!log" trong search để copy clipboard
        if (query.trim().equals("!log", ignoreCase = true)) {
            CineJoyDebug.dumpToClipboard(auto = false)
            return emptyList()
        }

        CineJoyDebug.clear()
        CineJoyDebug.log("search", "query=$query")

        val url = "$mainUrl/movies?search=${java.net.URLEncoder.encode(query, "UTF-8")}"
        CineJoyDebug.log("search", "url=$url")

        return try {
            val doc = app.get(url, headers = baseHeaders, referer = "$mainUrl/").document
            val items = doc.select("a[href*='/watch/'], a[href*='/movie/'], a[href*='/series/']")
                .mapNotNull { it.parent() }
                .distinct()
            val list = items.mapNotNull { it.toSearchResult() }
            CineJoyDebug.log("search", "found ${list.size} results")
            list
        } catch (e: Exception) {
            CineJoyDebug.logError("search", "exception", e)
            CineJoyDebug.dumpToClipboard(auto = true, reason = "search exception")
            emptyList()
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("a[href*='/watch/']")
            ?: this.selectFirst("a[href*='/movie/']")
            ?: this.selectFirst("a[href*='/series/']")
            ?: this.selectFirst("a")
            ?: return null

        val href = linkEl.attr("href").takeIf { it.isNotBlank() } ?: return null
        val fullUrl = fixUrl(href)

        val title = linkEl.attr("title").takeIf { it.isNotBlank() }
            ?: this.selectFirst("h3, .title, .name")?.text()
            ?: linkEl.text().takeIf { it.isNotBlank() }
            ?: return null

        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgTag?.attr("src")?.takeIf { it.isNotBlank() }
            ?: ""
        val posterUrl = if (rawPoster.isNotBlank()) fixUrl(rawPoster) else null

        return newMovieSearchResponse(title, fullUrl, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    // ========================================================================
    // LOAD
    // ========================================================================
    override suspend fun load(url: String): LoadResponse? {
        CineJoyDebug.clear()
        CineJoyDebug.log("load", "url=$url")

        try {
            val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(url)?.groupValues?.get(1)
            CineJoyDebug.log("load", "tmdbId=$tmdbId")

            if (tmdbId != null) {
                val infoUrl = "$apiInfo$tmdbId"
                CineJoyDebug.log("load", "info URL=$infoUrl")

                val infoText = try {
                    app.get(infoUrl, headers = baseHeaders).text
                } catch (e: Exception) {
                    CineJoyDebug.logError("load", "info fetch failed", e)
                    null
                }

                if (infoText != null) {
                    CineJoyDebug.log("load", "info response: ${infoText.take(300)}")

                    if (infoText.startsWith("{")) {
                        try {
                            val json = mapper.readTree(infoText)
                            val title = json.get("title")?.asText() ?: json.get("name")?.asText()
                            val posterPath = json.get("poster_path")?.asText()
                            val backdropPath = json.get("backdrop_path")?.asText()
                            val overview = json.get("overview")?.asText()

                            if (title != null) {
                                val poster = posterPath?.takeIf { it != "null" }
                                    ?.let { "https://image.tmdb.org/t/p/w500$it" }
                                val backdrop = backdropPath?.takeIf { it != "null" }
                                    ?.let { "https://image.tmdb.org/t/p/w1280$it" }
                                val isTv = url.contains("/watch/tv/")

                                CineJoyDebug.log("load", "title=$title isTv=$isTv")

                                return if (isTv) {
                                    val episodes = try {
                                        app.get(url, headers = baseHeaders).document
                                            .select("a[href*='/watch/tv/']")
                                            .mapIndexedNotNull { idx, el ->
                                                val ep = el.attr("href")
                                                if (ep.isBlank()) return@mapIndexedNotNull null
                                                newEpisode(fixUrl(ep)) {
                                                    this.name = el.text()
                                                        .takeIf { it.isNotBlank() } ?: "Episode ${idx + 1}"
                                                    this.episode = idx + 1
                                                }
                                            }
                                    } catch (e: Exception) {
                                        CineJoyDebug.logError("load", "episodes scrape failed", e)
                                        emptyList()
                                    }

                                    CineJoyDebug.log("load", "tv episodes=${episodes.size}")
                                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                                        this.posterUrl = poster
                                        this.backgroundPosterUrl = backdrop
                                        this.plot = overview
                                    }
                                } else {
                                    newMovieLoadResponse(title, url, TvType.Movie, url) {
                                        this.posterUrl = poster
                                        this.backgroundPosterUrl = backdrop
                                        this.plot = overview
                                    }
                                }
                            } else {
                                CineJoyDebug.logError("load", "info response OK but no title")
                            }
                        } catch (e: Exception) {
                            CineJoyDebug.logError("load", "info JSON parse failed", e)
                        }
                    } else {
                        CineJoyDebug.logError("load", "info not JSON: ${infoText.take(200)}")
                    }
                }
            }

            // Fallback scrape
            CineJoyDebug.log("load", "fallback to scrape")
            return scrapeLoad(url)
        } catch (e: Exception) {
            CineJoyDebug.logError("load", "outer exception", e)
            CineJoyDebug.dumpToClipboard(auto = true, reason = "load exception")
            return null
        }
    }

    private suspend fun scrapeLoad(url: String): LoadResponse? {
        return try {
            val doc = app.get(url, headers = baseHeaders, referer = "$mainUrl/").document
            val title = doc.selectFirst("h1, .movie-title, .title")?.text() ?: "Unknown"
            val description = doc.selectFirst(".description, .overview, .plot")?.text()
            val posterSrc = doc.selectFirst("img.poster, .poster img, .mediaCard img")?.attr("src") ?: ""
            val poster = if (posterSrc.isNotBlank()) fixUrl(posterSrc) else null

            val episodes = doc.select("a[href*='/watch/']")
                .mapIndexedNotNull { idx, el ->
                    val href = el.attr("href")
                    if (href.isBlank()) return@mapIndexedNotNull null
                    newEpisode(fixUrl(href)) {
                        this.name = el.text().takeIf { it.isNotBlank() } ?: "Tap ${idx + 1}"
                        this.episode = idx + 1
                    }
                }

            CineJoyDebug.log("scrapeLoad", "title=$title episodes=${episodes.size}")

            if (episodes.isNotEmpty()) {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.plot = description
                    this.posterUrl = poster
                }
            } else {
                newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.plot = description
                    this.posterUrl = poster
                }
            }
        } catch (e: Exception) {
            CineJoyDebug.logError("scrapeLoad", "exception", e)
            CineJoyDebug.dumpToClipboard(auto = true, reason = "scrapeLoad exception")
            null
        }
    }

    // ========================================================================
    // LOAD LINKS — không clear buffer (giữ context từ load)
    // ========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        CineJoyDebug.log("loadLinks", "start data=$data")

        // -------- Attempt 1: direct API (if site returns JSON) --------
        try {
            val resp = app.get(data, headers = baseHeaders, referer = "$mainUrl/")
            CineJoyDebug.log("loadLinks", "direct response: ${resp.text.take(300)}")

            if (resp.text.trimStart().startsWith("{")) {
                val api = resp.parsedSafe<CineJoyResponse>()
                if (api != null && api.code != 2004 && !api.url.isNullOrBlank()) {
                    CineJoyDebug.log("loadLinks", "direct API returned url=${api.url}")
                    callback.invoke(
                        newExtractorLink(name, name, api.url,
                            if (api.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
                CineJoyDebug.log("loadLinks", "direct API unusable (code=${api?.code})")
            }
        } catch (e: Exception) {
            CineJoyDebug.logError("loadLinks", "direct attempt exception", e)
        }

        // -------- Attempt 2: WebView capture --------
        CineJoyDebug.log("loadLinks", "fallback to WebView")
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(?i)(\.m3u8|/hls/|workers\.dev|/stream/)"""),
                useOkhttp = false,
                script = """
                    (function(){
                      var n = 0;
                      var t = setInterval(function(){
                        n++;
                        try {
                          document.querySelectorAll('video, audio').forEach(function(el){
                            el.muted = true;
                            var p = el.play();
                            if (p && p.catch) p.catch(function(){});
                          });
                          document.querySelectorAll('button, [data-play]').forEach(function(b){
                            try { b.click(); } catch (e) {}
                          });
                        } catch (e) {}
                        if (n > 25) clearInterval(t);
                      }, 1000);
                    })();
                """.trimIndent(),
                scriptCallback = { },
                timeout = 30_000L
            )

            val response = app.get(data, headers = baseHeaders, referer = "$mainUrl/", interceptor = resolver)
            val streamUrl = response.url
            CineJoyDebug.log("loadLinks", "WebView captured: $streamUrl")

            if (streamUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = baseHeaders
                    }
                )
                CineJoyDebug.log("loadLinks", "SUCCESS via WebView")
                return true
            }
            CineJoyDebug.logError("loadLinks", "WebView did not capture m3u8")
        } catch (e: Exception) {
            CineJoyDebug.logError("loadLinks", "WebView exception", e)
        }

        // -------- Failure: auto-copy log --------
        CineJoyDebug.logError("loadLinks", "FAILED — no links found")
        CineJoyDebug.dumpToClipboard(auto = true, reason = "loadLinks found no playable link")
        return false
    }
}
