package com.cinehd.extractors

import com.cinehd.utils.CryptoUtils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

object NxshaExtractor {
    private const val BASE_URL = "https://nxsha.space"
    private const val PASSPHRASE = "f4488ab4da401203d23baa129fc546153898162524635d6776826d0c867ccaa3"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val SERVER_FRIENDLY_NAMES = mapOf(
        "nitro" to "Nitro",
        "mhbox" to "MhPly",
        "rive-citadel" to "Citadel",
        "awsind" to "AwsPly",
        "watchout" to "Watchout",
        "castle" to "CastVid",
        "vidapi" to "VidPi",
        "hdhub4u" to "HDHub",
        "stvv" to "Stvvid",
        "holly" to "Lolly",
        "bkl-blast" to "MbBlast",
        "rive-primevids" to "Prvibd",
        "streamflix" to "StremFx",
        "rive-flowcast" to "River",
        "rive-quasar" to "Kutti",
        "ophm" to "Ophm",
        "rive-guru" to "Gbru"
    )

    suspend fun extract(
        tmdbId: Int,
        isTv: Boolean,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val queryObj = JSONObject().apply {
            put("tmdbId", tmdbId.toString())
            put("imdb_id", "")
            put("type", if (isTv) "tv" else "movie")
            put("season", if (isTv) season else 0)
            put("episode", if (isTv) episode else 0)
        }

        val q1 = CryptoUtils.encryptOpenSsl(queryObj.toString(), PASSPHRASE)
        val srvRes = app.get(
            "$BASE_URL/api/servers?q=$q1",
            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$BASE_URL/")
        ).text

        val srvJson = try { JSONObject(srvRes) } catch (e: Exception) { return }
        val srvHash = srvJson.optString("_hash")
        if (srvHash.isNullOrBlank()) return

        val decryptedServersStr = CryptoUtils.decryptOpenSsl(srvHash, PASSPHRASE)
        val decryptedServers = JSONObject(decryptedServersStr)
        val serversArray = decryptedServers.optJSONArray("servers") ?: JSONArray()

        val activeScrapers = ArrayList<Pair<String, String>>()
        for (i in 0 until serversArray.length()) {
            val sObj = serversArray.optJSONObject(i) ?: continue
            val scraper = sObj.optString("scraper")
            val name = sObj.optString("name")
            if (scraper.isNotEmpty()) {
                activeScrapers.add(scraper to name)
            }
        }

        // Lấy nguồn từ tối đa 16 server hàng đầu song song
        coroutineScope {
            val limited = activeScrapers.take(16)
            val deferred = limited.map { (scraper, srvName) ->
                async {
                    try {
                        val q2Obj = JSONObject().apply {
                            put("ex_lang", true)
                            put("provider", scraper)
                            put("tmdbId", tmdbId.toString())
                            put("imdb_id", "")
                            put("type", if (isTv) "tv" else "movie")
                            put("season", if (isTv) season else 0)
                            put("episode", if (isTv) episode else 0)
                        }
                        val q2 = CryptoUtils.encryptOpenSsl(q2Obj.toString(), PASSPHRASE)
                        val sourceRes = app.get(
                            "$BASE_URL/api/sources?q=$q2",
                            headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$BASE_URL/")
                        ).text

                        val sourceHash = JSONObject(sourceRes).optString("_hash")
                        if (sourceHash.isNotEmpty()) {
                            val decSourceStr = CryptoUtils.decryptOpenSsl(sourceHash, PASSPHRASE)
                            val decSources = JSONObject(decSourceStr)
                            val sourcesArr = decSources.optJSONArray("sources") ?: JSONArray()

                            val providerPretty = SERVER_FRIENDLY_NAMES[scraper] ?: srvName.replace(Regex("\s*-\\s*\\[.*?\\]"), "").trim()

                            for (k in 0 until sourcesArr.length()) {
                                val srcItem = sourcesArr.optJSONObject(k) ?: continue
                                val streamUrl = srcItem.optString("url")
                                if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) continue

                                val rawLabel = srcItem.optString("label").ifEmpty { srcItem.optString("quality") }
                                val cleanLabel = rawLabel
                                    .replace(Regex("\s*:\s*[\d,p]+\s*$"), "")
                                    .replace(Regex("\s*\|\s*(WEB-DL|BluRay|HDRip|WEBRip|HDTV)\b", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("\s*\|\s*"), " ")
                                    .trim()
                                    .ifEmpty { "multi-audio" }

                                val fullLabel = "$providerPretty - $cleanLabel"

                                M3u8Helper.generateM3u8(
                                    source = fullLabel,
                                    streamUrl = streamUrl,
                                    referer = "$BASE_URL/",
                                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$BASE_URL/")
                                ).forEach(callback)
                            }
                        }
                    } catch (e: Exception) {
                        // Bỏ qua server lỗi
                    }
                }
            }
            deferred.awaitAll()
        }
    }
}
