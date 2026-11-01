package com.serialtvmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class SerialTvMazaMeProvider : MainAPI() {
    override var mainUrl = "https://serialtvmaza.me"
    override var name = "SerialTvMazaMe"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Channel category slugs seen in the site navigation (2026-10-30).
    private val channels = setOf(
        "colors-tv", "star-plus", "sony-tv", "zee-tv",
        "sab-tv", "mtv", "sun-neo", "star-bharat", "dangal-tv", "web-series"
    )

    // Categories whose pages list movie/event posts directly.
    private val postCategories = setOf("movies", "awards-concerts")

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
        "$mainUrl/star-bharat/" to "Star Bharat",
        "$mainUrl/dangal-tv/" to "Dangal TV",
    )

    // ---------- URL helpers ----------

    private fun segments(url: String): List<String> = try {
        URI(url).path.orEmpty().trim('/').split('/').filter { it.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun isEpisodeUrl(url: String): Boolean {
        val s = segments(url)
        return s.size == 1 && s[0] !in reserved && s[0] !in channels && s[0] !in postCategories
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

    private fun editDist(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    /** True when the post slug starts with the same first words as the show slug (small typos allowed). */
    private fun sameShow(slug: String, showSlug: String): Boolean {
        val a = slug.split("-")
        val b = showSlug.split("-")
        val n = minOf(2, b.size)
        if (a.size < n) return false
        for (i in 0 until n) {
            val tol = if (b[i].length <= 2) 0 else maxOf(1, b[i].length / 4)
            if (a[i] != b[i] && editDist(a[i], b[i]) > tol) return false
        }
        return true
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

    private val dateInTitleRegex = Regex("""(\d{1,2})(?:st|nd|rd|th)\s+([A-Za-z]+)\s+(\d{4})""")
    private val monthNames = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )

    private fun dateKey(title: String): Int {
        val m = dateInTitleRegex.find(title) ?: return 0
        val day = m.groupValues[1].toIntOrNull() ?: return 0
        val month = monthNames.indexOf(m.groupValues[2].lowercase()) + 1
        val year = m.groupValues[3].toIntOrNull() ?: return 0
        return year * 10000 + month * 100 + day
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

    // ---------- Load (show page with episode list) ----------

    override suspend fun load(url: String): LoadResponse {
        var showUrl = url
        var doc = app.get(url).document
        var showTitle: String? = null
        val epTitle = doc.title().substringBeforeLast(" - ").trim().ifBlank { null }
        val epPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")

        if (isEpisodeUrl(url)) {
            val epSlug = segments(url).firstOrNull().orEmpty()
            val showLinks = doc.select("a[href]").filter {
                isShowUrl(it.absUrl("href")) && it.text().isNotBlank()
            }
            fun slugOf(a: org.jsoup.nodes.Element) = segments(a.absUrl("href")).lastOrNull().orEmpty()
            // 1) The show whose slug is the longest prefix of the episode slug.
            var showLink = showLinks
                .filter { epSlug.startsWith(slugOf(it) + "-") }
                .maxByOrNull { slugOf(it).length }
            // 2) Same first words (typos allowed).
            if (showLink == null) {
                showLink = showLinks.firstOrNull { sameShow(epSlug, slugOf(it)) }
            }
            // 3) Category meta, only if first letters agree.
            if (showLink == null) {
                val section = doc.selectFirst("meta[property=article:section]")?.attr("content")?.trim()
                if (section != null) {
                    showLink = showLinks.firstOrNull {
                        it.text().trim().equals(section, ignoreCase = true) &&
                                slugOf(it).take(2) == epSlug.take(2)
                    }
                }
            }
            if (showLink != null) {
                showUrl = showLink.absUrl("href")
                showTitle = showLink.text().trim()
                doc = app.get(showUrl).document
            } else {
                // Not part of any show (special post): expose this single episode.
                return newMovieLoadResponse(cleanEpisodeName(epTitle ?: "Episode"), url, TvType.Movie, url) {
                    this.posterUrl = epPoster
                }
            }
        }

        val showSlug = segments(showUrl).lastOrNull().orEmpty()
        val showName = showTitle ?: showSlug.replace('-', ' ')

        val collected = LinkedHashMap<String, Item>()
        val knownShows = doc.select("a[href]").map { it.absUrl("href") }
            .filter { isShowUrl(it) }.map { segments(it).lastOrNull().orEmpty() }.toSet()

        fun isForeign(slug: String): Boolean {
            val best = knownShows.filter { slug.startsWith("$it-") }.maxByOrNull { it.length }
            return best != null && best != showSlug
        }

        fun addFrom(d: Document) {
            parseItems(d).filter {
                val sg = segments(it.href).firstOrNull().orEmpty()
                !isForeign(sg) && sameShow(sg, showSlug)
            }.forEach { collected.putIfAbsent(it.href, it) }
        }

        addFrom(doc)
        // Remaining category pages are fetched in parallel.
        val extraDocs = (2..6).toList().amap { p ->
            try {
                app.get("${showUrl.trimEnd('/')}/page/$p/").document
            } catch (e: Exception) {
                null
            }
        }
        extraDocs.filterNotNull().forEach { addFrom(it) }

        // Episodes the site filed under another category are found through its search.
        try {
            val sq = URLEncoder.encode(showName, "UTF-8").replace("+", "%20")
            addFrom(app.get("$mainUrl/?s=$sq").document)
        } catch (e: Exception) {
        }

        // The episode that was tapped must always be in the list.
        if (isEpisodeUrl(url) && !collected.containsKey(url)) {
            collected[url] = Item(epTitle ?: showName, url, epPoster)
        }

        // Oldest first: the app shows the newest on top.
        val episodes = collected.values.sortedWith(
            compareBy<Item>(
                { dateKey(it.title) },
                { episodeNumRegex.find(it.title)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            )
        ).map { item ->
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
        val seen = HashSet<String>()
        val doc = try { app.get(data).document } catch (e: Exception) { null }
        var found = if (doc != null) extractFromPage(data, doc, seen, subtitleCallback, callback) else false
        if (!found && doc != null) found = followHops(data, doc, seen, subtitleCallback, callback)

        if (!found) {
            // Same post on the other mirror sites (one of them may carry the player).
            val path = try { URI(data).path.orEmpty() } catch (e: Exception) { "" }
            val own = hostOf(data)
            for (h in listOf("serialtvmaza.me", "serialtvmaza.net", "serialtvmaza.sbs")) {
                if (found) break
                if (h == own || path.isBlank()) continue
                val u = "https://$h$path"
                if (!seen.add(u)) continue
                val d2 = try { app.get(u, referer = data).document } catch (e: Exception) { continue }
                found = extractFromPage(u, d2, seen, subtitleCallback, callback)
                if (!found) found = followHops(u, d2, seen, subtitleCallback, callback)
            }
        }
        return found
    }

    /** Newer posts carry only a "... Click Here" link that opens the real page of the same episode. */
    private suspend fun followHops(
        pageUrl: String,
        doc: Document,
        seen: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val dateTok = Regex("""\d{1,2}(?:st|nd|rd|th)-[a-z]+-\d{4}""")
            .find(segments(pageUrl).firstOrNull().orEmpty())?.value
        val hops = doc.select("a[href]").filter {
            val h = it.absUrl("href")
            h.isNotBlank() && h != pageUrl && hostOf(h).startsWith("serialtvmaza") &&
                    it.text().contains("click here", ignoreCase = true) && isEpisodeUrl(h) &&
                    (dateTok == null || segments(h).first().contains(dateTok))
        }.map { it.absUrl("href") }.distinct().take(3)
        for (h in hops) {
            if (!seen.add(h)) continue
            val hd = try { app.get(h, referer = pageUrl).document } catch (e: Exception) { continue }
            if (extractFromPage(h, hd, seen, subtitleCallback, callback)) found = true
        }
        return found
    }

    private suspend fun extractFromPage(
        pageUrl: String,
        doc: Document,
        seen: HashSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val ownHosts = setOf(hostOf(mainUrl), hostOf(pageUrl))

        // "WATCH IN HD - SERVER 1..4" and "DOWNLOAD - SERVER 5" buttons.
        val serverLinks = doc.select("a[href]").filter {
            (it.text().contains("server", ignoreCase = true) || it.absUrl("href").contains("/vid/")) &&
                    hostOf(it.absUrl("href")) !in ownHosts
        }.map { a ->
            val prev = a.parent()?.previousElementSibling()?.text()?.trim().orEmpty()
            a.absUrl("href") to prev.ifBlank { a.text().trim() }
        }

        for ((link, label) in serverLinks) {
            if (isYoutube(link) || !seen.add(link)) continue
            if (resolveServer(link, label, pageUrl, seen, subtitleCallback, callback)) found = true
        }

        // Any iframe embedded directly in the post (YouTube only as a last resort).
        val ytFallback = ArrayList<String>()
        doc.select("iframe[src]").forEach {
            val src = it.absUrl("src")
            if (src.isBlank() || !seen.add(src)) return@forEach
            if (isYoutube(src)) {
                ytFallback.add(src)
            } else {
                loadExtractor(src, pageUrl, subtitleCallback, callback)
                found = true
            }
        }
        if (!found) {
            ytFallback.forEach {
                loadExtractor(it, pageUrl, subtitleCallback, callback)
                found = true
            }
        }
        return found
    }

    private fun isYoutube(url: String): Boolean {
        val h = hostOf(url)
        return h.endsWith("youtube.com") || h.endsWith("youtu.be") || h.endsWith("youtube-nocookie.com")
    }

    private val m3u8Regex = Regex("""https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""")
    private val mp4Regex = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""")
    private val jsRedirectRegex =
        Regex("""(?:location(?:\.href)?\s*=\s*|location\.replace\(\s*|window\.open\(\s*)["'](https?://[^"']+)["']""")
    private val metaRefreshRegex = Regex("""(?i)url=(https?://[^"'>\s]+)""")

    private val secretKeyRegex = Regex("""secretKey\s*=\s*["']([^"']+)["']""")
    private val encryptedRegex = Regex("""encryptedData\s*=\s*["']([0-9a-fA-F]+)["']""")

    /** Some player pages hide the stream URL as XOR-encoded hex. Decode it like the page's own script does. */
    private fun decodeHiddenStream(html: String): String? {
        val key = secretKeyRegex.find(html)?.groupValues?.get(1) ?: return null
        val hex = encryptedRegex.find(html)?.groupValues?.get(1) ?: return null
        if (key.isEmpty() || hex.length % 2 != 0) return null
        val sb = StringBuilder()
        var i = 0
        while (i < hex.length) {
            val code = hex.substring(i, i + 2).toInt(16)
            sb.append((code xor key[(i / 2) % key.length].code).toChar())
            i += 2
        }
        val out = sb.toString()
        return if (out.startsWith("http")) out else null
    }

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
        if (hostOf(finalUrl) != linkHost && !isYoutube(finalUrl) && seen.add(finalUrl)) {
            loadExtractor(finalUrl, referer, subtitleCallback, callback)
            found = true
        }

        val html = res.text.replace("\\/", "/")
        val page = res.document

        // 2) Iframes (same-site player pages are opened again, others go to extractors).
        page.select("iframe[src]").forEach {
            val src = it.absUrl("src")
            if (src.isNotBlank() && seen.add(src)) {
                if (hostOf(src) == linkHost) {
                    if (resolveServer(src, label, link, seen, subtitleCallback, callback)) found = true
                } else {
                    if (!isYoutube(src)) loadExtractor(src, link, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 3) JS / meta-refresh redirects.
        (jsRedirectRegex.findAll(html).map { it.groupValues[1] } +
                metaRefreshRegex.findAll(html).map { it.groupValues[1] })
            .filter { hostOf(it) != linkHost && !isYoutube(it) }
            .forEach {
                if (seen.add(it)) {
                    loadExtractor(it, link, subtitleCallback, callback)
                    found = true
                }
            }

        // 4) Direct streams inside the page.
        (listOfNotNull(decodeHiddenStream(html)?.takeIf { it.contains(".m3u8") }) + m3u8Regex.findAll(html).map { it.value }).distinct().forEach { u ->
            if (seen.add(u)) {
                // Player page uses "no-referrer", so send no Referer (same as Chrome).
                callback(
                    newExtractorLink(name, "$name - ${label.ifBlank { "HLS" }} (Auto)", u, ExtractorLinkType.M3U8) {
                        this.referer = ""
                        this.quality = Qualities.Unknown.value
                    }
                )
                // Also add each quality as a separate choice.
                try {
                    M3u8Helper.generateM3u8(name, u, "").forEach { callback(it) }
                } catch (e: Exception) {
                }
                found = true
            }
        }
        (listOfNotNull(decodeHiddenStream(html)?.takeIf { it.contains(".mp4") && !it.contains(".m3u8") }) + mp4Regex.findAll(html).map { it.value }).distinct().forEach { u ->
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
