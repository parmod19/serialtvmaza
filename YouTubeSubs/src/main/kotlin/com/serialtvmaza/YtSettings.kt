package com.serialtvmaza

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences

object YtSettings {
    private var prefs: SharedPreferences? = null

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("yt_subs_dl", Context.MODE_PRIVATE)
    }

    // 0 = unlimited
    fun playMax(): Int = prefs?.getInt("playmax", 720) ?: 720

    fun showSettings(ctx: Context) {
        val cur = playMax()
        val label = if (cur <= 0) "Unlimited" else cur.toString() + "p"
        val opts = intArrayOf(480, 720, 1080, 0)
        AlertDialog.Builder(ctx)
            .setTitle("Playback max quality: " + label)
            .setItems(arrayOf("480p", "720p (default)", "1080p", "Unlimited (up to 4K)")) { _, w ->
                prefs?.edit()?.putInt("playmax", opts[w])?.apply()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
