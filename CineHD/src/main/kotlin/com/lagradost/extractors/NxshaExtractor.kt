package com.lagradost.extractors

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    private fun evpBytesToKey(passphrase: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        var digest = ByteArray(0)
        val total = keyLen + ivLen
        val derived = ByteArray(total)
        var offset = 0

        while (offset < total) {
            md5.reset()
            if (digest.isNotEmpty()) {
                md5.update(digest)
            }
            md5.update(passphrase)
            md5.update(salt)
            digest = md5.digest()

            val toCopy = Math.min(digest.size, total - offset)
            System.arraycopy(digest, 0, derived, offset, toCopy)
            offset += toCopy
        }

        val key = derived.copyOfRange(0, keyLen)
        val iv = derived.copyOfRange(keyLen, total)
        return Pair(key, iv)
    }

    private fun encryptOpenSsl(plainText: String, passphrase: String): String {
        val salt = ByteArray(8)
        SecureRandom().nextBytes(salt)

        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt, 32, 16)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        val header = "Salted__".toByteArray(Charsets.UTF_8)
        val full = ByteArray(header.size + salt.size + encrypted.size)
        System.arraycopy(header, 0, full, 0, header.size)
        System.arraycopy(salt, 0, full, header.size, salt.size)
        System.arraycopy(encrypted, 0, full, header.size + salt.size, encrypted.size)

        return Base64.encodeToString(full, Base64.NO_WRAP)
            .replace("+", "-")
            .replace("/", "_")
            .replace("=", "")
    }

    private fun decryptOpenSsl(b64url: String, passphrase: String): String {
        var std = b64url.replace("-", "+").replace("_", "/")
        while (std.length % 4 != 0) std += "="

        val full = Base64.decode(std, Base64.DEFAULT)
        if (full.size < 16) throw IllegalArgumentException("Encrypted payload too short")

        val salt = full.copyOfRange(8, 16)
        val cipherText = full.copyOfRange(16, full.size)

        val (key, iv) = evpBytesToKey(passphrase.toByteArray(Charsets.UTF_8), salt, 32, 16)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(cipherText)

        return String(decrypted, Charsets.UTF_8)
    }

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

        val q1 = encryptOpenSsl(queryObj.toString(), PASSPHRASE)
        val srvRes = try {
            app.get(
                "$BASE_URL/api/servers?q=$q1",
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$BASE_URL/")
            ).text
        } catch (_: Exception) {
            return
        }

        val srvJson = try { JSONObject(srvRes) } catch (_: Exception) { return }
        val srvHash = srvJson.optString("_hash")
        if (srvHash.isNullOrBlank()) return

        val decryptedServersStr = try {
            decryptOpenSsl(srvHash, PASSPHRASE)
        } catch (_: Exception) {
            return
        }

        val decryptedServers = try { JSONObject(decryptedServersStr) } catch (_: Exception) { return }
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

        for (item in activeScrapers.take(12)) {
            val scraper = item.first
            val srvName = item.second
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
                val q2 = encryptOpenSsl(q2Obj.toString(), PASSPHRASE)
                val sourceRes = app.get(
                    "$BASE_URL/api/sources?q=$q2",
                    headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$BASE_URL/")
                ).text

                val sourceHash = JSONObject(sourceRes).optString("_hash")
                if (sourceHash.isNotEmpty()) {
                    val decSourceStr = decryptOpenSsl(sourceHash, PASSPHRASE)
                    val decSources = JSONObject(decSourceStr)
                    val sourcesArr = decSources.optJSONArray("sources") ?: JSONArray()

                    val providerPretty = SERVER_FRIENDLY_NAMES[scraper] ?: srvName.replace(Regex("""\s*-\s*\[.*?\]"""), "").trim()

                    for (k in 0 until sourcesArr.length()) {
                        val srcItem = sourcesArr.optJSONObject(k) ?: continue
                        val streamUrl = srcItem.optString("url")
                        if (streamUrl.isNullOrBlank() || !streamUrl.startsWith("http")) continue

                        val rawLabel = srcItem.optString("label").ifEmpty { srcItem.optString("quality") }
                        val cleanLabel = rawLabel
                            .replace(Regex("""\s*:\s*[\d,p]+\s*$"""), "")
                            .replace(Regex("""\s*\|\s*(WEB-DL|BluRay|HDRip|WEBRip|HDTV)\b""", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("""\s*\|\s*"""), " ")
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
            } catch (_: Exception) {
                // Skip failed server
            }
        }
    }
}
