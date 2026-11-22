package com.serialtvmaza

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object YtDownload {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private const val CHUNK = 8L * 1024 * 1024
    private const val TARGET_H = 720

    @Volatile
    private var busy = false
    private var appCtx: Context? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
    }

    fun start(videoId: String, title: String) {
        if (busy) {
            YtWatchLater.toast("A download is already running")
            return
        }
        busy = true
        YtDlList.add(videoId, title)
        YtWatchLater.toast("Download 720p started: " + title.take(40))
        thread(isDaemon = true) {
            try {
                run720(videoId, title)
            } catch (e: Throwable) {
                YtDlList.update(videoId, "failed", 0, "", "")
                YtWatchLater.toast("Download failed: " + (e.message ?: e.javaClass.simpleName).take(120))
            } finally {
                busy = false
            }
        }
    }

    private fun heightOf(v: VideoStream): Int = runCatching { v.height }.getOrNull() ?: 0

    private fun isOriginal(url: String): Boolean {
        val u = url.lowercase()
        return !u.contains("acont") || u.contains("acont%3doriginal") || u.contains("acont=original")
    }

    private fun run720(videoId: String, title: String) {
        val ctx = appCtx ?: throw IllegalStateException("download not initialised")
        val handler = YoutubeStreamLinkHandlerFactory.getInstance()
            .fromUrl("https://www.youtube.com/watch?v=" + videoId)
        val s = object : YoutubeStreamExtractor(ServiceList.YouTube, handler) {}
        s.fetchPage()

        val video = (s.videoOnlyStreams ?: emptyList())
            .filter { heightOf(it) in 1..TARGET_H && (it.format?.mimeType ?: "").contains("mp4") }
            .maxByOrNull { heightOf(it) }
            ?: throw IllegalStateException("no 720p or lower mp4 video stream")
        val vUrl = video.content ?: throw IllegalStateException("video url empty")

        val allAud = (s.audioStreams ?: emptyList()).filter { !(it.content ?: "").isEmpty() }
        val originals = allAud.filter { isOriginal(it.content ?: "") }
        val pool = if (originals.isNotEmpty()) originals else allAud
        val audio = pool.sortedWith(
            compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                (it.format?.mimeType ?: "").contains("mp4")
            }.thenByDescending { it.bitrate ?: 0 }
        ).firstOrNull() ?: throw IllegalStateException("no audio stream")
        val aUrl = audio.content ?: throw IllegalStateException("audio url empty")

        val dir = File(ctx.cacheDir, "ytdl2")
        dir.mkdirs()
        val vf = File(dir, videoId + ".v.mp4")
        val af = File(dir, videoId + ".a.m4a")
        val out = File(dir, videoId + ".out.mp4")
        try {
            YtWatchLater.toast("Downloading video (" + heightOf(video) + "p)...")
            downloadFile(vUrl, vf, videoId, 0, 45)
            YtWatchLater.toast("Downloading audio...")
            downloadFile(aUrl, af, videoId, 45, 90)
            YtWatchLater.toast("Merging video and audio...")
            if (!mux(vf, af, out)) throw IllegalStateException("merge failed")
            val safe = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(80).ifBlank { videoId }
            val where = publish(ctx, out, safe + " [" + videoId + "] 720p.mp4")
            YtDlList.update(videoId, "done", 100, where, lastUri)
            YtWatchLater.toast("Saved 720p: " + where)
        } finally {
            vf.delete()
            af.delete()
            out.delete()
        }
    }

    private fun copy(ins: InputStream, fos: FileOutputStream): Long {
        var n = 0L
        ins.use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val c = i.read(buf)
                if (c < 0) break
                fos.write(buf, 0, c)
                n += c
            }
        }
        return n
    }

    private var lastToastStep = -1

    private fun progress(id: String, from: Int, to: Int, done: Long, total: Long) {
        if (total <= 0L) return
        val pct = from + ((to - from) * done / total).toInt()
        YtDlList.update(id, "running", pct, "", "")
        val step = pct / 10
        if (step != lastToastStep) {
            lastToastStep = step
            YtWatchLater.toast("Download " + pct + "%")
        }
    }

    private fun uriOf(where: String): String {
        return if (where.startsWith("/")) "file://" + where else ""
    }

    private fun downloadFile(url: String, dest: File, id: String, from: Int, to: Int) {
        if (dest.exists()) dest.delete()
        var total = -1L
        http.newCall(
            Request.Builder().url(url).header("User-Agent", UA).header("Range", "bytes=0-0").build()
        ).execute().use { r ->
            if (!r.isSuccessful && r.code != 206) throw IllegalStateException("probe http " + r.code)
            total = r.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                ?: r.header("Content-Length")?.toLongOrNull()
                ?: -1L
        }
        FileOutputStream(dest).use { fos ->
            if (total <= 0L) {
                http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("http " + r.code)
                    val body = r.body ?: throw IllegalStateException("empty body")
                    copy(body.byteStream(), fos)
                }
            } else {
                var pos = 0L
                while (pos < total) {
                    val end = minOf(pos + CHUNK - 1, total - 1)
                    val req = Request.Builder().url(url)
                        .header("User-Agent", UA)
                        .header("Range", "bytes=" + pos + "-" + end)
                        .build()
                    http.newCall(req).execute().use { r ->
                        if (!r.isSuccessful && r.code != 206) throw IllegalStateException("chunk http " + r.code)
                        val body = r.body ?: throw IllegalStateException("empty chunk")
                        val got = copy(body.byteStream(), fos)
                        if (got <= 0L) throw IllegalStateException("no data at " + pos)
                        pos += got
                        progress(id, from, to, pos, total)
                    }
                }
            }
        }
        if (dest.length() <= 0L) throw IllegalStateException("downloaded file is empty")
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

    private var lastUri = ""

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
                    lastUri = uri.toString()
                    return "Movies/YouTubeSubs/" + name
                }
            }
        } catch (e: Exception) {
        }
        val d = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "YouTubeSubs")
        d.mkdirs()
        val t = File(d, name)
        file.copyTo(t, true)
        return t.absolutePath
    }
}
