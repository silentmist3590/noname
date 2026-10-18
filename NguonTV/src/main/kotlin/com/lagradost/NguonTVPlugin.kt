package com.nguontv

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NguonTVPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NguonTVProvider())
    }
}
