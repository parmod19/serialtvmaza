package com.serialtvmaza

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActorNames
import org.jsoup.nodes.Element
import java.net.URI

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
        "/movies/" to "Movies",
        "/tvshows/" to "Web Series",
    )

    private val itemSelector =
        "div.items.normal article, div#archive-content article, div.items.full article"

    @Volatile
    private var domainResolved = false

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
        val base = mainUrl + request.data
        val url = if (page <= 1) base else "${base.trimEnd('/')}/page/$page/"
        val doc = app.get(url).document
        val items = doc.select(itemSelector).mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    // ---------------- Search ----------------
    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("div.result-item article, $itemSelector")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ---------------- Load ----------------
    override suspend fun load(url: String): LoadResponse {
        resolveDomain()
        val pageUrl = rebase(url)
        val doc = app.get(pageUrl).document

        val title = doc.selectFirst("div.data > h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "Unknown"
        val poster = fixUrlNull(
            doc.selectFirst("div.poster img")?.getImgUrl()
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        )
        val description = doc.selectFirst("div.wp-content > p")?.text()?.trim()
        val tags = doc.select("div.sgeneros > a").map { it.text() }
        val year = Regex("(\\d{4})").find(doc.selectFirst("span.date")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val actors = doc.select("div.persons > div[itemprop=actor]").mapNotNull {
            it.selectFirst("meta[itemprop=name]")?.attr("content")?.takeIf { n -> n.isNotBlank() }
        }
        val recommendations = doc.select("div.owl-item article").mapNotNull { it.toSearchResult() }

        val episodeRows = doc.select("ul.episodios > li")
        val isSeries = pageUrl.contains("/tvshows/") || episodeRows.isNotEmpty()

        return if (isSeries) {
            val episodes = episodeRows.mapNotNull { li ->
                val a = li.selectFirst("div.episodiotitle > a") ?: return@mapNotNull null
                val nums = li.selectFirst("div.numerando")?.text()
                    ?.split("-")?.map { it.trim().toIntOrNull() }
                newEpisode(fixUrl(a.attr("href"))) {
                    this.name = a.text().trim()
                    this.season = nums?.getOrNull(0)
                    this.episode = nums?.getOrNull(1)
                    this.posterUrl = fixUrlNull(li.selectFirst("div.imagen > img")?.getImgUrl())
                }
            }
            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActorNames(actors)
            }
        } else {
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                addActorNames(actors)
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
        val pageUrl = rebase(data)
        val doc = app.get(pageUrl).document
        var found = false

        val options = doc.select(
            ".dooplay_player .options ul li, #video-sources .options ul li, " +
                "div.options ul li.dooplay_player_option, ul#playeroptionsul > li"
        ).distinctBy { it.attr("data-nume") + it.attr("data-post") }

        for (li in options) {
            val post = li.attr("data-post")
            val nume = li.attr("data-nume")
            val type = li.attr("data-type").ifBlank { "movie" }
            if (post.isBlank() || nume.isBlank() || nume == "trailer") continue

            val embed = fetchEmbed(post, nume, type, pageUrl) ?: continue
            if (loadExtractor(embed, pageUrl, subtitleCallback, callback)) found = true
        }

        if (!found) {
            doc.select("div.embed iframe, iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src").ifBlank { iframe.attr("data-src") })
                if (!src.isNullOrBlank() && !src.contains("youtube")) {
                    if (loadExtractor(src, pageUrl, subtitleCallback, callback)) found = true
                }
            }
        }
        return found
    }

    private suspend fun fetchEmbed(post: String, nume: String, type: String, referer: String): String? {
        val ajax = app.post(
            "$mainUrl/wp-admin/admin-ajax.php",
            data = mapOf(
                "action" to "doo_player_ajax",
                "post" to post,
                "nume" to nume,
                "type" to type
            ),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = referer
        ).parsedSafe<EmbedResponse>()?.embedUrl

        val raw = ajax?.takeIf { it.isNotBlank() } ?: app.get(
            "$mainUrl/wp-json/dooplayer/v2/$post/$type/$nume",
            referer = referer
        ).parsedSafe<EmbedResponse>()?.embedUrl

        if (raw.isNullOrBlank()) return null
        val url = Regex("""src=["']([^"']+)["']""").find(raw)?.groupValues?.get(1) ?: raw
        return fixUrlNull(url.replace("\\/", "/"))
    }

    // ---------------- Helpers ----------------
    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("h3 > a, div.title > a, h2.result-title > a") ?: selectFirst("a") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null
        val img = selectFirst("img")
        val title = a.text().trim().ifBlank { img?.attr("alt")?.trim().orEmpty() }
        if (title.isBlank()) return null
        val poster = fixUrlNull(img?.getImgUrl())
        val quality = getQualityFromString(selectFirst("span.quality")?.text())
        val isSeries = href.contains("/tvshows/") || hasClass("tvshows")

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    private fun Element.getImgUrl(): String? {
        return listOf("data-src", "data-lazy-src", "data-wpfc-original-src", "src")
            .map { attr(it) }
            .firstOrNull { it.isNotBlank() && !it.startsWith("data:") }
    }

    data class EmbedResponse(
        @JsonProperty("embed_url") val embedUrl: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}