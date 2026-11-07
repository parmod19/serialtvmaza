package com.serialtvmaza

import java.security.MessageDigest

object YtAuth {
    fun cookieValue(cookie: String, key: String): String? =
        cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter("=")

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun authHeader(cookie: String, origin: String = "https://www.youtube.com"): String? {
        val ts = System.currentTimeMillis() / 1000
        val parts = mutableListOf<String>()
        cookieValue(cookie, "SAPISID")?.let {
            parts += "SAPISIDHASH ${ts}_${sha1("$ts $it $origin")}"
        }
        cookieValue(cookie, "__Secure-1PAPISID")?.let {
            parts += "SAPISID1PHASH ${ts}_${sha1("$ts $it $origin")}"
        }
        cookieValue(cookie, "__Secure-3PAPISID")?.let {
            parts += "SAPISID3PHASH ${ts}_${sha1("$ts $it $origin")}"
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }
}
