package com.lagradost

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class CineHDPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineHDProvider())
    }
}

/**
 * CloudStream 3 Provider cho CineHD (cinehd.vc)
 * Bản cập nhật v2.1.0 - Khắc phục triệt để hiện tượng "Phát sai phim"
 */
class CineHDProvider : MainAPI() {
    override var mainUrl = "https://cinehd.vc"
    override var name = "CineHD"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // TMDB Hosts chống chặn DNS mạng Việt Nam (::1)
    private val tmdbHosts = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private var tmdbPrefer = 0

    // TMDB API Keys dự phòng
    private val tmdbKeys = listOf(
        "439c478a771f35c05022f9feabcca01c",
        "8476a7ab80ad76f0936744df0430e67c",
        "15d2ea6d0dc1d476efbca3eba2b9bbfb"
    )

    private val tmdbBearerToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"

    // Máy chủ phát
    private val vidrockBase = "https://vidrock.net"
    private val vidrockKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private val nxBase = "https://nxsha.space"
    private val nxPass = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"

    // Bỏ qua các host lưu trữ file rác/chậm
    private val blockedHostRegex = Regex(
        "gdflix|katdrive|sendcm|1fichier|fastpic|faststream|gdriveplayer|gdtot|filepress|sharer",
        RegexOption.IGNORE_CASE
    )

    override val mainPage = mainPageOf(
        "/trending/movie/week" to "Trending Movies",
        "/trending/tv/week" to "Trending TV Shows",
        "/movie/now_playing" to "In Theaters",
        "/movie/popular" to "Popular Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/popular" to "Popular Series",
        "/tv/top_rated" to "Top Rated Series"
    )

    // =========================================================================
    // TMDB Network & Catalog
    // =========================================================================

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"

        for (k in tmdbHosts.indices) {
            val hi = (tmdbPrefer + k) % tmdbHosts.size
            val host = tmdbHosts[hi]

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

            // Fallback Token
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

    private fun posterUrl(path: String?, size: String = "w500"): String {
        if (path.isNullOrBlank()) return ""
        val clean = path.removePrefix("/")
        val tmdbImg = "https://image.tmdb.org/t/p/$size/$clean"
        return "https://wsrv.nl/?url=${URLEncoder.encode(tmdbImg, "UTF-8")}&w=500&output=webp"
    }

    private fun parseYear(date: String?): Int? {
        if (date.isNullOrBlank()) return null
        val m = Regex("^(\\d{4})").find(date) ?: return null
        return m.groupValues[1].toIntOrNull()
    }

    private fun toSearchResponse(obj: JSONObject): SearchResponse? {
        val id = obj.optInt("id", 0)
        if (id <= 0) return null

        val mediaType = obj.optString("media_type")
        val isTv = if (mediaType.isNotBlank()) {
            mediaType == "tv"
        } else {
            obj.has("first_air_date") || obj.has("name")
        }

        val type = if (isTv) "tv" else "movie"
        val title = obj.optString(if (isTv) "name" else "title", "").trim()
        if (title.isBlank()) return null

        val poster = posterUrl(obj.optString("poster_path", ""))
        val dateStr = obj.optString(if (isTv) "first_air_date" else "release_date", "")
        val year = parseYear(dateStr)

        val linkData = JSONObject().apply {
            put("type", type)
            put("id", id)
            put("title", title)
            put("year", year ?: 0)
        }.toString()

        return if (isTv) {
            newTvSeriesSearchResponse(title, linkData, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, linkData, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "${request.data}${if (request.data.contains("?")) "&" else "?"}page=$page"
        val json = tmdbGet(pageUrl) ?: return newHomePageResponse(request.name, emptyList())

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val itemObj = results.optJSONObject(i) ?: continue
            val item = toSearchResponse(itemObj) ?: continue
            items.add(item)
        }

        val hasNext = page < json.optInt("total_pages", 1)
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val json = tmdbGet("/search/multi?query=$encoded&include_adult=false&page=1") ?: return emptyList()

        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            val item = toSearchResponse(obj) ?: continue
            items.add(item)
        }
        return items
    }

    // =========================================================================
    // Load Details (Movie / TV Series)
    // =========================================================================

    override suspend fun load(url: String): LoadResponse {
        val parsed = parseDataUrl(url) ?: throw ErrorLoadingException("Invalid CineHD URL: $url")
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
                put("year", year ?: 0)
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

            val episodes = mutableListOf<Episode>()
            for (sNum in seasonNums) {
                val sJson = tmdbGet("/tv/$id/season/$sNum")
                val epArray = sJson?.optJSONArray("episodes") ?: JSONArray()
                for (ei in 0 until epArray.length()) {
                    val ep = epArray.optJSONObject(ei) ?: continue
                    val epNum = ep.optInt("episode_number", ei + 1)
                    val epName = ep.optString("name", "Episode $epNum")
                    val still = posterUrl(ep.optString("still_path", ""))
                    val epPlot = ep.optString("overview", "")

                    val epData = JSONObject().apply {
                        put("type", "tv")
                        put("id", id)
                        put("s", sNum)
                        put("e", epNum)
                        put("title", title)
                        put("year", year ?: 0)
                    }.toString()

                    episodes.add(newEpisode(epData) {
                        this.name = epName
                        this.season = sNum
                        this.episode = epNum
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
                val m = Regex("/(movie|tv|series)/(\\d+)(?:/(\\d+)/(\\d+))?").find(url)
                if (m != null) {
                    val type = if (m.groupValues[1] == "series") "tv" else m.groupValues[1]
                    val id = m.groupValues[2].toInt()
                    JSONObject().apply {
                        put("type", type)
                        put("id", id)
                        if (m.groupValues[3].isNotBlank()) put("s", m.groupValues[3].toInt())
                        if (m.groupValues[4].isNotBlank()) put("e", m.groupValues[4].toInt())
                    }
                } else null
            }
        }.getOrNull()
    }

    // =========================================================================
    // Bộ lọc chống phát sai phim nghiêm ngặt (Strict Title Matching Filter)
    // =========================================================================

    private fun isTitleMatching(url: String, label: String, expectedTitle: String): Boolean {
        if (expectedTitle.isBlank()) return true

        val decodedUrl = runCatching { URLDecoder.decode(url, "UTF-8").lowercase() }.getOrDefault(url.lowercase())
        val combined = "$decodedUrl ${label.lowercase()}"

        val isDirectHlsStream = decodedUrl.contains("/hls/") || decodedUrl.contains("/stream2/") ||
                decodedUrl.contains("kkphimplayer") || decodedUrl.contains("phim1280") ||
                decodedUrl.contains("opstream") || decodedUrl.contains("nxstreamhub")

        val hasFileSignature = decodedUrl.contains(".mkv") || decodedUrl.contains(".mp4") ||
                decodedUrl.contains("/movies/") || decodedUrl.contains("zipdisk") ||
                decodedUrl.contains("pixeldrain") || combined.contains("bluray")

        if (hasFileSignature && !isDirectHlsStream) {
            val stopWords = setOf(
                "the", "a", "an", "and", "of", "in", "on", "at", "to", "for", "with",
                "part", "movie", "full", "hd", "bluray", "web", "dl", "1080p", "720p", "4k"
            )
            val titleWords = expectedTitle.lowercase()
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.length >= 2 && it !in stopWords }

            if (titleWords.isNotEmpty()) {
                val matchesWord = titleWords.any { word ->
                    Regex("\\b" + Regex.escape(word) + "\\b").containsMatchIn(combined) || combined.contains(word)
                }
                if (!matchesWord) {
                    return false
                }
            }
        }
        return true
    }

    // =========================================================================
    // Link Extraction: Ưu tiên nguồn chuẩn xác 100%, không phát sai phim
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
        val title = parsed.optString("title", "")
        if (id <= 0) return false
        val s = parsed.optInt("s", 1)
        val e = parsed.optInt("e", 1)

        var totalLinks = 0
        val seenUrls = HashSet<String>()

        // ---------------------------------------------------------------------
        // 1. NxSha API: CHỈ DÙNG CÁC NGUỒN CHUẨN XÁC CAO (LOẠI BỎ PHIM RÁC ẤN ĐỘ)
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

                // THỨ TỰ ƯU TIÊN SERVER AN TOÀN:
                val safeScrapersPriority = listOf(
                    "ophim",        // #1: Vietsub Full HD chuẩn 100%
                    "movone",       // #2: Phim gốc 1080p chuẩn
                    "vixolity",     // #3: HLS tốc độ cao
                    "vidapi",       // #4: HLS ổn định
                    "streamflix",   // #5: StreamFlix
                    "rive-citadel", // #6: Citadel
                    "rive-primevids", // #7: PrimeVids
                    "castle",       // #8: CastVid
                    "purstream"     // #9: PureWave
                )

                val orderedServers = mutableListOf<Pair<String, String>>()
                for (targetScraper in safeScrapersPriority) {
                    for (si in 0 until servers.length()) {
                        val sObj = servers.optJSONObject(si) ?: continue
                        val scraper = sObj.optString("scraper", "")
                        val sName = sObj.optString("name", scraper)
                        if (scraper.equals(targetScraper, ignoreCase = true) || scraper.contains(targetScraper, ignoreCase = true)) {
                            orderedServers.add(sName to scraper)
                            break
                        }
                    }
                }

                for ((sName, scraper) in orderedServers) {
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

                                val rawLabel = item.optString("label", item.optString("quality", "1080p"))

                                // KIỂM TRA CHỐNG PHÁT SAI PHIM:
                                if (!isTitleMatching(sUrl, rawLabel, title)) {
                                    continue
                                }

                                if (seenUrls.add(sUrl)) {
                                    val isHls = sUrl.contains(".m3u8") || sUrl.contains("/hls/")
                                    val displayName = when {
                                        scraper.contains("ophim") -> "CineHD • Vietsub ($sName)"
                                        scraper.contains("movone") -> "CineHD • Original 1080p ($rawLabel)"
                                        else -> "CineHD • $sName"
                                    }

                                    callback(
                                        newExtractorLink(
                                            name = "CineHD",
                                            source = displayName,
                                            url = sUrl,
                                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "$nxBase/"
                                            this.quality = if (rawLabel.contains("4k", true)) Qualities.P2160.value else Qualities.P1080.value
                                            this.headers = mapOf(
                                                "User-Agent" to ua,
                                                "Referer" to "$nxBase/"
                                            )
                                        }
                                    )
                                    totalLinks++
                                }
                            }
                        }
                    }
                }
            }
        }.onFailure { ex ->
            android.util.Log.w("CineHD", "NxSha load error: ${ex.message}")
        }

        // ---------------------------------------------------------------------
        // 2. VidRock API (Giải mã AES-256-GCM 12-byte IV khớp 100% TMDB ID)
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
                timeout = 8L
            ).text

            val vrJson = JSONObject(vrResp)
            val keys = vrJson.keys()
            while (keys.hasNext()) {
                val serverName = keys.next()
                val serverObj = vrJson.optJSONObject(serverName) ?: continue
                val token = serverObj.optString("url", "")
                if (token.isBlank()) continue

                val decryptedUrl = decryptVidRockGcm(token)
                if (decryptedUrl.startsWith("http") && !blockedHostRegex.containsMatchIn(decryptedUrl)) {
                    val lang = serverObj.optString("language", "")
                    val label = "CineHD Rock $serverName" + (if (lang.isNotBlank()) " - $lang" else "")

                    if (decryptedUrl.contains("streamrk.site/playlist")) {
                        extractStreamRkMp4s(decryptedUrl, label, callback)
                    } else if (seenUrls.add(decryptedUrl)) {
                        callback(
                            newExtractorLink(
                                name = "CineHD",
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
            android.util.Log.w("CineHD", "VidRock error: ${ex.message}")
        }

        return totalLinks > 0
    }

    private suspend fun extractStreamRkMp4s(
        playlistUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val res = app.get(
                playlistUrl,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "$vidrockBase/"
                ),
                timeout = 10L
            ).text

            val arr = JSONArray(res)
            for (i in 0 until minOf(2, arr.length())) {
                val obj = arr.optJSONObject(i) ?: continue
                val u = obj.optString("url", "")
                val resQuality = obj.optInt("resolution", 1080)
                if (u.startsWith("http")) {
                    callback(
                        newExtractorLink(
                            name = "CineHD",
                            source = "$label • MP4 ${resQuality}p",
                            url = u,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$vidrockBase/"
                            this.quality = if (resQuality >= 1080) Qualities.P1080.value else Qualities.P720.value
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

    // =========================================================================
    // VidRock Cryptography: AES-256-GCM (12-byte IV + Ciphertext + 16-byte Tag)
    // =========================================================================

    private fun decryptVidRockGcm(token: String): String {
        return runCatching {
            val raw = Base64.decode(token.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            if (raw.size < 28) return ""

            val iv = raw.copyOfRange(0, 12)
            val cipherAndTag = raw.copyOfRange(12, raw.size)

            val keyBytes = vidrockKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(128, iv)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
            val decryptedBytes = cipher.doFinal(cipherAndTag)
            String(decryptedBytes, Charsets.UTF_8)
        }.getOrDefault("")
    }

    // =========================================================================
    // NxSha Cryptography: OpenSSL EVP AES-256-CBC ("Salted__" prefix)
    // =========================================================================

    private fun evpBytesToKey(password: String, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        val d = mutableListOf<Byte>()
        var dI = ByteArray(0)

        while (d.size < keyLen + ivLen) {
            md5.reset()
            md5.update(dI)
            md5.update(passBytes)
            md5.update(salt)
            dI = md5.digest()
            for (b in dI) d.add(b)
        }

        val allBytes = d.toByteArray()
        val key = allBytes.copyOfRange(0, keyLen)
        val iv = allBytes.copyOfRange(keyLen, keyLen + ivLen)
        return Pair(key, iv)
    }

    private fun nxEncode(obj: JSONObject): String {
        return runCatching {
            val salt = ByteArray(8)
            SecureRandom().nextBytes(salt)

            val (key, iv) = evpBytesToKey(nxPass, salt, 32, 16)

           val payload = JSONObject(obj.toString()).apply {
                            put("_req_ts", System.currentTimeMillis())
                            put("_req_salt", java.util.UUID.randomUUID().toString().take(8))
                        }.toString()

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))

            val prefix = "Salted__".toByteArray(Charsets.UTF_8)
            val combined = prefix + salt + encrypted
            Base64.encodeToString(combined, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrDefault("")
    }

    private fun nxDecode(b64url: String): JSONObject {
        return runCatching {
            val raw = Base64.decode(b64url.replace('-', '+').replace('_', '/'), Base64.DEFAULT)
            if (raw.size <= 16) return JSONObject()

            val prefix = String(raw.copyOfRange(0, 8), Charsets.UTF_8)
            if (prefix != "Salted__") return JSONObject()

            val salt = raw.copyOfRange(8, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)

            val (key, iv) = evpBytesToKey(nxPass, salt, 32, 16)

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            val jsonStr = String(decryptedBytes, Charsets.UTF_8)

            JSONObject(jsonStr).apply {
                remove("_req_ts")
                remove("_req_salt")
            }
        }.getOrDefault(JSONObject())
    }
}
