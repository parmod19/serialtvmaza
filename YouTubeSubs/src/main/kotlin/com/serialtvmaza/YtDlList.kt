package com.serialtvmaza

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

object YtDlList {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("yt_dl_list", Context.MODE_PRIVATE)
    }

    @Synchronized
    private fun load(): JSONArray = try {
        JSONArray(prefs?.getString("items", "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    @Synchronized
    private fun save(a: JSONArray) {
        prefs?.edit()?.putString("items", a.toString())?.apply()
    }

    // one entry per download: id, title, state (running / done / failed), percent, path, uri
    @Synchronized
    fun add(id: String, title: String) {
        val a = load()
        val o = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("state", "running")
            .put("percent", 0)
            .put("path", "")
            .put("uri", "")
            .put("time", System.currentTimeMillis())
        val fresh = JSONArray()
        fresh.put(o)
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            if (x.optString("id") != id) fresh.put(x)
        }
        while (fresh.length() > 50) fresh.remove(fresh.length() - 1)
        save(fresh)
    }

    @Synchronized
    fun update(id: String, state: String, percent: Int, path: String, uri: String) {
        val a = load()
        for (i in 0 until a.length()) {
            val x = a.optJSONObject(i) ?: continue
            if (x.optString("id") == id) {
                x.put("state", state)
                x.put("percent", percent)
                if (path.isNotEmpty()) x.put("path", path)
                if (uri.isNotEmpty()) x.put("uri", uri)
                break
            }
        }
        save(a)
    }

    @Synchronized
    fun all(): List<JSONObject> {
        val a = load()
        val out = ArrayList<JSONObject>()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    fun showList(ctx: Context) {
        val items = all()
        if (items.isEmpty()) {
            AlertDialog.Builder(ctx)
                .setTitle("Downloads")
                .setMessage("Abhi koi download nahi hua")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val labels = items.map { o ->
            val st = o.optString("state")
            val tag = when (st) {
                "done" -> "✔ Done"
                "failed" -> "✖ Failed"
                else -> "⏳ " + o.optInt("percent") + "%"
            }
            tag + "  " + o.optString("title").take(50)
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Downloads")
            .setItems(labels) { _, w ->
                val o = items[w]
                val uri = o.optString("uri")
                if (o.optString("state") == "done" && uri.isNotEmpty()) {
                    try {
                        val i = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        i.setDataAndType(Uri.parse(uri), "video/mp4")
                        i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        ctx.startActivity(i)
                    } catch (e: Exception) {
                        YtWatchLater.toast("Video app nahi mila")
                    }
                } else {
                    YtWatchLater.toast("Abhi file taiyar nahi hai")
                }
            }
            .setNeutralButton("Clear list") { _, _ ->
                save(JSONArray())
                YtWatchLater.toast("Download list cleared")
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
