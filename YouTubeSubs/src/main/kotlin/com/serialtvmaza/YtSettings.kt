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

    fun preferH264(): Boolean = prefs?.getBoolean("h264", true) ?: true

    // 0 = original audio, AAC first; 1 = original audio, Opus first; 2 = no audio (video only, for testing)
    fun audioMode(): Int = prefs?.getInt("audiomode", 0) ?: 0

    // send a browser User-Agent with the video links
    fun browserUa(): Boolean = prefs?.getBoolean("browserua", true) ?: true

    private fun audioLabel(): String {
        return when (audioMode()) {
            1 -> "Original, Opus"
            2 -> "OFF (video only - test)"
            else -> "Original, AAC"
        }
    }

    fun showSettings(ctx: Context) {
        val cur = playMax()
        val label = if (cur <= 0) "Unlimited" else cur.toString() + "p"
        val items = arrayOf(
            "Max quality: " + label + " (tap to change)",
            "Prefer H264: " + (if (preferH264()) "ON" else "OFF") + " (smoother on most phones)",
            "Audio: " + audioLabel() + " (tap to change)",
            "Browser User-Agent: " + (if (browserUa()) "ON" else "OFF") + " (tap to toggle)"
        )
        AlertDialog.Builder(ctx)
            .setTitle("Playback settings")
            .setItems(items) { _, w ->
                when (w) {
                    0 -> pickQuality(ctx)
                    1 -> {
                        prefs?.edit()?.putBoolean("h264", !preferH264())?.apply()
                        showSettings(ctx)
                    }
                    2 -> pickAudio(ctx)
                    else -> {
                        prefs?.edit()?.putBoolean("browserua", !browserUa())?.apply()
                        showSettings(ctx)
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickQuality(ctx: Context) {
        val opts = intArrayOf(480, 720, 1080, 0)
        AlertDialog.Builder(ctx)
            .setTitle("Playback max quality")
            .setItems(arrayOf("480p", "720p (default)", "1080p", "Unlimited (up to 4K)")) { _, w ->
                prefs?.edit()?.putInt("playmax", opts[w])?.apply()
                showSettings(ctx)
            }
            .setNegativeButton("Back") { _, _ -> showSettings(ctx) }
            .show()
    }

    private fun pickAudio(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("Audio track")
            .setItems(arrayOf("Original, AAC (default)", "Original, Opus", "OFF - video only (test)")) { _, w ->
                prefs?.edit()?.putInt("audiomode", w)?.apply()
                showSettings(ctx)
            }
            .setNegativeButton("Back") { _, _ -> showSettings(ctx) }
            .show()
    }
}
