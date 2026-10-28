package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
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

/**
 * CloudStream 3 Provider cho CineJoy (cinejoy.pk)
 * Chuyển đổi hoàn chỉnh từ SkyStream plugin v6
 *
 * Tính năng:
 * - TMDB Catalog & Tìm kiếm với cơ chế chống chặn DNS (tự động chuyển api.tmdb.org khi api.themoviedb.org bị lỗi ::1).
 * - Đa nguồn phát chính xác từ CineJoy:
 *     1. VidRock API (Giải mã AES-256-GCM lấy luồng Orion, Luna, Atlas...)
 *     2. NxSha API (Giải mã OpenSSL AES-256-CBC lấy các máy chủ Nitro, MbPly, AwsPly, Citadel, CastVid, StremFx, PureWave, Vixo...)
 *     3. VidLove API (Các máy chủ Nebula, Solara, Lisbon 4K, Athens...)
 * - Hỗ trợ phụ đề đa ngôn ngữ từ các nguồn phát (gửi qua subtitleCallback).
 * - Sắp xếp ưu tiên máy chủ tốc độ cao và lọc bỏ link rác.
 */
class CineJoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "CineJoy"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // Khóa TMDB từ plugin SkyStream
    private val tmdbApiKey = "42a3ebef5dc7bfeb0eb352ffbfa50e18"
    private val tmdbOrigins = listOf("https://api.themoviedb.org", "https://api.tmdb.org")
    private val tmdbImgBase = "https://image.tmdb.org/t/p"

    // Các máy chủ phát luồng của CineJoy
    private val vidrockBase = "https://streamrk.site"
    private val nxBase = "https://nxsha.site"
    private val vidloveBase = "https://cloudstream.vidlove.stream"
    private val playerRef = "https://cinejoy.pk/"

    // User-Agent chuẩn
    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // Regex phát hiện các trang bị nhà mạng chặn hoặc trang cảnh báo
    private val blockedHostRegex = Regex(
        "internetpositif|trustpositif|blockpage|block\\.|blocked\\.|lawfulblock|zapret|safebrowse",
        RegexOption.IGNORE_CASE
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // =========================================================================
    // Hỗ trợ TMDB API chống lỗi mạng IPv6 ::1 (ConnectPlan.kt error)
    // =========================================================================

    private fun tmdbGet(pathAndQuery: String): JSONObject? {
        val glue = if (pathAndQuery.contains("?")) "&" else "?"
        for (origin in tmdbOrigins) {
            val fullUrl = "$origin/3$pathAndQuery${glue}api_key=$tmdbApiKey"
            try {
                val req = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", ua)
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val body = res.body?.string() ?: return@use
                        return JSONObject(body)
                    }
                }
            } catch (_: Exception) {
                // Tự động thử domain tiếp theo (api.tmdb.org) nếu api.themoviedb.org bị nghẽn
            }
        }
        return null
    }

    private fun posterUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path else "$tmdbImgBase/$size$path"
    }

    private fun parseYear(dateStr: String?): Int? {
        if (dateStr.isNullOrBlank()) return null
        return Regex("^(\\d{4})").find(dateStr)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun fixEpisode(ep: Int): Int {
        // Chuẩn hóa số tập giống logic SkyStream: ep % 1000 nếu lớn hơn 1000
        return if (ep > 1000) (ep % 1000).takeIf { it > 0 } ?: ep else ep
    }

    // =========================================================================
    // Trang chủ (MainPage) & Tìm kiếm (Search)
    // =========================================================================

    override val mainPage = mainPageOf(
        "/trending/all/day" to "Trending Now",
        "/movie/popular" to "Popular Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/movie/now_playing" to "Now Playing",
        "/tv/popular" to "Popular TV Shows",
        "/tv/top_rated" to "Top Rated TV Shows",
        "/tv/on_the_air" to "TV Shows On The Air",
        "/discover/movie?with_genres=28" to "Action Movies",
        "/discover/movie?with_genres=35" to "Comedy Movies",
        "/discover/movie?with_genres=27" to "Horror Movies",
        "/discover/movie?with_genres=878" to "Sci-Fi Movies",
        "/discover/tv?with_genres=10759" to "Action & Adventure Series",
        "/discover/tv?with_genres=18" to "Drama Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val glue = if (request.data.contains("?")) "&" else "?"
        val json = tmdbGet("${request.data}${glue}page=$page")
            ?: return newHomePageResponse(request.name, emptyList())

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val id = r.optInt("id", -1).takeIf { it > 0 } ?: continue

            // Nhận diện Movie hoặc TV Show
            val mediaType = r.optString("media_type")
            val isTv = if (mediaType.isNotBlank()) {
                mediaType == "tv"
            } else {
                r.has("first_air_date") || request.data.contains("/tv")
            }

            val title = r.optString("title", r.optString("name", "Unknown"))
            val poster = posterUrl(r.optString("poster_path", r.optString("backdrop_path", "")))
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

        return newHomePageResponse(listOf(HomePageList(request.name, items)), hasNext = page < json.optInt("total_pages", 1))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = tmdbGet("/search/multi?query=$q&page=1&include_adult=false") ?: return emptyList()

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val mediaType = r.optString("media_type")
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
    // Chi tiết phim (Movie / TV Series)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse? {
        val payload = parseDataUrl(url) ?: return null
        val type = payload.optString("type", "movie")
        val id = payload.optInt("id", 0)
        if (id <= 0) return null

        if (type == "movie") {
            val json = tmdbGet("/movie/$id?append_to_response=external_ids") ?: return null
            val title = json.optString("title", "Unknown")
            val poster = posterUrl(json.optString("poster_path", ""))
            val backdrop = posterUrl(json.optString("backdrop_path", ""), "w780")
            val year = parseYear(json.optString("release_date", ""))
            val overview = json.optString("overview", "")
            val duration = json.optInt("runtime", 0).takeIf { it > 0 }

            val genres = mutableListOf<String>()
            val genresJson = json.optJSONArray("genres") ?: JSONArray()
            for (i in 0 until genresJson.length()) {
                genresJson.optJSONObject(i)?.optString("name")?.let { genres.add(it) }
            }

            val data = JSONObject().apply {
                put("type", "movie")
                put("id", id)
                put("title", title)
            }.toString()

            return newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.duration = duration
                this.score = Score.from10(json.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0 })
                this.tags = genres
            }
        } else {
            val json = tmdbGet("/tv/$id?append_to_response=external_ids") ?: return null
            val title = json.optString("name", "Unknown")
            val poster = posterUrl(json.optString("poster_path", ""))
            val backdrop = posterUrl(json.optString("backdrop_path", ""), "w780")
            val year = parseYear(json.optString("first_air_date", ""))
            val overview = json.optString("overview", "")
            val status = when (json.optString("status", "").lowercase()) {
                "ended", "canceled" -> ShowStatus.Completed
                else -> ShowStatus.Ongoing
            }

            val genres = mutableListOf<String>()
            val genresJson = json.optJSONArray("genres") ?: JSONArray()
            for (i in 0 until genresJson.length()) {
                genresJson.optJSONObject(i)?.optString("name")?.let { genres.add(it) }
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
                    val epScore = Score.from10(ep.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0 })

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
                        this.score = epScore
                    })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.score = Score.from10(json.optDouble("vote_average", 0.0).toFloat().takeIf { it > 0 })
                this.showStatus = status
                this.tags = genres
            }
        }
    }

    private fun parseDataUrl(url: String): JSONObject? {
        return runCatching {
            if (url.startsWith("{")) JSONObject(url)
            else {
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
    // Trích xuất Link xem phim (VidRock AES-GCM + NxSha AES-CBC + VidLove)
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
                            for (si in 0 until subs.length()) {
                                val sub = subs.optJSONObject(si) ?: continue
                                val subUrl = sub.optString("url", sub.optString("file", ""))
                                val subLang = sub.optString("label", sub.optString("language", "English"))
                                if (subUrl.startsWith("http")) {
                                    subtitleCallback(newSubtitleFile(subLang, subUrl))
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
                            val subLang = sub.optString("label", sub.optString("language", "English"))
                            if (subFile.startsWith("http")) {
                                subtitleCallback(newSubtitleFile(subLang, subFile))
                            }
                        }
                    }
                }
            }
        }.onFailure { ex ->
            log.append("VidLove error: ${ex.message}\n")
        }

        if (DEBUG) copyLogToClipboard(log.toString())
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
                val obj = arr.optJSONObject(i) ?: continue
                val u = obj.optString("url", "")
                if (u.startsWith("http")) {
                    val res = obj.optInt("resolution", 1080)
                    callback(
                        newExtractorLink(
                            name = "CineJoy",
                            source = "$label (MP4 ${res}p)",
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$vidrockBase/"
                            this.quality = parseQualityValue("${res}p")
                            this.headers = mapOf(
                                "User-Agent" to ua,
                                "Referer" to "$vidrockBase/"
                            )
                        }
                    )
                }
            }
        }
    }

    private fun parseQualityValue(q: String): Int {
        val s = q.lowercase()
        return when {
            s.contains("4k") || s.contains("2160") -> Qualities.P2160.value
            s.contains("1440") || s.contains("2k") -> Qualities.P1440.value
            s.contains("1080") || s.contains("fhd") -> Qualities.P1080.value
            s.contains("720") || s.contains("hd") -> Qualities.P720.value
            s.contains("480") || s.contains("sd") -> Qualities.P480.value
            s.contains("360") -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }

    // =========================================================================
    // Thuật toán giải mã AES-256-GCM (VidRock) & OpenSSL AES-CBC (NxSha)
    // =========================================================================

    private fun decryptVidRock(token: String): String {
        return try {
            val parts = token.split(".")
            if (parts.size != 3) return ""
            val iv = hexToBytes(parts[0])
            val ciphertext = hexToBytes(parts[1])
            val tag = hexToBytes(parts[2])

            val cipherData = ByteArray(ciphertext.size + tag.size)
            System.arraycopy(ciphertext, 0, cipherData, 0, ciphertext.size)
            System.arraycopy(tag, 0, cipherData, ciphertext.size, tag.size)

            val key = hexToBytes("49646e746b6e6f7777686174746f736179736f736875747468656675636b7570")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            val keySpec = SecretKeySpec(key, "AES")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, spec)

            val decrypted = cipher.doFinal(cipherData)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private val nxSecretKey = "49646e746b6e6f7777686174746f736179736f736875747468656675636b7570"

    private fun nxEncode(obj: JSONObject): String {
        return try {
            val plain = obj.toString()
            val salt = ByteArray(8).apply { SecureRandom().nextBytes(this) }
            val (key, iv) = evpBytesToKey(nxSecretKey.toByteArray(Charsets.UTF_8), salt, 32, 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))

            val magic = "Salted__".toByteArray(Charsets.UTF_8)
            val combined = ByteArray(magic.size + salt.size + encrypted.size)
            System.arraycopy(magic, 0, combined, 0, magic.size)
            System.arraycopy(salt, 0, combined, magic.size, salt.size)
            System.arraycopy(encrypted, 0, combined, magic.size + salt.size, encrypted.size)

            val b64 = Base64.encodeToString(combined, Base64.NO_WRAP)
            URLEncoder.encode(b64, "UTF-8")
        } catch (_: Exception) {
            ""
        }
    }

    private fun nxDecode(hashStr: String): JSONObject {
        return try {
            val data = Base64.decode(hashStr, Base64.DEFAULT)
            if (data.size < 16) return JSONObject()
            val magic = String(data.copyOfRange(0, 8), Charsets.UTF_8)
            val (salt, ciphertext) = if (magic == "Salted__") {
                data.copyOfRange(8, 16) to data.copyOfRange(16, data.size)
            } else {
                ByteArray(8) to data
            }

            val (key, iv) = evpBytesToKey(nxSecretKey.toByteArray(Charsets.UTF_8), salt, 32, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(ciphertext)
            JSONObject(String(decrypted, Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun evpBytesToKey(
        password: ByteArray,
        salt: ByteArray,
        keyLen: Int,
        ivLen: Int
    ): Pair<ByteArray, ByteArray> {
        val totalLen = keyLen + ivLen
        val md = MessageDigest.getInstance("MD5")
        var currentHash = ByteArray(0)
        val keyAndIv = ByteArray(totalLen)
        var offset = 0

        while (offset < totalLen) {
            md.reset()
            if (currentHash.isNotEmpty()) md.update(currentHash)
            md.update(password)
            md.update(salt)
            currentHash = md.digest()

            val toCopy = minOf(currentHash.size, totalLen - offset)
            System.arraycopy(currentHash, 0, keyAndIv, offset, toCopy)
            offset += toCopy
        }

        val key = keyAndIv.copyOfRange(0, keyLen)
        val iv = keyAndIv.copyOfRange(keyLen, keyLen + ivLen)
        return key to iv
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun copyLogToClipboard(text: String) {
        runCatching {
            val act = com.lagradost.cloudstream3.CommonActivity.activity ?: return
            act.runOnUiThread {
                runCatching {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("CineJoy log", text.take(60000)))
                    android.widget.Toast.makeText(
                        act, "Đã chép log CineJoy vào clipboard", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
