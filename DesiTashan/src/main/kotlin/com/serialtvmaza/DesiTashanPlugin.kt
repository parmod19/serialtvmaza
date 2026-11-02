package com.serialtvmaza

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DesiTashanPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DesiTashanProvider())
    }
}