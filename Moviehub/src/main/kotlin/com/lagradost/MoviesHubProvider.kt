package com.lagradost

import android.util.Base64
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.zip.GZIPInputStream
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Port của plugin SkyStream "MoviesHubHD" sang CloudStream.
 *  - Danh mục: TMDB qua proxy của site (/api/tmdb?endpoint=...)
 *  - Stream  : API JSON của VidNest (new.vidnest.fun/<provider>/movie|tv/...), response mã hóa AES-GCM/ bảng chữ cái xáo trộn
 */
class MoviesHubProvider : MainAPI() {
    override var mainUrl = "https://www.movieshubhd.com"
    override var name = "MoviesHubHD"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    private val mapper = jacksonObjectMapper()
    private val tmdbImg = "https://image.tmdb.org/t/p"
    private val tmdbDirect = "https://api.themoviedb.org/3"

    /** Tùy chọn: nếu proxy của site lỗi, điền TMDB API key của bạn để gọi thẳng TMDB. */
    private val tmdbKey = ""

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // ---- VidNest ----
    private val vnApi = "https://new.vidnest.fun"
    private val vnAlpha = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/"
    private val b64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val vnSecret = "F9k2mR8vL4qP7wY1zN0fG3bS6tH5jC8d_x4R7vN2mP9qL1"
    private val vnRelays = listOf(
        "https://long-night-d802.streamed-5.workers.dev",
        "https://blue-bush-5977.streamed-7.workers.dev",
        "https://rapid-heart-03c8.streamed-4.workers.dev",
        "https://super-flower-9c85.streamed-2.workers.dev",
        "https://flat-sky-cf20.streamed-850.workers.dev",
        "https://vidrift-cf.streamed-8.workers.dev"
    )
    // path -> tên server hiển thị
    private val vnProviders = listOf(
        "allmovies" to "Delta",
        "nextgencloudfabric" to "Zeta",
        "onehd" to "Alfa",
        "flixhq" to "Beta",
        "rpmvid" to "Prime",
        "vidzee" to "Gama",
        "ophim" to "Lamda",
        "yflix" to "Catflix",
        "rogflix" to "Filxer"
    )

    override val mainPage = mainPageOf(
        "/trending/all/week" to "Trending",
        "/movie/now_playing" to "Latest Releases",
        "/discover/movie?with_original_language=hi&sort_by=popularity.desc" to "Hindi Movies",
        "/movie/top_rated" to "Top Rated Movies",
        "/tv/top_rated" to "Top Rated TV Series",
        "/tv/popular" to "Popular TV Series",
        "/discover/movie?with_genres=28&sort_by=popularity.desc" to "Action",
        "/discover/movie?with_genres=35&sort_by=popularity.desc" to "Comedy",
        "/discover/movie?with_genres=18&sort_by=popularity.desc" to "Drama",
        "/discover/movie?with_genres=27&sort_by=popularity.desc" to "Horror",
        "/discover/movie?with_genres=10749&sort_by=popularity.desc" to "Romance",
        "/discover/movie?with_genres=878&sort_by=popularity.desc" to "Science Fiction",
        "/discover/movie?with_genres=53&sort_by=popularity.desc" to "Thriller",
        "/discover/movie?with_genres=16&sort_by=popularity.desc" to "Animation",
    )

    // ------------------------------------------------------------------ helpers

    private fun JsonNode.str(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k ->
            get(k)?.takeIf { !it.isNull && !it.isContainerNode }?.asText()?.takeIf { it.isNotBlank() }
        }

    private fun img(p: String?, size: String = "w500"): String? =
        if (p.isNullOrBlank()) null else "$tmdbImg/$size$p"

    private suspend fun fetchJson(url: String, headers: Map<String, String>): JsonNode? {
        return try {
            val res = app.get(url, headers = headers)
            if (res.code >= 400) {
                HubLog.log("http", "HTTP ${res.code} $url")
                return null
            }
            mapper.readTree(res.text)
        } catch (e: Exception) {
            HubLog.log("http", "GET $url lỗi: $e")
            null
        }
    }

    /** TMDB qua proxy của site, fallback TMDB trực tiếp nếu có key. */
    private suspend fun tmdb(path: String): JsonNode? {
        val q = path.indexOf('?')
        val ep = if (q >= 0) path.substring(0, q) else path
        val rest = if (q >= 0) "&" + path.substring(q + 1) else ""
        val viaSite = fetchJson(
            "$mainUrl/api/tmdb?endpoint=${URLEncoder.encode(ep, "UTF-8")}$rest",
            mapOf("User-Agent" to ua, "Referer" to "$mainUrl/")
        )
        if (viaSite != null && viaSite.get("status_code") == null && viaSite.get("error") == null) return viaSite
        if (tmdbKey.isNotBlank()) {
            val sep = if (q >= 0) "&" else "?"
            return fetchJson("$tmdbDirect$path${sep}api_key=$tmdbKey&language=en-US", mapOf("User-Agent" to ua))
        }
        return null
    }

    private fun isAnime(r: JsonNode): Boolean {
        val genres = r.get("genre_ids")?.map { it.asInt() }
            ?: r.get("genres")?.map { it.get("id")?.asInt() ?: -1 }
            ?: emptyList()
        val jp = r.get("origin_country")?.any { it.asText() == "JP" } == true
        return genres.contains(16) && (r.str("original_language") == "ja" || jp)
    }

    private fun JsonNode.toItem(forced: String?): SearchResponse? {
        val id = get("id")?.takeIf { it.isNumber }?.asInt() ?: return null
        val mt = str("media_type")
        val t = forced ?: when {
            mt == "movie" || mt == "tv" -> mt
            str("first_air_date") != null || str("name") != null -> "tv"
            else -> "movie"
        }
        if (t != "movie" && t != "tv") return null
        val title = str("title", "name") ?: return null
        val poster = img(str("poster_path"))
        val year = str("release_date", "first_air_date")?.take(4)?.toIntOrNull()
        val url = "$mainUrl/details/$id?type=$t"
        return if (t == "movie") {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, url, if (isAnime(this)) TvType.Anime else TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    // ------------------------------------------------------------------ main page / search

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data
        val forced = when {
            data.startsWith("/movie") || data.startsWith("/discover/movie") -> "movie"
            data.startsWith("/tv") || data.startsWith("/discover/tv") -> "tv"
            else -> null
        }
        val sep = if (data.contains("?")) "&" else "?"
        val j = tmdb("$data${sep}page=$page")
        val list = j?.get("results")?.mapNotNull { it.toItem(forced) }.orEmpty()
        if (list.isEmpty()) HubLog.error("main", "Danh mục '${request.name}' trống (path=$data)")
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val enc = URLEncoder.encode(q, "UTF-8")
        val seen = HashSet<String>()
        val out = mutableListOf<SearchResponse>()
        for (pg in 1..2) {
            val j = tmdb("/search/multi?query=$enc&page=$pg&include_adult=false") ?: continue
            for (r in j.get("results") ?: continue) {
                val mt = r.str("media_type")
                if (mt != "movie" && mt != "tv") continue
                val it = r.toItem(null) ?: continue
                if (seen.add(it.url)) out += it
            }
        }
        return out
    }

    // ------------------------------------------------------------------ load

    override suspend fun load(url: String): LoadResponse? {
        val m = Regex("""/details/(\d+)(?:\?[^#]*type=(movie|tv))?""").find(url) ?: return null
        val id = m.groupValues[1]
        val type = m.groupValues[2].ifEmpty { "movie" }
        val extra = "?append_to_response=credits,videos,recommendations"

        val d = tmdb("/$type/$id$extra")
        if (d == null) {
            HubLog.error("load", "Không lấy được chi tiết /$type/$id")
            return null
        }

        val poster = img(d.str("poster_path"))
        val backdrop = img(d.str("backdrop_path"), "w1280")
        val plot = d.str("overview")
        val tagList = d.get("genres")?.mapNotNull { it.str("name") }?.take(5)
        val cast = d.get("credits")?.get("cast")?.take(12)?.mapNotNull { c ->
            c.str("name")?.let { Actor(it, img(c.str("profile_path"), "w185")) }
        }
        val trailer = d.get("videos")?.get("results")?.firstOrNull {
            it.str("site") == "YouTube" && Regex("Trailer|Teaser", RegexOption.IGNORE_CASE)
                .containsMatchIn(it.str("type") ?: "")
        }?.str("key")
        val recs = d.get("recommendations")?.get("results")?.take(15)
            ?.mapNotNull { it.toItem(type) }

        if (type == "movie") {
            val title = d.str("title") ?: return null
            val year = d.str("release_date")?.take(4)?.toIntOrNull()
            val runtime = d.get("runtime")?.takeIf { it.isNumber }?.asInt()
            return newMovieLoadResponse(title, "$mainUrl/details/$id?type=movie", TvType.Movie, "movie|$id") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = plot
                this.tags = tagList
                this.duration = runtime
                this.recommendations = recs
                if (!cast.isNullOrEmpty()) addActors(cast)
                if (trailer != null) addTrailer("https://www.youtube.com/watch?v=$trailer")
            }
        }

        val title = d.str("name") ?: return null
        val year = d.str("first_air_date")?.take(4)?.toIntOrNull()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val seasons = (d.get("seasons") ?: return null)
            .filter { (it.get("season_number")?.asInt() ?: 0) > 0 && (it.get("episode_count")?.asInt() ?: 0) > 0 }
            .sortedBy { it.get("season_number").asInt() }
            .take(30)

        val episodes = mutableListOf<Episode>()
        val seasonJsons = seasons.amap { s ->
            val sn = s.get("season_number").asInt()
            sn to tmdb("/tv/$id/season/$sn")
        }
        for ((sn, sj) in seasonJsons) {
            for (ep in sj?.get("episodes") ?: continue) {
                val airDate = ep.str("air_date")
                if (airDate != null && airDate > today) continue // chưa chiếu
                val en = ep.get("episode_number")?.asInt() ?: continue
                episodes += newEpisode("tv|$id|$sn|$en") {
                    this.name = ep.str("name") ?: "Episode $en"
                    this.season = sn
                    this.episode = en
                    this.posterUrl = img(ep.str("still_path"))
                    this.description = ep.str("overview")
                }
            }
        }
        if (episodes.isEmpty()) {
            HubLog.error("load", "Không có tập nào đã chiếu cho tv/$id")
            return null
        }

        val status = d.str("status") ?: ""
        return newTvSeriesLoadResponse(
            title, "$mainUrl/details/$id?type=tv",
            if (isAnime(d)) TvType.Anime else TvType.TvSeries, episodes
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
            this.plot = plot
            this.tags = tagList
            this.recommendations = recs
            this.showStatus =
                if (Regex("Ended|Canceled", RegexOption.IGNORE_CASE).containsMatchIn(status)) ShowStatus.Completed
                else ShowStatus.Ongoing
            if (!cast.isNullOrEmpty()) addActors(cast)
            if (trailer != null) addTrailer("https://www.youtube.com/watch?v=$trailer")
        }
    }

    // ------------------------------------------------------------------ VidNest decrypt

    private fun vnNonce(): String {
        val sb = StringBuilder()
        repeat(22) { sb.append(b64[Random.nextInt(62)]) }
        return sb.toString()
    }

    /** response cũ: {"encrypted":true,"data":"<base64 bảng chữ cái xáo trộn>"} */
    private fun vnDecode(data: String): String {
        val map = HashMap<Char, Int>()
        vnAlpha.forEachIndexed { i, c -> map[c] = i }
        val out = ByteArrayOutputStream()
        var buf = 0
        var bits = 0
        for (ch in data) {
            val v = map[ch] ?: continue
            buf = ((buf shl 6) or v) and 0xFFFFFF
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buf shr bits) and 0xFF)
            }
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Giải mã AES-256-GCM (bỏ qua kiểm tra tag): raw = iv[12] | ciphertext | tag[16],
     * key = SHA-256(keyMaterial). GCM = CTR bắt đầu từ counter 2.
     */
    private fun vnDecryptRaw(raw: ByteArray, keyMaterial: String): String? {
        return try {
            if (raw.size < 29) return null
            val iv = raw.copyOfRange(0, 12)
            val ct = raw.copyOfRange(12, raw.size - 16)
            val key = MessageDigest.getInstance("SHA-256").digest(keyMaterial.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv + byteArrayOf(0, 0, 0, 2))
            )
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /** response v2: {v:2, n, d}; d = base64url(iv | ct | tag), key = SHA-256("<n>|" + secret) */
    private fun vnDecryptV2(d: String, n: String): String? =
        vnDecryptRaw(Base64.decode(d, Base64.URL_SAFE), "$n|$vnSecret")

    private fun hex(b: ByteArray, n: Int = 40): String =
        b.take(n).joinToString("") { "%02x".format(it) }

    private fun looksJson(t: String?): Boolean {
        val x = t?.trimStart('\uFEFF', ' ', '\n', '\r', '\t') ?: return false
        return x.startsWith("{") || x.startsWith("[")
    }

    private suspend fun vnFetch(provPath: String, path: String): JsonNode? {
        val nonce = vnNonce()
        val url = "$vnApi/$provPath$path?_n=$nonce"
        return try {
            val res = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to ua,
                    "Referer" to "https://vidnest.fun/",
                    "Origin" to "https://vidnest.fun"
                ),
                timeout = 14
            )
            if (res.code >= 400) {
                HubLog.log("vn", "$provPath HTTP ${res.code}")
                return null
            }
            // đọc byte thô: response có thể là nhị phân, không được đọc bằng text
            var data = res.body.bytes()
            HubLog.log(
                "vn",
                "$provPath HTTP ${res.code} type=${res.headers["content-type"]} enc=${res.headers["content-encoding"]} " +
                    "len=${data.size} head=${hex(data)}"
            )
            if (data.size > 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
                data = GZIPInputStream(data.inputStream()).readBytes()
            }

            var text: String? = String(data, Charsets.UTF_8)
            if (!looksJson(text)) {
                // Thử: body nhị phân thô = iv|ct|tag, khóa từ nonce mình đã gửi
                text = vnDecryptRaw(data, "$nonce|$vnSecret")
                if (looksJson(text)) HubLog.log("vn", "$provPath: giải mã nhị phân bằng nonce thành công")
            }
            if (!looksJson(text)) {
                HubLog.log("vn", "$provPath: không nhận ra định dạng response")
                return null
            }

            val j = mapper.readTree(text) ?: return null
            val out: JsonNode? = when {
                j.get("v")?.asInt() == 2 && j.get("d")?.isTextual == true ->
                    vnDecryptV2(j.get("d").asText(), j.str("n") ?: "")?.let { mapper.readTree(it) }
                j.get("encrypted")?.asBoolean() == true && j.get("data")?.isTextual == true ->
                    mapper.readTree(vnDecode(j.get("data").asText()))
                else -> j
            }
            if (out != null && out.isObject) out else null
        } catch (e: Exception) {
            HubLog.log("vn", "$provPath lỗi: $e")
            null
        }
    }

    // ------------------------------------------------------------------ streams

    private data class Cand(
        val url: String, val lang: String, val quality: String,
        val type: String, val headers: Map<String, String>
    )

    private fun headersOf(n: JsonNode?): Map<String, String> =
        if (n != null && n.isObject)
            n.fieldNames().asSequence().associateWith { n.get(it).asText() }
        else emptyMap()

    private fun vnStreams(j: JsonNode): List<Cand> {
        val out = mutableListOf<Cand>()
        val top = headersOf(j.get("headers"))
        fun add(x: JsonNode?) {
            val u = x?.str("url") ?: return
            val h = headersOf(x.get("headers")).ifEmpty { top }
            out += Cand(u, x.str("language") ?: "", x.str("quality") ?: "", x.str("type") ?: "", h)
        }
        j.str("url")?.let { out += Cand(it, "", j.str("quality") ?: "", j.str("type") ?: "", top) }
        for (k in listOf("streams", "sources")) {
            val arr = j.get(k)
            if (arr != null && arr.isArray) for (x in arr) add(x)
        }
        j.get("data")?.get("stream")?.str("playlist")?.let { out += Cand(it, "", "", "hls", top) }
        return out
    }

    private fun cleanHeaders(h: Map<String, String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((k0, v0) in h) {
            val k = k0.trim()
            if (Regex("^(connection|accept|accept-encoding|host|content-length)$", RegexOption.IGNORE_CASE).matches(k)) continue
            out[k] = v0.trim()
        }
        out["Origin"]?.let { out["Origin"] = it.trimEnd('/') }
        if (!out.containsKey("User-Agent")) out["User-Agent"] = ua
        return out
    }

    private fun qualityOf(text: String): String {
        if (Regex("""\b(4k|2160p?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) return "4K"
        val m = Regex("""\b(1080|720|480|360)p?\b""", RegexOption.IGNORE_CASE).find(text)
        return if (m != null) m.groupValues[1] + "p" else "Auto"
    }

    private fun qualityInt(q: String): Int = when (q) {
        "4K" -> 2160
        "Auto" -> Qualities.Unknown.value
        else -> q.removeSuffix("p").toIntOrNull() ?: Qualities.Unknown.value
    }

    /** đường dẫn tương đối /hls/... -> relay đầu tiên trả playlist */
    private suspend fun viaRelay(rel: String, start: Int): String? {
        for (k in 0 until 3) {
            val base = vnRelays[(start + k) % vnRelays.size]
            val u = base + rel
            try {
                val r = app.get(u, headers = mapOf("User-Agent" to ua), timeout = 9)
                if ((r.code == 200 || r.code == 206) && r.text.trimStart().startsWith("#EXTM3U")) return u
            } catch (e: Exception) {
                // thử relay kế tiếp
            }
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data: "movie|<tmdb>"  hoặc  "tv|<tmdb>|<s>|<e>"
        val parts = data.split("|")
        val isTv = parts.getOrNull(0) == "tv"
        val id = parts.getOrNull(1) ?: return false
        val path = if (isTv) "/tv/$id/${parts.getOrNull(2) ?: "1"}/${parts.getOrNull(3) ?: "1"}" else "/movie/$id"
        HubLog.log("links", "loadLinks $data")

        val answers = vnProviders.amap { (provPath, provName) ->
            Triple(provPath, provName, vnFetch(provPath, path))
        }

        var found = false
        val seenSub = HashSet<String>()
        var relayIdx = Random.nextInt(vnRelays.size)
        val seenUrls = HashSet<String>()

        for ((provPath, provName, j) in answers) {
            if (j == null) {
                HubLog.log("vn", "$provPath: không có dữ liệu")
                continue
            }

            // phụ đề đi kèm provider
            val subs = j.get("subtitles")
            if (subs != null && subs.isArray) {
                for (s in subs) {
                    val su0 = s.str("url") ?: continue
                    val lang = (s.str("lang", "label") ?: "Subtitle").replace(Regex("""\s+\d+$"""), "").trim()
                    if (!seenSub.add(lang)) continue
                    val su = if (su0.startsWith("http", true)) su0
                    else vnRelays[0] + (if (su0.startsWith("/")) "" else "/") + su0
                    subtitleCallback(newSubtitleFile(lang, su))
                }
            }

            val perLabel = HashMap<String, Int>()
            for (x in vnStreams(j).take(6)) {
                var u = x.url
                if (Regex("""/embed[/.]|embed\.php""", RegexOption.IGNORE_CASE).containsMatchIn(u)) continue // trang web player
                val lang = x.lang.replace(Regex("^original$", RegexOption.IGNORE_CASE), "").trim()
                val q = qualityOf(x.quality + " " + u)
                val isMp4 = Regex("mp4|mkv", RegexOption.IGNORE_CASE).containsMatchIn(x.type) ||
                    Regex("""\.(mp4|mkv)$""", RegexOption.IGNORE_CASE).containsMatchIn(u.substringBefore("?"))

                var label = "MoviesHub $provName"
                if (lang.isNotEmpty()) label += " - $lang"
                val tags = mutableListOf<String>()
                if (q != "Auto") tags += q
                if (isMp4) tags += "MP4"
                if (tags.isNotEmpty()) label += " (" + tags.joinToString(", ") + ")"
                val n = (perLabel[label] ?: 0) + 1
                perLabel[label] = n
                if (n > 3) continue // cùng file từ các mirror
                if (n > 1) label += " #$n"

                var hdrs = cleanHeaders(x.headers)
                if (!u.startsWith("http", true)) {
                    val rel = if (u.startsWith("/")) u else "/$u"
                    u = viaRelay(rel, relayIdx++) ?: continue
                    hdrs = mapOf("User-Agent" to ua)
                }
                if (!seenUrls.add(u)) continue

                val referer = hdrs["Referer"] ?: hdrs["referer"] ?: ""
                callback(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = u,
                        type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = qualityInt(q)
                        this.headers = hdrs
                    }
                )
                found = true
            }
        }

        if (!found) {
            HubLog.error(
                "links",
                "Không có server nào trả link cho $data (provider có dữ liệu: ${answers.count { it.third != null }}/${answers.size})"
            )
        }
        return found
    }
}
