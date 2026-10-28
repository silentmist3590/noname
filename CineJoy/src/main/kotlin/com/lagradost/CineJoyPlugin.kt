package com.lagradost

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineJoyPlugin: Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider chính
        registerMainAPI(CineJoyProvider())
    }
}
