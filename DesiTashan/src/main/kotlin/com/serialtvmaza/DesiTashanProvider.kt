package com.serialtvmaza

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class DesiTashanProvider : MainAPI() {
    override var mainUrl = "https://www.desitashan.su"
    override var name = "desitashan.su"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val playerBase = "https://workers.showdetails.org/hls/"
    private val playerOrigin = "https://workers.showdetails.org/"

    // Show page ke kitne pages tak episodes laane hain (1 page = lagbhag 35 episodes)
    private val maxEpisodePages = 6

    private val episodeUrlRegex = Regex("/[0-9a-f]{16}/?$")

    override val mainPage = mainPageOf(
        "/" to "Latest Episodes",
        "/category/star-plus/" to "Star Plus",
        "/category/colors-tv/" to "Colors TV",
        "/category/zee-tv/" to "Zee TV",
        "/category/sony-tv/" to "Sony TV",
        "/category/sab-tv/" to "SAB TV",
        "/category/and-tv/" to "And TV",
        "/category/dangal-tv/" to "Dangal TV",
        "/category/bigg-boss-20/" to "Bigg Boss 20",
    )

    private data class Card(
        val title: String,
        val href: String,
        val poster: String?,
        val epNo: Int?,
        val date: String?,
        val showName: String?,
        val showUrl: String?
    )

    // ---------------- Home ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = mainUrl + request.data
        val url = if (page <= 1) base else "${base}page/$page/"
        val doc = app.get(url, referer = "$mainUrl/").document
        val items = doc.select("article.card")
            .mapNotNull { it.toCard()?.toSearch() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    // ---------------- Search ----------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val res = app.get("$mainUrl/search.php?s=$q", referer = "$mainUrl/")
        val out = ArrayList<SearchResponse>()
        val seen = HashSet<String>()

        // Search page ke JSON-LD list mein har episode ka url, name aur image hota hai
        val ld = Regex(
            "\"url\":\"([^\"]+)\",\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"(?:,\"image\":\"([^\"]*)\")?"
        )
        ld.findAll(res.text).forEach { m ->
            val url = unescapeJson(m.groupValues[1])
            if (!episodeUrlRegex.containsMatchIn(url) || !seen.add(url)) return@forEach
            val title = unescapeJson(m.groupValues[2]).trim()
            if (title.isBlank()) return@forEach
            val poster = fixUrlNull(unescapeJson(m.groupValues[3]).ifBlank { null })
            out.add(newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = poster })
        }

        if (out.isEmpty()) {
            res.document.select("article.card").forEach { el ->
                val c = el.toCard() ?: return@forEach
                if (seen.add(c.href)) {
                    out.add(newMovieSearchResponse(c.title, c.href, TvType.Movie) { this.posterUrl = c.poster })
                }
            }
        }
        return out
    }

    // ---------------- Load ----------------
    override suspend fun load(url: String): LoadResponse? {
        return if (url.contains("/category/")) loadShow(url) else loadEpisode(url)
    }

    private suspend fun loadShow(showUrl: String): LoadResponse {
        val first = app.get(showUrl, referer = "$mainUrl/").document
        val title = first.selectFirst("h1")?.text()?.trim()
            ?.removePrefix("Latest ")?.removeSuffix(" Episodes")?.trim()
            ?.ifBlank { null } ?: "Unknown"
        val lastPage = first.select("a.page-numbers[data-page]")
            .mapNotNull { it.attr("data-page").toIntOrNull() }
            .maxOrNull() ?: 1

        val cards = ArrayList<Card>()
        cards.addAll(first.select("article.card").mapNotNull { it.toCard() })

        val base = showUrl.trimEnd('/')
        for (p in 2..minOf(lastPage, maxEpisodePages)) {
            try {
                val doc = app.get("$base/page/$p/", referer = "$mainUrl/").document
                cards.addAll(doc.select("article.card").mapNotNull { it.toCard() })
            } catch (e: Exception) {
                break
            }
        }

        val unique = cards.distinctBy { it.href }
        val ordered = if (unique.isNotEmpty() && unique.all { it.epNo != null }) {
            unique.sortedBy { it.epNo }
        } else {
            unique.reversed()
        }

        val poster = fixUrlNull(first.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: unique.firstOrNull()?.poster

        val episodes = ordered.map { c ->
            newEpisode(c.href) {
                this.name = c.date ?: c.title
                this.episode = c.epNo
                this.posterUrl = c.poster
            }
        }

        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
        }
    }

    private suspend fun loadEpisode(url: String): LoadResponse {
        val doc = app.get(url, referer = "$mainUrl/").document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.removePrefix("Watch ")?.substringBefore(" on Desi Tashan")?.trim()
            ?: "Unknown"

        // Episode ke title se pehchano ki kaun sa category link "show" ka hai
        val show = doc.select("a[href*=/category/]")
            .map { it.text().trim() to fixUrl(it.attr("href")) }
            .filter { (t, _) -> t.isNotBlank() && title.startsWith(t, ignoreCase = true) }
            .maxByOrNull { it.first.length }
        if (show != null) return loadShow(show.second)

        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
        }
    }

    // ---------------- Links ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var html = ""
        for (i in 1..3) {
            html = app.get(data, referer = "$mainUrl/").text
            if (html.contains("getlink.php")) break
            delay(1500)
        }

        // getlink ka host badal sakta hai, isliye koi bhi host chalega
        val links = Regex("https?://[^\"'\\s<>]*getlink\\.php\\?[^\"'\\s<>]*")
            .findAll(html).map { it.value.replace("&amp;", "&") }.toList()
        val link = links.firstOrNull { it.contains("type=jwplayer") }
            ?: links.firstOrNull()
            ?: return false

        val tokens = Regex("[?&](v|part\\d+)=([^&\\s\"']+)")
            .findAll(link)
            .map { it.groupValues[1] to pctDecode(it.groupValues[2]) }
            .distinctBy { it.second }
            .sortedBy { partOrder(it.first) }
            .toList()

        var found = false
        for ((i, t) in tokens.withIndex()) {
            try {
                val meta = app.get(
                    "${playerBase}media_meta.php",
                    params = mapOf("v" to t.second, "type" to "player"),
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                    referer = playerOrigin
                ).parsedSafe<MediaMeta>()
                val src = meta?.source ?: continue
                val file = src.file ?: continue
                val videoUrl = URI(playerBase).resolve(file).toString()
                val label = src.title?.takeIf { it.isNotBlank() } ?: "Part ${i + 1}"

                callback(
                    newExtractorLink(this.name, "$name $label", videoUrl, ExtractorLinkType.VIDEO) {
                        this.referer = playerOrigin
                        this.quality = Qualities.P720.value
                    }
                )
                found = true
            } catch (e: Exception) {
                // is part mein dikkat, agla try karo
            }
        }
        return found
    }

    // ---------------- Helpers ----------------
    private fun Element.toCard(): Card? {
        val a = selectFirst("div.content h3 a") ?: selectFirst("div.thumbnail a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (!episodeUrlRegex.containsMatchIn(href)) return null
        val img = selectFirst("div.thumbnail img")
        val title = a.text().trim().ifBlank { img?.attr("title").orEmpty().trim() }
        if (title.isBlank()) return null
        val poster = fixUrlNull(img?.attr("src")?.takeIf { it.isNotBlank() })
        val epNo = Regex("(\\d+)")
            .find(selectFirst("span.episode-number-card")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val date = selectFirst("div.episode-date-card")?.text()?.trim()?.ifBlank { null }
        val show = select("div.post-meta a[href*=/category/]")
            .map { it.text().trim() to fixUrl(it.attr("href")) }
            .filter { (t, _) -> t.isNotBlank() && title.startsWith(t, ignoreCase = true) }
            .maxByOrNull { it.first.length }
        return Card(title, href, poster, epNo, date, show?.first, show?.second)
    }

    private fun Card.toSearch(): SearchResponse {
        val sUrl = showUrl
        val sName = showName
        return if (sUrl != null && sName != null) {
            newTvSeriesSearchResponse(sName, sUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    private fun unescapeJson(s: String): String {
        return Regex("\\\\u([0-9a-fA-F]{4})")
            .replace(s) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\/", "/")
            .replace("\\\"", "\"")
    }

    // %XX ko decode karo, par '+' ko mat chhedo (base64 token ka hissa hai)
    private fun pctDecode(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val h = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (h != null) {
                    sb.append(h.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString().replace(' ', '+')
    }

    private fun partOrder(key: String): Int =
        if (key == "v") 1 else key.removePrefix("part").toIntOrNull() ?: 99

    data class MediaMeta(
        @JsonProperty("source") val source: MediaSource? = null,
        @JsonProperty("poster") val poster: String? = null
    )

    data class MediaSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("mimeType") val mimeType: String? = null,
        @JsonProperty("title") val title: String? = null
    )
}