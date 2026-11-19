package com.serialtvmaza

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class VkSpeed : ExtractorApi() {
    override val name = "VKSpeed"
    override val mainUrl = "https://vkspeed.com"
    override val requiresReferer = false

    private val sourceRegex =
        Regex("""file\s*:\s*["'](https?://[^"']+)["']\s*,\s*label\s*:\s*["']([^"']*)["']""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = app.get(url, referer = referer ?: "https://articleweb.xyz/").text
        val js = try { getAndUnpack(html) } catch (e: Exception) { html }
        val text = if (js.contains("sources")) js else html

        sourceRegex.findAll(text).forEach { m ->
            val link = m.groupValues[1]
            val label = m.groupValues[2]
            callback(
                newExtractorLink(name, "$name $label", link, ExtractorLinkType.VIDEO) {
                    this.referer = "https://vkspeed.com/"
                    this.quality = getQualityFromName(label)
                }
            )
        }
    }
}