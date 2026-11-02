package com.serialtvmaza

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DesiCinemasProvider : MainAPI() {
    override var mainUrl = "https://desicinemas.online"
    override var name = "desicinemas.online"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie)

    // Token mein User-Agent bandha hota hai, isliye har jagah yehi bhejna hai
    private val ua =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/movies/" to "Latest Movies",
        "/category/bollywood-movies/" to "Bollywood",
        "/category/hindi-dubbed-movies/" to "Hindi Dubbed",
        "/category/punjabi-movies/" to "Punjabi",
        "/category/gujarati-movies/" to "Gujarati",
        "/category/marathi/" to "Marathi",
        "/category/romance/" to "Romance",
        "/category/thriller/" to "Thriller",
    )

    // ---------------- Home ----------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = mainUrl + request.data
        val url = if (page <= 1) base else "${base}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("article.TPost")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    // ---------------- Search ----------------
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/?s=$q").document
        return doc.select("article.TPost")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ---------------- Load ----------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.Title")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore(" Full Movie")?.trim()
            ?: "Unknown"
        val poster = fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val plot = doc.select("div.Description > p")
            .firstOrNull { it.classNames().isEmpty() }?.text()?.trim()
        val year = Regex("(\\d{4})").find(doc.selectFirst("span.Date")?.text().orEmpty())
            ?.groupValues?.get(1)?.toIntOrNull()
        val tags = doc.select("p.Genre a").map { it.text() }.distinct()
        val recommendations = doc.select("div.TPostMv div.TPost")
            .mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = plot
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // ---------------- Links ----------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val hosts = doc.select("p.AAIco-dns").map { it.text().trim() }
        val links = doc.select("a.Button[href*=cinemas.php]").map { it.attr("href") }

        var found = false
        links.forEachIndexed { i, link ->
            val host = hosts.getOrNull(i)?.ifBlank { null } ?: "Server ${i + 1}"
            try {
                if (resolveLink(link, host, callback)) found = true
            } catch (e: Exception) {
                // is server mein dikkat, agla try karo
            }
        }
        return found
    }

    // groundbanks (bina referer) -> flow.tvlogy.to iframe (referer groundbanks) -> m3u8
    private suspend fun resolveLink(
        link: String,
        host: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val gbHtml = app.get(link, headers = mapOf("User-Agent" to ua)).text
        val iframe = Regex("<iframe[^>]*src=['\"]([^'\"]+)", RegexOption.IGNORE_CASE)
            .find(gbHtml)?.groupValues?.get(1) ?: return false

        val playerHtml = app.get(
            iframe,
            headers = mapOf("User-Agent" to ua),
            referer = "https://groundbanks.net/"
        ).text

        val fileRegex =
            Regex("\"(?:src|file)\"\\s*:\\s*\"(https?:[^\"]+\\.m3u8[^\"]*)\"")
        var m3u8 = fileRegex.find(playerHtml)?.groupValues?.get(1)
        if (m3u8 == null && playerHtml.contains("JuicyCodes.Run")) {
            val unpacked = decodeJuicy(playerHtml)
            m3u8 = unpacked?.let { fileRegex.find(it)?.groupValues?.get(1) }
        }
        val stream = m3u8?.replace("\\/", "/") ?: return false

        callback(
            newExtractorLink(this.name, host, stream, ExtractorLinkType.M3U8) {
                this.referer = "https://flow.tvlogy.to/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to ua)
            }
        )
        return true
    }

    // JuicyCodes.Run("..."+"...") -> base64 -> packed JS -> unpack
    private fun decodeJuicy(html: String): String? {
        val run = Regex("JuicyCodes\\.Run\\((.*?)\\);", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        val b64 = Regex("\"([^\"]*)\"").findAll(run).joinToString("") { it.groupValues[1] }
        val padded = b64 + "=".repeat((4 - b64.length % 4) % 4)
        val packed = String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)

        val m = Regex(
            "\\}\\('(.*)',(\\d+),(\\d+),'(.*?)'\\.split\\('\\|'\\)",
            RegexOption.DOT_MATCHES_ALL
        ).find(packed) ?: return null
        val p = m.groupValues[1]
        val a = m.groupValues[2].toInt()
        val c = m.groupValues[3].toInt()
        val k = m.groupValues[4].split("|")

        val dict = HashMap<String, String>()
        for (i in c - 1 downTo 0) {
            val key = encodeBase(i, a)
            dict[key] = k.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: key
        }
        return Regex("\\b\\w+\\b").replace(p) { dict[it.value] ?: it.value }
            .replace("\\'", "'")
    }

    private fun encodeBase(n: Int, a: Int): String {
        val r = n % a
        val head = if (n < a) "" else encodeBase(n / a, a)
        val tail = if (r > 35) (r + 29).toChar().toString() else r.toString(36)
        return head + tail
    }

    // ---------------- Helpers ----------------
    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href*=/movies/]") ?: return null
        val href = fixUrl(a.attr("href"))
        if (href.isBlank()) return null
        val img = selectFirst("img")
        val title = selectFirst(".Title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: a.attr("title").ifBlank { img?.attr("alt").orEmpty() }.trim()
        if (title.isBlank()) return null
        val poster = fixUrlNull(
            listOf("data-src", "data-lazy-src", "src")
                .map { img?.attr(it).orEmpty() }
                .firstOrNull { it.isNotBlank() && !it.startsWith("data:") && !it.endsWith(".svg") }
        )
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}