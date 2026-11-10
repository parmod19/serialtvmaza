import glob

pp = glob.glob("YouTubeSubs/src/**/YouTubeSubsProvider.kt", recursive=True)[0]
sp = glob.glob("YouTubeSubs/src/**/YtSettings.kt", recursive=True)[0]

t = open(pp, encoding="utf-8").read()
assert "val capB = YtSettings.playMax()" in t, "capB line missing"
assert "isOriginalAudio(url: String)" in t, "isOriginalAudio missing - run patch9.py first"

if "fun <T> pickAudio2" not in t:
    # remove the diagnostic toast from patch15, if present
    toast_line = '        YtWatchLater.toast("Audio tracks: " + (collected.firstOrNull()?.audioTracks?.size ?: 0))\n'
    t = t.replace(toast_line, "")

    # replace the audio logic inside the cap block
    head = "            if (capB <= 0 || hh <= capB) {\n"
    tail = "                callback(l)\n"
    assert t.count(head) == 1, "cap block start not found (patch12 applied?)"
    hi = t.index(head) + len(head)
    ti = t.index(tail, hi)
    new_logic = (
        "                val amode = YtSettings.audioMode()\n"
        "                if (amode == 2) {\n"
        "                    if (l.audioTracks.isNotEmpty()) l.audioTracks = emptyList()\n"
        "                } else {\n"
        "                    val best = pickAudio2(l.audioTracks, amode == 1) { it.url }\n"
        "                    if (best != null && l.audioTracks.size != 1) l.audioTracks = listOf(best)\n"
        "                }\n"
    )
    t = t[:hi] + new_logic + t[ti:]

    marker = "    // ---------- playback ----------\n"
    assert t.count(marker) == 1, "playback marker not found"
    fn = r'''    private fun audioItag2(url: String): Int {
        return Regex("[?&/]itag[=/](\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    // exactly one audio track: the original one, AAC first (or Opus first when preferOpus)
    private fun <T> pickAudio2(tracks: List<T>, preferOpus: Boolean, urlOf: (T) -> String): T? {
        if (tracks.isEmpty()) return null
        val originals = tracks.filter { isOriginalAudio(urlOf(it)) }
        val pool = if (originals.isNotEmpty()) originals else tracks
        val prefer = if (preferOpus) listOf(251, 250, 249, 140, 141, 139) else listOf(140, 251, 141, 250, 139, 249)
        for (itag in prefer) {
            val m = pool.firstOrNull { audioItag2(urlOf(it)) == itag }
            if (m != null) return m
        }
        return pool.first()
    }

'''
    t = t.replace(marker, fn + marker, 1)
    print("Provider patched")
else:
    print("Provider already patched")

settings_code = '''package com.serialtvmaza

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
            "Audio: " + audioLabel() + " (tap to change)"
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
                    else -> pickAudio(ctx)
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
'''

open(pp, "w", encoding="utf-8").write(t)
open(sp, "w", encoding="utf-8").write(settings_code)
print("Done. Settings file rewritten:", sp)