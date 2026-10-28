package com.lagradost.extractors

import com.lagradost.utils.CryptoUtils
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject

object VidRockExtractor {
    private const val BASE_URL = "https://vidrock.net"
    private const val KEY_HEX = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    suspend fun extract(
        tmdbId: Int,
        isTv: Boolean,
        season: Int,
        episode: Int,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val apiPath = if (isTv) "api/tv/$tmdbId/$season/$episode" else "api/movie/$tmdbId"
        val requestUrl = "$BASE_URL/$apiPath"

        val response = app.get(
            requestUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$BASE_URL/"
            )
        ).text

        val json = try { JSONObject(response) } catch (e: Exception) { return }

        val serverNames = json.keys()
        while (serverNames.hasNext()) {
            val serverName = serverNames.next()
            val serverObj = json.optJSONObject(serverName) ?: continue
            val encryptedUrl = serverObj.optString("url")
            val type = serverObj.optString("type")
            val language = serverObj.optString("language", "")

            if (encryptedUrl.isNullOrBlank()) continue

            val decryptedUrl = try {
                CryptoUtils.decryptVidRockGcm(encryptedUrl, KEY_HEX)
            } catch (e: Exception) {
                null
            } ?: continue

            if (!decryptedUrl.startsWith("http")) continue

            val labelSuffix = if (language.isNotEmpty()) " - $language" else ""

            if (type == "mp4" && decryptedUrl.contains("streamrk.site/playlist")) {
                try {
                    val plRes = app.get(
                        decryptedUrl,
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to "$BASE_URL/"
                        )
                    ).text
                    val arr = JSONArray(plRes)
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val directMp4 = item.optString("url")
                        val res = item.optInt("resolution", 1080)
                        if (directMp4.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "Rock $serverName",
                                    name = "Rock $serverName - ${res}p MP4",
                                    url = directMp4,
                                    referer = "$BASE_URL/",
                                    quality = getQualityFromName("${res}p"),
                                    isM3u8 = false,
                                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$BASE_URL/")
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                M3u8Helper.generateM3u8(
                    source = "Rock $serverName$labelSuffix",
                    streamUrl = decryptedUrl,
                    referer = "$BASE_URL/",
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$BASE_URL/"
                    )
                ).forEach(callback)
            }
        }
    }
}
