package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ghi log trong bộ nhớ; [error] tự copy log gần nhất vào clipboard (kèm toast). */
object HubLog {
    private const val MAX_LINES = 120
    private const val MIN_COPY_INTERVAL_MS = 4000L

    private val lock = Any()
    private val lines = mutableListOf<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var appContext: Context? = null
    private var lastCopy = 0L

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun log(tag: String, msg: String) {
        synchronized(lock) {
            lines.add("${fmt.format(Date())} [$tag] ${msg.take(1500)}")
            while (lines.size > MAX_LINES) lines.removeAt(0)
        }
    }

    fun error(tag: String, msg: String, t: Throwable? = null) {
        val trace = t?.let { "\n" + it.stackTraceToString().take(2500) } ?: ""
        log(tag, "ERROR: $msg$trace")
        copyToClipboard("$tag: $msg")
    }

    private fun copyToClipboard(reason: String) {
        val ctx = appContext ?: return
        val now = System.currentTimeMillis()
        val text: String
        synchronized(lock) {
            if (now - lastCopy < MIN_COPY_INTERVAL_MS) return
            lastCopy = now
            text = "=== MoviesHubHD log ===\nReason: $reason\n\n" + lines.joinToString("\n")
        }
        Handler(Looper.getMainLooper()).post {
            try {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("MoviesHubHD log", text))
                Toast.makeText(ctx, "MoviesHubHD: đã copy log lỗi vào clipboard", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                // bỏ qua
            }
        }
    }
}
