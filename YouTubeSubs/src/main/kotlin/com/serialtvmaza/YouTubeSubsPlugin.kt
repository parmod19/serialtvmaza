package com.serialtvmaza

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class YouTubeSubsPlugin : Plugin() {
    override fun load(context: Context) {
        YtStore.init(context)
        YtSettings.init(context)
        registerMainAPI(YouTubeSubsProvider())
        openSettings = { ctx -> YtUi.showAccounts(ctx) }
    }
}
