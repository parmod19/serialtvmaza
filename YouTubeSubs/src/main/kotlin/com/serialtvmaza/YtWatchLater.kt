package com.serialtvmaza

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

object YtWatchLater {
    private var appCtx: Context? = null
    private var prefs: SharedPreferences? = null
    private val ui = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = ctx.applicationContext.getSharedPreferences("yt_subs_wl", Context.MODE_PRIVATE)
    }

    fun toast(msg: String) {
        val c = appCtx ?: return
        ui.post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
    }

    fun list(): List<Pair<String, String>> {
        val a = try {
            JSONArray(prefs?.getString("items", "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            if (id.isNotBlank()) out.add(Pair(id, o.optString("title", id)))
        }
        return out
    }

    private fun save(items: List<Pair<String, String>>) {
        val a = JSONArray()
        items.forEach { a.put(JSONObject().put("id", it.first).put("title", it.second)) }
        prefs?.edit()?.putString("items", a.toString())?.apply()
    }

    fun contains(id: String): Boolean = list().any { it.first == id }

    // returns true if added, false if removed
    fun toggle(id: String, title: String): Boolean {
        val cur = list().toMutableList()
        val idx = cur.indexOfFirst { it.first == id }
        if (idx >= 0) {
            cur.removeAt(idx)
            save(cur)
            return false
        }
        cur.add(Pair(id, title))
        save(cur)
        return true
    }

    fun clear() {
        save(emptyList())
    }

    fun showManage(ctx: Context) {
        val items = list()
        if (items.isEmpty()) {
            AlertDialog.Builder(ctx)
                .setTitle("Watch Later")
                .setMessage("Watch Later is empty")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = items.map { it.second.take(60) }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Watch Later (" + items.size + ") - tap to remove")
            .setItems(labels) { _, w ->
                AlertDialog.Builder(ctx)
                    .setTitle("Remove from Watch Later?")
                    .setMessage(items[w].second)
                    .setPositiveButton("Remove") { _, _ ->
                        toggle(items[w].first, items[w].second)
                        toast("Removed")
                        showManage(ctx)
                    }
                    .setNegativeButton("Back") { _, _ -> showManage(ctx) }
                    .show()
            }
            .setNeutralButton("Clear all") { _, _ ->
                save(emptyList())
                toast("Cleared")
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
