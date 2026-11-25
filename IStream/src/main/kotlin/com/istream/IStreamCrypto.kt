package com.istream

import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object IStreamCrypto {
    private const val SECRET = "iSf#2024\$Xk9@mNpQrStUvWxYz1234Ab"
    private const val SALT = "iStreamFlareSalt"

    private val aesKey: SecretKeySpec by lazy {
        val spec = PBEKeySpec(
            SECRET.toCharArray(),
            SALT.toByteArray(StandardCharsets.UTF_8),
            10000,
            256
        )
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        SecretKeySpec(bytes, "AES")
    }

    fun safeDecrypt(response: String): String {
        return try {
            val obj = JSONObject(response)
            if (!obj.optBoolean("encrypted", false)) return response
            decrypt(obj.getString("data"))
        } catch (e: Exception) {
            response
        }
    }

    private fun decrypt(data: String): String {
        val raw = Base64.decode(data, Base64.DEFAULT)
        require(raw.size >= 28) { "Invalid encrypted data length" }
        val iv = raw.copyOfRange(0, 12)
        val tag = raw.copyOfRange(12, 28)
        val ct = raw.copyOfRange(28, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        return String(cipher.doFinal(ct + tag), StandardCharsets.UTF_8)
    }
}
