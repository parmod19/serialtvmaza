package com.istream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLEncoder

class IStreamProvider : MainAPI() {
    // TODO: asli API base URL yahan daalo
    override var mainUrl = "https://example.com"
    override var name = "IStream"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TODO: agar app koi token/header bhejta hai to yahan add karo
    private val apiHeaders = mapOf(
        "User-Agent" to "okhttp/4.12.0"
    )

    private suspend fun getJson(url: String): String {
        val raw = app.get(url, headers = apiHeaders).text
        return IStreamCrypto.safeDecrypt(raw)
    }

    // TODO: asli JSON field names se badlo
    data class Item(
        val id: String? = null,
        val title: String? = null,
        val poster: String? = null
    )

    // TODO: asli endpoint daalo
    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val json = getJson(request.data)
        val items = parseJson<List<Item>>(json).map {
            newMovieSearchResponse(it.title ?: "", it.id ?: "", TvType.Movie) {
                this.posterUrl = it.poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val json = getJson("$mainUrl/search?q=$q") // TODO
        return parseJson<List<Item>>(json).map {
            newMovieSearchResponse(it.title ?: "", it.id ?: "", TvType.Movie) {
                this.posterUrl = it.poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val json = getJson("$mainUrl/detail?id=$url") // TODO
        val item = parseJson<Item>(json)
        return newMovieLoadResponse(item.title ?: "", url, TvType.Movie, url) {
            this.posterUrl = item.poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val json = getJson("$mainUrl/stream?id=$data") // TODO
        // TODO: json se video URL nikaalo, phir:
        // callback(newExtractorLink(name, name, videoUrl) { this.quality = Qualities.P1080.value })
        return true
    }
}
