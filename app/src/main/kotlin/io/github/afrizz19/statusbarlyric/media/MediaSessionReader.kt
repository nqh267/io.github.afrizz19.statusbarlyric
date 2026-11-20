package io.github.afrizz19.statusbarlyric.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import io.github.afrizz19.statusbarlyric.xposed.HookPrefs

class MediaSessionReader(
    context: Context,
    private val notificationListener: ComponentName? = null,
) {

    private val sessionManager: MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager

    fun readActiveTrack(): TrackInfo? {
        val manager = sessionManager ?: return null
        val controllers = try {
            manager.getActiveSessions(notificationListener)
        } catch (e: SecurityException) {
            Log.w(TAG, "Not permitted to read media sessions", e)
            return null
        }

        val whitelist = HookPrefs.targetPackages(SystemClock.uptimeMillis())
        if (whitelist.isEmpty()) return null

        val supported = controllers.filter { it.packageName in whitelist }
        if (supported.isEmpty()) return null

        val chosen = supported.firstOrNull { it.isPlaying() } ?: supported.first()
        return chosen.toTrackInfo()
    }

    private fun MediaController.isPlaying(): Boolean =
        playbackState?.state == PlaybackState.STATE_PLAYING

    private fun MediaController.toTrackInfo(): TrackInfo? {
        val metadata = metadata ?: return null
        val state = playbackState

        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty()
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        if (title.isBlank()) return null

        val isPlaying = state?.state == PlaybackState.STATE_PLAYING
        val trackKey = "$packageName|${artist.trim()}|${title.trim()}"
        val anchor = anchorFor(
            trackKey = trackKey,
            reported = (state?.position ?: 0L).coerceAtLeast(0),
            reportedAt = state?.lastPositionUpdateTime?.takeIf { it > 0 }
                ?: SystemClock.elapsedRealtime(),
            isPlaying = isPlaying,
        )

        return TrackInfo(
            packageName = packageName,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            durationMs = duration.coerceAtLeast(0),
            positionMs = anchor.positionMs,
            isPlaying = isPlaying,
            positionSampledAt = anchor.sampledAt,
            speed = state?.playbackSpeed ?: 1f,
        )
    }

    private class Anchor(val positionMs: Long, val sampledAt: Long)

    private fun anchorFor(
        trackKey: String,
        reported: Long,
        reportedAt: Long,
        isPlaying: Boolean,
    ): Anchor {
        val now = SystemClock.elapsedRealtime()

        if (trackKey != anchorTrackKey) {
            anchorTrackKey = trackKey
            anchorPosition = reported
            anchorAt = reportedAt
            return Anchor(reported, reportedAt)
        }

        val expected = if (isPlaying) {
            anchorPosition + (now - anchorAt).coerceAtLeast(0)
        } else {
            anchorPosition
        }

        val bogus = isPlaying &&
            reported <= NEAR_ZERO_MS &&
            expected > BOGUS_REWIND_MS

        if (!bogus) {
            anchorPosition = reported
            anchorAt = reportedAt
        }
        return Anchor(anchorPosition, anchorAt)
    }

    private var anchorTrackKey: String? = null
    private var anchorPosition = 0L
    private var anchorAt = 0L

    companion object {
        private const val TAG = "MediaSessionReader"
        private const val NEAR_ZERO_MS = 1500L
        private const val BOGUS_REWIND_MS = 5000L
    }
}
