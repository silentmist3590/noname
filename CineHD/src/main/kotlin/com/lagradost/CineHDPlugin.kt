package com.cinehd

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineHDPlugin: Plugin() {
    override fun load(context: Context) {
        // Đăng ký Provider chính vào CloudStream 3
        registerMainAPI(CineHDProvider())
    }
}
