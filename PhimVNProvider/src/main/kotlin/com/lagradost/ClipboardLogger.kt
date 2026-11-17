package com.phimvn

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CommonActivity

/**
 * Logger đặc thù cho PhimVN.
 *
 * - Tag cố định để filter logcat nhanh: adb logcat -s PhimVN
 * - Mỗi lần log → append vào buffer + tự động copy buffer vào Clipboard
 * - reset() xoá sạch → dùng ở đầu loadLinks để mỗi phiên play có log riêng
 * - Cap MAX_LINES để không bị OOM khi user browse nhiều phim
 * - Cap MAX_LINE_LENGTH để không bị kẹt URL hash dài
 */
object ClipboardLogger {

    private const val TAG = "PhimVN"
    private const val MAX_LINES = 400
    private const val MAX_LINE_LENGTH = 250
    private const val CLIP_LABEL = "PhimVN Log"

    private val buffer = mutableListOf<String>()

    /** Ghi log + copy buffer vào clipboard. Không throw nếu clipboard fail. */
    fun logAndCopy(tag: String, message: String) {
        val safe = if (message.length > MAX_LINE_LENGTH) {
            message.take(MAX_LINE_LENGTH) + "…"
        } else message

        val line = "[$tag] $safe"
        buffer.add(line)

        // Cắt bớt nếu vượt ngưỡng (bỏ 50 dòng cũ nhất)
        if (buffer.size > MAX_LINES) {
            buffer.subList(0, 50).clear()
        }

        Log.d(TAG, line)

        val ctx = CommonActivity.activity ?: return
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, buffer.joinToString("\n")))
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard write failed: ${t.message}")
        }
    }

    /** Xoá buffer + xoá clipboard. */
    fun reset() {
        buffer.clear()
        val ctx = CommonActivity.activity ?: return
        try {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("", ""))
        } catch (t: Throwable) {
            Log.e(TAG, "clipboard reset failed: ${t.message}")
        }
    }
}
