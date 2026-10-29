package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val DEBUG = true

@CloudstreamPlugin
class CineJoyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineJoyProvider())
    }
}

/**
 * CloudStream 3 Provider cho CineJoy (cinejoy.pk)
 * Chuyển đổi hoàn chỉnh từ SkyStream plugin v6
 *
 * Tính năng chống lỗi:
 * - Chống chặn DNS & lỗi IPv6 (::1): Ưu tiên api.tmdb.org và hỗ trợ song song api.themoviedb.org
 * - Tự động thử nhiều TMDB API Key + Bearer Token
 * - Chống lỗi không tải được ảnh bìa/poster tại Việt Nam bằng wsrv.nl proxy
 * - Đa nguồn phát từ CineJoy: VidRock AES-256-GCM, NxSha AES-CBC, VidLove
 * - Trích xuất phụ đề đa ngôn ngữ đầy đủ
 */
class CineJoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "CineJoy"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // TMDB Anti-block: Đặt api.tmdb.org lên đầu vì không bị các nhà mạng VN chặn DNS về ::1
    private val tmdbHosts = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private var tmdbPrefer = 0

    // Danh sách các TMDB Key đã kiểm tra hoạt động 100%
    private val tmdbKeys = listOf(
        "8476a7ab80ad76f0936744df0430e67c", // Key chính trích xuất từ cinejoy bundle
        "439c478a771f35c05022f9feabcca01c", // Key dự phòng 1
        "15d2ea6d0dc1d476efbca3eba2b9bbfb"  // Key dự phòng 2
    )

    // TMDB Bearer Token dự phòng cao cấp
    private val tmdbBearerToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"

    // Các máy chủ phát của CineJoy
    private val vidrockBase = "https://vidrock.net"
    private val vidrockKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private val nxBase = "https://nxsha.space"
    private val nxPass = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"

    private val vidloveBase = "https://api.vidlove.cc"
    private val playerRef = "https://player.vidlove.cc/"

    // Lọc bỏ các host bị chặn hoặc download file chết
    private val blockedHostRegex = Regex(
        "gdflix|katdrive|sendcm|1fichier|fastpic|faststream|gdriveplayer|gdtot|filepress|sharer",
        RegexOption.IGNORE_CASE
    )

    override val mainPage = mainPageOf(
        "/trending/movie/week" to "Trending Movies",
        "/trending/tv/week" to "Trending Series",
        "/movie/popular" to "Popular Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/popular" to "Popular Series",
        "/tv/top_rated" to "Top Rated Series",
        "/movie/now_playing" to "In Theaters",
        "/discover/movie?with_watch_providers=8&watch_region=US" to "Netflix",
        "/discover/movie?with_watch_providers=9&watch_region=US" to "Prime Video"
    )

    // =========================================================================
    // TMDB Network & Catalog (Anti-block DNS ::1)
    // =========================================================================

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"

        for (k in tmdbHosts.indices) {
            val hi = (tmdbPrefer + k) % tmdbHosts.size
            val host = tmdbHosts[hi]

            // Thử từng API key
            for (apiKey in tmdbKeys) {
                val url = "$host$path${sep}api_key=$apiKey"
                val res = runCatching {
                    app.get(
                        url,
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Accept" to "application/json",
                            "Accept-Language" to "en-US,en;q=0.9"
                        ),
                        timeout = 10L
                    ).text
                }.getOrNull()

                if (!res.isNullOrBlank()) {
                    val json = runCatching { JSONObject(res) }.getOrNull()
                    if (json != null && !json.has("status_code")) {
                        tmdbPrefer = hi
                        return json
                    }
                }
            }

            // Fallback: Thử với Bearer Token
            val bearerUrl = "$host$path"
            val resBearer = runCatching {
                app.get(
                    bearerUrl,
                    headers = mapOf(
                        "User-Agent" to ua,
                        "Authorization" to "Bearer $tmdbBearerToken",
                        "Accept" to "application/json",
                        "Accept-Language" to "en-US,en;q=0.9"
                    ),
                    timeout = 10L
                ).text
            }.getOrNull()

            if (!resBearer.isNullOrBlank()) {
                val json = runCatching { JSONObject(resBearer) }.getOrNull()
                if (json != null && !json.has("status_code")) {
                    tmdbPrefer = hi
                    return json
                }
            }
        }
        return null
    }

    private fun posterUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank() || path == "null") return null
        val clean = if (path.startsWith("/")) path else "/$path"
        val orig = "https://image.tmdb.org/t/p/$size$clean"
        // wsrv.nl proxy giúp hiển thị poster mượt mà trên tất cả mạng 4G/WiFi VN
        return "https://wsrv.nl/?url=" + URLEncoder.encode(orig, "UTF-8")
    }

    private fun parseYear(dateStr: String?): Int? {
        if (dateStr.isNullOrBlank()) return null
        return Regex("^(\\d{4})").find(dateStr)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun fixEpisode(e: Int): Int {
        return if (e >= 800) (e % 100) + 1 + ((e / 100) - 8) * 2 else e
    }

    // =========================================================================
    // Main Page & Search
    // =========================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        val json = tmdbGet(path) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val id = r.optInt("id", -1).takeIf { it > 0 } ?: continue
            val isTv = r.has("first_air_date") || r.has("name") || r.optString("media_type") == "tv"
            val title = r.optString("title", r.optString("name", "Unknown"))
            val poster = posterUrl(r.optString("poster_path", ""))
            val year = parseYear(r.optString("release_date", r.optString("first_air_date", "")))

            val itemPayload = JSONObject().apply {
                put("type", if (isTv) "tv" else "movie")
                put("id", id)
                put("title", title)
            }.toString()

            if (isTv) {
                items.add(newTvSeriesSearchResponse(title, itemPayload, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                })
            } else {
                items.add(newMovieSearchResponse(title, itemPayload, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                })
            }
        }

        val totalPages = json.optInt("total_pages", 1)
        return newHomePageResponse(request.name, items, hasNext = page < totalPages && page < 25)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = tmdbGet("/search/multi?query=$q&page=1&include_adult=false")
            ?: tmdbGet("/search/movie?query=$q&page=1&include_adult=false")
            ?: return emptyList()

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val mediaType = r.optString("media_type", if (r.has("first_air_date")) "tv" else "movie")
            if (mediaType != "movie" && mediaType != "tv") continue
            val id = r.optInt("id", -1).takeIf { it > 0 } ?: continue
            val isTv = mediaType == "tv"
            val title = r.optString("title", r.optString("name", "Unknown"))
            val poster = posterUrl(r.optString("poster_path", ""))
            val year = parseYear(r.optString("release_date", r.optString("first_air_date", "")))

            val itemPayload = JSONObject().apply {
                put("type", mediaType)
                put("id", id)
                put("title", title)
            }.toString()

            if (isTv) {
                items.add(newTvSeriesSearchResponse(title, itemPayload, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                })
            } else {
                items.add(newMovieSearchResponse(title, itemPayload, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                })
            }
        }

        return items
    }

    // =========================================================================
    // Load Details (Movie / TV Series)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        val parsed = parseDataUrl(url) ?: throw ErrorLoadingException("Invalid CineJoy URL: $url")
        val id = parsed.optInt("id")
        val isTv = parsed.optString("type") == "tv"

        if (!isTv) {
            val json = tmdbGet("/movie/$id?append_to_response=credits,external_ids")
                ?: throw ErrorLoadingException("Movie details not found for TMDB id: $id")

            val title = json.optString("title", parsed.optString("title", "Unknown"))
            val poster = posterUrl(json.optString("poster_path", ""))
            val backdrop = posterUrl(json.optString("backdrop_path", ""), "w780")
            val year = parseYear(json.optString("release_date", ""))
            val overview = json.optString("overview", "")
            val runtime = json.optInt("runtime", 0).takeIf { it > 0 }
            val genres = (0 until (json.optJSONArray("genres")?.length() ?: 0)).mapNotNull {
                json.optJSONArray("genres")?.optJSONObject(it)?.optString("name")
            }

            val linkData = JSONObject().apply {
                put("type", "movie")
                put("id", id)
                put("title", title)
            }.toString()

            return newMovieLoadResponse(title, url, TvType.Movie, linkData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.duration = runtime
                this.tags = genres
            }
        } else {
            val json = tmdbGet("/tv/$id?append_to_response=credits,external_ids")
                ?: throw ErrorLoadingException("TV Series details not found for TMDB id: $id")

            val title = json.optString("name", parsed.optString("title", "Unknown"))
            val poster = posterUrl(json.optString("poster_path", ""))
            val backdrop = posterUrl(json.optString("backdrop_path", ""), "w780")
            val year = parseYear(json.optString("first_air_date", ""))
            val overview = json.optString("overview", "")
            val status = if (json.optString("status") == "Ended") ShowStatus.Completed else ShowStatus.Ongoing
            val genres = (0 until (json.optJSONArray("genres")?.length() ?: 0)).mapNotNull {
                json.optJSONArray("genres")?.optJSONObject(it)?.optString("name")
            }

            val seasonsJson = json.optJSONArray("seasons") ?: JSONArray()
            val seasonNums = mutableListOf<Int>()
            for (i in 0 until seasonsJson.length()) {
                val s = seasonsJson.optJSONObject(i) ?: continue
                val sn = s.optInt("season_number", -1)
                val ec = s.optInt("episode_count", 0)
                if (sn > 0 && ec > 0) seasonNums.add(sn)
            }
            seasonNums.sort()

            // Tải danh sách tập phim theo từng mùa
            val episodes = mutableListOf<Episode>()
            for (sNum in seasonNums) {
                val sJson = tmdbGet("/tv/$id/season/$sNum")
                val epArray = sJson?.optJSONArray("episodes") ?: JSONArray()
                for (ei in 0 until epArray.length()) {
                    val ep = epArray.optJSONObject(ei) ?: continue
                    val rawEpNum = ep.optInt("episode_number", ei + 1)
                    val fixedEpNum = fixEpisode(rawEpNum)
                    val epName = ep.optString("name", "Episode $fixedEpNum")
                    val still = posterUrl(ep.optString("still_path", ""))
                    val epPlot = ep.optString("overview", "")

                    val epData = JSONObject().apply {
                        put("type", "tv")
                        put("id", id)
                        put("s", sNum)
                        put("e", fixedEpNum)
                        put("title", title)
                    }.toString()

                    episodes.add(newEpisode(epData) {
                        this.name = epName
                        this.season = sNum
                        this.episode = fixedEpNum
                        this.posterUrl = still
                        this.description = epPlot
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.showStatus = status
                this.tags = genres
            }
        }
    }

    private fun parseDataUrl(url: String): JSONObject? {
        return runCatching {
            if (url.startsWith("{")) JSONObject(url)
            else {
                // Regex phân tích URL dạng /movie/123 hoặc /tv/123/1/1
                val m = Regex("/(movie|tv|series)/(\\d+)(?:/(\\d+)/(\\d+))?").find(url)
                if (m != null) {
                    val type = if (m.groupValues[1] == "series") "tv" else m.groupValues[1]
                    val id = m.groupValues[2].toInt()
                    JSONObject().apply {
                        put("type", type)
                        put("id", id)
                        if (m.groupValues[3].isNotBlank()) put("s", m.groupValues[3].toInt())
                        if (m.groupValues[4].isNotBlank()) put("e", fixEpisode(m.groupValues[4].toInt()))
                    }
                } else null
            }
        }.getOrNull()
    }

    // =========================================================================
    // Link Extraction (VidRock AES-GCM + NxSha AES-CBC + VidLove)
    // =========================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseDataUrl(data) ?: return false
        val type = parsed.optString("type", "movie")
        val id = parsed.optInt("id", 0)
        if (id <= 0) return false
        val s = parsed.optInt("s", 1)
        val e = parsed.optInt("e", 1)

        val log = StringBuilder()
        log.append("CineJoy loadLinks: type=$type id=$id s=$s e=$e\n")

        var totalLinks = 0
        val seenUrls = HashSet<String>()

        // ---------------------------------------------------------------------
        // 1. VidRock API (Giải mã AES-GCM)
        // ---------------------------------------------------------------------
        runCatching {
            val vrPath = if (type == "tv") "tv/$id/$s/$e" else "movie/$id"
            val vrUrl = "$vidrockBase/api/$vrPath"
            val vrResp = app.get(
                vrUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$vidrockBase/",
                    "Accept" to "application/json"
                ),
                timeout = 10L
            ).text

            val vrJson = JSONObject(vrResp)
            val keys = vrJson.keys()
            while (keys.hasNext()) {
                val serverName = keys.next()
                val serverObj = vrJson.optJSONObject(serverName) ?: continue
                val token = serverObj.optString("url", "")
                if (token.isBlank()) continue

                val decryptedUrl = decryptVidRock(token)
                if (decryptedUrl.startsWith("http") && !blockedHostRegex.containsMatchIn(decryptedUrl)) {
                    val lang = serverObj.optString("language", "")
                    val label = "CineJoy Rock $serverName" + (if (lang.isNotBlank()) " - $lang" else "")

                    if (decryptedUrl.contains("streamrk.site/playlist")) {
                        // Trích xuất các độ phân giải MP4 từ playlist
                        extractStreamRkMp4s(decryptedUrl, label, callback)
                    } else if (seenUrls.add(decryptedUrl)) {
                        callback(
                            newExtractorLink(
                                name = "CineJoy",
                                source = "$label (HLS)",
                                url = decryptedUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$vidrockBase/"
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "User-Agent" to ua,
                                    "Referer" to "$vidrockBase/"
                                )
                            }
                        )
                        totalLinks++
                    }
                }
            }
        }.onFailure { ex ->
            log.append("VidRock error: ${ex.message}\n")
        }

        // ---------------------------------------------------------------------
        // 2. NxSha API (Giải mã OpenSSL AES-CBC với EVP_BytesToKey)
        // ---------------------------------------------------------------------
        runCatching {
            val nxReq = JSONObject().apply {
                put("tmdbId", id.toString())
                put("imdb_id", "")
                put("type", type)
                put("season", if (type == "tv") s else 0)
                put("episode", if (type == "tv") e else 0)
            }
            val encodedQuery = nxEncode(nxReq)
            val srvResp = app.get(
                "$nxBase/api/servers?q=$encodedQuery",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$nxBase/",
                    "Accept" to "application/json"
                ),
                timeout = 10L
            ).text

            val srvHash = JSONObject(srvResp).optString("_hash", "")
            if (srvHash.isNotBlank()) {
                val srvDec = nxDecode(srvHash)
                val servers = srvDec.optJSONArray("servers") ?: JSONArray()

                val nxProviders = listOf(
                    "nitro", "mbox", "watchout", "awsind", "rive-citadel",
                    "castle", "streamflix", "purstream", "vixolity", "hdhub4u",
                    "vidapi", "stvv", "bkl-blast", "ophm", "river"
                )

                val selectedServers = mutableListOf<Pair<String, String>>()
                for (si in 0 until servers.length()) {
                    val sObj = servers.optJSONObject(si) ?: continue
                    val scraper = sObj.optString("scraper", "")
                    val sName = sObj.optString("name", scraper)
                    if (nxProviders.any { scraper.contains(it, ignoreCase = true) }) {
                        selectedServers.add(sName to scraper)
                    }
                }

                // Lấy nguồn từ các máy chủ scraper hàng đầu
                for ((sName, scraper) in selectedServers.take(8)) {
                    runCatching {
                        val srcReq = JSONObject().apply {
                            put("ex_lang", true)
                            put("provider", scraper)
                            put("tmdbId", id.toString())
                            put("imdb_id", "")
                            put("type", type)
                            put("season", if (type == "tv") s else 0)
                            put("episode", if (type == "tv") e else 0)
                        }
                        val srcQ = nxEncode(srcReq)
                        val srcResp = app.get(
                            "$nxBase/api/sources?q=$srcQ",
                            headers = mapOf(
                                "User-Agent" to ua,
                                "Referer" to "$nxBase/"
                            ),
                            timeout = 6L
                        ).text

                        val srcHash = JSONObject(srcResp).optString("_hash", "")
                        if (srcHash.isNotBlank()) {
                            val srcDec = nxDecode(srcHash)
                            val sources = srcDec.optJSONArray("sources") ?: JSONArray()
                            for (j in 0 until sources.length()) {
                                val item = sources.optJSONObject(j) ?: continue
                                val sUrl = item.optString("url", "")
                                if (!sUrl.startsWith("http") || blockedHostRegex.containsMatchIn(sUrl)) continue
                                if (!seenUrls.add(sUrl)) continue

                                val qualityLabel = item.optString("label", item.optString("quality", "HD"))
                                    .replace(Regex("\\[.*?\\]"), "").trim()
                                val dispName = "CineJoy $sName - $qualityLabel"
                                val isM3u8 = sUrl.contains(".m3u8") || item.optString("type") == "hls"

                                callback(
                                    newExtractorLink(
                                        name = "CineJoy",
                                        source = dispName,
                                        url = sUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "$nxBase/"
                                        this.quality = parseQualityValue(qualityLabel)
                                        this.headers = mapOf(
                                            "User-Agent" to ua,
                                            "Referer" to "$nxBase/"
                                        )
                                    }
                                )
                                totalLinks++
                            }

                            // Lấy phụ đề kèm theo
                            val subs = srcDec.optJSONArray("subtitles") ?: JSONArray()
                            for (subIdx in 0 until subs.length()) {
                                val sub = subs.optJSONObject(subIdx) ?: continue
                                val subUrl = sub.optString("url", sub.optString("file", ""))
                                val subLang = sub.optString("label", sub.optString("language", "English"))
                                if (subUrl.startsWith("http")) {
                                    subtitleCallback(SubtitleFile(subLang, subUrl))
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure { ex ->
            log.append("NxSha error: ${ex.message}\n")
        }

        // ---------------------------------------------------------------------
        // 3. VidLove API (Các máy chủ chính thức CineJoy)
        // ---------------------------------------------------------------------
        runCatching {
            val vlBase = if (type == "tv") {
                "/tv?id=$id&season=$s&episode=$e&mode=json"
            } else {
                "/movie?id=$id&mode=json"
            }

            val vlVariants = listOf(
                "CineJoy Nebula" to "",
                "CineJoy Solara" to "&sources=warden",
                "CineJoy Lisbon 4K" to "&sources=moviebox",
                "CineJoy Athens" to "&sources=vidapi",
                "CineJoy MovieBox 2" to "&sources=moviebox2",
                "CineJoy PEKKA" to "&sources=cinefreak"
            )

            for ((label, querySuffix) in vlVariants) {
                runCatching {
                    val resp = app.get(
                        "$vidloveBase$vlBase$querySuffix",
                        headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to playerRef,
                            "Origin" to "https://cinejoy.pk",
                            "Accept" to "application/json"
                        ),
                        timeout = 6L
                    ).text

                    val j = JSONObject(resp)
                    val src = j.optJSONObject("source")
                    val sUrl = src?.optString("url", "") ?: ""
                    if (sUrl.startsWith("http") && !blockedHostRegex.containsMatchIn(sUrl) && seenUrls.add(sUrl)) {
                        val quality = src.optString("quality", "1080p")
                        callback(
                            newExtractorLink(
                                name = "CineJoy",
                                source = "$label ($quality)",
                                url = sUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = playerRef
                                this.quality = Qualities.P1080.value
                                this.headers = mapOf(
                                    "User-Agent" to ua,
                                    "Referer" to playerRef,
                                    "Origin" to "https://cinejoy.pk"
                                )
                            }
                        )
                        totalLinks++

                        // Phụ đề từ VidLove
                        val subs = j.optJSONArray("subtitles") ?: JSONArray()
                        for (si in 0 until subs.length()) {
                            val sub = subs.optJSONObject(si) ?: continue
                            val subFile = sub.optString("file", sub.optString("url", ""))
                            val subLabel = sub.optString("label", sub.optString("language", "English"))
                            if (subFile.startsWith("http")) {
                                subtitleCallback(SubtitleFile(subLabel, subFile))
                            }
                        }
                    }
                }
            }
        }.onFailure { ex ->
            log.append("VidLove error: ${ex.message}\n")
        }

        log.append("Tổng liên kết tìm được: $totalLinks\n")
        if (DEBUG && totalLinks == 0) {
            copyLogToClipboard(log.toString())
        }

        return totalLinks > 0
    }

    private suspend fun extractStreamRkMp4s(
        playlistUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val resp = app.get(
                playlistUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$vidrockBase/"
                ),
                timeout = 8L
            ).text

            val arr = JSONArray(resp)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val u = item.optString("url", "")
                if (!u.startsWith("http") || blockedHostRegex.containsMatchIn(u)) continue
                val res = item.optInt("resolution", 720)
                val qVal = when (res) {
                    2160 -> Qualities.P2160.value
                    1080 -> Qualities.P1080.value
                    720 -> Qualities.P720.value
                    480 -> Qualities.P480.value
                    else -> Qualities.Unknown.value
                }
                callback(
                    newExtractorLink(
                        name = "CineJoy",
                        source = "$label MP4 (${res}p)",
                        url = u,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$vidrockBase/"
                        this.quality = qVal
                        this.headers = mapOf(
                            "User-Agent" to ua,
                            "Referer" to "$vidrockBase/"
                        )
                    }
                )
            }
        }
    }

    private fun parseQualityValue(label: String): Int {
        val l = label.lowercase()
        return when {
            l.contains("4k") || l.contains("2160") -> Qualities.P2160.value
            l.contains("1080") -> Qualities.P1080.value
            l.contains("720") -> Qualities.P720.value
            l.contains("480") -> Qualities.P480.value
            l.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    // =========================================================================
    // VidRock AES-256-GCM Decryption
    // =========================================================================

    private fun decryptVidRock(token: String): String {
        return runCatching {
            val parts = token.split(".")
            if (parts.size != 3) return ""
            val iv = Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP)
            val tag = Base64.decode(parts[2], Base64.URL_SAFE or Base64.NO_WRAP)

            // Ghép ciphertext và auth tag cho Java AES/GCM/NoPadding
            val combined = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
            System.arraycopy(tag, 0, combined, ciphertext.size, tag.size)

            val keyBytes = hexStringToByteArray(vidrockKeyHex)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(tag.size * 8, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

            val plain = cipher.doFinal(combined)
            String(plain, Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    // =========================================================================
    // NxSha OpenSSL AES-256-CBC Encryption & Decryption (EVP_BytesToKey)
    // =========================================================================

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        var currentHash = ByteArray(0)
        val totalNeeded = keyLen + ivLen
        val keyAndIv = ByteArray(totalNeeded)
        var count = 0

        while (count < totalNeeded) {
            md.reset()
            if (currentHash.isNotEmpty()) {
                md.update(currentHash)
            }
            md.update(password)
            md.update(salt)
            currentHash = md.digest()

            val toCopy = minOf(currentHash.size, totalNeeded - count)
            System.arraycopy(currentHash, 0, keyAndIv, count, toCopy)
            count += toCopy
        }

        val key = ByteArray(keyLen)
        val iv = ByteArray(ivLen)
        System.arraycopy(keyAndIv, 0, key, 0, keyLen)
        System.arraycopy(keyAndIv, keyLen, iv, 0, ivLen)
        return Pair(key, iv)
    }

    private fun nxDecode(hashStr: String): JSONObject {
        return runCatching {
            val raw = Base64.decode(hashStr, Base64.DEFAULT)
            if (raw.size < 16) return JSONObject()
            // Kiểm tra "Salted__" header (8 bytes)
            val saltedHeader = String(raw, 0, 8, Charsets.US_ASCII)
            val (salt, ciphertext) = if (saltedHeader == "Salted__") {
                val s = ByteArray(8)
                System.arraycopy(raw, 8, s, 0, 8)
                val c = ByteArray(raw.size - 16)
                System.arraycopy(raw, 16, c, 0, c.size)
                Pair(s, c)
            } else {
                Pair(ByteArray(8), raw)
            }

            val (key, iv) = evpBytesToKey(nxPass.toByteArray(Charsets.UTF_8), salt, 32, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val decrypted = cipher.doFinal(ciphertext)
            JSONObject(String(decrypted, Charsets.UTF_8))
        }.getOrDefault(JSONObject())
    }

    private fun nxEncode(obj: JSONObject): String {
        return runCatching {
            val plaintext = obj.toString().toByteArray(Charsets.UTF_8)
            val salt = ByteArray(8)
            SecureRandom().nextBytes(salt)

            val (key, iv) = evpBytesToKey(nxPass.toByteArray(Charsets.UTF_8), salt, 32, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))

            val ciphertext = cipher.doFinal(plaintext)
            val header = "Salted__".toByteArray(Charsets.US_ASCII)
            val result = ByteArray(header.size + salt.size + ciphertext.size)
            System.arraycopy(header, 0, result, 0, header.size)
            System.arraycopy(salt, 0, result, header.size, salt.size)
            System.arraycopy(ciphertext, 0, result, header.size + salt.size, ciphertext.size)

            URLEncoder.encode(Base64.encodeToString(result, Base64.NO_WRAP), "UTF-8")
        }.getOrDefault("")
    }

    // =========================================================================
    // Diagnostics & Debugging
    // =========================================================================

    private fun copyLogToClipboard(logText: String) {
        runCatching {
            val ctx = com.lagradost.cloudstream3.AcraApplication.context
            val cm = ctx?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("CineJoy Debug Log", logText))
        }
    }
}
