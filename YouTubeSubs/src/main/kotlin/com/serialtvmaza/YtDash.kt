package com.serialtvmaza

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

// Local DASH engine: video-only + audio-only streams are described in a DASH manifest that is served
// from a loopback server, so the player fetches the stream in ranged chunks. The idea follows the
// approach used by the YouTube plugin in Abodabodd/re-3arabi (credit to the original author).
object YtDash {
    private class Vid(val url: String, val mime: String, val height: Int, val initR: String?, val indexR: String?)
    private class Aud(val url: String, val mime: String, val bitrate: Int, val initR: String?, val indexR: String?)

    @Volatile
    var lastInfo: String = ""

    private var server: ServerSocket? = null
    private var port = 0
    private val manifests = ConcurrentHashMap<String, String>()

    private fun isOriginalAudio(url: String): Boolean {
        val u = url.lowercase()
        return !u.contains("acont") || u.contains("acont%3doriginal") || u.contains("acont=original")
    }

    private fun rng(a: Int, b: Int): String? = if (a >= 0 && b > 0) "$a-$b" else null

    private fun guessMime(url: String, audio: Boolean): String {
        val webm = url.lowercase().contains("webm")
        return if (audio) {
            if (webm) "audio/webm" else "audio/mp4"
        } else {
            if (webm) "video/webm" else "video/mp4"
        }
    }

    suspend fun links(
        videoId: String,
        capHeight: Int,
        preferH264: Boolean,
        source: String,
        callback: (ExtractorLink) -> Unit
    ): Int {
        var count = 0
        try {
            val handler = YoutubeStreamLinkHandlerFactory.getInstance()
                .fromUrl("https://www.youtube.com/watch?v=" + videoId)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, handler) {}
            s.fetchPage()
            val duration = if (s.length > 0) s.length else 3600L

            val vids = ArrayList<Vid>()
            for (vs in (s.videoOnlyStreams ?: emptyList())) {
                try {
                    val u = vs.content ?: continue
                    val h = runCatching { vs.height }.getOrNull() ?: 0
                    if (h <= 0) continue
                    val mime: String = vs.format?.mimeType?.takeIf { it.isNotEmpty() } ?: guessMime(u, false)
                    vids.add(Vid(u, mime, h, rng(vs.initStart, vs.initEnd), rng(vs.indexStart, vs.indexEnd)))
                } catch (e: Exception) {
                }
            }

            val allAud = ArrayList<Aud>()
            for (asr in (s.audioStreams ?: emptyList())) {
                try {
                    val u = asr.content ?: continue
                    val br: Int = asr.bitrate ?: 128000
                    val mime: String = asr.format?.mimeType?.takeIf { it.isNotEmpty() } ?: guessMime(u, true)
                    allAud.add(
                        Aud(u, mime, if (br > 0) br else 128000, rng(asr.initStart, asr.initEnd), rng(asr.indexStart, asr.indexEnd))
                    )
                } catch (e: Exception) {
                }
            }
            val originals = allAud.filter { isOriginalAudio(it.url) }
            val auds = if (originals.isNotEmpty()) originals else allAud

            fun bestAudio(videoMime: String): Aud? {
                val key = if (videoMime.contains("webm")) "webm" else "mp4"
                return auds.sortedWith(
                    compareByDescending<Aud> { it.mime.contains(key) }.thenByDescending { it.bitrate }
                ).firstOrNull()
            }

            val byHeight = vids.filter { capHeight <= 0 || it.height <= capHeight }.groupBy { it.height }
            val chosen = ArrayList<Vid>()
            for ((_, list) in byHeight) {
                val mp4 = list.firstOrNull { it.mime.contains("mp4") }
                val webm = list.firstOrNull { it.mime.contains("webm") }
                val pick = if (preferH264) (mp4 ?: webm) else (webm ?: mp4)
                if (pick != null) chosen.add(pick)
            }
            chosen.sortByDescending { it.height }

            lastInfo = "video-only " + (s.videoOnlyStreams?.size ?: 0) + ", usable " + vids.size + ", audio " + allAud.size + ", after cap " + chosen.size
            if (chosen.isNotEmpty() && auds.isNotEmpty()) {
                ensureServer()
                for (v in chosen) {
                    val a = bestAudio(v.mime) ?: continue
                    val local = register(manifest(v, a, duration)) ?: continue
                    callback(
                        newExtractorLink(source, source + " " + v.height + "p (DASH)", local, type = ExtractorLinkType.DASH) {
                            this.referer = "https://www.youtube.com"
                            this.quality = v.height
                        }
                    )
                    count++
                }
            }

            // muxed mp4 (video + audio in one file): handy for downloads
            for (vs in (s.videoStreams ?: emptyList())) {
                try {
                    val u = vs.content ?: continue
                    val h = runCatching { vs.height }.getOrNull() ?: 0
                    if (h <= 0 || (capHeight > 0 && h > capHeight)) continue
                    callback(
                        newExtractorLink(source, source + " " + h + "p (audio+video mp4)", u) {
                            this.referer = "https://www.youtube.com"
                            this.quality = h
                        }
                    )
                } catch (e: Exception) {
                }
            }
        } catch (e: Throwable) {
            lastInfo = "error " + e.javaClass.simpleName + ": " + (e.message ?: "").take(100)
        }
        return count
    }

    private fun segBase(initR: String?, indexR: String?): String {
        return if (initR != null && indexR != null) {
            "<SegmentBase indexRange=\"" + indexR + "\"><Initialization range=\"" + initR + "\" /></SegmentBase>"
        } else {
            ""
        }
    }

    private fun esc(url: String): String = url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun manifest(v: Vid, a: Aud, durationSec: Long): String {
        val vCodecs = if (v.mime.contains("webm")) "vp9" else "avc1.4d401f"
        val aCodecs = if (a.mime.contains("webm")) "opus" else "mp4a.40.2"
        val sb = StringBuilder()
        sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" type=\"static\" minBufferTime=\"PT5.0S\" mediaPresentationDuration=\"PT" + durationSec + "S\">")
        sb.append("<Period>")
        sb.append("<AdaptationSet mimeType=\"" + v.mime + "\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">")
        sb.append("<Representation id=\"video\" bandwidth=\"4000000\" height=\"" + v.height + "\" codecs=\"" + vCodecs + "\">")
        sb.append("<BaseURL>" + esc(v.url) + "</BaseURL>")
        sb.append(segBase(v.initR, v.indexR))
        sb.append("</Representation></AdaptationSet>")
        sb.append("<AdaptationSet mimeType=\"" + a.mime + "\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">")
        sb.append("<Representation id=\"audio_0\" bandwidth=\"" + a.bitrate + "\" codecs=\"" + aCodecs + "\">")
        sb.append("<BaseURL>" + esc(a.url) + "</BaseURL>")
        sb.append(segBase(a.initR, a.indexR))
        sb.append("</Representation></AdaptationSet>")
        sb.append("</Period></MPD>")
        return sb.toString()
    }

    // ---------- tiny local http server (loopback only) ----------

    @Synchronized
    private fun ensureServer() {
        val cur = server
        if (cur != null && !cur.isClosed) return
        try {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = ss
            port = ss.localPort
            thread(isDaemon = true) {
                try {
                    while (!ss.isClosed) {
                        val c = ss.accept()
                        thread(isDaemon = true) { handle(c) }
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun register(xml: String): String? {
        if (port == 0) return null
        if (manifests.size > 400) manifests.clear()
        val id = UUID.randomUUID().toString()
        manifests[id] = xml
        return "http://127.0.0.1:" + port + "/" + id + ".mpd"
    }

    private fun handle(sock: Socket) {
        try {
            sock.use { s ->
                s.soTimeout = 15000
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val line = reader.readLine() ?: return
                while (true) {
                    val h = reader.readLine() ?: break
                    if (h.isEmpty()) break
                }
                val parts = line.split(" ")
                val out = s.getOutputStream()
                var xml: String? = null
                if (parts.size > 1 && (parts[0] == "GET" || parts[0] == "HEAD")) {
                    val path = parts[1].removePrefix("/").substringBefore("?").removeSuffix(".mpd")
                    xml = manifests[path]
                }
                if (xml == null) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                } else {
                    val body = xml.toByteArray(Charsets.UTF_8)
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: application/dash+xml\r\nContent-Length: " + body.size + "\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    if (parts[0] == "GET") out.write(body)
                }
                out.flush()
            }
        } catch (e: Exception) {
        }
    }
}
