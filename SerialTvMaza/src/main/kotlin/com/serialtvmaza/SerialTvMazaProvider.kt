package com.serialtvmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class SerialTvMazaProvider : MainAPI() {
    override var mainUrl = "https://serialtvmaza.sbs"
    override var name = "SerialTvMaza"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Channel category slugs seen in the site navigation (2026-10-30).
    private val channels = setOf(
        "colors-tv", "star-plus", "sony-tv", "zee-tv",
        "sab-tv", "mtv", "sun-neo", "star-bharat"
    )

    // Top-level WordPress paths that are NOT episode posts.
    private val reserved = setOf(
        "page", "feed", "contact-us", "privacy-policy",
        "dmca-copyright-policy-disclaimer", "sitemap", "wp-content",
        "wp-json", "category", "tag", "author", "comments"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Episodes",
        "$mainUrl/star-plus/" to "Star Plus",
        "$mainUrl/colors-tv/" to "Colors TV",
        "$mainUrl/zee-tv/" to "Zee TV",
        "$mainUrl/sony-tv/" to "Sony TV",
        "$mainUrl/sab-tv/" to "SAB TV",
        "$mainUrl/mtv/" to "MTV",
        "$mainUrl/sun-neo/" to "Sun Neo",
    )

    // ---------- URL helpers ----------

    private fun segments(url: String): List<String> = try {
        URI(url).path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun isEpisodeUrl(url: String): Boolean {
        val s = segments(url)
        return s.size == 1 && s[0] !in reserved && s[0] !in channels
    }

    private fun isShowUrl(url: String): Boolean {
        val s = segments(url)
        return s.size == 2 && s[0] in channels
    }

    private fun hostOf(url: String): String = try {
        URI(url).host.orEmpty().removePrefix("www.")
    } catch (e: Exception) {
        ""
    }

    // ---------- Title helpers ----------

    private val dateRegex = Regex("""\s+\d{1,2}(?:st|nd|rd|th)\s+[A-Za-z]+\s+\d{4}.*$""")
    private val episodeSuffixRegex = Regex("""(?i)\s+(?:full\s+)?episode\s+\d+\s*$""")
    private val boilerplateRegex =
        Regex("""(?i)\s*watch\s+online(?:\s+full|\s+video)?(?:\s+episode)?(?:\s+hd)?""")
    private val episodeNumRegex = Regex("""(?i)episode\s*(\d+)""")

    private fun showNameFromTitle(title: String): String {
        val noDate = title.replace(dateRegex, "").trim()
        return noDate.replace(episodeSuffixRegex, "").trim().ifBlank { title }
    }

    private fun cleanEpisodeName(title: String): String =
        title.replace(boilerplateRegex, "").replace(Regex("""\s{2,}"""), " ").trim()

    // ---------- Listing parser ----------

    private data class Item(val title: String, val href: String, val poster: String?)

    /** Parses episode posts (title link + thumbnail) from home, category, and search pages. */
    private fun parseItems(doc: Document): List<Item> {
        val posters = HashMap<String, String>()
        doc.select("a[href]:has(img)").forEach { a ->
            val href = a.absUrl("href")
            if (!isEpisodeUrl(href) || posters.containsKey(href)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val src = img.absUrl("data-src").ifBlank { img.absUrl("src") }
            if (src.isNotBlank()) posters[href] = src
        }
        return doc.select("h2 a[href], h3 a[href]").mapNotNull { a ->
            val href = a.absUrl("href")
            if (!isEpisodeUrl(href)) return@mapNotNull null
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            Item(title, href, posters[href])
        }.distinctBy { it.href }
    }

    /** One result per show (several episodes of a show collapse into one card). */
    private fun toSearchResults(items: List<Item>): List<SearchResponse> =
        items.distinctBy { showNameFromTitle(it.title).lowercase() }.map {
            newTvSeriesSearchResponse(showNameFromTitle(it.title), it.href, TvType.TvSeries) {
                this.posterUrl = it.poster
            }
        }

    // ---------- Main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page/" else request.data
        val doc = app.get(url).document
        val results = toSearchResults(parseItems(doc))
        val hasNext = doc.select("a[href*=/page/${page + 1}/]").isNotEmpty()
        return newHomePageResponse(listOf(HomePageList(request.name, results)), hasNext)
    }

    // ---------- Search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        // WordPress search; spaces encoded as %20 (multi-word handling, see knowledge base).
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val doc = app.get("$mainUrl/?s=$q").document
        return toSearchResults(parseItems(doc))
    }

    // ---------- Load (show page with episode list) ----------

    override suspend fun load(url: String): LoadResponse {
        var showUrl = url
        var doc = app.get(url).document
        var showTitle: String? = null

        if (isEpisodeUrl(url)) {
            // The URL came from an episode post: jump to its show category page.
            val section = doc.selectFirst("meta[property=article:section]")?.attr("content")?.trim()
            val showLink = doc.select("a[href]").firstOrNull {
                isShowUrl(it.absUrl("href")) && section != null &&
                        it.text().trim().equals(section, ignoreCase = true)
            }
            if (showLink != null) {
                showUrl = showLink.absUrl("href")
                showTitle = showLink.text().trim()
                doc = app.get(showUrl).document
            } else {
                // Could not find the show: expose this single episode.
                val title = doc.selectFirst("h1")?.text()?.trim() ?: "Episode"
                val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                return newMovieLoadResponse(cleanEpisodeName(title), url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }
        }

        val showSlug = segments(showUrl).lastOrNull().orEmpty()
        val slugKey = showSlug.take(8) // tolerant prefix match (site spells names inconsistently)

        val collected = LinkedHashMap<String, Item>()
        var pageDoc = doc
        var p = 1
        while (p <= 8) {
            val items = parseItems(pageDoc).filter { item ->
                val slug = segments(item.href).firstOrNull().orEmpty()
                slugKey.isBlank() || slug.startsWith(slugKey)
            }
            var added = 0
            items.forEach { if (collected.putIfAbsent(it.href, it) == null) added++ }
            if (added == 0) break
            p++
            pageDoc = try {
                app.get("${showUrl.trimEnd('/')}/page/$p/").document
            } catch (e: Exception) {
                break
            }
        }

        val episodes = collected.values.reversed().map { item ->
            newEpisode(item.href) {
                this.name = cleanEpisodeName(item.title)
                this.episode = episodeNumRegex.find(item.title)?.groupValues?.get(1)?.toIntOrNull()
                this.posterUrl = item.poster
            }
        }

        val title = showTitle
            ?: doc.selectFirst("h1")?.text()?.replace(Regex("""(?i)^(category|archives?):?\s*"""), "")
                ?.replace(Regex("""(?i)\s*archives?$"""), "")?.trim()
                ?.ifBlank { null }
            ?: showSlug.replace('-', ' ').replaceFirstChar { it.uppercase() }

        return newTvSeriesLoadResponse(title, showUrl, TvType.TvSeries, episodes) {
            this.posterUrl = collected.values.firstOrNull { it.poster != null }?.poster
        }
    }

    // ---------- Links ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val seen = HashSet<String>()
        var found = false
        val siteHost = hostOf(mainUrl)

        // "WATCH IN HD - SERVER 1..4" and "DOWNLOAD - SERVER 5" buttons.
        val serverLinks = doc.select("a[href]").filter {
            it.text().contains("server", ignoreCase = true) &&
                    hostOf(it.absUrl("href")) != siteHost
        }.map { it.absUrl("href") to it.text().trim() }

        for ((link, label) in serverLinks) {
            if (!seen.add(link)) continue
            if (resolveServer(link, label, data, seen, subtitleCallback, callback)) found = true
        }

        // Any iframe embedded directly in the post.
        doc.select("iframe[src]").forEach {
            val src = it.absUrl("src")
            if (src.isNotBlank() && seen.add(src)) {
                loadExtractor(src, data, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }

    private val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
    private val mp4Regex = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
    private val jsRedirectRegex =
        Regex("""(?:location(?:\.href)?\s*=\s*|location\.replace\(\s*|window\.open\(\s*)["'](https?://[^"']+)["']""")
    private val metaRefreshRegex = Regex("""(?i)url=(https?://[^"'>\s]+)""")

    /**
     * Opens one redirector link (articleweb.xyz vid php page) and tries, in order:
     * HTTP redirect target, iframes, JS/meta redirects, direct m3u8/mp4 URLs.
     */
    private suspend fun resolveServer(
        link: String,
        label: String,
        referer: String,
        seen: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = try {
            app.get(link, referer = referer)
        } catch (e: Exception) {
            return false
        }
        var found = false
        val linkHost = hostOf(link)

        // 1) Redirected to a real video host.
        val finalUrl = res.url
        if (hostOf(finalUrl) != linkHost && seen.add(finalUrl)) {
            loadExtractor(finalUrl, referer, subtitleCallback, callback)
            found = true
        }

        val html = res.text.replace("\\/", "/")
        val page = res.document

        // 2) Iframes.
        page.select("iframe[src]").forEach {
            val src = it.absUrl("src")
            if (src.isNotBlank() && seen.add(src)) {
                loadExtractor(src, link, subtitleCallback, callback)
                found = true
            }
        }

        // 3) JS / meta-refresh redirects.
        (jsRedirectRegex.findAll(html).map { it.groupValues[1] } +
                metaRefreshRegex.findAll(html).map { it.groupValues[1] })
            .filter { hostOf(it) != linkHost }
            .forEach {
                if (seen.add(it)) {
                    loadExtractor(it, link, subtitleCallback, callback)
                    found = true
                }
            }

        // 4) Direct streams inside the page.
        m3u8Regex.findAll(html).map { it.value }.distinct().forEach { u ->
            if (seen.add(u)) {
                callback(
                    newExtractorLink(name, "$name - ${label.ifBlank { "HLS" }}", u, ExtractorLinkType.M3U8) {
                        this.referer = link
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }
        mp4Regex.findAll(html).map { it.value }.distinct().forEach { u ->
            if (seen.add(u)) {
                callback(
                    newExtractorLink(name, "$name - ${label.ifBlank { "MP4" }}", u, ExtractorLinkType.VIDEO) {
                        this.referer = link
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        return found
    }
}
