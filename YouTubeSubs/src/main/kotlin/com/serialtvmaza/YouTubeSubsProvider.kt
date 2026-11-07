package com.serialtvmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class YouTubeSubsProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Subs"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Others)
    override val mainPage = mainPageOf("subs" to "Subscriptions")

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
            this.posterUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
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
        if (page <= 1) YtDl.onFeed(out.items.map { it.url to it.name })

        if (out.items.isEmpty() && page <= 1) {
            throw ErrorLoadingException("No videos found. Session may have expired - login again from plugin settings.")
        }
        return newHomePageResponse(request.name, out.items, out.token != null)
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
        val id = videoId(url) ?: throw ErrorLoadingException("Invalid YouTube url")
        val watch = "$mainUrl/watch?v=$id"
        val info = try {
            JSONObject(app.get("https://www.youtube.com/oembed?url=$watch&format=json").text)
        } catch (e: Exception) {
            null
        }
        val title = info?.optString("title", "").orEmpty().ifBlank { "YouTube video" }
        val author = info?.optString("author_name", "").orEmpty()
        return newMovieLoadResponse(title, watch, TvType.Others, watch) {
            this.posterUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg"
            this.plot = if (author.isBlank()) null else "Channel: $author"
        }
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
                    .forEach { l -> val cap = YtDl.playMax(); if (cap <= 0 || l.quality <= cap) callback(l) }
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

        // 3) last resort: CloudStream built-in extractors
        if (!found) {
            found = loadExtractor("https://www.youtube.com/watch?v=$id", subtitleCallback, callback)
        }
        return found
    }
}