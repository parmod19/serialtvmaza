package com.serialtvmaza

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import com.lagradost.cloudstream3.utils.loadExtractor

object YtDl {
    private const val VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    private const val IOS_UA =
        "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
    private const val CHUNK = 8L * 1024 * 1024

    private var appCtx: Context? = null
    private var prefs: SharedPreferences? = null
    private var provider: YouTubeSubsProvider? = null

    @Volatile
    private var started = false

    @Volatile
    private var running = false

    private val ui = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private class Stream(
        val videoUrl: String,
        val videoLen: Long,
        val audioUrl: String?,
        val audioLen: Long,
        val height: Int,
        val ua: String
    )

    // ---------- setup and settings storage ----------

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        prefs = ctx.applicationContext.getSharedPreferences("yt_subs_dl", Context.MODE_PRIVATE)
    }

    private fun enabled() = prefs?.getBoolean("enabled", false) ?: false
    private fun quality() = prefs?.getInt("quality", 720) ?: 720
    private fun maxPer() = prefs?.getInt("max", 3) ?: 3
    fun playMax(): Int = prefs?.getInt("playmax", 720) ?: 720
    private fun baselined() = prefs?.getBoolean("baselined", false) ?: false

    private fun loadSeen(): MutableSet<String> {
        val a = try {
            JSONArray(prefs?.getString("seen", "[]") ?: "[]")
        } catch (e: Exception) {
            JSONArray()
        }
        val s = LinkedHashSet<String>()
        for (i in 0 until a.length()) s.add(a.optString(i))
        return s
    }

    private fun saveSeen(s: Set<String>) {
        val a = JSONArray()
        s.toList().takeLast(600).forEach { a.put(it) }
        prefs?.edit()?.putString("seen", a.toString())?.apply()
    }

    private fun toast(msg: String) {
        val c = appCtx ?: return
        ui.post { Toast.makeText(c, msg, Toast.LENGTH_LONG).show() }
    }

    private fun idOf(url: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    // runs a suspend block on the current (background) thread without kotlinx.coroutines
    private fun <T> blocking(block: suspend () -> T): T {
        val latch = java.util.concurrent.CountDownLatch(1)
        var holder: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context: CoroutineContext = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) {
                holder = result
                latch.countDown()
            }
        })
        latch.await()
        return holder!!.getOrThrow()
    }

    // ---------- triggers ----------

    fun start(p: YouTubeSubsProvider) {
        provider = p
        if (started) return
        started = true
        thread(isDaemon = true) {
            try {
                Thread.sleep(60_000L)
            } catch (e: InterruptedException) {
                return@thread
            }
            while (true) {
                try {
                    if (enabled()) {
                        val items = blocking { p.feedForDownload() }
                        process(items)
                    }
                } catch (e: Throwable) {
                }
                try {
                    Thread.sleep(30 * 60_000L)
                } catch (e: InterruptedException) {
                    return@thread
                }
            }
        }
    }

    fun onFeed(items: List<Pair<String, String>>) {
        if (!enabled()) return
        thread(isDaemon = true) { process(items) }
    }

    fun checkNow() {
        val p = provider ?: return
        thread(isDaemon = true) {
            try {
                val items = blocking { p.feedForDownload() }
                if (items.isEmpty()) toast("Feed empty - login first") else process(items)
            } catch (e: Throwable) {
                toast("Check failed: ${e.message}")
            }
        }
    }

    fun downloadLatestNow() {
        val p = provider ?: return
        thread(isDaemon = true) {
            synchronized(this) {
                if (running) {
                    toast("A download is already running")
                    return@thread
                }
                running = true
            }
            try {
                val items = blocking { p.feedForDownload() }
                val first = items.firstOrNull()
                val id = first?.let { idOf(it.first) }
                if (first == null || id == null) {
                    toast("Feed empty - login first")
                    return@thread
                }
                downloadOne(id, first.second)
            } catch (e: Throwable) {
                toast("Error: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    private fun process(items: List<Pair<String, String>>) {
        if (!enabled() || items.isEmpty()) return
        synchronized(this) {
            if (running) return
            running = true
        }
        try {
            val seen = loadSeen()
            val entries = items.mapNotNull { p ->
                val id = idOf(p.first)
                if (id == null) null else id to p.second
            }
            if (!baselined()) {
                entries.forEach { seen.add(it.first) }
                saveSeen(seen)
                prefs?.edit()?.putBoolean("baselined", true)?.apply()
                toast("Auto-download ready: only new videos from now will be saved")
                return
            }
            val fresh = entries.filter { it.first !in seen }.take(maxPer())
            for ((id, title) in fresh) {
                seen.add(id)
                saveSeen(seen)
                downloadOne(id, title)
            }
        } finally {
            running = false
        }
    }

    // ---------- download one video ----------

    private fun downloadOne(id: String, title: String) {
        val ctx = appCtx ?: return
        val q = quality()
        toast("Downloading ${q}p: ${title.take(40)}")
        val dir = File(ctx.cacheDir, "ytdl")
        dir.mkdirs()
        val vTmp = File(dir, "$id.v.tmp")
        val aTmp = File(dir, "$id.a.tmp")
        val outTmp = File(dir, "$id.mp4")
        try {
            val s = resolve(id, q)
            if (s == null) {
                toast("No downloadable stream: ${title.take(40)}")
                return
            }
            val au = s.audioUrl
            val ok = if (au == null) {
                download(s.videoUrl, s.ua, outTmp, s.videoLen)
            } else {
                download(s.videoUrl, s.ua, vTmp, s.videoLen) &&
                    download(au, s.ua, aTmp, s.audioLen) &&
                    mux(vTmp, aTmp, outTmp)
            }
            if (!ok) {
                outTmp.delete()
                toast("Download failed: ${title.take(40)}")
                return
            }
            val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80)
            val where = publish(ctx, outTmp, "$safe [$id] ${s.height}p.mp4")
            toast("Saved ${s.height}p: $where")
        } catch (e: Exception) {
            toast("Download error: ${e.message}")
        } finally {
            vTmp.delete()
            aTmp.delete()
        }
    }

    // ---------- stream selection ----------

    private fun playerJson(id: String, client: JSONObject, ua: String, cname: String, cver: String): JSONObject? {
        return try {
            val body = JSONObject()
                .put("context", JSONObject().put("client", client))
                .put("videoId", id)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", ua)
                .header("Origin", "https://www.youtube.com")
                .header("X-Youtube-Client-Name", cname)
                .header("X-Youtube-Client-Version", cver)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { r ->
                val txt = r.body?.string() ?: return null
                JSONObject(txt)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolve(id: String, q: Int): Stream? {
        val vr = JSONObject()
            .put("clientName", "ANDROID_VR").put("clientVersion", "1.60.19")
            .put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
            .put("osName", "Android").put("osVersion", "12L")
            .put("androidSdkVersion", 32).put("hl", "en").put("gl", "IN")
        val a = pick(playerJson(id, vr, VR_UA, "28", "1.60.19"), q, VR_UA)
        if (a != null && a.height >= q) return a

        val ios = JSONObject()
            .put("clientName", "IOS").put("clientVersion", "19.45.4")
            .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone").put("osVersion", "18.1.0.22B83")
            .put("hl", "en").put("gl", "IN")
        val b = pick(playerJson(id, ios, IOS_UA, "5", "19.45.4"), q, IOS_UA)
        return if (b != null && (a == null || b.height > a.height)) b else a
    }

    private fun progStream(f: JSONObject, ua: String) = Stream(
        f.optString("url", ""),
        f.optString("contentLength", "0").toLongOrNull() ?: 0L,
        null,
        0L,
        f.optInt("height", 0),
        ua
    )

    private fun pick(j: JSONObject?, q: Int, ua: String): Stream? {
        val sd = j?.optJSONObject("streamingData") ?: return null
        val formats = sd.optJSONArray("formats") ?: JSONArray()
        val adaptive = sd.optJSONArray("adaptiveFormats") ?: JSONArray()

        var prog: JSONObject? = null
        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            if (f.optString("url", "").isBlank()) continue
            if (!f.optString("mimeType", "").startsWith("video/mp4")) continue
            val h = f.optInt("height", 0)
            if (h > q) continue
            if (prog == null || h > prog.optInt("height", 0)) prog = f
        }
        if (prog != null && prog.optInt("height", 0) >= q) return progStream(prog, ua)

        var v: JSONObject? = null
        var au: JSONObject? = null
        for (i in 0 until adaptive.length()) {
            val f = adaptive.optJSONObject(i) ?: continue
            if (f.optString("url", "").isBlank()) continue
            val mt = f.optString("mimeType", "")
            if (mt.startsWith("video/mp4") && mt.contains("avc1")) {
                val h = f.optInt("height", 0)
                if (h <= q && (v == null || h > v.optInt("height", 0) ||
                        (h == v.optInt("height", 0) && f.optInt("bitrate", 0) > v.optInt("bitrate", 0)))
                ) v = f
            } else if (mt.startsWith("audio/mp4")) {
                if (au == null || f.optInt("bitrate", 0) > au.optInt("bitrate", 0)) au = f
            }
        }
        if (v != null && au != null && v.optInt("height", 0) > (prog?.optInt("height", 0) ?: 0)) {
            return Stream(
                v.optString("url", ""),
                v.optString("contentLength", "0").toLongOrNull() ?: 0L,
                au.optString("url", ""),
                au.optString("contentLength", "0").toLongOrNull() ?: 0L,
                v.optInt("height", 0),
                ua
            )
        }
        return if (prog != null) progStream(prog, ua) else null
    }

    // ---------- file download, mux, save ----------

    private fun download(url: String, ua: String, dest: File, total: Long): Boolean {
        if (dest.exists()) dest.delete()
        var pos = 0L
        FileOutputStream(dest).use { fos ->
            while (total <= 0L || pos < total) {
                val end = if (total > 0L) minOf(pos + CHUNK - 1, total - 1) else pos + CHUNK - 1
                val req = Request.Builder().url(url)
                    .header("User-Agent", ua)
                    .header("Range", "bytes=$pos-$end")
                    .build()
                var got = 0L
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return false
                    val body = r.body ?: return false
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { ins ->
                        while (true) {
                            val c = ins.read(buf)
                            if (c < 0) break
                            fos.write(buf, 0, c)
                            got += c
                        }
                    }
                }
                if (got <= 0L) return false
                pos += got
                if (total <= 0L && got < CHUNK) break
            }
        }
        return dest.length() > 0L
    }

    private fun trackOf(e: MediaExtractor, prefix: String): Int {
        for (i in 0 until e.trackCount) {
            val m = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (m.startsWith(prefix)) return i
        }
        return -1
    }

    private fun copyTrack(e: MediaExtractor, m: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = e.readSampleData(buf, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = e.sampleTime
            info.flags = if ((e.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            m.writeSampleData(track, buf, info)
            e.advance()
        }
    }

    private fun mux(video: File, audio: File, out: File): Boolean {
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var mx: MediaMuxer? = null
        try {
            ve.setDataSource(video.absolutePath)
            ae.setDataSource(audio.absolutePath)
            val vi = trackOf(ve, "video/")
            val ai = trackOf(ae, "audio/")
            if (vi < 0 || ai < 0) return false
            ve.selectTrack(vi)
            ae.selectTrack(ai)
            if (out.exists()) out.delete()
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            mx = m
            val vt = m.addTrack(ve.getTrackFormat(vi))
            val at = m.addTrack(ae.getTrackFormat(ai))
            m.start()
            copyTrack(ve, m, vt)
            copyTrack(ae, m, at)
            m.stop()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            try { mx?.release() } catch (e: Exception) { }
            try { ve.release() } catch (e: Exception) { }
            try { ae.release() } catch (e: Exception) { }
        }
    }

    private fun publish(ctx: Context, file: File, name: String): String {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val cv = ContentValues()
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name)
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/YouTubeSubs")
                cv.put(MediaStore.Video.Media.IS_PENDING, 1)
                val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) {
                    ctx.contentResolver.openOutputStream(uri)?.use { o ->
                        file.inputStream().use { it.copyTo(o) }
                    }
                    cv.clear()
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                    ctx.contentResolver.update(uri, cv, null, null)
                    file.delete()
                    return "Movies/YouTubeSubs/$name"
                }
            }
        } catch (e: Exception) {
        }
        return try {
            val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "YouTubeSubs")
            d.mkdirs()
            val t = File(d, name)
            file.copyTo(t, true)
            file.delete()
            t.absolutePath
        } catch (e: Exception) {
            file.absolutePath
        }
    }

    // ---------- settings dialogs ----------

    fun showSettings(ctx: Context) {
        val items = arrayOf(
            "Auto-download: " + (if (enabled()) "ON" else "OFF") + " (tap to toggle)",
            "Quality: ${quality()}p (tap to choose 360p / 720p)",
            "Max videos per check: ${maxPer()} (tap to change)",
            "Playback max quality: ${if (playMax() <= 0) "Unlimited" else playMax().toString() + "p"} (tap to change)",
            "Check feed now",
            "Download latest feed video now (test)",
            "Reset: forget already-seen videos",
            "Debug: show why download fails (latest video)"
        )
        AlertDialog.Builder(ctx)
            .setTitle("Auto-download settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val on = !enabled()
                        prefs?.edit()?.putBoolean("enabled", on)?.putBoolean("baselined", false)?.apply()
                        if (on) {
                            toast("Auto-download ON - checking feed")
                            checkNow()
                        } else {
                            toast("Auto-download OFF")
                        }
                        showSettings(ctx)
                    }
                    1 -> pickQuality(ctx)
                    2 -> pickMax(ctx)
                    3 -> pickPlayMax(ctx)
                    4 -> {
                        checkNow()
                        showSettings(ctx)
                    }
                    5 -> {
                        downloadLatestNow()
                        showSettings(ctx)
                    }
                    6 -> {
                        prefs?.edit()?.remove("seen")?.putBoolean("baselined", false)?.apply()
                        toast("Reset done")
                        showSettings(ctx)
                    }
                    else -> debugLatest(ctx)
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun describe(label: String, j: JSONObject?): String {
        if (j == null) return label + ": no response\n\n"
        val ps = j.optJSONObject("playabilityStatus")
        val status = ps?.optString("status", "?") ?: "?"
        val reason = ps?.optString("reason", "") ?: ""
        val sd = j.optJSONObject("streamingData")
        val f = sd?.optJSONArray("formats")
        val a = sd?.optJSONArray("adaptiveFormats")
        var fUrl = 0
        var aUrl = 0
        val hs = ArrayList<String>()
        if (f != null) {
            for (i in 0 until f.length()) {
                val o = f.optJSONObject(i) ?: continue
                if (o.optString("url", "").isNotBlank()) {
                    fUrl++
                    hs.add(o.optInt("height", 0).toString() + "p")
                }
            }
        }
        if (a != null) {
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                if (o.optString("url", "").isNotBlank()) aUrl++
            }
        }
        val hls = (sd?.optString("hlsManifestUrl", "") ?: "").isNotBlank()
        return label + ": " + status + " " + reason.take(80) + "\n" +
            "  mp4 formats with url: " + fUrl + "/" + (f?.length() ?: 0) + " " + hs.joinToString(",") + "\n" +
            "  adaptive with url: " + aUrl + "/" + (a?.length() ?: 0) + "\n" +
            "  hls: " + hls + "\n\n"
    }

    fun debugLatest(ctx: Context) {
        val p = provider
        if (p == null) {
            toast("Plugin not ready")
            return
        }
        toast("Checking latest video...")
        thread(isDaemon = true) {
            val sb = StringBuilder()
            try {
                val items = blocking { p.feedForDownload() }
                val first = items.firstOrNull()
                val id = first?.let { idOf(it.first) }
                if (id == null) {
                    sb.append("Feed empty or not logged in\n")
                } else {
                    sb.append("Video: " + id + "\n\n")
                    val vr = JSONObject()
                        .put("clientName", "ANDROID_VR").put("clientVersion", "1.60.19")
                        .put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
                        .put("osName", "Android").put("osVersion", "12L")
                        .put("androidSdkVersion", 32).put("hl", "en").put("gl", "IN")
                    sb.append(describe("ANDROID_VR", playerJson(id, vr, VR_UA, "28", "1.60.19")))
                    val ios = JSONObject()
                        .put("clientName", "IOS").put("clientVersion", "19.45.4")
                        .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
                        .put("osName", "iPhone").put("osVersion", "18.1.0.22B83")
                        .put("hl", "en").put("gl", "IN")
                    sb.append(describe("IOS", playerJson(id, ios, IOS_UA, "5", "19.45.4")))
                    val links = ArrayList<String>()
                    try {
                        blocking {
                            loadExtractor("https://www.youtube.com/watch?v=" + id, { _ -> }) { l ->
                                links.add(l.name + " | " + l.quality + " | " + l.type)
                            }
                        }
                    } catch (e: Throwable) {
                        links.add("error: " + e.message)
                    }
                    sb.append("Built-in extractor links (" + links.size + "):\n")
                    links.take(25).forEach { sb.append("  " + it + "\n") }
                }
            } catch (e: Throwable) {
                sb.append("Error: " + e.message)
            }
            ui.post {
                AlertDialog.Builder(ctx)
                    .setTitle("Debug info")
                    .setMessage(sb.toString())
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun pickQuality(ctx: Context) {
        val opts = intArrayOf(360, 720)
        AlertDialog.Builder(ctx)
            .setTitle("Download quality")
            .setItems(arrayOf("360p (direct mp4, small)", "720p (video + audio merged)")) { _, w ->
                prefs?.edit()?.putInt("quality", opts[w])?.apply()
                showSettings(ctx)
            }
            .setNegativeButton("Back") { _, _ -> showSettings(ctx) }
            .show()
    }

    private fun pickPlayMax(ctx: Context) {
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

    private fun pickMax(ctx: Context) {
        val opts = intArrayOf(1, 3, 5, 10)
        AlertDialog.Builder(ctx)
            .setTitle("Max videos per check")
            .setItems(opts.map { it.toString() }.toTypedArray()) { _, w ->
                prefs?.edit()?.putInt("max", opts[w])?.apply()
                showSettings(ctx)
            }
            .setNegativeButton("Back") { _, _ -> showSettings(ctx) }
            .show()
    }
}
