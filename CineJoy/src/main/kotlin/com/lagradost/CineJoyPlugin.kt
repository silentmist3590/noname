package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class CineJoyPlugin : Plugin() {
    override fun load(context: Context) {
        // Đăng ký CineJoy Provider vào CloudStream
        registerMainAPI(CineJoyProvider())
    }
}
