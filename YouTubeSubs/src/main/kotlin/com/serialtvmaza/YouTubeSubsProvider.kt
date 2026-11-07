package com.serialtvmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class YouTubeSubsProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Subs"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Others)
    override val mainPage = mainPageOf("subs" to "Subscriptions", "wl" to "Watch Later")

    private var continuation: String? = null
    private var clientVersion: String? = null

    companion object {
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val IOS_UA =
            "com.google.ios.youtube/19.45.4 (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)"
        private const val VR_UA =
            "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
        private const val JSON_MT = "application/json"
    }

    // ---------- helpers ----------

    private fun videoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    private fun jsonBody(o: JSONObject) = o.toString().toRequestBody(JSON_MT.toMediaType())

    private suspend fun cver(cookie: String): String {
        clientVersion?.let { return it }
        val v = try {
            val html = app.get(
                "https://www.youtube.com/",
                headers = mapOf(
                    "Cookie" to cookie,
                    "User-Agent" to DESKTOP_UA,
                    "Accept-Language" to "en-US,en;q=0.9"
                )
            ).text
            Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
        clientVersion = v ?: "2.20250601.00.00"
        return clientVersion!!
    }

    private fun webContext(ver: String) = JSONObject().put(
        "client",
        JSONObject().put("clientName", "WEB").put("clientVersion", ver).put("hl", "en").put("gl", "IN")
    )

    private fun authHeaders(cookie: String, ver: String): Map<String, String> {
        val h = mutableMapOf(
            "Cookie" to cookie,
            "User-Agent" to DESKTOP_UA,
            "Origin" to "https://www.youtube.com",
            "X-Origin" to "https://www.youtube.com",
            "Referer" to "https://www.youtube.com/feed/subscriptions",
            "X-Goog-AuthUser" to "0",
            "X-Youtube-Client-Name" to "1",
            "X-Youtube-Client-Version" to ver,
            "Accept-Language" to "en-US,en;q=0.9"
        )
        YtAuth.authHeader(cookie)?.let { h["Authorization"] = it }
        return h
    }

    private fun txt(o: JSONObject?): String {
        if (o == null) return ""
        val simple = o.optString("simpleText", "")
        if (simple.isNotBlank()) return simple
        val runs = o.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text", "") ?: "" }
    }

    private class Parsed {
        val items = ArrayList<SearchResponse>()
        val seen = HashSet<String>()
        var token: String? = null
    }

    private fun addItem(out: Parsed, id: String, title: String, channel: String) {
        if (id.isBlank() || title.isBlank() || !out.seen.add(id)) return
        val label = if (channel.isBlank()) title else "$title • $channel"
        out.items += newMovieSearchResponse(label, "$mainUrl/watch?v=$id", TvType.Others) {
            this.posterUrl = "https://i.ytimg.com/vi/$id/mqdefault.jpg"
        }
    }

    private fun addVideoRenderer(v: JSONObject, out: Parsed) {
        addItem(
            out,
            v.optString("videoId", ""),
            txt(v.optJSONObject("title")),
            txt(v.optJSONObject("ownerText"))
        )
    }

    private fun addLockup(l: JSONObject, out: Parsed) {
        if (l.optString("contentType", "") != "LOCKUP_CONTENT_TYPE_VIDEO") return
        val meta = l.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content", "").orEmpty()
        val channel = meta?.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")?.optJSONObject(0)
            ?.optJSONArray("metadataParts")?.optJSONObject(0)
            ?.optJSONObject("text")?.optString("content", "").orEmpty()
        addItem(out, l.optString("contentId", ""), title, channel)
    }

    private fun walk(node: Any?, out: Parsed) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("videoRenderer")?.let { addVideoRenderer(it, out) }
                node.optJSONObject("lockupViewModel")?.let { addLockup(it, out) }
                node.optJSONObject("continuationItemRenderer")?.let { cir ->
                    val t = cir.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")?.optString("token", "")
                    if (!t.isNullOrBlank()) out.token = t
                }
                val keys = node.keys()
                while (keys.hasNext()) walk(node.opt(keys.next()), out)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), out)
        }
    }

    // ---------- main page: subscriptions feed ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (request.data == "wl") {
            if (page > 1) return newHomePageResponse(HomePageList(request.name, emptyList<SearchResponse>(), true), false)
            val cards = YtWatchLater.list().map { (wid, wtitle) ->
                newMovieSearchResponse(wtitle, "$mainUrl/watch?v=$wid&yswl=1", TvType.Others) {
                    this.posterUrl = "https://i.ytimg.com/vi/$wid/mqdefault.jpg"
                }
            }
            return newHomePageResponse(HomePageList(request.name, cards, true), false)
        }
        val cookie = YtStore.activeCookie()
            ?: throw ErrorLoadingException(
                "Login required: Settings > Extensions > Downloaded > YouTube Subs > Login with Google"
            )
        val ver = cver(cookie)

        val body = JSONObject().put("context", webContext(ver))
        if (page <= 1) {
            continuation = null
            body.put("browseId", "FEsubscriptions")
        } else {
            val token = continuation ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
            body.put("continuation", token)
        }

        val res = app.post(
            "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
            headers = authHeaders(cookie, ver),
            requestBody = jsonBody(body)
        ).text

        val out = Parsed()
        walk(JSONObject(res), out)
        continuation = out.token

        if (out.items.isEmpty() && page <= 1) {
            throw ErrorLoadingException("No videos found. Session may have expired - login again from plugin settings.")
        }
        return newHomePageResponse(HomePageList(request.name, out.items, true), out.token != null)
    }

    // ---------- auto-download support ----------

    suspend fun feedForDownload(): List<Pair<String, String>> {
        return try {
            val cookie = YtStore.activeCookie() ?: return emptyList()
            val ver = cver(cookie)
            val body = JSONObject().put("context", webContext(ver)).put("browseId", "FEsubscriptions")
            val res = app.post(
                "https://www.youtube.com/youtubei/v1/browse?prettyPrint=false",
                headers = authHeaders(cookie, ver),
                requestBody = jsonBody(body)
            ).text
            val out = Parsed()
            walk(JSONObject(res), out)
            out.items.map { it.url to it.name }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- details ----------

    override suspend fun load(url: String): LoadResponse {
        var realUrl = url
        val isAdd = url.startsWith("ytwl://add/")
        val isRemove = url.startsWith("ytwl://remove/")
        if (isAdd || isRemove) {
            val rest = url.substringAfter(if (isAdd) "ytwl://add/" else "ytwl://remove/")
            val aid = rest.substringBefore("?")
            val at = try {
                URLDecoder.decode(rest.substringAfter("?t=", ""), "UTF-8")
            } catch (e: Exception) {
                ""
            }
            if (isAdd) {
                if (!YtWatchLater.contains(aid)) YtWatchLater.toggle(aid, at.ifBlank { aid })
                YtWatchLater.toast("Added to Watch Later ✔ (" + YtWatchLater.list().size + " saved)")
            } else {
                if (YtWatchLater.contains(aid)) YtWatchLater.toggle(aid, "")
                YtWatchLater.toast("Removed from Watch Later")
            }
            realUrl = "$mainUrl/watch?v=$aid"
        }
        val id = videoId(realUrl) ?: throw ErrorLoadingException("Invalid YouTube url")
        if (realUrl.contains("yswl=1")) return loadWatchLater(id, realUrl)
        val watch = "$mainUrl/watch?v=$id"
        val info = try {
            JSONObject(app.get("https://www.youtube.com/oembed?url=$watch&format=json").text)
        } catch (e: Exception) {
            null
        }
        val title = info?.optString("title", "").orEmpty().ifBlank { "YouTube video" }
        val author = info?.optString("author_name", "").orEmpty()
        val thumb = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        val inWl = YtWatchLater.contains(id)
        val enc = URLEncoder.encode(title, "UTF-8")
        val actionCard = if (inWl) {
            newMovieSearchResponse("🗑 Remove from Watch Later", "ytwl://remove/$id?t=$enc", TvType.Others) {
                this.posterUrl = thumb
            }
        } else {
            newMovieSearchResponse("⭐ Add to Watch Later", "ytwl://add/$id?t=$enc", TvType.Others) {
                this.posterUrl = thumb
            }
        }
        return newMovieLoadResponse(title, watch, TvType.Others, watch) {
            this.posterUrl = thumb
            this.plot = if (author.isBlank()) null else "Channel: $author"
            this.recommendations = listOf(actionCard)
        }
    }

    private suspend fun loadWatchLater(id: String, url: String): LoadResponse {
        val all = YtWatchLater.list()
        val start = all.indexOfFirst { it.first == id }.let { if (it < 0) 0 else it }
        val ordered = all.drop(start) + all.take(start)
        if (ordered.isEmpty()) throw ErrorLoadingException("Watch Later is empty")
        val episodes = ordered.mapIndexed { i, p ->
            newEpisode("$mainUrl/watch?v=${p.first}") {
                this.name = p.second
                this.episode = i + 1
                this.posterUrl = "https://i.ytimg.com/vi/${p.first}/mqdefault.jpg"
            }
        }
        return newTvSeriesLoadResponse("Watch Later", url, TvType.Others, episodes) {
            this.posterUrl = "https://i.ytimg.com/vi/${ordered.first().first}/hqdefault.jpg"
            this.plot = "Plays your saved videos one after another"
        }
    }

    // auto-dubbed / secondary audio tracks carry acont=dubbed... in the url; keep only the original
    private fun isOriginalAudio(url: String): Boolean {
        val u = url.lowercase()
        return !u.contains("acont") || u.contains("acont%3doriginal") || u.contains("acont=original")
    }

    // height label (144..4320) of a YouTube stream: from the itag in the url, else guessed from width (16:9)
    private fun linkHeight(url: String, width: Int): Int {
        val itag = Regex("[?&/]itag[=/](\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
        val h: Int? = when (itag) {
            160, 278, 330, 394, 694 -> 144
            133, 242, 331, 395, 695 -> 240
            134, 243, 332, 396, 696, 18 -> 360
            135, 244, 333, 397, 697 -> 480
            136, 247, 334, 398, 698, 298, 302, 22 -> 720
            137, 248, 335, 399, 699, 299, 303 -> 1080
            264, 271, 336, 400, 700, 304, 308 -> 1440
            266, 313, 337, 401, 701, 305, 315 -> 2160
            272, 402, 702 -> 4320
            else -> null
        }
        return h ?: (width * 9 / 16)
    }

    // ---------- playback ----------

    private suspend fun player(id: String, client: JSONObject, ua: String, cname: String, cver: String): JSONObject? {
        return try {
            val body = JSONObject()
                .put("context", JSONObject().put("client", client))
                .put("videoId", id)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
            val res = app.post(
                "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
                headers = mapOf(
                    "User-Agent" to ua,
                    "Origin" to "https://www.youtube.com",
                    "X-Youtube-Client-Name" to cname,
                    "X-Youtube-Client-Version" to cver
                ),
                requestBody = jsonBody(body)
            ).text
            JSONObject(res)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("ytwl://toggle/")) {
            val tid = data.substringAfter("ytwl://toggle/").substringBefore("?")
            val tt = try {
                URLDecoder.decode(data.substringAfter("?t=", ""), "UTF-8")
            } catch (e: Exception) {
                ""
            }
            val added = YtWatchLater.toggle(tid, tt.ifBlank { tid })
            YtWatchLater.toast(if (added) "Added to Watch Later ✔" else "Removed from Watch Later")
            return false
        }
        val id = videoId(data) ?: return false
        var found = false

        // 1) iOS client -> HLS master playlist (all resolutions)
        val iosClient = JSONObject()
            .put("clientName", "IOS").put("clientVersion", "19.45.4")
            .put("deviceMake", "Apple").put("deviceModel", "iPhone16,2")
            .put("osName", "iPhone").put("osVersion", "18.1.0.22B83")
            .put("hl", "en").put("gl", "IN")
        val ios = player(id, iosClient, IOS_UA, "5", "19.45.4")
        val hls = ios?.optJSONObject("streamingData")?.optString("hlsManifestUrl", "").orEmpty()
        if (hls.isNotBlank()) {
            val h = mapOf("User-Agent" to IOS_UA)
            callback(
                newExtractorLink(name, "$name Auto (HLS)", hls, ExtractorLinkType.M3U8) {
                    this.referer = "https://www.youtube.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = h
                }
            )
            try {
                M3u8Helper.generateM3u8(name, hls, "https://www.youtube.com/", headers = h)
                    .forEach { l -> val cap = YtSettings.playMax(); if (cap <= 0 || l.quality <= cap) callback(l) }
            } catch (e: Exception) {
            }
            found = true
        }

        // 2) Android VR client -> muxed mp4 formats (audio+video, good for download)
        run {
            val vrClient = JSONObject()
                .put("clientName", "ANDROID_VR").put("clientVersion", "1.60.19")
                .put("deviceMake", "Oculus").put("deviceModel", "Quest 3")
                .put("osName", "Android").put("osVersion", "12L")
                .put("androidSdkVersion", 32).put("hl", "en").put("gl", "IN")
            val vr = player(id, vrClient, VR_UA, "28", "1.60.19")
            val formats = vr?.optJSONObject("streamingData")?.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val u = f.optString("url", "")
                    if (u.isBlank()) continue
                    val hgt = f.optInt("height", 0)
                    callback(
                        newExtractorLink(name, "$name ${hgt}p (audio+video mp4)", u) {
                            this.referer = "https://www.youtube.com/"
                            this.quality = if (hgt > 0) hgt else Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to VR_UA)
                        }
                    )
                    found = true
                }
            }
        }

        // 3) CloudStream built-in YouTube extractor, capped to the playback max quality
        val capB = YtSettings.playMax()
        val builtIn = loadExtractor("https://www.youtube.com/watch?v=$id", subtitleCallback) { l ->
            val hh = linkHeight(l.url, l.quality)
            if (capB <= 0 || hh <= capB) {
                val orig = l.audioTracks.filter { isOriginalAudio(it.url) }
                if (orig.isNotEmpty() && orig.size != l.audioTracks.size) l.audioTracks = orig
                callback(l)
            }
        }
        return found || builtIn
    }
}