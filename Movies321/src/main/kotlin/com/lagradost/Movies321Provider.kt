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
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Đặt false khi đã chạy ổn để tắt việc chép log vào clipboard
private const val DEBUG = true

@CloudstreamPlugin
class Movies321Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Movies321Provider())
    }
}

/**
 * 321movies.co.uk là ứng dụng Next.js vẽ phía trình duyệt: HTML không chứa link nào nên không cào DOM được.
 *  - Danh mục phim lấy từ TMDB v3 bằng token đọc công khai mà chính site nhúng trong bundle của họ.
 *  - Link phát lấy từ API player của site:
 *      GET {site}/api/player/vixsrc-playlist?type=movie&id={tmdbId}
 *      GET {site}/api/player/vixsrc-playlist?type=tv&id={tmdbId}&season={s}&episode={e}
 *    mỗi nguồn có `file` = "enc:" + base64url(URL bị XOR với khóa công khai trong bundle player).
 *    Đây là che link (obfuscation) chứ không phải DRM.
 *  - Mỗi link giải mã được kiểm tra song song bằng Range 0-2047 với Referer/Origin của site.
 *    Link chết hẳn (404/5xx/DNS/HTML) bị bỏ; 401/403/429/timeout được giữ lại và ghi "(có thể không phát)".
 */
class Movies321Provider : MainAPI() {
    override var mainUrl = "https://321movies.co.uk"
    override var name = "321Movies"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdb = "https://api.themoviedb.org/3"
    private val img = "https://image.tmdb.org/t/p/"
    private val tmdbToken =
        "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJiYmYxODFkOTRiMzk2MTg1ZDBhYmQ5NzA5M2ZkNDhlMCIsIm5iZiI6MTY5MjUzNzk2MS45MTgwMDAyLCJzdWIiOiI2NGUyMTQ2OTM3MTA5NzAxMWM1NDk3YjgiLCJzY29wZXMiOlsiYXBpX3JlYWQiXSwidmVyc2lvbiI6MX0.t4GsujVl9LceOrnPmx-WDdncTSAx60QBLAoaiuTCvXI"

    // Khóa XOR công khai trong bundle player của site. Nếu site đổi khóa, loadLinks báo "DECODE_FAILED".
    private val playerKey =
        "j7wYkYhVgQn5x2L6k2M8hVQfD4zN3bP1aR7uT0cXyE6dZX4sWAd87JKMN8HHGG654GVCFRLMNBOPUY7LK"

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    // Thứ tự ưu tiên các nguồn mà player của site coi là ổn định nhất
    private val preferred = listOf(
        "rivestream", "streamaggregator", "peestream", "movy", "pstream",
        "frame", "bcine", "cinesrc", "vidgod"
    )
    private val maxStreams = 16

    override val mainPage = mainPageOf(
        "/trending/movie/day" to "Phim lẻ thịnh hành",
        "/movie/popular" to "Phim lẻ phổ biến",
        "/tv/popular" to "Phim bộ phổ biến",
        "/trending/tv/week" to "Phim bộ thịnh hành tuần",
        "/movie/top_rated" to "Phim lẻ đánh giá cao",
        "/tv/airing_today" to "Phim bộ chiếu hôm nay",
        "/movie/upcoming" to "Sắp chiếu"
    )

    // ---------------------------------------------------------------- TMDB

    // Nhà mạng ở một số nước chặn api.themoviedb.org nên thử thêm tên miền dự phòng chính thức api.tmdb.org
    private val tmdbHosts = listOf("https://api.themoviedb.org/3", "https://api.tmdb.org/3")
    private var tmdbPrefer = 0
    private val tmdbLog = StringBuilder()

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        val headers = mapOf(
            "Authorization" to "Bearer $tmdbToken",
            "Accept" to "application/json"
        )
        for (round in 0 until 2) {
            for (k in tmdbHosts.indices) {
                val hi = (tmdbPrefer + k) % tmdbHosts.size
                val url = "${tmdbHosts[hi]}$path${sep}language=en-US"
                val r = runCatching { app.get(url, headers = headers, timeout = 20L) }
                val resp = r.getOrNull()
                if (resp == null) {
                    tmdbLog.append(tmdbHosts[hi]).append(" -> ")
                        .append(r.exceptionOrNull()?.javaClass?.simpleName).append(": ")
                        .append(r.exceptionOrNull()?.message?.take(120)).append('\n')
                    continue
                }
                val body = runCatching { resp.text }.getOrDefault("")
                val obj = runCatching { JSONObject(body) }.getOrNull()
                if (obj != null && !obj.has("status_code")) {
                    tmdbPrefer = hi
                    return obj
                }
                tmdbLog.append(tmdbHosts[hi]).append(" -> HTTP ").append(resp.code)
                    .append(" ").append(body.take(120).replace('\n', ' ')).append('\n')
                if (obj != null && obj.optInt("status_code") == 34) return null // không tồn tại
            }
            if (round == 0) Thread.sleep(500)
        }
        return null
    }

    private fun image(path: String?, size: String): String? {
        if (path.isNullOrBlank() || path == "null") return null
        // image.tmdb.org bị chặn ở một số mạng nên đi qua proxy ảnh wsrv.nl
        return "https://wsrv.nl/?url=" + URLEncoder.encode(img + size + (if (path.startsWith("/")) path else "/$path"), "UTF-8")
    }

    private fun yearOf(date: String?): Int? =
        date?.take(4)?.toIntOrNull()

    private fun JSONObject.str(key: String): String? =
        optString(key, "").takeIf { it.isNotBlank() && it != "null" }

    private fun itemType(o: JSONObject, forcedTv: Boolean?): TvType {
        val tv = forcedTv ?: (o.optString("media_type") == "tv" || o.has("first_air_date"))
        return if (tv) TvType.TvSeries else TvType.Movie
    }

    private fun toSearch(o: JSONObject, forcedTv: Boolean?): SearchResponse? {
        val id = o.optLong("id", 0L)
        if (id == 0L || o.optBoolean("adult", false)) return null
        if (o.optString("media_type") == "person") return null
        val type = itemType(o, forcedTv)
        val title = o.str("title") ?: o.str("name") ?: o.str("original_title")
            ?: o.str("original_name") ?: return null
        val poster = image(o.str("poster_path"), "w500") ?: image(o.str("backdrop_path"), "w780")
        val path = if (type == TvType.TvSeries) "tv" else "movie"
        val date = if (type == TvType.TvSeries) o.str("first_air_date") else o.str("release_date")
        return newMovieSearchResponse(title, "$mainUrl/$path/$id", type) {
            this.posterUrl = poster
            this.year = yearOf(date)
        }
    }

    private fun parseList(arr: JSONArray?, forcedTv: Boolean?): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            toSearch(o, forcedTv)?.let { out.add(it) }
        }
        return out
    }

    // ------------------------------------------------------------ main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val res = tmdbGet("$path?page=$page")
        val forcedTv = path.startsWith("/tv") || path.contains("/tv/")
        val items = parseList(res?.optJSONArray("results"), forcedTv)
        if (items.isEmpty() && DEBUG) {
            copyLog("321Movies getMainPage $path page=$page\n$tmdbLog")
            tmdbLog.setLength(0)
        }
        val total = res?.optInt("total_pages", 1) ?: 1
        return newHomePageResponse(request.name, items, hasNext = page < total && page < 20)
    }

    // --------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        if (q.isBlank()) return emptyList()
        val res = tmdbGet("/search/multi?query=$q&include_adult=false&page=1")
            ?: tmdbGet("/search/movie?query=$q&include_adult=false&page=1")
        return parseList(res?.optJSONArray("results"), null)
    }

    // ----------------------------------------------------------------- load

    private data class Ref(val tv: Boolean, val id: String, val season: Int?, val episode: Int?)

    private fun refOf(url: String): Ref? {
        val m = Regex("/(movie|tv|series)/(\\d+)(?:/(?:season/)?(\\d+))?(?:/(?:episode/)?(\\d+))?",
            RegexOption.IGNORE_CASE).find(url) ?: return null
        val kind = m.groupValues[1].lowercase()
        return Ref(
            tv = kind != "movie",
            id = m.groupValues[2],
            season = m.groupValues[3].toIntOrNull(),
            episode = m.groupValues[4].toIntOrNull()
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val ref = refOf(url) ?: return null
        val path = if (ref.tv) {
            "/tv/${ref.id}?append_to_response=credits"
        } else {
            "/movie/${ref.id}?append_to_response=credits"
        }
        val d = tmdbGet(path) ?: return null
        if (d.optLong("id", 0L) == 0L) return null

        val title = d.str("title") ?: d.str("name") ?: return null
        val kind = if (ref.tv) "tv" else "movie"
        val pageUrl = "$mainUrl/$kind/${d.optLong("id")}"
        val poster = image(d.str("poster_path"), "w500") ?: image(d.str("backdrop_path"), "w780")
        val backdrop = image(d.str("backdrop_path") ?: d.str("poster_path"), "w1280")
        val year = yearOf(if (ref.tv) d.str("first_air_date") else d.str("release_date"))

        val tags = mutableListOf<String>()
        d.optJSONArray("genres")?.let { g ->
            for (i in 0 until g.length()) g.optJSONObject(i)?.str("name")?.let { tags.add(it) }
        }
        val cast = mutableListOf<String>()
        d.optJSONObject("credits")?.optJSONArray("cast")?.let { c ->
            for (i in 0 until minOf(c.length(), 12)) c.optJSONObject(i)?.str("name")?.let { cast.add(it) }
        }
        val plot = listOfNotNull(
            d.str("overview"),
            cast.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "Diễn viên: $it" }
        ).joinToString("\n\n").takeIf { it.isNotBlank() }
        val runtime = d.optInt("runtime", 0).takeIf { it > 0 }
            ?: d.optJSONArray("episode_run_time")?.optInt(0, 0)?.takeIf { it > 0 }

        if (!ref.tv) {
            return newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = runtime
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = d.optJSONArray("seasons")
        if (seasons != null) {
            var fetched = 0
            for (i in 0 until seasons.length()) {
                val s = seasons.optJSONObject(i) ?: continue
                val sn = s.optInt("season_number", 0)
                if (sn <= 0) continue                       // bỏ "Specials"
                if (ref.season != null && sn != ref.season) continue
                if (++fetched > 40) break
                val sd = tmdbGet("/tv/${ref.id}/season/$sn") ?: continue
                val eps = sd.optJSONArray("episodes") ?: continue
                for (j in 0 until eps.length()) {
                    val e = eps.optJSONObject(j) ?: continue
                    val en = e.optInt("episode_number", 0)
                    if (en <= 0) continue
                    episodes.add(
                        newEpisode("$pageUrl/$sn/$en") {
                            this.name = e.str("name") ?: "Episode $en"
                            this.season = sn
                            this.episode = en
                            this.description = e.str("overview")
                            this.posterUrl = image(e.str("still_path"), "w300")
                                ?: image(d.str("poster_path"), "w300")
                        }
                    )
                }
            }
        }
        if (episodes.isEmpty()) return null

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.plot = plot
            this.year = year
            this.tags = tags
            this.duration = runtime
        }
    }

    // ----------------------------------------------------------- loadLinks

    /** Giải mã "enc:<base64url>" thành URL thật; trả "" nếu không giải được. */
    private fun decodeFile(file: String?): String {
        if (file.isNullOrBlank()) return ""
        if (!file.startsWith("enc:")) return file
        return runCatching {
            var b64 = file.substring(4).replace('-', '+').replace('_', '/')
            while (b64.length % 4 != 0) b64 += "="
            val raw = Base64.decode(b64, Base64.DEFAULT)
            if (raw.size < 9) return@runCatching ""
            val key = playerKey.toByteArray(Charsets.ISO_8859_1)
            val out = StringBuilder(raw.size)
            for (i in 8 until raw.size) {
                val salt = raw[(i - 8) % 8].toInt() and 0xFF
                val k = key[(i - 8 + salt) % key.size].toInt() and 0xFF
                out.append(((raw[i].toInt() and 0xFF) xor k).toChar())
            }
            val s = out.toString()
            if (s.startsWith("http://") || s.startsWith("https://")) s else ""
        }.getOrDefault("")
    }

    private fun siteHeaders(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl) + extra

    private data class Cand(
        val url: String, val family: String, val label: String,
        val declared: String, val rank: Int
    )

    private data class Verdict(
        val state: String,                       // ok | dead | unknown
        val m3u8: Boolean,
        val headers: Map<String, String>? = null, // bộ header đã chạy được
        val note: String = "",
        val url: String? = null                   // link thay thế (link gốc bỏ qua proxy)
    )

    /** Lấy JSON bằng OkHttp chặn luồng (request đầu tiên của site có thể mất ~20 giây). */
    private fun blockingGet(client: OkHttpClient, url: String, headers: Map<String, String>): Pair<Int, String> {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        return runCatching {
            client.newCall(rb.build()).execute().use { r -> r.code to (r.body?.string() ?: "") }
        }.getOrElse { 0 to (it.message ?: "") }
    }

    private fun playerPlaylist(ref: Ref, log: StringBuilder): JSONObject? {
        var q = "type=${if (ref.tv) "tv" else "movie"}&id=${ref.id}"
        if (ref.tv) q += "&season=${ref.season ?: 1}&episode=${ref.episode ?: 1}"
        val url = "$mainUrl/api/player/vixsrc-playlist?$q"
        val client = app.baseClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val h = siteHeaders(mapOf("Accept" to "application/json"))
        for (i in 0 until 4) {
            val (code, body) = blockingGet(client, url, h)
            log.append("player #").append(i + 1).append(": HTTP ").append(code)
                .append(" len=").append(body.length).append('\n')
            if (code == 200) {
                val j = runCatching { JSONObject(body) }.getOrNull()
                if (j != null && (j.optJSONArray("playlist")?.length() ?: 0) > 0) return j
            }
            if (code == 404) return null
            if (i < 3) Thread.sleep(2000)
        }
        return null
    }

    private fun getBytes(
        client: OkHttpClient, url: String, headers: Map<String, String>, range: String?, limit: Long
    ): Pair<Int, String> {
        return try {
            val rb = Request.Builder().url(url)
            headers.forEach { (k, v) -> rb.header(k, v) }
            rb.header("Accept", "*/*")
            if (range != null) rb.header("Range", range)
            client.newCall(rb.build()).execute().use { r ->
                val body = runCatching { r.peekBody(limit).string() }.getOrDefault("")
                if (r.code == 200 || r.code == 206) {
                    r.code to body
                } else {
                    r.code to body.take(200).replace('\n', ' ')
                }
            }
        } catch (e: UnknownHostException) {
            -1 to "dns"
        } catch (e: Exception) {
            0 to (e.message ?: e.javaClass.simpleName)
        }
    }

    private fun resolveUrl(base: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return runCatching { java.net.URI(base).resolve(ref).toString() }.getOrDefault(ref)
    }

    /**
     * Kiểm tra tới tận đoạn video đầu tiên: tải playlist, nếu là master thì tải playlist con,
     * rồi xin 1KB đầu của đoạn đầu. Link nào đoạn video trả 403/404 sẽ gây lỗi 2004 khi phát.
     */
    private fun chain(client: OkHttpClient, url: String, headers: Map<String, String>): Verdict {
        val (c1, b1) = getBytes(client, url, headers, null, 400_000)
        if (c1 == -1) return Verdict("dead", false, note = "DNS")
        if (c1 != 200 && c1 != 206) {
            val st = if (c1 == 401 || c1 == 403 || c1 == 429 || c1 == 0) "unknown" else "dead"
            return Verdict(st, false, note = "playlist HTTP $c1 ${b1.take(220)}")
        }
        val head = b1.replace("\uFEFF", "").trimStart()
        if (!head.startsWith("#EXTM3U")) {
            if (Regex("^<(!doctype|html|\\?xml)", RegexOption.IGNORE_CASE).containsMatchIn(head)) {
                return if (head.contains("<MPD", true)) Verdict("ok", false, headers, "mpd")
                else Verdict("dead", false, note = "trả về HTML")
            }
            return if (head.isNotEmpty()) Verdict("ok", false, headers, "không phải m3u8")
            else Verdict("unknown", false, note = "rỗng")
        }
        var text = head
        var base = url
        if (text.contains("#EXT-X-STREAM-INF")) {
            val lines = text.lines()
            val idx = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
            val v = lines.drop(idx + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                ?: return Verdict("unknown", true, note = "master không có variant")
            base = resolveUrl(url, v)
            val (c2, b2) = getBytes(client, base, headers, null, 400_000)
            if (c2 != 200 && c2 != 206) return Verdict("unknown", true, note = "variant HTTP $c2")
            text = b2.trimStart()
        }
        val seg = text.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return Verdict("unknown", true, note = "không thấy đoạn video")
        val segUrl = resolveUrl(base, seg)
        val (c3, _) = getBytes(client, segUrl, headers, "bytes=0-1023", 1024)
        return if (c3 == 200 || c3 == 206) Verdict("ok", true, headers, "đoạn video HTTP $c3")
        else Verdict("unknown", true, note = "đoạn video HTTP $c3 ${segUrl.take(80)}")
    }

    /** Referer/Origin mà link proxy mang theo trong tham số headers=<json> (dành cho máy chủ gốc). */
    private fun embeddedHeaders(url: String): Map<String, String>? = runCatching {
        val raw = Regex("[?&]headers=([^&]+)").find(url)?.groupValues?.get(1) ?: return@runCatching null
        val j = JSONObject(java.net.URLDecoder.decode(raw, "UTF-8"))
        val m = HashMap<String, String>()
        m["User-Agent"] = ua
        j.optString("Referer", "").takeIf { it.isNotBlank() }?.let { m["Referer"] = it }
        j.optString("Origin", "").takeIf { it.isNotBlank() }?.let { m["Origin"] = it }
        m
    }.getOrNull()

    /** Giải mã tham số url=<...> của link proxy để lấy link gốc phía sau. */
    private fun innerUrl(url: String): String? = runCatching {
        val raw = Regex("[?&]url=([^&]+)").find(url)?.groupValues?.get(1) ?: return@runCatching null
        java.net.URLDecoder.decode(raw, "UTF-8").takeIf { it.startsWith("http") }
    }.getOrNull()

    /**
     * Link proxy của site có thể mang header giả IP (X-Forwarded-For...) trong tham số headers=<json>.
     * Cloudflare trả 403 cho các header đó, nên tạo bản link đã bỏ chúng đi.
     */
    private fun cleanProxyUrl(url: String): String? = runCatching {
        val m = Regex("([?&]headers=)([^&]+)").find(url) ?: return@runCatching null
        val j = JSONObject(java.net.URLDecoder.decode(m.groupValues[2], "UTF-8"))
        val bad = Regex(
            "^(x-forwarded-.*|x-real-ip|x-client-ip|x-originating-ip|cf-connecting-ip|true-client-ip|cf-ipcountry|x-country)$",
            RegexOption.IGNORE_CASE
        )
        var changed = false
        for (k in j.keys().asSequence().toList()) {
            if (bad.matches(k)) { j.remove(k); changed = true }
        }
        if (!changed) return@runCatching null
        url.replaceRange(m.range, m.groupValues[1] + URLEncoder.encode(j.toString(), "UTF-8"))
    }.getOrNull()

    private fun isProxy(url: String) = url.contains(".workers.dev/")

    private fun probeOne(
        client: OkHttpClient, url: String, setA: Map<String, String>, setB: Map<String, String>,
        extra: List<Map<String, String>>
    ): Verdict {
        val notes = StringBuilder()
        var first: Verdict? = null
        fun attempt(tag: String, target: String, hs: Map<String, String>, replaceUrl: Boolean): Verdict? {
            val v = chain(client, target, hs)
            if (v.state == "ok") {
                return v.copy(
                    note = v.note + " (" + tag + ")",
                    url = if (replaceUrl) target else null
                )
            }
            if (first == null) first = v
            notes.append(" | ").append(tag).append(": ").append(v.note.take(150))
            return null
        }

        // 1. Đúng cách site làm: Referer/Origin của site
        attempt("site", url, setA, false)?.let { return it }

        // 1b. Cùng link proxy nhưng bỏ header giả IP khỏi tham số headers=
        cleanProxyUrl(url)?.let { cu -> attempt("sạch", cu, setA, true)?.let { return it } }

        // 2. Bỏ qua proxy: gọi thẳng link gốc với Referer/Origin mà link proxy mang theo
        val emb = embeddedHeaders(url)
        var inner = innerUrl(url)
        var depth = 0
        while (inner != null && depth < 3) {
            if (emb != null) attempt("gốc" + depth, inner, emb, true)?.let { return it }
            else for (e in extra) attempt("gốc" + depth + "+ref", inner, e, true)?.let { return it }
            inner = innerUrl(inner)
            depth++
        }

        // 3. Link trực tiếp không có Referer riêng: thử các Referer thấy ở các nguồn khác
        if (!isProxy(url) && emb == null) {
            for (e in extra) attempt("ref=" + (e["Referer"] ?: ""), url, e, false)?.let { return it }
        }
        // 4. Không Referer
        attempt("trống", url, setB, false)?.let { return it }

        val f = first!!
        return f.copy(note = notes.toString().drop(3).ifBlank { f.note })
    }

    /** Kiểm tra song song bằng luồng thường (không cần coroutines). */
    private fun probeAll(
        cands: List<Cand>, setA: Map<String, String>, setB: Map<String, String>,
        extra: List<Map<String, String>>
    ): List<Verdict> {
        if (cands.isEmpty()) return emptyList()
        val client = app.baseClient.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val pool = Executors.newFixedThreadPool(minOf(cands.size, 8))
        return try {
            val fs = cands.map { c -> pool.submit(Callable { probeOne(client, c.url, setA, setB, extra) }) }
            fs.map { f -> runCatching { f.get(70, TimeUnit.SECONDS) }.getOrDefault(Verdict("unknown", false, note = "quá thời gian")) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun heightOf(label: String): Int = when {
        Regex("4k|2160", RegexOption.IGNORE_CASE).containsMatchIn(label) -> 2160
        else -> Regex("(1080|720|480|360)").find(label)?.value?.toIntOrNull() ?: 0
    }

    private fun qualityValue(h: Int): Int = when (h) {
        2160 -> Qualities.P2160.value
        1080 -> Qualities.P1080.value
        720 -> Qualities.P720.value
        480 -> Qualities.P480.value
        360 -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val log = StringBuilder("321Movies loadLinks\ndata=$data\n")
        var found = false
        var verified = 0
        try {
            val ref = refOf(data)
            if (ref == null) {
                log.append("URL không nhận ra\n")
                return false
            }
            val payload = playerPlaylist(ref, log)
            if (payload == null) {
                log.append("PLAYER_OFFLINE: player của 321movies không trả nguồn nào\n")
                return false
            }

            // Giải mã và loại trùng
            val seen = HashSet<String>()
            val cands = mutableListOf<Cand>()
            var encoded = 0
            val groups = payload.optJSONArray("playlist") ?: JSONArray()
            for (gi in 0 until groups.length()) {
                val srcs = groups.optJSONObject(gi)?.optJSONArray("sources") ?: continue
                for (si in 0 until srcs.length()) {
                    val s = srcs.optJSONObject(si) ?: continue
                    val file = s.optString("file", "")
                    if (file.startsWith("enc:")) encoded++
                    val real = decodeFile(file)
                    if (!real.startsWith("http") || !seen.add(real)) continue
                    val fam = s.optString("provider", "other").removePrefix("sourcepack-")
                    cands.add(
                        Cand(
                            url = real, family = fam, label = s.optString("label", ""),
                            declared = s.optString("type", ""),
                            rank = preferred.indexOf(fam).let { if (it < 0) 50 else it }
                        )
                    )
                }
            }
            log.append("nguồn giải mã được: ${cands.size} (enc: $encoded)\n")
            if (cands.isEmpty()) {
                log.append(
                    if (encoded > 0) "DECODE_FAILED: site đã đổi khóa che link, plugin cần cập nhật\n"
                    else "NO_SOURCES\n"
                )
                return false
            }

            val headers = siteHeaders()
            val extra = cands.mapNotNull { embeddedHeaders(it.url) }.distinctBy { it["Referer"] }
            val verdicts = probeAll(cands, headers, mapOf("User-Agent" to ua), extra)
            val ok = mutableListOf<Pair<Cand, Verdict>>()
            val unknown = mutableListOf<Pair<Cand, Verdict>>()
            cands.forEachIndexed { i, c ->
                val v = verdicts[i]
                log.append("  [").append(v.state).append(" ").append(v.note).append("] ").append(c.family).append(" | ")
                    .append(c.label).append(" | ").append((v.url ?: c.url).take(150)).append('\n')
                if (v.state == "ok") ok.add(c to v) else if (v.state == "unknown") unknown.add(c to v)
            }
            val order = Comparator<Pair<Cand, Verdict>> { a, b ->
                val ha = heightOf(a.first.label).let { if (it == 0) 500 else it }   // "Auto" xếp sau 1080p
                val hb = heightOf(b.first.label).let { if (it == 0) 500 else it }
                if (ha != hb) hb.compareTo(ha) else a.first.rank.compareTo(b.first.rank)
            }
            verified = ok.size
            ok.sortWith(order)
            unknown.sortWith(order)

            // Chỉ hiện link chưa kiểm tra được khi không có link nào đã kiểm tra chạy được
            val picked = ok.map { it to false } +
                (if (ok.isEmpty()) unknown.take(4).map { it to true } else emptyList())

            val names = HashMap<String, Int>()
            for ((pair, unverified) in picked) {
                if (names.values.sum() >= maxStreams) break
                val (c, v) = pair
                val fam = c.family.replaceFirstChar { it.uppercase() }
                val label = c.label.replace(Regex("\\bauto\\b", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s*\\|\\s*"), " · ").replace(Regex("\\s+"), " ").trim()
                    .ifBlank { fam }
                var disp = label + if (unverified) " (có thể không phát)" else ""
                val n = (names[disp] ?: 0) + 1
                names[disp] = n
                if (n > 1) disp += " #$n"

                val isM3u8 = if (v.state == "ok") v.m3u8 else c.declared.equals("hls", true)
                val hs = v.headers ?: headers
                callback(
                    newExtractorLink(
                        name, disp, v.url ?: c.url,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = hs["Referer"] ?: ""
                        // Link đã kiểm tra xếp trên (CloudStream sắp theo quality), link chưa chắc chắn xếp cuối
                        this.quality = if (unverified) 100
                        else heightOf(c.label).let { if (it > 0) qualityValue(it) else Qualities.P720.value }
                        this.headers = hs
                    }
                )
                found = true
            }
            if (!found) log.append("NO_STREAMS: mọi nguồn đều chết\n")
            return found
        } catch (e: Exception) {
            log.append("EXCEPTION: ").append(e.javaClass.simpleName).append(": ")
                .append(e.message).append('\n')
            return found
        } finally {
            if (DEBUG && (!found || verified == 0)) copyLog(log.toString())
        }
    }

    // Tự chép log vào clipboard khi không tìm được link, để gửi cho người phát triển
    private fun copyLog(text: String) {
        runCatching {
            val act = com.lagradost.cloudstream3.CommonActivity.activity ?: return
            act.runOnUiThread {
                runCatching {
                    val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("321Movies log", text.take(60000)))
                    android.widget.Toast.makeText(
                        act, "Đã chép log 321Movies vào clipboard", android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }
}
