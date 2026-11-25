package com.istreamfresh

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class IStreamFreshPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(IStreamFlare())
        registerExtractorAPI(Istreamcdn())
        registerExtractorAPI(Istreamjam())
        registerExtractorAPI(Iasbase())
    }
}
