package com.lagradost

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

/** Ghi log nối tiếp và copy toàn bộ vào clipboard sau mỗi dòng (để dán gửi khi báo lỗi). */
object ClipboardLogger {
    private const val TAG = "PhimNguonC"
    private const val MAX_CHARS = 60_000
    private val buffer = StringBuilder()

    @Synchronized
    fun reset() {
        buffer.setLength(0)
    }

    @Synchronized
    fun logAndCopy(title: String, message: String) {
        val entry = "=== [$title] ===\n$message\n"
        Log.d(TAG, entry)
        buffer.append(entry).append('\n')
        if (buffer.length > MAX_CHARS) buffer.delete(0, buffer.length - MAX_CHARS)
        try {
            val ctx = CommonActivity.activity ?: return
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("PhimNguonC Log", buffer.toString()))
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard write failed: ${t.message}")
        }
    }
}
