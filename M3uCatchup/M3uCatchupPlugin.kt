package com.mycatchup

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class M3uCatchupPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(M3uCatchupProvider())
    }
}
