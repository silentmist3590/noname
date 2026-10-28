package com.cinehd.extractors

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

object VidLoveExtractor {
    private const val BASE_URL = "https://api.vidlove.cc"
    private const val PLAYER_REF = "https://player.vidlove.cc/"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val SOURCES = listOf(
        "MovieBox" to "&sources=moviebox",
        "MovieBox 2" to "&sources=moviebox2",
        "Best" to "",
        "Grand Warden" to "&sources=warden",
        "PEKKA" to "&sources=cinefreak",
        "VidAPI" to "&sources=vidapi",
        "IPCloud" to "&sources=ipcloud",
        "TCloud" to "&sources=tcloud"
    )

    suspend fun extract(
        tmdbId: Int,
        isTv: Boolean,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val basePath = if (isTv) {
            "/tv?id=$tmdbId&season=$season&episode=$episode&mode=json"
        } else {
            "/movie?id=$tmdbId&mode=json"
        }

        coroutineScope {
            val jobs = SOURCES.map { (label, sourceParam) ->
                async {
                    try {
                        val url = "$BASE_URL$basePath$sourceParam"
                        val response = app.get(
                            url,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to PLAYER_REF,
                                "Accept" to "application/json"
                            )
                        ).text

                        val json = JSONObject(response)
                        val source = json.optJSONObject("source") ?: return@async
                        val streamUrl = source.optString("url")

                        if (!streamUrl.startsWith("http")) return@async

                        // Lấy phụ đề nếu có
                        val subs = json.optJSONArray("subtitles")
                        if (subs != null) {
                            for (i in 0 until subs.length()) {
                                val subObj = subs.optJSONObject(i) ?: continue
                                val subUrl = subObj.optString("url")
                                val subLang = subObj.optString("lang").ifEmpty { subObj.optString("label") }
                                if (subUrl.startsWith("http")) {
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            lang = subLang,
                                            url = subUrl
                                        )
                                    )
                                }
                            }
                        }

                        M3u8Helper.generateM3u8(
                            source = "VidLove ($label)",
                            streamUrl = streamUrl,
                            referer = PLAYER_REF,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to PLAYER_REF
                            )
                        ).forEach(callback)
                    } catch (e: Exception) {
                        // Bỏ qua server không khả dụng
                    }
                }
            }
            jobs.awaitAll()
        }
    }
}
