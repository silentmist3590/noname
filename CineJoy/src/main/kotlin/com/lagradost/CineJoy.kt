package com.lagradost

import android.content.Context
import android.content.ClipboardManager
import android.content.ClipData
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
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@CloudstreamPlugin
class CineJoyPlugin : Plugin() {
    override fun load(context: Context) {
        appContext = context
        registerMainAPI(CineJoyProvider())
    }

    companion object {
        var appContext: Context? = null
    }
}

class CineJoyProvider : MainAPI() {
    override var mainUrl = "https://cinejoy.pk"
    override var name = "CineJoy"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val tmdbHosts = listOf(
        "https://api.tmdb.org/3",
        "https://api.themoviedb.org/3"
    )
    private var tmdbPrefer = 0

    private val tmdbKeys = listOf(
        "8476a7ab80ad76f0936744df0430e67c",
        "439c478a771f35c05022f9feabcca01c",
        "15d2ea6d0dc1d476efbca3eba2b9bbfb"
    )

    private val tmdbBearerToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"

    private val vidrockBase = "https://vidrock.net"
    private val vidrockKeyHex = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"

    private val nxBase = "https://nxsha.space"
    private val nxPass = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"

    private val vidloveBase = "https://api.vidlove.cc"
    private val playerRef = "https://player.vidlove.cc/"

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
        "/movie/now_playing" to "In Theaters"
    )

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        for (k in tmdbHosts.indices) {
            val hi = (tmdbPrefer + k) % tmdbHosts.size
            val host = tmdbHosts[hi]
            for (apiKey in tmdbKeys) {
                val url = "$host$path${sep}api_key=$apiKey"
                val res = runCatching {
                    app.get(url, headers = mapOf("User-Agent" to ua, "Accept" to "application/json"), timeout = 10L).text
                }.getOrNull()
                if (!res.isNullOrBlank() && !res.trim().startsWith("<")) {
                    val json = runCatching { JSONObject(res) }.getOrNull()
                    if (json != null && !json.has("status_code")) {
                        tmdbPrefer = hi
                        return json
                    }
                }
            }
        }
        return null
    }

    private fun posterUrl(path: String?, size: String = "w500"): String? {
        if (path.isNullOrBlank() || path == "null") return null
        val clean = if (path.startsWith("/")) path else "/$path"
        return "https://wsrv.nl/?url=" + URLEncoder.encode("https://image.tmdb.org/t/p/$size$clean", "UTF-8")
    }

    private fun parseYear(dateStr: String?): Int? {
        if (dateStr.isNullOrBlank()) return null
        return Regex("^(\\d{4})").find(dateStr)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun fixEpisode(e: Int): Int {
        return if (e >= 800) (e % 100) + 1 + ((e / 100) - 8) * 2 else e
    }

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
                items.add(newTvSeriesSearchResponse(title, itemPayload, TvType.TvSeries) { this.posterUrl = poster; this.year = year })
            } else {
                items.add(newMovieSearchResponse(title, itemPayload, TvType.Movie) { this.posterUrl = poster; this.year = year })
            }
        }
        return newHomePageResponse(request.name, items, hasNext = page < (json.optInt("total_pages", 1)) && page < 25)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val json = tmdbGet("/search/multi?query=$q&page=1&include_adult=false") ?: return emptyList()
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
                items.add(newTvSeriesSearchResponse(title, itemPayload, TvType.TvSeries) { this.posterUrl = poster; this.year = year })
            } else {
                items.add(newMovieSearchResponse(title, itemPayload, TvType.Movie) { this.posterUrl = poster; this.year = year })
            }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val parsed = parseDataUrl(url) ?: throw ErrorLoadingException("Invalid URL: $url")
        val id = parsed.optInt("id")
        val isTv = parsed.optString("type") == "tv"
        if (!isTv) {
            val json = tmdbGet("/movie/$id") ?: throw ErrorLoadingException("Details not found")
            val title = json.optString("title", "Unknown")
            val poster = posterUrl(json.optString("poster_path", ""))
            val linkData = JSONObject().put("type", "movie").put("id", id).put("title", title).toString()
            return newMovieLoadResponse(title, url, TvType.Movie, linkData) { this.posterUrl = poster }
        } else {
            val json = tmdbGet("/tv/$id") ?: throw ErrorLoadingException("Details not found")
            val title = json.optString("name", "Unknown")
            val poster = posterUrl(json.optString("poster_path", ""))
            val episodes = mutableListOf<Episode>()
            val sJson = tmdbGet("/tv/$id/season/1")
            val epArray = sJson?.optJSONArray("episodes") ?: JSONArray()
            for (ei in 0 until epArray.length()) {
                val ep = epArray.optJSONObject(ei) ?: continue
                val epNum = ep.optInt("episode_number", ei + 1)
                val epData = JSONObject().put("type", "tv").put("id", id).put("s", 1).put("e", epNum).toString()
                episodes.add(newEpisode(epData) { this.name = ep.optString("name"); this.season = 1; this.episode = epNum })
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { this.posterUrl = poster }
        }
    }

    private fun parseDataUrl(url: String): JSONObject? {
        return runCatching {
            if (url.startsWith("{")) JSONObject(url) else null
        }.getOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parsed = parseDataUrl(data) ?: return false
        val type = parsed.optString("type", "movie")
        val id = parsed.optInt("id", 0)
        val s = parsed.optInt("s", 1)
        val e = parsed.optInt("e", 1)

        val log = StringBuilder()
        log.append("CineJoy loadLinks start: type=$type id=$id s=$s e=$e\n")

        var totalLinks = 0
        val seenUrls = HashSet<String>()

        // 1. VidRock API
        try {
            val vrPath = if (type == "tv") "tv/$id/$s/$e" else "movie/$id"
            val vrUrl = "$vidrockBase/api/$vrPath"
            log.append("VidRock trying URL: $vrUrl\n")
            val vrResp = app.get(vrUrl, headers = mapOf("User-Agent" to ua, "Referer" to "$vidrockBase/"), timeout = 10L).text
            
            if (vrResp.trim().startsWith("<")) {
                log.append("VidRock returned HTML/Cloudflare block snippet: ${vrResp.take(200)}\n")
            } else {
                val vrJson = JSONObject(vrResp)
                val keys = vrJson.keys()
                while (keys.hasNext()) {
                    val serverName = keys.next()
                    val serverObj = vrJson.optJSONObject(serverName) ?: continue
                    val token = serverObj.optString("url", "")
                    if (token.isBlank()) continue

                    val decryptedUrl = if (token.startsWith("http")) token else decryptVidRock(token, log)
                    log.append("VidRock server $serverName final URL: ${decryptedUrl.take(100)}\n")

                    if (decryptedUrl.startsWith("http") && !blockedHostRegex.containsMatchIn(decryptedUrl)) {
                        if (seenUrls.add(decryptedUrl)) {
                            callback(newExtractorLink("CineJoy", "VidRock $serverName", decryptedUrl, ExtractorLinkType.M3U8) {
                                this.referer = "$vidrockBase/"
                                this.quality = Qualities.P1080.value
                            })
                            totalLinks++
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            log.append("VidRock exception: ${ex.message}\n")
        }

        // 2. NxSha API
        try {
            val nxReq = JSONObject().apply {
                put("tmdbId", id.toString())
                put("type", type)
                put("season", if (type == "tv") s else 0)
                put("episode", if (type == "tv") e else 0)
            }
            val encodedQuery = nxEncode(nxReq)
            val srvResp = app.get("$nxBase/api/servers?q=$encodedQuery", headers = mapOf("User-Agent" to ua, "Referer" to "$nxBase/"), timeout = 10L).text
            
            if (srvResp.trim().startsWith("<")) {
                log.append("NxSha returned HTML/Cloudflare block snippet: ${srvResp.take(200)}\n")
            } else {
                val srvHash = JSONObject(srvResp).optString("_hash", "")
                if (srvHash.isNotBlank()) {
                    val srvDec = nxDecode(srvHash, log)
                    val servers = srvDec.optJSONArray("servers") ?: JSONArray()
                    log.append("NxSha found servers count: ${servers.length()}\n")
                } else {
                    log.append("NxSha hash empty in response: ${srvResp.take(200)}\n")
                }
            }
        } catch (ex: Exception) {
            log.append("NxSha exception: ${ex.message}\n")
        }

        // 3. VidLove API
        try {
            val vlBase = if (type == "tv") "/tv?id=$id&season=$s&episode=$e&mode=json" else "/movie?id=$id&mode=json"
            val fullUrl = "$vidloveBase$vlBase"
            log.append("VidLove trying URL: $fullUrl\n")
            val resp = app.get(
                fullUrl, 
                headers = mapOf(
                    "User-Agent" to ua, 
                    "Referer" to playerRef, 
                    "Origin" to "https://cinejoy.pk",
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ), 
                timeout = 6L
            ).text
            
            if (resp.trim().startsWith("<")) {
                log.append("VidLove blocked by Cloudflare (HTML returned)\n")
            } else {
                val j = JSONObject(resp)
                val src = j.optJSONObject("source")
                val sUrl = src?.optString("url", "") ?: ""
                if (sUrl.startsWith("http") && !blockedHostRegex.containsMatchIn(sUrl) && seenUrls.add(sUrl)) {
                    callback(newExtractorLink("CineJoy", "VidLove", sUrl, ExtractorLinkType.M3U8) {
                        this.referer = playerRef
                        this.quality = Qualities.P1080.value
                    })
                    totalLinks++
                }
            }
        } catch (ex: Exception) {
            log.append("VidLove exception: ${ex.message}\n")
        }

        log.append("Tổng liên kết tìm được: $totalLinks\n")

        if (totalLinks == 0) {
            val errorLogContent = log.toString()
            android.util.Log.e("CineJoyError", errorLogContent)
            try {
                CineJoyPlugin.appContext?.let { ctx ->
                    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("CineJoy Debug Log", errorLogContent))
                }
            } catch (_: Exception) {}
        }

        return totalLinks > 0
    }

    private fun decryptVidRock(token: String, log: StringBuilder): String {
        return runCatching {
            val decoded = Base64.decode(token, Base64.URL_SAFE or Base64.NO_WRAP)
            if (decoded.size > 28) {
                // Xử lý dạng gộp IV (12 bytes đầu) + Ciphertext + Tag
                val iv = decoded.copyOfRange(0, 12)
                val ciphertextAndTag = decoded.copyOfRange(12, decoded.size)
                val keyBytes = hexStringToByteArray(vidrockKeyHex)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val gcmSpec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), gcmSpec)
                return String(cipher.doFinal(ciphertextAndTag), Charsets.UTF_8)
            } else {
                log.append("VidRock decoded size too small: ${decoded.size}\n")
                ""
            }
        }.onFailure {
            log.append("VidRock decrypt error: ${it.message}\n")
        }.getOrDefault("")
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md = MessageDigest.getInstance("MD5")
        var currentHash = ByteArray(0)
        val totalNeeded = keyLen + ivLen
        val keyAndIv = ByteArray(totalNeeded)
        var count = 0
        while (count < totalNeeded) {
            md.reset()
            if (currentHash.isNotEmpty()) md.update(currentHash)
            md.update(password)
            md.update(salt)
            currentHash = md.digest()
            val toCopy = minOf(currentHash.size, totalNeeded - count)
            System.arraycopy(currentHash, 0, keyAndIv, count, toCopy)
            count += toCopy
        }
        return Pair(keyAndIv.copyOfRange(0, keyLen), keyAndIv.copyOfRange(keyLen, keyLen + ivLen))
    }

    private fun nxDecode(hashStr: String, log: StringBuilder): JSONObject {
        return runCatching {
            val raw = Base64.decode(hashStr, Base64.DEFAULT)
            if (raw.size < 16) {
                log.append("NxSha raw size < 16\n")
                return JSONObject()
            }
            val (salt, ciphertext) = if (String(raw, 0, 8, Charsets.US_ASCII) == "Salted__") {
                Pair(raw.copyOfRange(8, 16), raw.copyOfRange(16, raw.size))
            } else {
                Pair(ByteArray(8), raw)
            }
            val (key, iv) = evpBytesToKey(nxPass.toByteArray(Charsets.UTF_8), salt, 32, 16)
            
            // Thử giải mã với PKCS5Padding, nếu lỗi block length thì fallback sang NoPadding
            val decrypted = try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(ciphertext)
            } catch (e: Exception) {
                log.append("NxSha CBC/PKCS5 failed, trying NoPadding: ${e.message}\n")
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(ciphertext)
            }
            
            JSONObject(String(decrypted, Charsets.UTF_8))
        }.onFailure {
            log.append("NxSha decode error: ${it.message}\n")
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
}
