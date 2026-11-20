package com.serialtvmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class DesiRulezProvider : MainAPI() {
    override var mainUrl = "https://desiruleztv.net"
    override var name = "DesiRulez"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Channel category slugs from the top bar of the site.
    private val channels = setOf(
        "star-plus", "colors-tv", "zeetv", "sony-tv", "sony-sab-tv",
        "mtv-india", "netflix", "prime-video"
    )

    // Top-level WordPress paths that are NOT episode posts.
    private val reserved = setOf(
        "page", "feed", "category", "tag", "author", "home", "wp-content", "wp-json",
        "wp-admin", "comments", "sitemap", "contact-us", "privacy-policy"
    )

    // Episode post slugs always contain a date like 19th-november-2026.
    private val episodeSlugRegex = Regex("""\d{1,2}(?:st|nd|rd|th)-[a-z]+-\d{4}""")

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Episodes",
        "$mainUrl/category/star-plus/" to "Star Plus",
        "$mainUrl/category/colors-tv/" to "Colors TV",
        "$mainUrl/category/zeetv/" to "Zee TV",
        "$mainUrl/category/sony-tv/" to "Sony TV",
        "$mainUrl/category/sony-sab-tv/" to "Sony SAB",
        "$mainUrl/category/mtv-india/" to "MTV India",
        "$mainUrl/category/netflix/" to "Netflix India",
        "$mainUrl/category/prime-video/" to "Prime Video",
    )

    // ---------- URL helpers ----------

    private fun segments(url: String): List<String> = try {
        URI(url).path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun hostOf(url: String): String = try {
        URI(url).host.orEmpty().removePrefix("www.")
    } catch (e: Exception) {
        ""
    }

    private fun fixLink(raw: String?): String {
        val u = raw?.trim().orEmpty()
        return when {
            u.isBlank() || u.startsWith("data:") -> ""
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> mainUrl + u
            else -> u
        }
    }

    private fun isEpisodeUrl(url: String): Boolean {
        if (hostOf(url) != hostOf(mainUrl)) return false
        val s = segments(url)
        return s.size == 1 && s[0].lowercase() !in reserved &&
                episodeSlugRegex.containsMatchIn(s[0].lowercase())
    }

    private fun isShowCategory(url: String): Boolean {
        if (hostOf(url) != hostOf(mainUrl)) return false
        val s = segments(url)
        return s.size == 2 && s[0] == "category" && s[1].lowercase() !in channels
    }

    // ---------- Title helpers ----------

    private val dateRegex = Regex("""\s+\d{1,2}(?:st|nd|rd|th)\s+[A-Za-z]+\s+\d{4}.*$""")
    private val boilerplateRegex = Regex("""(?i)\s*watch\s+video\s+episode\s+online""")
    private val dateInTitleRegex = Regex("""(\d{1,2})(?:st|nd|rd|th)\s+([A-Za-z]+)\s+(\d{4})""")
    private val monthNames = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )

    private fun showNameFromTitle(title: String): String {
        val noDate = title.replace(dateRegex, "").replace(boilerplateRegex, "").trim()
        return noDate.ifBlank { title }
    }

    private fun cleanEpisodeName(title: String): String =
        title.replace(boilerplateRegex, "").replace(Regex("""\s{2,}"""), " ").trim()

    private fun dateKey(title: String): Int {
        val m = dateInTitleRegex.find(title) ?: return 0
        val day = m.groupValues[1].toIntOrNull() ?: return 0
        val month = monthNames.indexOf(m.groupValues[2].lowercase()) + 1
        val year = m.groupValues[3].toIntOrNull() ?: return 0
        return year * 10000 + month * 100 + day
    }

    // ---------- Listing parser ----------

    private data class Item(val title: String, val href: String, val poster: String?)

    private fun parseItems(doc: Document): List<Item> {
        val posters = HashMap<String, String>()
        doc.select("a[href]:has(img)").forEach { a ->
            val href = fixLink(a.attr("href"))
            if (!isEpisodeUrl(href) || posters.containsKey(href)) return@forEach
            val img = a.selectFirst("img") ?: return@forEach
            val src = fixLink(img.attr("data-src").ifBlank { img.attr("src") })
            if (src.isNotBlank()) posters[href] = src
        }
        return doc.select("h2 a[href], h3 a[href]").mapNotNull { a ->
            val href = fixLink(a.attr("href"))
            if (!isEpisodeUrl(href)) return@mapNotNull null
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null
            Item(title, href, posters[href])
        }.distinctBy { it.href }
    }

    /** One result per show (used for search). */
    private fun toSearchResults(items: List<Item>): List<SearchResponse> =
        items.distinctBy { showNameFromTitle(it.title).lowercase() }.map {
            newTvSeriesSearchResponse(showNameFromTitle(it.title), it.href, TvType.TvSeries) {
                this.posterUrl = it.poster
            }
        }

    /** One card per episode, newest date first. */
    private fun toEpisodeCards(items: List<Item>): List<SearchResponse> =
        items.sortedByDescending { dateKey(it.title) }.map {
            newTvSeriesSearchResponse(cleanEpisodeName(it.title), it.href, TvType.TvSeries) {
                this.posterUrl = it.poster
            }
        }

    // ---------- Main page ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = request.data.trimEnd('/')
        val url = if (page > 1) "$base/page/$page/" else request.data
        val doc = app.get(url).document
        val results = toEpisodeCards(parseItems(doc))
        val hasNext = doc.select("a[href*=/page/${page + 1}/]").isNotEmpty()
        return newHomePageResponse(listOf(HomePageList(request.name, results)), hasNext)
    }

    // ---------- Search ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val doc = app.get("$mainUrl/?s=$q").document
        return toSearchResults(parseItems(doc))
    }

    // ---------- Load ----------

    override suspend fun load(url: String): LoadResponse {
        var showUrl = url
        var doc = app.get(url).document
        var showTitle: String? = null

        if (isEpisodeUrl(url)) {
            // The URL came from an episode post: find its show category link.
            val showLink = doc.select("a[href*=/category/]").firstOrNull {
                isShowCategory(fixLink(it.attr("href")))
            }
            if (showLink != null) {
                showUrl = fixLink(showLink.attr("href"))
                showTitle = showLink.text().trim().ifBlank { null }
                doc = app.get(showUrl).document
            } else {
                val title = doc.selectFirst("h1")?.text()?.trim() ?: "Episode"
                val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                return newMovieLoadResponse(cleanEpisodeName(title), url, TvType.Movie, url) {
                    this.posterUrl = poster
                }
            }
        }

        val collected = LinkedHashMap<String, Item>()
        var pageDoc = doc
        var p = 1
        while (p <= 10) {
            var added = 0
            parseItems(pageDoc).forEach { if (collected.putIfAbsent(it.href, it) == null) added++ }
            if (added == 0) break
            p++
            val res = try {
                app.get("${showUrl.trimEnd('/')}/page/$p/")
            } catch (e: Exception) {
                break
            }
            if (res.code == 404) break
            pageDoc = res.document
        }

        // Oldest first, numbered by position so every episode has a unique number.
        val ordered = collected.values.reversed()
            .withIndex()
            .sortedWith(compareBy<IndexedValue<Item>>({ dateKey(it.value.title) }, { it.index }))
        val episodes = ordered.mapIndexed { i, iv ->
            val item = iv.value
            newEpisode(item.href) {
                this.name = cleanEpisodeName(item.title)
                this.episode = i + 1
                this.posterUrl = item.poster
            }
        }

        val title = showTitle
            ?: doc.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: segments(showUrl).lastOrNull().orEmpty().replace('-', ' ')
                .replaceFirstChar { it.uppercase() }

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
        val doc = app.get(data, referer = "$mainUrl/").document
        val seen = HashSet<String>()
        var found = false

        val frames = doc.select("iframe[src], iframe[data-src]").map {
            fixLink(it.attr("src").ifBlank { it.attr("data-src") })
        }.filter { it.startsWith("http") }

        frames.forEachIndexed { index, src ->
            if (!seen.add(src)) return@forEachIndexed
            val label = "Server ${index + 1} - ${playerLabel(src)}"
            if (resolvePlayer(src, label, subtitleCallback, callback)) found = true
        }
        return found
    }

    private fun playerLabel(url: String): String {
        val h = hostOf(url)
        return when {
            h.contains("vishi") && url.contains("vjvidara") -> "Vishi 2"
            h.contains("vishi") -> "Vishi"
            h.contains("upbolt") -> "Upbolt"
            h.contains("vkspeed") -> "Vkspeed"
            else -> h.substringBefore('.').replaceFirstChar { it.uppercase() }
        }
    }

    private val lockDataRegex =
        Regex("""lockData\s*=\s*\[\s*["']([0-9a-fA-F]{32})["']\s*,\s*["']([0-9a-fA-F]+)["']""")

    /** Same unlocking routine as the Vishi player page (sfc32 keystream + chained XOR). */
    private fun unlockStream(seed: String, hexStr: String): String? {
        if (seed.length < 32 || hexStr.length % 2 != 0) return null
        val s = IntArray(4) { i -> seed.substring(i * 8, i * 8 + 8).toLong(16).toInt() }
        fun next(): Int {
            var a = s[0]
            var b = s[1]
            var c = s[2]
            var d = s[3]
            var t = a + b
            a = b xor (b ushr 9)
            b = c + (c shl 3)
            c = (c shl 21) or (c ushr 11)
            d += 1
            t += d
            c += t
            s[0] = a
            s[1] = b
            s[2] = c
            s[3] = d
            return t
        }
        repeat(12) { next() }
        val out = StringBuilder()
        var prev = 0
        var j = 0
        while (j + 1 < hexStr.length) {
            val p = hexStr.substring(j, j + 2).toInt(16) xor ((next() ushr 8) and 255) xor prev
            out.append(p.toChar())
            prev = p
            j += 2
        }
        val res = out.toString()
        return if (res.startsWith("http")) res else null
    }

    private val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
    private val mp4Regex = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
    private val noReferrerRegex =
        Regex("""(?i)name=["']referrer["']\s+content=["']no-referrer""")

    /**
     * Opens one player page, looks for the stream address in the page and in its
     * unpacked script, and falls back to CloudStream's own extractors.
     */
    private suspend fun resolvePlayer(
        url: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val host = hostOf(url)
        val html = try {
            app.get(url, referer = "$mainUrl/").text
        } catch (e: Exception) {
            ""
        }
        val blocked = html.contains("Just a moment", ignoreCase = true)

        val texts = ArrayList<String>()
        if (html.isNotBlank() && !blocked) {
            texts.add(html.replace("\\/", "/"))
            lockDataRegex.find(html)?.let { lm ->
                unlockStream(lm.groupValues[1], lm.groupValues[2])?.let { texts.add(it) }
            }
            if (html.contains("eval(function(p,a,c,k,e")) {
                try {
                    texts.add(getAndUnpack(html).replace("\\/", "/"))
                } catch (e: Exception) {
                }
            }
        }

        val playerReferer = if (noReferrerRegex.containsMatchIn(html)) "" else "https://$host/"
        var found = false
        val seenUrls = HashSet<String>()

        for (t in texts) {
            for (m in m3u8Regex.findAll(t)) {
                val u = m.value
                if (!seenUrls.add(u)) continue
                callback(
                    newExtractorLink(name, "$name - $label (Auto)", u, ExtractorLinkType.M3U8) {
                        this.referer = playerReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                try {
                    M3u8Helper.generateM3u8("$name - $label", u, playerReferer).forEach { callback(it) }
                } catch (e: Exception) {
                }
                found = true
            }
            for (m in mp4Regex.findAll(t)) {
                val u = m.value
                if (!seenUrls.add(u)) continue
                callback(
                    newExtractorLink(name, "$name - $label", u, ExtractorLinkType.VIDEO) {
                        this.referer = playerReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
        }

        if (!found) {
            // Nothing readable in the page (or blocked): try CloudStream's built-in extractors.
            loadExtractor(url, "$mainUrl/", subtitleCallback, callback)
            found = true
        }
        return found
    }
}