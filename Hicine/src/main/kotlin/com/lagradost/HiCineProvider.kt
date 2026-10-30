package com.hicine

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

/**
 * HiCine v1 - port của plugin SkyStream "HiCine" (TJ-Plugins).
 *
 * API: https://api.hicine.sbs/api/<collection>?offset=&limit=
 * Link: worker  /api/links?vcloud=..  ->  {tokens:{fsl:{ts,sig},..}}  ->  <worker>/go?type=..&vcloud=..&ts=..&sig=..
 * (player tự theo 302 tới file thật). Mọi lỗi được copy log vào clipboard.
 */
class HiCineProvider : MainAPI() {
    override var mainUrl = "https://api.hicine.sbs"
    override var name = "HiCine"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    companion object {
        const val VERSION = 1
        const val UA =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        const val TMDB_KEY = "e716f19ab4d25edc5247239a8f3494f8"
        const val PAGE = 18
        val SKIP_SERVERS = setOf("ten", "gofile")
        val SERVER_PREF = listOf("fsl", "server1", "fsl2", "pixel")
        val SERVER_LABEL = mapOf(
            "fsl" to "FSL", "fsl2" to "FSL v2", "pixel" to "PixelDrain",
            "server1" to "Server 1", "gofile" to "Gofile", "ten" to "10Gbps"
        )
    }

    private val mapper = jacksonObjectMapper()
    private var lastCopy = 0L

    // ---------------------------------------------------------------- debug
    private inner class Dbg(private val tag: String) {
        private val sb = StringBuilder()
        private val t0 = System.currentTimeMillis()
        fun log(msg: String) {
            val line = "[${System.currentTimeMillis() - t0}ms] $msg"
            synchronized(sb) { sb.append(line).append('\n') }
            Log.d("HiCine", line)
        }

        fun err(msg: String, e: Throwable?) {
            log("LỖI $msg: ${e?.javaClass?.simpleName}: ${e?.message}")
            Log.e("HiCine", msg, e)
        }

        fun copy(reason: String) {
            val now = System.currentTimeMillis()
            if (now - lastCopy < 3000) return
            lastCopy = now
            val text = synchronized(sb) {
                "HiCine v$VERSION | $tag | $reason\n" +
                    "Android ${android.os.Build.VERSION.RELEASE} | ${android.os.Build.MODEL}\n" + sb.toString()
            }
            try {
                val act = CommonActivity.activity ?: return
                act.runOnUiThread {
                    try {
                        val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("HiCine log", text))
                        Toast.makeText(act, "HiCine: đã copy log lỗi ($reason)", Toast.LENGTH_LONG).show()
                    } catch (_: Throwable) {
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- collections
    private class Col(val path: String, val type: TvType)

    private val cols = mapOf(
        "bolly_movies" to Col("bollywood_movies", TvType.Movie),
        "bolly_series" to Col("bollywood_series", TvType.TvSeries),
        "movies" to Col("hollywood_movies", TvType.Movie),
        "series" to Col("hollywood_series", TvType.TvSeries),
        "anime" to Col("anime", TvType.Anime)
    )
    private val pathToCt: Map<String, String> =
        cols.entries.associate { it.value.path to it.key } + mapOf("movies" to "movies", "series" to "series")

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "recent" to "Latest Uploads",
        "movies" to "Hollywood Movies",
        "series" to "Hollywood Series",
        "bolly_movies" to "Bollywood Movies",
        "bolly_series" to "Bollywood Series",
        "anime" to "Anime"
    )

    // ---------------------------------------------------------------- helpers
    private suspend fun getJson(url: String): JsonNode {
        val r = app.get(url, headers = mapOf("User-Agent" to UA, "Accept" to "application/json, text/plain, */*"))
        if (r.code !in 200..299) throw Exception("HTTP ${r.code} $url")
        return mapper.readTree(r.text)
    }

    private fun JsonNode.s(key: String): String {
        val n = get(key)
        return if (n == null || n.isNull) "" else n.asText("")
    }

    private fun decode(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#039;", "'").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ")

    private fun stripTags(h: String) = decode(h.replace(Regex("<[^>]*>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun yearOf(rec: JsonNode): Int? {
        Regex("\\((19\\d{2}|20\\d{2})\\)").find(rec.s("title"))?.let { return it.groupValues[1].toInt() }
        val y = rec.s("date").take(4).toIntOrNull()
        return if (y != null && y in 1901..2099) y else null
    }

    private fun cleanTitle(t: String) =
        decode(t.trim()).replace(Regex("\\s*\\((19\\d{2}|20\\d{2})\\)\\s*$"), "").trim()

    private fun fixImg(u: String): String {
        val s = u.trim()
        return when {
            s.isEmpty() -> ""
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "https://storage.hicine.sbs$s"
            s.startsWith("http://") -> "https://" + s.substring(7)
            else -> s
        }
    }

    private fun qualityText(t: String): String {
        val m = Regex("\\b(2160p|1440p|1080p|720p|480p|360p)\\b", RegexOption.IGNORE_CASE).find(t)
        if (m != null) return m.groupValues[1].lowercase()
        if (Regex("\\b4k\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) return "2160p"
        return ""
    }

    private fun qInt(q: String) = q.removeSuffix("p").toIntOrNull() ?: Qualities.Unknown.value

    private fun sizeText(t: String) =
        Regex("([\\d.]+)\\s*(GB|MB)", RegexOption.IGNORE_CASE).find(t)?.let { it.groupValues[1] + it.groupValues[2].uppercase() } ?: ""

    private val langs = listOf(
        "hindi", "punjabi", "urdu", "tamil", "telugu", "marathi", "kannada", "malayalam",
        "bengali", "gujarati", "english", "korean", "japanese", "chinese", "spanish", "arabic"
    )

    private fun langText(t: String): String {
        val s = decode(t.lowercase())
        return langs.filter { s.contains(it) }.joinToString("-")
    }

    private fun langLabel(l: String) = l.split("-").joinToString("-") { it.replaceFirstChar { c -> c.uppercase() } }

    private fun langTier(l: String) = when {
        l.isEmpty() -> 3
        l.contains("hindi") -> 0
        l.contains("punjabi") || l.contains("urdu") -> 1
        l.contains("english") -> 4
        else -> 2
    }

    private fun detailUrl(ct: String, id: String) = "$mainUrl/api/${cols[ct]!!.path}/$id"

    private fun ctOf(rec: JsonNode, forced: String?): String {
        if (forced != null && cols.containsKey(forced)) return forced
        val c = rec.s("contentType")
        if (cols.containsKey(c)) return c
        val cats = rec.s("categories")
        return when {
            Regex("anime", RegexOption.IGNORE_CASE).containsMatchIn(cats) -> "anime"
            Regex("bollywood", RegexOption.IGNORE_CASE).containsMatchIn(cats) ->
                if (Regex("series", RegexOption.IGNORE_CASE).containsMatchIn(cats)) "bolly_series" else "bolly_movies"
            Regex("series|korean|k-drama", RegexOption.IGNORE_CASE).containsMatchIn(cats) -> "series"
            else -> "movies"
        }
    }

    private fun toCard(rec: JsonNode, forced: String? = null): SearchResponse? {
        val id = rec.s("record_id")
        if (id.isEmpty()) return null
        val ct = ctOf(rec, forced)
        val type = cols[ct]!!.type
        val title = cleanTitle(rec.s("title"))
        val poster = fixImg(rec.s("featured_image").ifEmpty { rec.s("poster") })
        val url = detailUrl(ct, id)
        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster.ifEmpty { null }
                this.year = yearOf(rec)
            }
        } else {
            newTvSeriesSearchResponse(title, url, type) {
                this.posterUrl = poster.ifEmpty { null }
                this.year = yearOf(rec)
            }
        }
    }

    private fun arr(d: JsonNode): List<JsonNode> {
        val a = if (d.isArray) d else d.get("data")
        return if (a != null && a.isArray) a.toList() else emptyList()
    }

    // ---------------------------------------------------------------- main page
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val dbg = Dbg("getMainPage ${request.name}")
        try {
            val off = (page - 1) * PAGE
            val key = request.data
            val items: List<SearchResponse> = when (key) {
                "trending" -> arr(getJson("$mainUrl/api/trending-paginated?offset=$off&limit=$PAGE")).mapNotNull { toCard(it) }
                "recent" -> {
                    if (page > 1) emptyList()
                    else arr(getJson("$mainUrl/api/recent")).take(PAGE).mapNotNull { toCard(it) }
                }
                else -> arr(getJson("$mainUrl/api/${cols[key]!!.path}?offset=$off&limit=$PAGE")).mapNotNull { toCard(it, key) }
            }
            dbg.log("${request.name} page=$page -> ${items.size}")
            if (items.isEmpty() && page == 1) {
                dbg.copy("trang chủ trống")
            }
            return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty() && key != "recent")
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("getMainPage", e)
            dbg.copy("getMainPage lỗi")
            return null
        }
    }

    // ---------------------------------------------------------------- search
    override suspend fun search(query: String): List<SearchResponse> {
        val dbg = Dbg("search $query")
        try {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            fun norm(t: String) = t.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "")
            val words = q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }

            suspend fun fetchQ(t: String): List<JsonNode> = try {
                arr(getJson("$mainUrl/api/search/" + URLEncoder.encode(t, "UTF-8").replace("+", "%20")))
            } catch (e: Throwable) {
                if (e is java.util.concurrent.CancellationException) throw e
                dbg.log("search '$t' lỗi: ${e.message}")
                emptyList()
            }

            val items = fetchQ(q).toMutableList()
            if (items.size < 3 && words.size > 1) {
                val variants = mutableListOf(words.joinToString("-"), words.joinToString(" "))
                words.sortedByDescending { it.length }.take(2).forEach { if (it.length > 2) variants.add(it) }
                for (v in variants.distinct().filter { it != q }) {
                    for (rec in fetchQ(v)) {
                        val t = norm(rec.s("title").ifEmpty { rec.s("name") })
                        if (words.all { t.contains(norm(it)) }) items.add(rec)
                    }
                }
            }
            val seen = HashSet<String>()
            val out = ArrayList<SearchResponse>()
            for (rec in items) {
                val c = toCard(rec) ?: continue
                val k = c.name.lowercase() + "|" + (rec.s("record_id"))
                val k2 = c.name.lowercase() + "|" + (yearOf(rec) ?: 0)
                if (!seen.add(k2)) continue
                out.add(c)
            }
            dbg.log("search '$q' -> ${out.size}")
            return out
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("search", e)
            dbg.copy("search lỗi")
            return emptyList()
        }
    }

    // ---------------------------------------------------------------- parse links
    private class Variant(val url: String, val quality: String)
    private class EpInfo(val num: Int, val variants: List<Variant>)

    private fun parseSeasonEpisodes(text: String): List<EpInfo> {
        val out = ArrayList<EpInfo>()
        for (line in decode(text).split(Regex("\\r?\\n"))) {
            val m = Regex("episode\\s*(\\d+)\\s*[:\\-]?\\s*(.*)", RegexOption.IGNORE_CASE).find(line) ?: continue
            val num = m.groupValues[1].toInt()
            val rest = m.groupValues[2]
            val variants = ArrayList<Variant>()
            val re = Regex("(https?://[^\\s,]+?)\\s*,\\s*,?\\s*([^:]*?)(?=\\s*:|$)")
            for (h in re.findAll(rest)) {
                val label = h.groupValues[2].trim()
                variants.add(Variant(h.groupValues[1], qualityText(label).ifEmpty { label.ifEmpty { "auto" } }))
            }
            if (variants.isEmpty()) {
                Regex("https?://[^\\s,]+").findAll(rest).forEach { variants.add(Variant(it.value, "auto")) }
            }
            if (variants.isNotEmpty()) out.add(EpInfo(num, variants))
        }
        return out
    }

    private class Target(val workerUrl: String, val quality: String, val size: String, val lang: String)

    private fun parseMovieLinks(links: String): List<Target> {
        val out = ArrayList<Target>()
        for (line in links.split(Regex("\\r?\\n"))) {
            val m = Regex("https?://[^\\s,]+").find(line) ?: continue
            out.add(Target(m.value, qualityText(line).ifEmpty { "auto" }, sizeText(line), langText(line)))
        }
        return out
    }

    // ---------------------------------------------------------------- load
    private fun parseUrl(url: String): Triple<String, String, String>? {
        val m = Regex("/api/([a-z_\\-]+)/(\\d+)").find(url) ?: return null
        val ct = pathToCt[m.groupValues[1].lowercase()] ?: return null
        return Triple(ct, m.groupValues[2], detailUrl(ct, m.groupValues[2]))
    }

    private suspend fun tmdb(title: String, year: Int?, tv: Boolean, poster: String): Triple<String, Double?, String?>? {
        return try {
            val kind = if (tv) "tv" else "movie"
            val q = title.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "").trim()
            if (q.isEmpty()) return null
            val d = getJson("https://api.themoviedb.org/3/search/$kind?api_key=$TMDB_KEY&query=" +
                URLEncoder.encode(q, "UTF-8"))
            val list = arr(d.get("results") ?: return null)
            val key = Regex("/([A-Za-z0-9]{20,})\\.(?:webp|jpe?g|png)").find(poster)?.groupValues?.get(1)
            var hit = key?.let { k -> list.firstOrNull { it.s("poster_path").contains(k) } }
            if (hit == null && year != null) {
                hit = list.firstOrNull { (it.s("release_date").ifEmpty { it.s("first_air_date") }).take(4) == year.toString() }
            }
            if (hit == null) return null
            val bd = hit.s("backdrop_path")
            Triple(hit.s("overview"), hit.get("vote_average")?.asDouble(),
                if (bd.isNotEmpty()) "https://image.tmdb.org/t/p/w1280$bd" else null)
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val dbg = Dbg("load")
        try {
            val p = parseUrl(url) ?: throw Exception("URL HiCine không nhận ra: $url")
            val (ct, id, durl) = p
            val det = getJson(durl)
            if (det.has("error")) throw Exception("API: ${det.s("error")}")
            val type = cols[ct]!!.type
            val title = cleanTitle(det.s("title"))
            val poster = fixImg(det.s("featured_image").ifEmpty { det.s("poster") })
            val year = yearOf(det)
            var plot = stripTags(det.s("content").ifEmpty { det.s("excerpt") })
            val tags = det.s("categories").split(",").map { it.trim() }
                .filter { it.isNotEmpty() && !Regex("^(\\d{3,4}p|\\d{4}|featured)$", RegexOption.IGNORE_CASE).matches(it) }
            var score: Double? = null
            var backdrop: String? = null
            if (plot.isEmpty()) {
                val tm = tmdb(title, year, type != TvType.Movie, poster)
                if (tm != null) {
                    plot = tm.first
                    score = tm.second
                    backdrop = tm.third
                }
            }

            // ---- tập phim
            val episodes = ArrayList<Episode>()
            for (s in 1..20) {
                val txt = det.s("season_$s")
                if (txt.isEmpty()) continue
                for (e in parseSeasonEpisodes(txt)) {
                    episodes.add(newEpisode("$durl|||$s|||${e.num}") {
                        this.name = "Episode ${e.num}"
                        this.season = s
                        this.episode = e.num
                        this.posterUrl = poster.ifEmpty { null }
                    })
                }
            }
            dbg.log("load $ct/$id title=$title eps=${episodes.size} movieLinks=${parseMovieLinks(det.s("links")).size}")

            val isSeries = episodes.isNotEmpty() && type != TvType.Movie
            return if (isSeries || (episodes.isNotEmpty() && parseMovieLinks(det.s("links")).isEmpty())) {
                newTvSeriesLoadResponse(title, durl, if (type == TvType.Movie) TvType.TvSeries else type, episodes) {
                    this.posterUrl = poster.ifEmpty { null }
                    this.backgroundPosterUrl = backdrop ?: poster.ifEmpty { null }
                    this.year = year
                    this.plot = plot.ifEmpty { null }
                    this.tags = tags
                }
            } else {
                if (parseMovieLinks(det.s("links")).isEmpty()) {
                    plot = (if (plot.isEmpty()) "" else "$plot\n\n") + "⚠ Chưa có link cho phim này trên HiCine."
                }
                newMovieLoadResponse(title, durl, TvType.Movie, "$durl|||0|||0") {
                    this.posterUrl = poster.ifEmpty { null }
                    this.backgroundPosterUrl = backdrop ?: poster.ifEmpty { null }
                    this.year = year
                    this.plot = plot.ifEmpty { null }
                    this.tags = tags
                }
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("load", e)
            dbg.copy("load lỗi")
            return null
        }
    }

    // ---------------------------------------------------------------- links
    private suspend fun goPixel(goUrl: String, dbg: Dbg): String? {
        return try {
            val r = app.get(goUrl, headers = mapOf("User-Agent" to UA, "Range" to "bytes=0-0"), timeout = 10L)
            val fin = r.okhttpResponse.request.url.toString()
            val m = Regex("^https?://(pixeldrain\\.[a-z]+)/(?:u|api/file)/([A-Za-z0-9]+)").find(fin)
            if (m != null) "https://${m.groupValues[1]}/api/file/${m.groupValues[2]}" else {
                dbg.log("pixel final=${fin.take(80)}")
                null
            }
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.log("pixel lỗi ${e.message}")
            null
        }
    }

    private suspend fun resolve(
        t: Target, dbg: Dbg, allowFallback: Boolean, callback: (ExtractorLink) -> Unit
    ): Int {
        val qi = t.workerUrl.indexOf('?')
        if (qi < 0) return 0
        val base = t.workerUrl.substring(0, qi).trimEnd('/')
        val vcloud = Regex("[?&]vcloud=([^&]+)").find(t.workerUrl)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: return 0
        val d = getJson("$base/api/links?vcloud=" + URLEncoder.encode(vcloud, "UTF-8"))
        val tokens = d.get("tokens")
        if (tokens == null || !tokens.isObject || tokens.size() == 0) {
            dbg.log("link chết: ${vcloud.takeLast(14)}")
            return 0
        }
        var keys = tokens.fieldNames().asSequence().toList()
        if (!allowFallback) keys = keys.filter { it !in SKIP_SERVERS }
        keys = keys.sortedBy { val i = SERVER_PREF.indexOf(it); if (i < 0) 99 else i }
        var n = 0
        val size = t.size.ifEmpty { d.s("size") }
        for (k in keys) {
            val tk = tokens.get(k)
            val ts = tk.s("ts")
            val sig = tk.s("sig")
            if (ts.isEmpty() || sig.isEmpty()) continue
            var url = "$base/go?type=${URLEncoder.encode(k, "UTF-8")}&vcloud=${URLEncoder.encode(vcloud, "UTF-8")}" +
                "&ts=${URLEncoder.encode(ts, "UTF-8")}&sig=${URLEncoder.encode(sig, "UTF-8")}"
            if (k == "pixel") {
                url = goPixel(url, dbg) ?: continue
            }
            var label = SERVER_LABEL[k] ?: k.uppercase()
            if (t.lang.isNotEmpty()) label += " • ${langLabel(t.lang)}"
            if (t.quality != "auto" && t.quality.isNotEmpty()) label += " • ${t.quality}"
            if (size.isNotEmpty()) label += " • $size"
            val q = if (t.quality == "auto") Qualities.Unknown.value else qInt(t.quality)
            callback(
                newExtractorLink(name, label, url, ExtractorLinkType.VIDEO) {
                    this.quality = q
                    this.headers = mapOf("User-Agent" to UA)
                }
            )
            n++
        }
        return n
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val dbg = Dbg("loadLinks")
        try {
            val parts = data.split("|||")
            val durl = parts[0]
            val season = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val ep = parts.getOrNull(2)?.toIntOrNull() ?: 0
            val det = getJson(durl)
            val targets = ArrayList<Target>()
            if (season > 0 && ep > 0) {
                val txt = det.s("season_$season")
                val seasonLang = langText(txt.lineSequence().firstOrNull() ?: "")
                val e = parseSeasonEpisodes(txt).firstOrNull { it.num == ep }
                e?.variants?.forEach { targets.add(Target(it.url, it.quality, "", seasonLang)) }
            } else {
                targets.addAll(parseMovieLinks(det.s("links")))
            }
            dbg.log("targets=${targets.size} data=${data.takeLast(40)}")
            if (targets.isEmpty()) {
                dbg.copy("không có link nào được tải lên")
                return false
            }
            val sorted = targets.sortedWith(
                compareBy<Target> { langTier(it.lang) }.thenByDescending { qInt(it.quality) }
            )
            var total = 0
            for (t in sorted) {
                var got = 0
                for (attempt in 1..2) {
                    try {
                        got = resolve(t, dbg, false, callback)
                        if (got > 0) break
                    } catch (e: Throwable) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        dbg.err("resolve ${t.quality}", e)
                    }
                }
                if (got == 0) {
                    try {
                        got = resolve(t, dbg, true, callback)
                    } catch (e: Throwable) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        dbg.err("resolve-fallback ${t.quality}", e)
                    }
                }
                dbg.log("${t.quality} ${t.lang} -> $got link")
                total += got
            }
            if (total == 0) {
                dbg.copy("không lấy được link phát")
                return false
            }
            return true
        } catch (e: Throwable) {
            if (e is java.util.concurrent.CancellationException) throw e
            dbg.err("loadLinks", e)
            dbg.copy("loadLinks lỗi")
            return false
        }
    }
}
