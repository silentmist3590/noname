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

    private suspend fun tmdbGet(path: String): JSONObject? {
        val sep = if (path.contains("?")) "&" else "?"
        val url = "$tmdb$path${sep}language=en-US"
        val headers = mapOf(
            "Authorization" to "Bearer $tmdbToken",
            "Accept" to "application/json"
        )
        for (attempt in 0 until 3) {
            val body = runCatching { app.get(url, headers = headers).text }.getOrNull()
            val obj = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (obj != null && !obj.has("status_code")) return obj
            if (obj != null && obj.optInt("status_code") == 34) return null // không tồn tại
            Thread.sleep(350L * (attempt + 1))
        }
        return null
    }

    private fun image(path: String?, size: String): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return img + size + (if (path.startsWith("/")) path else "/$path")
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

    private data class Verdict(val state: String, val m3u8: Boolean)   // state: ok | dead | unknown

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

    private fun probeOne(client: OkHttpClient, url: String, headers: Map<String, String>): Verdict {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        rb.header("Accept", "*/*").header("Range", "bytes=0-2047")
        return try {
            client.newCall(rb.build()).execute().use { r ->
                val code = r.code
                if (code == 200 || code == 206) {
                    val head = runCatching { r.peekBody(2048).string() }.getOrDefault("")
                        .replace("﻿", "").trimStart()
                    when {
                        head.startsWith("#EXTM3U") -> Verdict("ok", true)
                        Regex("^<(!doctype|html|\\?xml)", RegexOption.IGNORE_CASE).containsMatchIn(head) ->
                            if (head.contains("<MPD", true)) Verdict("ok", false) else Verdict("dead", false)
                        head.isNotEmpty() -> Verdict("ok", false)
                        else -> Verdict("unknown", false)
                    }
                } else if (code == 401 || code == 403 || code == 429) {
                    Verdict("unknown", false)
                } else {
                    Verdict("dead", false)
                }
            }
        } catch (e: UnknownHostException) {
            Verdict("dead", false)
        } catch (e: Exception) {
            Verdict("unknown", false)
        }
    }

    /** Kiểm tra song song bằng luồng thường (không cần coroutines). */
    private fun probeAll(cands: List<Cand>, headers: Map<String, String>): List<Verdict> {
        if (cands.isEmpty()) return emptyList()
        val client = app.baseClient.newBuilder()
            .callTimeout(9, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
        val pool = Executors.newFixedThreadPool(minOf(cands.size, 8))
        return try {
            val fs = cands.map { c -> pool.submit(Callable { probeOne(client, c.url, headers) }) }
            fs.map { f -> runCatching { f.get(14, TimeUnit.SECONDS) }.getOrDefault(Verdict("unknown", false)) }
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
            val verdicts = probeAll(cands, headers)
            val ok = mutableListOf<Pair<Cand, Verdict>>()
            val unknown = mutableListOf<Pair<Cand, Verdict>>()
            cands.forEachIndexed { i, c ->
                val v = verdicts[i]
                log.append("  [").append(v.state).append("] ").append(c.family).append(" | ")
                    .append(c.label).append(" | ").append(c.url.take(90)).append('\n')
                if (v.state == "ok") ok.add(c to v) else if (v.state == "unknown") unknown.add(c to v)
            }
            val order = Comparator<Pair<Cand, Verdict>> { a, b ->
                val ha = heightOf(a.first.label).let { if (it == 0) 500 else it }   // "Auto" xếp sau 1080p
                val hb = heightOf(b.first.label).let { if (it == 0) 500 else it }
                if (ha != hb) hb.compareTo(ha) else a.first.rank.compareTo(b.first.rank)
            }
            ok.sortWith(order)
            unknown.sortWith(order)

            val picked = ok.map { it to false } +
                unknown.take(maxOf(0, 6 - ok.size)).map { it to true }

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
                callback(
                    newExtractorLink(
                        name, disp, c.url,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = qualityValue(heightOf(c.label))
                        this.headers = headers
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
            if (DEBUG && !found) copyLog(log.toString())
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
