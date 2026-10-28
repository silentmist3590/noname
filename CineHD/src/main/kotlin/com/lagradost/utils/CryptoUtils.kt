package com.cinehd.utils

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {

    /**
     * Giải mã token VidRock AES-256-GCM: base64url(iv[12B] || ciphertext || tag[16B])
     */
    fun decryptVidRockGcm(token: String, keyHex: String): String {
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

    /**
     * Thuật toán OpenSSL EVP BytesToKey chuẩn của CryptoJS (sử dụng lặp MD5)
     */
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

    /**
     * Mã hóa định dạng OpenSSL "Salted__" + 8 byte salt + AES-256-CBC
     */
    fun encryptOpenSsl(plainText: String, passphrase: String): String {
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

    /**
     * Giải mã định dạng OpenSSL CryptoJS AES-256-CBC từ chuỗi Base64Url
     */
    fun decryptOpenSsl(b64url: String, passphrase: String): String {
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
}
