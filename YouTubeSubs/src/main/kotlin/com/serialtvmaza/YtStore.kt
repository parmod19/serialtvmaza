package com.serialtvmaza

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

object YtStore {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("yt_subs_store", Context.MODE_PRIVATE)
    }

    private fun load(): JSONObject = try {
        JSONObject(prefs?.getString("data", null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun save(o: JSONObject) {
        prefs?.edit()?.putString("data", o.toString())?.apply()
    }

    fun names(): List<String> {
        val acc = load().optJSONObject("accounts") ?: return emptyList()
        return acc.keys().asSequence().toList()
    }

    fun active(): String? = load().optString("active", "").ifBlank { null }

    fun activeCookie(): String? {
        val d = load()
        val n = d.optString("active", "")
        if (n.isBlank()) return null
        return d.optJSONObject("accounts")?.optString(n, "")?.takeIf { it.isNotBlank() }
    }

    fun add(cookie: String): String {
        val d = load()
        val acc = d.optJSONObject("accounts") ?: JSONObject()
        val sid = YtAuth.cookieValue(cookie, "SAPISID")
        var name: String? = null
        val iter = acc.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (sid != null && YtAuth.cookieValue(acc.optString(k, ""), "SAPISID") == sid) {
                name = k
                break
            }
        }
        if (name == null) {
            var i = acc.length() + 1
            while (acc.has("Account $i")) i++
            name = "Account $i"
        }
        acc.put(name, cookie)
        d.put("accounts", acc)
        d.put("active", name)
        save(d)
        return name
    }

    fun setActive(name: String) {
        val d = load()
        if (d.optJSONObject("accounts")?.has(name) == true) {
            d.put("active", name)
            save(d)
        }
    }

    fun remove(name: String) {
        val d = load()
        val acc = d.optJSONObject("accounts") ?: return
        acc.remove(name)
        if (d.optString("active", "") == name) {
            d.put("active", acc.keys().asSequence().firstOrNull() ?: "")
        }
        save(d)
    }
}
