package com.cinefreak

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class CineFreakPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CineFreakProvider())
    }
}
