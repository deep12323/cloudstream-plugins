package com.audiobookbay

import java.net.URLEncoder

object TorrentStreamHelper {

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
}
