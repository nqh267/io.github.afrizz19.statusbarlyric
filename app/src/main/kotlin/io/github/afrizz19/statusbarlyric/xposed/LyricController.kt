package io.github.afrizz19.statusbarlyric.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import io.github.afrizz19.statusbarlyric.api.LyricsRepository
import io.github.afrizz19.statusbarlyric.media.MediaSessionReader
import io.github.afrizz19.statusbarlyric.media.TrackInfo
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs
import io.github.afrizz19.statusbarlyric.sync.ActiveLine
import io.github.afrizz19.statusbarlyric.sync.LyricSyncEngine
import io.github.afrizz19.statusbarlyric.sync.WordProgressResolver
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.util.concurrent.Executors

object LyricController {

    private const val TAG = "StatusBarLyric16"
    private const val TICK_MS = 100L
    private const val POLL_MS = 500L
    private const val LIVE_CAPTION_DELAY_MS = 2500L

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "lyric-fetch") }

    private val repo = LyricsRepository()
    private var reader: MediaSessionReader? = null

    private class Slot(lyric: LyricView, clock: TextView) {
        val lyric = WeakReference(lyric)
        val clock = WeakReference(clock)
        fun alive() = lyric.get() != null
    }

    private val slots = mutableListOf<Slot>()

    private var currentTrackKey: String? = null
    private var trackStartedElapsedMs: Long = 0L
    private var lyricsMissingForTrack: Boolean = false
    private var engine: LyricSyncEngine? = null
    private var fetchToken = 0
    private var shownText: String? = null

    private var running = false
    private var cachedTrack: TrackInfo? = null
    private var lastPollMs = 0L

    fun start(context: Context, lyricView: LyricView, clock: TextView) {
        slots.removeAll { !it.alive() }
        slots.add(Slot(lyricView, clock))
        if (reader == null) reader = MediaSessionReader(context)

        shownText?.let { applyToSlots(it) }

        if (running) return
        running = true
        main.post(tick)
        XposedBridge.log("$TAG: controller started (${slots.size} slot(s))")
    }

    private val tick = object : Runnable {
        override fun run() {
            try {
                update()
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: tick failed: ${android.util.Log.getStackTraceString(t)}")
            }
            slots.removeAll { !it.alive() }
            if (slots.isEmpty()) {
                running = false
                XposedBridge.log("$TAG: controller stopped (no views)")
                return
            }
            main.postDelayed(this, TICK_MS)
        }
    }

    private fun update() {
        val now = SystemClock.elapsedRealtime()
        val style = applyStyle()

        val track = if (now - lastPollMs >= POLL_MS) {
            lastPollMs = now
            reader?.readActiveTrack().also { cachedTrack = it }
        } else {
            cachedTrack
        }

        if (track == null || !track.isPlaying) {
            clearTrack()
            render(null)
            return
        }

        if (track.trackKey != currentTrackKey) onTrackChanged(track, now)

        val currentEngine = engine
        if (currentEngine != null) {
            val pos = track.estimatedPositionMs(now) + style.syncOffsetMs
            val active = currentEngine.activeLineAt(pos)
            val text = active?.text?.takeIf { it.isNotBlank() }
            render(text)
            pushWordProgress(style, active, pos)
            return
        }

        // Fallback to Live Caption after 2.5s when neither Lrcmux nor LRCLIB has lyrics
        if (lyricsMissingForTrack && now - trackStartedElapsedMs >= LIVE_CAPTION_DELAY_MS) {
            val caption = HookPrefs.recentLiveCaption(
                nowRealtimeMs = System.currentTimeMillis(),
                uptimeMs = SystemClock.uptimeMillis(),
            )
            for (slot in slots) slot.lyric.get()?.clearWordProgress()
            render(caption)
        }
    }

    private fun pushWordProgress(style: LyricPrefs.Style, active: ActiveLine?, pos: Long) {
        val words = active?.words
        if (style.mode != LyricPrefs.MODE_WORD || words.isNullOrEmpty()) {
            for (slot in slots) slot.lyric.get()?.clearWordProgress()
            return
        }

        val offset = WordProgressResolver.charOffsetAt(words, pos)
        for (slot in slots) {
            val lyric = slot.lyric.get() ?: continue
            val sung = if (style.textColor == LyricPrefs.FOLLOW_CLOCK) {
                slot.clock.get()?.currentTextColor ?: continue
            } else {
                style.textColor
            }
            lyric.setWordProgress(
                offset = offset,
                forText = active.text,
                sungColor = sung,
                unsungColor = LyricPrefs.dimColorFor(style, sung),
                smooth = style.wordSmooth,
                words = words,
                positionMs = pos,
            )
        }
    }

    private fun onTrackChanged(track: TrackInfo, nowElapsedMs: Long) {
        currentTrackKey = track.trackKey
        trackStartedElapsedMs = nowElapsedMs
        lyricsMissingForTrack = false
        engine = null
        render(null)

        val token = ++fetchToken
        val artist = track.artist
        val title = track.title
        val album = track.album
        val durationMs = track.durationMs

        io.execute {
            val result = repo.fetchLyrics(artist, title, album, durationMs)
            main.post {
                if (token != fetchToken) return@post
                if (result == null || result.lines.isEmpty()) {
                    lyricsMissingForTrack = true
                    XposedBridge.log("$TAG: no lyrics for $artist - $title (Live Caption fallback armed)")
                    return@post
                }
                lyricsMissingForTrack = false
                engine = LyricSyncEngine(result.lines)
                XposedBridge.log(
                    "$TAG: loaded ${result.lines.size} lines for $artist - $title " +
                        "(level=${result.level} source=${result.source ?: "?"})"
                )
            }
        }
    }

    private fun clearTrack() {
        currentTrackKey = null
        trackStartedElapsedMs = 0L
        lyricsMissingForTrack = false
        engine = null
        cachedTrack = null
        fetchToken++
    }

    private fun applyStyle(): LyricPrefs.Style {
        val style = HookPrefs.style(SystemClock.uptimeMillis())
        for (slot in slots) {
            val lyric = slot.lyric.get() ?: continue
            val color = if (style.textColor == LyricPrefs.FOLLOW_CLOCK) {
                slot.clock.get()?.currentTextColor ?: continue
            } else {
                style.textColor
            }
            lyric.applyStyle(style, color)
        }
        return style
    }

    private fun render(text: String?) {
        if (text == shownText) return
        val first = shownText == null && text != null
        shownText = text
        applyToSlots(text)
        if (first) XposedBridge.log("$TAG: rendering to ${slots.size} slot(s)")
    }

    private fun applyToSlots(text: String?) {
        for (slot in slots) {
            val lyric = slot.lyric.get() ?: continue
            val clock = slot.clock.get()

            if (text == null) {
                lyric.hideLyric { clock?.visibility = View.VISIBLE }
                continue
            }

            clock?.visibility = View.GONE
            lyric.setLyric(text)
        }
    }
}
