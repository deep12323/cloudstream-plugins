package com.audiobookbay

import android.content.Context
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.net.URLDecoder
import java.net.URLEncoder

data class TorrentAudioFile(
    val id: Int,
    val path: String,
    val length: Long,
    val extension: String
) {
    val fileName: String get() = path.substringAfterLast('/')
}

data class TorrServerSession(
    val serverUrl: String,
    val hash: String,
    val files: List<TorrentAudioFile>
)

object TorrentStreamHelper {

    val AUDIO_EXTENSIONS = setOf(
        "m4b", "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "wma",
        "webm", "mp4", "mkv"
    )

    private val FALLBACK_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "http://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://tracker.dler.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "http://tracker.openbittorrent.com:80/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
        "udp://p4p.arenabg.com:1337/announce"
    )

    fun buildMagnet(infoHash: String, title: String, pageTrackers: List<String>): String {
        val cleanHash = infoHash.trim().lowercase()
        val encodedTitle = runCatching { URLEncoder.encode(title.trim(), "UTF-8") }.getOrNull() ?: "AudioBook"
        val sb = StringBuilder("magnet:?xt=urn:btih:$cleanHash&dn=$encodedTitle")

        val seen = mutableSetOf<String>()
        val allTrackers = pageTrackers + FALLBACK_TRACKERS
        for (tr in allTrackers) {
            val cleanTr = tr.trim()
            if (cleanTr.isBlank()) continue
            val key = cleanTr.lowercase()
            if (seen.add(key)) {
                val encodedTr = runCatching { URLEncoder.encode(cleanTr, "UTF-8") }.getOrNull() ?: cleanTr
                sb.append("&tr=").append(encodedTr)
            }
        }
        return sb.toString()
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = 1024.0
        val mb = kb * 1024.0
        val gb = mb * 1024.0
        return when {
            bytes >= gb -> String.format("%.2f GB", bytes / gb)
            bytes >= mb -> String.format("%.1f MB", bytes / mb)
            bytes >= kb -> String.format("%.0f KB", bytes / kb)
            else -> "$bytes B"
        }
    }

    fun cleanChapterDisplayName(rawPath: String): String {
        val name = rawPath.substringAfterLast('/')
        return name.substringBeforeLast('.').ifBlank { name }
    }

    /**
     * Natural numeric-aware comparator so "Chapter 2.mp3" sorts before "Chapter 10.mp3".
     */
    val naturalOrderComparator = Comparator<String> { a, b ->
        var ia = 0
        var ib = 0
        while (ia < a.length && ib < b.length) {
            val ca = a[ia]
            val cb = b[ib]
            if (ca.isDigit() && cb.isDigit()) {
                var endA = ia
                while (endA < a.length && a[endA].isDigit()) endA++
                var endB = ib
                while (endB < b.length && b[endB].isDigit()) endB++

                val numA = a.substring(ia, endA).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(ib, endB).trimStart('0').ifEmpty { "0" }
                if (numA.length != numB.length) return@Comparator numA.length.compareTo(numB.length)
                val cmp = numA.compareTo(numB)
                if (cmp != 0) return@Comparator cmp

                ia = endA
                ib = endB
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return@Comparator cmp
                ia++
                ib++
            }
        }
        a.length.compareTo(b.length)
    }

    suspend fun resolveTorrServerSession(
        context: Context?,
        magnetUrl: String,
        title: String,
        maxWaitSeconds: Int
    ): TorrServerSession? {
        return try {
            val serverUrl = getOrStartTorrServer(context) ?: return null
            val hash = addTorrent(serverUrl, magnetUrl, title) ?: return null
            val files = waitForAudioFiles(serverUrl, hash, maxWaitSeconds)
            if (files.isEmpty()) return null
            TorrServerSession(serverUrl, hash, files)
        } catch (_: Throwable) {
            null
        }
    }

    fun buildStreamUrl(serverUrl: String, fileName: String, hash: String, fileId: Int): String {
        val encodedName = runCatching { URLEncoder.encode(fileName, "UTF-8") }.getOrNull() ?: "audio.mp3"
        return "$serverUrl/stream/$encodedName?link=$hash&index=$fileId&play=true&preload=true"
    }

    private suspend fun isServerAlive(url: String): Boolean {
        return try {
            app.get("$url/echo", timeout = 1L).code == 200
        } catch (_: Throwable) {
            false
        }
    }

    private fun getInbuiltUrlFromReflection(): String? {
        return try {
            val torrentClass = Class.forName("com.lagradost.cloudstream3.ui.player.Torrent")
            val field: Field = torrentClass.getDeclaredField("TORRENT_SERVER_URL")
            field.isAccessible = true
            val url = field.get(null) as? String
            url?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private fun startInbuiltTorrServer(context: Context): String? {
        return try {
            val torrServerClass = Class.forName("torrServer.TorrServer")
            val startMethod: Method = torrServerClass.getDeclaredMethod(
                "startTorrentServer",
                String::class.java,
                Long::class.javaPrimitiveType
            )
            val port = startMethod.invoke(null, context.cacheDir.absolutePath, 0L) as? Long ?: return null
            if (port <= 0) return null

            val url = "http://127.0.0.1:$port"
            runCatching {
                val torrentClass = Class.forName("com.lagradost.cloudstream3.ui.player.Torrent")
                val field = torrentClass.getDeclaredField("TORRENT_SERVER_URL")
                field.isAccessible = true
                field.set(null, url)
            }
            runCatching {
                val addTrackersMethod = torrServerClass.getDeclaredMethod("addTrackers", String::class.java)
                addTrackersMethod.invoke(null, FALLBACK_TRACKERS.joinToString("\n"))
            }
            url
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun getOrStartTorrServer(context: Context?): String? {
        // 1. Check already-running CloudStream in-built TorrServer URL
        getInbuiltUrlFromReflection()?.let { url ->
            if (isServerAlive(url)) return url
        }

        // 2. Check standard local TorrServer port
        val externalUrl = "http://127.0.0.1:8090"
        if (isServerAlive(externalUrl)) return externalUrl

        // 3. Boot CloudStream's bundled TorrServer if Context is available
        if (context != null) {
            val bootedUrl = startInbuiltTorrServer(context.applicationContext ?: context)
            if (bootedUrl != null) {
                for (i in 1..15) {
                    if (isServerAlive(bootedUrl)) return bootedUrl
                    delay(150)
                }
            }
        }
        return null
    }

    private suspend fun addTorrent(serverUrl: String, magnetUrl: String, title: String): String? {
        return try {
            val payload = JSONObject().apply {
                put("action", "add")
                put("link", magnetUrl)
                put("title", title)
                put("save_to_db", false)
            }
            val resp = app.post("$serverUrl/torrents", json = payload, timeout = 8L).text
            JSONObject(resp).optString("hash").takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun waitForAudioFiles(
        serverUrl: String,
        hash: String,
        maxWaitSeconds: Int
    ): List<TorrentAudioFile> {
        val maxAttempts = (maxWaitSeconds * 2).coerceAtLeast(1)
        for (attempt in 1..maxAttempts) {
            delay(500)
            try {
                val payload = JSONObject().apply {
                    put("action", "get")
                    put("hash", hash)
                }
                val respText = app.post("$serverUrl/torrents", json = payload, timeout = 5L).text
                val json = JSONObject(respText)
                if (!json.has("file_stats")) continue

                val stats = json.getJSONArray("file_stats")
                if (stats.length() == 0) continue

                val allFiles = mutableListOf<TorrentAudioFile>()
                for (i in 0 until stats.length()) {
                    val item = stats.getJSONObject(i)
                    val path = item.optString("path")
                    if (path.isBlank() || path.contains(".pad", ignoreCase = true)) continue
                    val ext = path.substringAfterLast('.', "").lowercase()
                    allFiles.add(
                        TorrentAudioFile(
                            id = item.getInt("id"),
                            path = path,
                            length = item.optLong("length", 0L),
                            extension = ext
                        )
                    )
                }

                val audioFiles = allFiles.filter { it.extension in AUDIO_EXTENSIONS }
                val chosen = audioFiles.ifEmpty { allFiles }
                return chosen.sortedWith { a, b -> naturalOrderComparator.compare(a.path, b.path) }
            } catch (_: Throwable) {
                // Retry until timeout
            }
        }
        return emptyList()
    }

    fun decodeParam(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
