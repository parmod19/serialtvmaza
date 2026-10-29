package com.serialtvmaza

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SerialTvMazaPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(SerialTvMazaProvider())
    }
}
