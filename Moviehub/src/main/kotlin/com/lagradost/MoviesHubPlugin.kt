package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MoviesHubPlugin : Plugin() {
    override fun load(context: Context) {
        HubLog.init(context)
        registerMainAPI(MoviesHubProvider())
    }
}
