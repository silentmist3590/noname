package com.lagradost.extractors

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object VidRockExtractor {
    private const val BASE_URL = "https://vidrock.net"
    private const val KEY_HEX = "7f3e9c2a8b5d1f4e6a9c3b7d2e5f8a1c4b6d9e2f5a8c1b4d7e9f2a5c8b1d4e7f"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /**
     * Giải mã token VidRock AES-256-GCM: base64url(iv[12B] || ciphertext || tag[16B])
     */
    private fun decryptVidRockGcm(token: String, keyHex: String): String {
        val stdB64 = token.replace("-", "+").replace("_", "/").let {
            val pad = it.length % 4
            if (pad == 2) "$it==" else if (pad == 3) "$it=" else it
        }
        val fullBytes = Base64.decode(stdB64, Base64.DEFAULT)
        if (fullBytes.size < 28) throw IllegalArgumentException("Token too short for GCM")

        val iv = fullBytes.copyOfRange(0, 12)
        val cipherAndTag = fullBytes.copyOfRange(12, fullBytes.size)
        val keyBytes = keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return String(cipher.doFinal(cipherAndTag), Charsets.UTF_8)
    }

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

        val response = try {
            app.get(
                requestUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$BASE_URL/"
                )
            ).text
        } catch (_: Exception) {
            return
        }

        val json = try { JSONObject(response) } catch (_: Exception) { return }

        val serverNames = json.keys()
        while (serverNames.hasNext()) {
            val serverName = serverNames.next()
            val serverObj = json.optJSONObject(serverName) ?: continue
            val encryptedUrl = serverObj.optString("url")
            val type = serverObj.optString("type")
            val language = serverObj.optString("language", "")

            if (encryptedUrl.isNullOrBlank()) continue

            val decryptedUrl: String = try {
                decryptVidRockGcm(encryptedUrl, KEY_HEX)
            } catch (_: Exception) {
                ""
            }

            if (decryptedUrl.isEmpty() || !decryptedUrl.startsWith("http")) continue

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
                            @Suppress("DEPRECATION")
                            callback.invoke(
                                ExtractorLink(
                                    source = "Rock $serverName",
                                    name = "Rock $serverName - ${res}p MP4",
                                    url = directMp4,
                                    referer = "$BASE_URL/",
                                    quality = getQualityFromName("${res}p"),
                                    type = ExtractorLinkType.VIDEO,
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
