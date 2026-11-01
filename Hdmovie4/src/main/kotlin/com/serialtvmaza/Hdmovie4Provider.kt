package com.serialtvmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class Hdmovie4Provider : MainAPI() {
    // Domains jo order mein check hongi: pehle .com, phir .shop
    private val domains = listOf(
        "https://hdmovie4.com",
        "https://hdmovie4.shop"
    )

    override var mainUrl = "https://hdmovie4.com"
    override var name = "Hdmovie4"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "/" to "Latest",
        "/genre/bollywood" to "Bollywood",
        "/genre/hindi-dubbed" to "Hindi Dubbed",
        "/genre/hollywood" to "Hollywood",
        "/genre/netflix" to "Netflix",
        "/genre/punjabi" to "Punjabi",
        "/genre/romance" to "Romance",
    )

    @Volatile
    private var domainResolved = false

    private val epRegex = Regex("""EP\s*0*(\d+)""", RegexOption.IGNORE_CASE)

    // ---------------- Domain fallback ----------------
    private suspend fun resolveDomain() {
        if (domainResolved) return
        for (d in domains) {
            try {
                val res = app.get(d, timeout = 10L)
                if (res.code !in 200..299) continue
                val uri = URI(res.url)
                val host = uri.host ?: continue
                // Redirect kisi dusri (hdmovie4 se alag) site par gaya to skip
                if (!host.contains("hdmovie4", ignoreCase = true)) continue
                mainUrl = "${uri.scheme}://$host"
                domainResolved = true
                return
            } catch (e: Exception) {
                // is domain par nahi khula, agla try karo
            }
        }
    }

    // Purane saved link ka domain naye domain se badal do
    private fun rebase(url: String): String {
        val re = Regex("^https?://[^/]*hdmovie4[^/]*", RegexOption.IGNORE_CASE)
        return if (re.containsMatchIn(url)) mainUrl + re.replaceFirst(url, "") else url
    }

    // ---------------- Home ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val url = "$mainUrl${request.data}?page=$page"
        val doc = app.get(url).document
        val items = doc.select("article.movie-card, article.search-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    // ---------------- Search ----------------
    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q").document
        return doc.select("article.search-card, article.movie-card")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ---------------- Load ----------------
    override suspend fun load(url: String): LoadResponse {
        resolveDomain()
        val pageUrl = rebase(url)
        val doc = app.get(pageUrl).document

        val title = doc.selectFirst("h1.single-title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Watch Online")?.trim()
            ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.selectFirst("div.synopsis-text")?.text()?.trim()
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val servers = doc.select("button.server-tab-btn[data-embed-url]").map { b ->
            b.attr("data-embed-url") to (b.selectFirst("span.server-name-text")?.text()?.trim() ?: "Server")
        }
        val epMatches = servers.count { epRegex.containsMatchIn(it.second) }
        val isSeries = servers.size > 1 && epMatches >= 2 && epMatches * 2 >= servers.size

        return if (isSeries) {
            val episodes = servers.mapIndexed { i, (embed, label) ->
                val epNo = epRegex.find(label)?.groupValues?.get(1)?.toIntOrNull() ?: (i + 1)
                newEpisode("$pageUrl@@$embed") {
                    this.name = label
                    this.season = 1
                    this.episode = epNo
                }
            }
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    // ---------------- Links ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        val parts = data.split("@@", limit = 2)
        val pageUrl = rebase(parts[0])
        val doc = app.get(pageUrl).document

        val servers: List<Pair<String, String>> = if (parts.size == 2) {
            val embed = parts[1]
            val label = doc.select("button.server-tab-btn[data-embed-url]")
                .firstOrNull { it.attr("data-embed-url") == embed }
                ?.selectFirst("span.server-name-text")?.text()?.trim() ?: "Server"
            listOf(embed to label)
        } else {
            doc.select("button.server-tab-btn[data-embed-url]").map { b ->
                b.attr("data-embed-url") to (b.selectFirst("span.server-name-text")?.text()?.trim() ?: "Server")
            }
        }

        var found = false
        for ((embed, label) in servers) {
            try {
                if (resolvePlayer(embed, label, pageUrl, callback)) found = true
            } catch (e: Exception) {
                // is server mein dikkat, agla try karo
            }
        }
        return found
    }

    // /hdm2-player/XXXX/ -> hdm2.biz/play?... -> data-stream-url (playlist)
    private suspend fun resolvePlayer(
        embed: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(fixUrl(embed), referer = referer)
        val loader = res.document.selectFirst("script#player-loader") ?: return false
        val stream = loader.attr("data-stream-url")
        if (stream.isBlank()) return false

        val uri = URI(res.url)
        val origin = "${uri.scheme}://${uri.host}"
        val streamUrl = if (stream.startsWith("http")) stream else origin + stream

        callback(
            newExtractorLink(name, "$name $label", streamUrl, ExtractorLinkType.M3U8) {
                this.referer = "$origin/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("Origin" to origin)
            }
        )
        return true
    }

    // ---------------- Helpers ----------------
    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("h3.movie-title a, h2.search-card-title a")
            ?: selectFirst("a[href*=/movie/]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null
        val img = selectFirst("img")
        val title = a.text().trim().ifBlank { img?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = fixUrlNull(img?.attr("src")?.takeIf { it.isNotBlank() })
        val quality = getQualityFromString(
            selectFirst("span.badge-quality, span.search-badge-quality")?.text()
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.quality = quality
        }
    }
}