package io.github.afrizz19.statusbarlyric.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

class LyricsRepository(
    private val client: LrcMuxApiClient = LrcMuxApiClient(),
) {

    fun fetchLyrics(
        artist: String,
        title: String,
        album: String = "",
        durationMs: Long = 0L,
    ): LyricsResult? {
        val (cleanArtist, cleanTitle, isRemix) = normalizeMetadata(artist, title)

        val primary = runCatching {
            client.fetchLyrics(cleanArtist, cleanTitle, album, if (isRemix) 0L else durationMs)
        }.getOrNull() ?: if (cleanArtist.isNotBlank() && isRemix) {
            runCatching { client.fetchLyrics("", cleanTitle, "", 0L) }.getOrNull()
        } else if (cleanArtist != artist || cleanTitle != title) {
            runCatching { client.fetchLyrics(artist, title, album, durationMs) }.getOrNull()
        } else {
            null
        }

        if (primary != null && primary.lines.isNotEmpty()) {
            val stripped = TranslationFilter.strip(primary.lines)
            return if (stripped.size == primary.lines.size) primary else primary.copy(lines = stripped)
        }

        val fallback = fetchFromLrcLib(cleanArtist, cleanTitle, album, durationMs, isRemix) ?: return null
        if (fallback.lines.isEmpty()) return null

        val stripped = TranslationFilter.strip(fallback.lines)
        return if (stripped.size == fallback.lines.size) fallback else fallback.copy(lines = stripped)
    }

    private data class NormalizedTrack(
        val artist: String,
        val title: String,
        val isRemix: Boolean,
    )

    private fun normalizeMetadata(rawArtist: String, rawTitle: String): NormalizedTrack {
        val remixRegex = Regex(
            """(?i)\b(remix|vinahouse|nonstop|mashup|speed\s*up|slowed|reverb|lofi|lo-fi|dj|mix|cover|edit|beat|karaoke|instrumental|tiktok|tik\s*tok)\b"""
        )
        val isRemix = remixRegex.containsMatchIn(rawTitle) || remixRegex.containsMatchIn(rawArtist)

        var title = rawTitle
            .replace(Regex("""\s*(\||//)\s*.*$"""), "")
            .replace(
                Regex(
                    """[\(\[\{【「].*?(official|music\s*video|mv|lyric|lyrics|audio|video|hd|4k|8k|hq|visualizer|live|cover|remix|vinahouse|nonstop|mashup|lofi|lo-fi|dj|mix|speed\s*up|slowed|reverb|ver\.?|version|full|teaser|trailer|shorts|tiktok|tik\s*tok|prod\.|ft\.|feat\.|edit).*?[\)\]\}】」]""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            .replace(
                Regex(
                    """(?i)\b(official\s*(music\s*)?video|official\s*mv|official\s*audio|lyric(s)?\s*video|full\s*mv|audio\s*only|4k\s*remaster|hot\s*tik\s*tok|nhạc\s*trẻ|hd)\b|#\S+""",
                ),
                "",
            )
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '–', '—', ':')
            .trim()

        var artist = rawArtist
            .replace(Regex("""(?i)\s*-\s*Topic$"""), "")
            .replace(Regex("""(?i)\b(official|vevo|channel|music|remix|dj|tv|media|entertainment)\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val parts = title.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            val left = parts[0].trim()
            val right = parts[1].trim()
            if (remixRegex.containsMatchIn(right)) {
                title = left
            } else if (remixRegex.containsMatchIn(left)) {
                title = right
            } else if (artist.isBlank()) {
                artist = left
                title = right
            } else {
                title = right
            }
        }

        title = title
            .replace(
                Regex("""(?i)\b(remix|vinahouse|nonstop|mashup|speed\s*up|slowed|reverb|lofi|lo-fi|cover|edit|ver\.?\s*\d*|version)\b"""),
                "",
            )
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '–', '—', ':')
            .trim()

        return NormalizedTrack(
            artist = if (isRemix) "" else artist,
            title = title.ifBlank { rawTitle.trim() },
            isRemix = isRemix,
        )
    }

    private fun fetchFromLrcLib(
        artist: String,
        title: String,
        album: String,
        durationMs: Long,
        isRemix: Boolean,
    ): LyricsResult? {
        if (title.isBlank()) return null

        if (artist.isNotBlank() && !isRemix) {
            val params = buildList {
                add("artist_name=${encode(artist)}")
                add("track_name=${encode(title)}")
                if (album.isNotBlank()) add("album_name=${encode(album)}")
                if (durationMs > 0L) add("duration=${durationMs / 1000L}")
            }.joinToString("&")

            val exactJson = httpGet("https://lrclib.net/api/get?$params")
            if (exactJson != null) {
                parseLrcLibObject(JSONObject(exactJson))?.let { return it }
            }
        }

        if (artist.isNotBlank()) {
            searchLrcLib("$artist $title", if (isRemix) 0L else durationMs)?.let { return it }
        }

        return searchLrcLib(title, if (isRemix) 0L else durationMs)
    }

    private fun searchLrcLib(query: String, durationMs: Long): LyricsResult? {
        val searchJson = httpGet("https://lrclib.net/api/search?q=${encode(query.trim())}") ?: return null
        val arr = runCatching { JSONArray(searchJson) }.getOrNull() ?: return null
        val targetSec = if (durationMs > 0L) durationMs / 1000.0 else -1.0
        var bestResult: LyricsResult? = null
        var bestDiff = Double.MAX_VALUE
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val parsed = parseLrcLibObject(obj) ?: continue
            if (targetSec <= 0.0) return parsed
            val dur = obj.optDouble("duration", -1.0)
            val diff = if (dur > 0.0) abs(dur - targetSec) else 9999.0
            if (diff < bestDiff) {
                bestDiff = diff
                bestResult = parsed
            }
        }
        return bestResult
    }

    private fun parseLrcLibObject(obj: JSONObject): LyricsResult? {
        val synced = obj.optString("syncedLyrics", "").trim()
        if (synced.isEmpty() || synced == "null") return null
        val lines = parseLrcText(synced)
        if (lines.isEmpty()) return null
        return LyricsResult(
            lines = lines,
            level = SyncLevel.LINE,
            source = "lrclib",
        )
    }

    private fun parseLrcText(lrc: String): List<SyncedLine> {
        val tagRegex = Regex("""\[(\d{1,3}):(\d{2})(?:\.(\d{1,3}))?]""")
        val entries = mutableListOf<SyncedLine>()

        for (rawLine in lrc.lineSequence()) {
            val matches = tagRegex.findAll(rawLine).toList()
            if (matches.isEmpty()) continue
            val text = rawLine.replace(tagRegex, "").trim()
            for (m in matches) {
                val min = m.groupValues[1].toLongOrNull() ?: continue
                val sec = m.groupValues[2].toLongOrNull() ?: continue
                val fracRaw = m.groupValues[3]
                val msPart = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                val startMs = min * 60_000L + sec * 1000L + msPart
                entries.add(SyncedLine(startMs = startMs, text = text))
            }
        }

        return entries.sortedBy { it.startMs }
    }

    private fun httpGet(urlStr: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 5000
                setRequestProperty("User-Agent", "StatusBarLyric16/1.0 (Android)")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun encode(v: String): String = URLEncoder.encode(v, "UTF-8")
}
