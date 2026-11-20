package io.github.afrizz19.statusbarlyric.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches synced lyrics from lrcmux first, then automatically falls back to LRCLIB
 * after cleaning noisy YouTube / TikTok / social media titles via Regex.
 */
class LyricsRepository(
    private val client: LrcMuxApiClient = LrcMuxApiClient(),
) {

    fun fetchLyrics(
        artist: String,
        title: String,
        album: String = "",
        durationMs: Long = 0L,
    ): LyricsResult? {
        val (cleanArtist, cleanTitle) = normalizeMetadata(artist, title)

        // 1. Primary: Lrcmux (supports word-level karaoke + line-level)
        val primary = runCatching {
            client.fetchLyrics(cleanArtist, cleanTitle, album, durationMs)
        }.getOrNull() ?: if (cleanArtist != artist || cleanTitle != title) {
            runCatching { client.fetchLyrics(artist, title, album, durationMs) }.getOrNull()
        } else {
            null
        }

        if (primary != null && primary.lines.isNotEmpty()) {
            val stripped = TranslationFilter.strip(primary.lines)
            return if (stripped.size == primary.lines.size) primary else primary.copy(lines = stripped)
        }

        // 2. Fallback: LRCLIB (line-level LRC)
        val fallback = fetchFromLrcLib(cleanArtist, cleanTitle, album, durationMs) ?: return null
        if (fallback.lines.isEmpty()) return null

        val stripped = TranslationFilter.strip(fallback.lines)
        return if (stripped.size == fallback.lines.size) fallback else fallback.copy(lines = stripped)
    }

    private fun normalizeMetadata(rawArtist: String, rawTitle: String): Pair<String, String> {
        var title = rawTitle
            // Cut trailing channel/hashtag suffixes after | or //
            .replace(Regex("""\s*(||//)\s*.*$"""), "")
            // Remove bracketed / parenthesized tags like (Official MV), [Lyrics], (Remix), etc.
            .replace(
                Regex(
                    """[\(\[\{【「].*?(official|music\s*video|mv|lyric|lyrics|audio|video|hd|4k|8k|hq|visualizer|live|cover|remix|speed\s*up|slowed|reverb|ver\.?|version|full|teaser|trailer|shorts|tiktok|prod\.|ft\.|feat\.).*?[\)\]\}】」]""",
                    RegexOption.IGNORE_CASE,
                ),
                "",
            )
            // Remove standalone noisy keywords and hashtags
            .replace(
                Regex(
                    """(?i)\b(official\s*(music\s*)?video|official\s*mv|official\s*audio|lyric(s)?\s*video|full\s*mv|audio\s*only|4k\s*remaster|hd)\b|#\S+""",
                ),
                "",
            )
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .trim('-', '–', '—', ':')
            .trim()

        var artist = rawArtist
            .replace(Regex("""(?i)\s*-\s*Topic$"""), "")
            .replace(Regex("""(?i)\b(official|vevo|channel)\b"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        // Extract "Artist - Title" when artist metadata is empty or matches generic app/uploader
        if (artist.isBlank() || title.contains(" - ")) {
            val parts = title.split(Regex("""\s+[-–—]\s+"""), limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                if (artist.isBlank()) artist = parts[0].trim()
                title = parts[1].trim()
            }
        }

        return artist to title.ifBlank { rawTitle.trim() }
    }

    private fun fetchFromLrcLib(
        artist: String,
        title: String,
        album: String,
        durationMs: Long,
    ): LyricsResult? {
        if (title.isBlank()) return null

        // Try exact /api/get first when artist is available
        if (artist.isNotBlank()) {
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

        // Fall back to /api/search?q=...
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        val searchJson = httpGet("https://lrclib.net/api/search?q=${encode(query)}") ?: return null
        val arr = runCatching { JSONArray(searchJson) }.getOrNull() ?: return null
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseLrcLibObject(obj)?.let { return it }
        }
        return null
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
