package com.serialtvmaza

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class DesiCinemasPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(DesiCinemasProvider())
    }
}