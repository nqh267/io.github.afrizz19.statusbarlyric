package io.github.afrizz19.statusbarlyric.xposed

import io.github.afrizz19.statusbarlyric.BuildConfig
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge

object HookPrefs {

    private const val TAG = "StatusBarLyric16"
    private const val RELOAD_MS = 400L

    private var prefs: XSharedPreferences? = null
    private var lastReload = 0L
    private var warned = false

    private fun current(nowMs: Long): XSharedPreferences? {
        val existing = prefs ?: runCatching {
            XSharedPreferences(BuildConfig.APPLICATION_ID, LyricPrefs.FILE)
        }.getOrNull()?.also { prefs = it }

        if (existing == null) return null

        if (!existing.file.canRead()) {
            if (!warned) {
                warned = true
                XposedBridge.log(
                    "$TAG: settings not readable (${existing.file}); using defaults. " +
                        "Open the app once with the module enabled."
                )
            }
            return null
        }

        if (nowMs - lastReload >= RELOAD_MS) {
            lastReload = nowMs
            runCatching { existing.reload() }
        }
        return existing
    }

    fun targetPackages(nowMs: Long): Set<String> {
        val p = current(nowMs) ?: return LyricPrefs.DEFAULT_PACKAGES
        if (!p.getBoolean(LyricPrefs.KEY_TARGET_CUSTOMIZED, false)) {
            return LyricPrefs.DEFAULT_PACKAGES
        }
        val raw = p.getString(LyricPrefs.KEY_TARGET_PACKAGES, "").orEmpty()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Returns fresh Live Caption text if updated within the last 5 seconds, else null. */
    fun recentLiveCaption(nowRealtimeMs: Long, uptimeMs: Long): String? {
        val p = current(uptimeMs) ?: return null
        val ts = p.getLong(LyricPrefs.KEY_LIVE_CAPTION_TS, 0L)
        if (ts <= 0L || nowRealtimeMs - ts > 5000L) return null
        return p.getString(LyricPrefs.KEY_LIVE_CAPTION_TEXT, null)?.takeIf { it.isNotBlank() }
    }

    fun style(nowMs: Long): LyricPrefs.Style {
        val prefs = current(nowMs) ?: return LyricPrefs.Style.DEFAULT
        return LyricPrefs.Style(
            textColor = prefs.getInt(LyricPrefs.KEY_TEXT_COLOR, LyricPrefs.FOLLOW_CLOCK),
            shadowOn = prefs.getBoolean(LyricPrefs.KEY_SHADOW_ON, false),
            shadowColor = prefs.getInt(LyricPrefs.KEY_SHADOW_COLOR, BLACK),
            shadowRadiusDp = prefs.getFloat(LyricPrefs.KEY_SHADOW_RADIUS, LyricPrefs.DEF_SHADOW_RADIUS),
            shadowDxDp = prefs.getFloat(LyricPrefs.KEY_SHADOW_DX, LyricPrefs.DEF_SHADOW_DX),
            shadowDyDp = prefs.getFloat(LyricPrefs.KEY_SHADOW_DY, LyricPrefs.DEF_SHADOW_DY),
            outlineOn = prefs.getBoolean(LyricPrefs.KEY_OUTLINE_ON, false),
            outlineColor = prefs.getInt(LyricPrefs.KEY_OUTLINE_COLOR, BLACK),
            outlineWidthDp = prefs.getFloat(LyricPrefs.KEY_OUTLINE_WIDTH, LyricPrefs.DEF_OUTLINE_WIDTH),
            syncOffsetMs = prefs.getInt(LyricPrefs.KEY_SYNC_OFFSET, LyricPrefs.DEF_SYNC_OFFSET),
            mode = prefs.getInt(LyricPrefs.KEY_LYRIC_MODE, LyricPrefs.MODE_LINE),
            wordDimColor = prefs.getInt(LyricPrefs.KEY_WORD_DIM_COLOR, LyricPrefs.DERIVE_DIM),
            wordDimAlpha = prefs.getFloat(LyricPrefs.KEY_WORD_DIM_ALPHA, LyricPrefs.DEF_WORD_DIM_ALPHA),
            wordSmooth = prefs.getBoolean(LyricPrefs.KEY_WORD_SMOOTH, false),
        )
    }

    private val BLACK = 0xFF000000.toInt()
}
