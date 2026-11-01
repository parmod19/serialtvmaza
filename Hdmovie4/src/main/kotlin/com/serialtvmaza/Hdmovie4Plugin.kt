package com.serialtvmaza

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Hdmovie4Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Hdmovie4Provider())
    }
}