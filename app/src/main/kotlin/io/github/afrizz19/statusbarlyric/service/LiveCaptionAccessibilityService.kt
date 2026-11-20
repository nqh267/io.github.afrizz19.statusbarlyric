package io.github.afrizz19.statusbarlyric.service

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs

class LiveCaptionAccessibilityService : AccessibilityService() {

    private val prefs: SharedPreferences by lazy { LyricPrefs.of(applicationContext) }
    private var lastSentText: String = ""
    private var lastSentAt: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != LIVE_CAPTION_PKG) return

        val root = rootInActiveWindow ?: event.source ?: return
        val lines = mutableListOf<String>()
        collectCaptions(root, lines)

        val caption = lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && !IGNORED_LABELS.contains(it.lowercase()) }
            .lastOrNull()
            .orEmpty()

        if (caption.isBlank()) return
        val now = System.currentTimeMillis()
        if (caption == lastSentText && now - lastSentAt < 1200L) return

        lastSentText = caption
        lastSentAt = now
        LyricPrefs.updateLiveCaption(prefs, caption, now)
    }

    private fun collectCaptions(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty() && node.className?.toString()?.contains("TextView") == true) {
            out.add(text)
        }
        for (i in 0 until node.childCount) {
            collectCaptions(node.getChild(i), out)
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    companion object {
        private const val LIVE_CAPTION_PKG = "com.google.android.as"
        private val IGNORED_LABELS = setOf(
            "live caption",
            "phụ đề trực tiếp",
            "captions",
            "settings",
            "cài đặt",
        )
    }
}
