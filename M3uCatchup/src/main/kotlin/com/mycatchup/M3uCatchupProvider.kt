package com.mycatchup

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

// ====== SIRF YE 2 LINES EDIT KARNI HAIN ======
private const val PLAYLIST_URL = "https://game.playindia.fun/Jtv/9HsOlk/Playlist.m3u"
private const val SLOT_MINUTES = 60 // catchup slot size: 30 ya 60
// =============================================

data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val catchup: String?,
    val catchupSource: String?,
    val catchupDays: Int,
    val userAgent: String?,
    val referer: String?
)

data class Play(
    val ch: Channel,
    val start: Long = 0L, // epoch seconds, 0 = live
    val end: Long = 0L
)

class M3uCatchupProvider : MainAPI() {
    override var name = "My M3U Catchup"
    override var mainUrl = "https://m3u.local"
    override var lang = "en"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Live, TvType.TvSeries)

    private var cache: List<Channel>? = null

    private val b64 = android.util.Base64.URL_SAFE or
        android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING

    // data http se shuru hoga taaki CloudStream fixUrl se kharab na kare
    private fun enc(s: String): String =
        "https://m3u.local/#" + android.util.Base64.encodeToString(s.toByteArray(), b64)

    private fun dec(s: String): String =
        String(android.util.Base64.decode(s.substringAfter("#"), b64))

    // ---------- M3U parsing ----------
    private suspend fun channels(): List<Channel> {
        cache?.let { return it }
        val text = app.get(PLAYLIST_URL).text
        val out = ArrayList<Channel>()
        val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

        var attrs: Map<String, String> = emptyMap()
        var title = ""
        var ua: String? = null
        var ref: String? = null

        for (raw in text.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    attrs = attrRegex.findAll(line)
                        .associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    title = line.substringAfterLast(",").trim()
                    ua = null
                    ref = null
                }
                line.startsWith("#EXTVLCOPT:http-user-agent=", true) ->
                    ua = line.substringAfter("=")
                line.startsWith("#EXTVLCOPT:http-referrer=", true) ->
                    ref = line.substringAfter("=")
                line.isNotEmpty() && !line.startsWith("#") -> {
                    if (title.isNotEmpty()) {
                        val src = attrs["catchup-source"]
                        val type = attrs["catchup"]
                            ?: if (!src.isNullOrEmpty()) "default" else null
                        val days = (attrs["catchup-days"] ?: attrs["tvg-rec"])
                            ?.toIntOrNull() ?: 3
                        out.add(
                            Channel(
                                name = title,
                                url = line,
                                logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                                group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                                    ?: "Channels",
                                catchup = type,
                                catchupSource = src,
                                catchupDays = days.coerceIn(1, 14),
                                userAgent = ua,
                                referer = ref
                            )
                        )
                    }
                    title = ""
                }
            }
        }
        cache = out
        return out
    }

    private fun Channel.toSearch(): SearchResponse {
        val data = enc(this.toJson())
        return if (catchup != null) {
            newTvSeriesSearchResponse(name, data, TvType.TvSeries) { posterUrl = logo }
        } else {
            newLiveSearchResponse(name, data, TvType.Live) { posterUrl = logo }
        }
    }

    // ---------- Home + search ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = channels().groupBy { it.group }.map { (g, l) ->
            HomePageList(g, l.map { it.toSearch() }, true)
        }
        return newHomePageResponse(lists, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return channels()
            .filter { it.name.contains(query, ignoreCase = true) }
            .map { it.toSearch() }
    }

    // ---------- Load: Live + day/time wise catchup ----------
    override suspend fun load(url: String): LoadResponse {
        val c = parseJson<Channel>(dec(url))

        if (c.catchup == null) {
            return newLiveStreamLoadResponse(c.name, url, enc(Play(c).toJson())) {
                posterUrl = c.logo
                plot = c.group
            }
        }

        val episodes = ArrayList<Episode>()
        val seasons = ArrayList<SeasonData>()

        // Season 1 = Live
        seasons.add(SeasonData(1, "Live"))
        episodes.add(
            newEpisode(enc(Play(c).toJson())) {
                this.name = "LIVE"
                this.season = 1
                this.episode = 1
            }
        )

        val dayFmt = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val nowMs = System.currentTimeMillis()

        for (dayBack in 0 until c.catchupDays) {
            val day = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -dayBack)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val seasonNo = dayBack + 2
            val label = when (dayBack) {
                0 -> "Today"
                1 -> "Yesterday"
                else -> dayFmt.format(day.time)
            }
            seasons.add(SeasonData(seasonNo, label))

            var minute = 0
            var epNo = 1
            while (minute < 24 * 60) {
                val startCal = (day.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, minute / 60)
                    set(Calendar.MINUTE, minute % 60)
                }
                val startMs = startCal.timeInMillis
                if (startMs >= nowMs) break
                val endMs = minOf(startMs + SLOT_MINUTES * 60_000L, nowMs)

                episodes.add(
                    newEpisode(enc(Play(c, startMs / 1000, endMs / 1000).toJson())) {
                        this.name = timeFmt.format(Date(startMs)) + " - " +
                            timeFmt.format(Date(startMs + SLOT_MINUTES * 60_000L))
                        this.season = seasonNo
                        this.episode = epNo
                    }
                )
                epNo++
                minute += SLOT_MINUTES
            }
        }

        return newTvSeriesLoadResponse(c.name, url, TvType.TvSeries, episodes) {
            posterUrl = c.logo
            plot = c.group
            seasonNames = seasons
        }
    }

    // ---------- Catchup URL builder ----------
    private fun fill(t: String, start: Long, end: Long, now: Long): String {
        val d = Date(start * 1000)
        fun f(p: String) = SimpleDateFormat(p, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(d)

        val dur = (end - start).toString()
        return t
            .replace("\${start}", start.toString())
            .replace("{start}", start.toString())
            .replace("\${timestamp}", start.toString())
            .replace("{timestamp}", start.toString())
            .replace("{utc}", start.toString())
            .replace("\${end}", end.toString())
            .replace("{end}", end.toString())
            .replace("{utcend}", end.toString())
            .replace("\${duration}", dur)
            .replace("{duration}", dur)
            .replace("\${offset}", (now - start).toString())
            .replace("{offset}", (now - start).toString())
            .replace("{lutc}", now.toString())
            .replace("{now}", now.toString())
            .replace("{Y}", f("yyyy"))
            .replace("{m}", f("MM"))
            .replace("{d}", f("dd"))
            .replace("{H}", f("HH"))
            .replace("{M}", f("mm"))
            .replace("{S}", f("ss"))
    }

    private fun flussonic(url: String, start: Long, dur: Long): String {
        val q = if (url.contains("?")) "?" + url.substringAfter("?") else ""
        val base = url.substringBefore("?")
        val res = if (base.endsWith("/mpegts")) {
            base.removeSuffix("mpegts") + "archive-$start-$dur.ts"
        } else {
            base.replace(Regex("""/([^/]*)\.m3u8$"""), "/$1-$start-$dur.m3u8")
        }
        return res + q
    }

    private fun catchupUrl(c: Channel, start: Long, end: Long): String {
        val now = System.currentTimeMillis() / 1000
        val src = c.catchupSource.orEmpty()
        return when (c.catchup?.lowercase()) {
            "append" -> c.url + fill(src, start, end, now)
            "shift", "timeshift" ->
                c.url + (if (c.url.contains("?")) "&" else "?") + "utc=$start&lutc=$now"
            "flussonic", "flussonic-hls", "flussonic-ts", "fs" ->
                flussonic(c.url, start, end - start)
            else -> if (src.isNotEmpty()) fill(src, start, end, now) else c.url
        }
    }

    // ---------- Playback ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val p = parseJson<Play>(dec(data))
        val link = if (p.start == 0L) p.ch.url else catchupUrl(p.ch, p.start, p.end)

        val hdr = HashMap<String, String>()
        p.ch.userAgent?.let { hdr["User-Agent"] = it }

        callback(
            newExtractorLink(
                name,
                p.ch.name,
                link,
                if (link.contains("m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = p.ch.referer ?: ""
                this.quality = Qualities.Unknown.value
                this.headers = hdr
            }
        )
        return true
    }
}
