package io.github.afrizz19.statusbarlyric.prefs

import android.content.Context
import android.content.SharedPreferences

object LyricPrefs {

    const val FILE = "lyric_settings"

    const val KEY_TEXT_COLOR = "text_color"
    const val KEY_SHADOW_ON = "shadow_on"
    const val KEY_SHADOW_COLOR = "shadow_color"
    const val KEY_SHADOW_RADIUS = "shadow_radius_dp"
    const val KEY_SHADOW_DX = "shadow_dx_dp"
    const val KEY_SHADOW_DY = "shadow_dy_dp"

    const val KEY_OUTLINE_ON = "outline_on"
    const val KEY_OUTLINE_COLOR = "outline_color"
    const val KEY_OUTLINE_WIDTH = "outline_width_dp"

    const val KEY_SYNC_OFFSET = "sync_offset_ms"
    const val KEY_LYRIC_MODE = "lyric_mode"
    const val KEY_WORD_DIM_COLOR = "word_dim_color"
    const val KEY_WORD_DIM_ALPHA = "word_dim_alpha"
    const val KEY_WORD_SMOOTH = "word_smooth"

    /** Comma-separated package whitelist (String is rock-solid across XSharedPreferences). */
    const val KEY_TARGET_PACKAGES = "target_packages_csv"
    const val KEY_TARGET_CUSTOMIZED = "target_packages_customized"

    /** Live Caption fallback text bridged via shared prefs for SystemUI hook. */
    const val KEY_LIVE_CAPTION_TEXT = "live_caption_text"
    const val KEY_LIVE_CAPTION_TS = "live_caption_ts"

    const val FOLLOW_CLOCK = 0
    const val DERIVE_DIM = 0
    const val MODE_LINE = 0
    const val MODE_WORD = 1

    private const val BLACK = 0xFF000000.toInt()

    const val DEF_SHADOW_RADIUS = 2f
    const val DEF_SHADOW_DX = 0f
    const val DEF_SHADOW_DY = 1f
    const val DEF_OUTLINE_WIDTH = 1f
    const val DEF_SYNC_OFFSET = 0
    const val DEF_WORD_DIM_ALPHA = 0.45f

    const val SYNC_OFFSET_MIN = -10000
    const val SYNC_OFFSET_MAX = 10000

    val DEFAULT_PACKAGES = setOf(
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "app.revanced.android.apps.youtube.music",
        "com.google.android.youtube",
        "app.revanced.android.youtube",
    )

    val PRESETS = listOf(
        0xFFFFFFFF.toInt(),
        0xFF000000.toInt(),
        0xFFFF5252.toInt(),
        0xFFFF9800.toInt(),
        0xFFFFEB3B.toInt(),
        0xFF4CAF50.toInt(),
        0xFF00E5FF.toInt(),
        0xFF448AFF.toInt(),
        0xFFB388FF.toInt(),
        0xFFFF80AB.toInt(),
    )

    @Volatile
    var sharedWithHook: Boolean = false
        private set

    @Suppress("DEPRECATION")
    fun of(context: Context): SharedPreferences =
        (runCatching { context.getSharedPreferences(FILE, Context.MODE_WORLD_READABLE) }
            .onSuccess { sharedWithHook = true }
            .onFailure { sharedWithHook = false }
            .getOrElse { context.getSharedPreferences(FILE, Context.MODE_PRIVATE) })
            .also {
                if (!it.contains(KEY_TEXT_COLOR)) setTextColor(it, FOLLOW_CLOCK)
            }

    fun textColor(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_TEXT_COLOR, FOLLOW_CLOCK)

    fun setTextColor(prefs: SharedPreferences, color: Int) {
        prefs.edit().putInt(KEY_TEXT_COLOR, color).apply()
    }

    fun targetPackages(prefs: SharedPreferences): Set<String> {
        if (!prefs.getBoolean(KEY_TARGET_CUSTOMIZED, false)) {
            return DEFAULT_PACKAGES
        }
        val raw = prefs.getString(KEY_TARGET_PACKAGES, "").orEmpty()
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun setTargetPackages(prefs: SharedPreferences, packages: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_TARGET_CUSTOMIZED, true)
            .putString(KEY_TARGET_PACKAGES, packages.sorted().joinToString(","))
            .apply()
    }

    fun updateLiveCaption(prefs: SharedPreferences, text: String, timestampMs: Long) {
        prefs.edit()
            .putString(KEY_LIVE_CAPTION_TEXT, text)
            .putLong(KEY_LIVE_CAPTION_TS, timestampMs)
            .apply()
    }

    data class Style(
        val textColor: Int,
        val shadowOn: Boolean,
        val shadowColor: Int,
        val shadowRadiusDp: Float,
        val shadowDxDp: Float,
        val shadowDyDp: Float,
        val outlineOn: Boolean,
        val outlineColor: Int,
        val outlineWidthDp: Float,
        val syncOffsetMs: Int,
        val mode: Int,
        val wordDimColor: Int,
        val wordDimAlpha: Float,
        val wordSmooth: Boolean,
    ) {
        companion object {
            val DEFAULT = Style(
                textColor = FOLLOW_CLOCK,
                shadowOn = false,
                shadowColor = BLACK,
                shadowRadiusDp = DEF_SHADOW_RADIUS,
                shadowDxDp = DEF_SHADOW_DX,
                shadowDyDp = DEF_SHADOW_DY,
                outlineOn = false,
                outlineColor = BLACK,
                outlineWidthDp = DEF_OUTLINE_WIDTH,
                syncOffsetMs = DEF_SYNC_OFFSET,
                mode = MODE_LINE,
                wordDimColor = DERIVE_DIM,
                wordDimAlpha = DEF_WORD_DIM_ALPHA,
                wordSmooth = false,
            )
        }
    }

    fun dimColorFor(style: Style, sungColor: Int): Int {
        if (style.wordDimColor != DERIVE_DIM) return style.wordDimColor
        val alpha = (((sungColor ushr 24) and 0xFF) * style.wordDimAlpha)
            .toInt().coerceIn(0, 255)
        return (alpha shl 24) or (sungColor and 0x00FFFFFF)
    }

    fun style(prefs: SharedPreferences): Style = Style(
        textColor = prefs.getInt(KEY_TEXT_COLOR, FOLLOW_CLOCK),
        shadowOn = prefs.getBoolean(KEY_SHADOW_ON, false),
        shadowColor = prefs.getInt(KEY_SHADOW_COLOR, BLACK),
        shadowRadiusDp = prefs.getFloat(KEY_SHADOW_RADIUS, DEF_SHADOW_RADIUS),
        shadowDxDp = prefs.getFloat(KEY_SHADOW_DX, DEF_SHADOW_DX),
        shadowDyDp = prefs.getFloat(KEY_SHADOW_DY, DEF_SHADOW_DY),
        outlineOn = prefs.getBoolean(KEY_OUTLINE_ON, false),
        outlineColor = prefs.getInt(KEY_OUTLINE_COLOR, BLACK),
        outlineWidthDp = prefs.getFloat(KEY_OUTLINE_WIDTH, DEF_OUTLINE_WIDTH),
        syncOffsetMs = prefs.getInt(KEY_SYNC_OFFSET, DEF_SYNC_OFFSET),
        mode = prefs.getInt(KEY_LYRIC_MODE, MODE_LINE),
        wordDimColor = prefs.getInt(KEY_WORD_DIM_COLOR, DERIVE_DIM),
        wordDimAlpha = prefs.getFloat(KEY_WORD_DIM_ALPHA, DEF_WORD_DIM_ALPHA),
        wordSmooth = prefs.getBoolean(KEY_WORD_SMOOTH, false),
    )

    fun save(prefs: SharedPreferences, style: Style) {
        prefs.edit()
            .putInt(KEY_TEXT_COLOR, style.textColor)
            .putBoolean(KEY_SHADOW_ON, style.shadowOn)
            .putInt(KEY_SHADOW_COLOR, style.shadowColor)
            .putFloat(KEY_SHADOW_RADIUS, style.shadowRadiusDp)
            .putFloat(KEY_SHADOW_DX, style.shadowDxDp)
            .putFloat(KEY_SHADOW_DY, style.shadowDyDp)
            .putBoolean(KEY_OUTLINE_ON, style.outlineOn)
            .putInt(KEY_OUTLINE_COLOR, style.outlineColor)
            .putFloat(KEY_OUTLINE_WIDTH, style.outlineWidthDp)
            .putInt(KEY_SYNC_OFFSET, style.syncOffsetMs)
            .putInt(KEY_LYRIC_MODE, style.mode)
            .putInt(KEY_WORD_DIM_COLOR, style.wordDimColor)
            .putFloat(KEY_WORD_DIM_ALPHA, style.wordDimAlpha)
            .putBoolean(KEY_WORD_SMOOTH, style.wordSmooth)
            .apply()
    }

    fun parseHex(input: String): Int? {
        val hex = input.trim().removePrefix("#")
        val expanded = when (hex.length) {
            3 -> "FF" + hex.map { "$it$it" }.joinToString("")
            6 -> "FF$hex"
            8 -> hex
            else -> return null
        }
        val value = expanded.toLongOrNull(16) ?: return null
        return value.toInt()
    }

    fun toHex(color: Int): String = String.format("#%08X", color)
}
