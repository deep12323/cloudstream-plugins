package com.desiremovies

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

open class DesireMoviesHubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.pw"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolveHubCloud(url, null, null, null, callback)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun getQualityInt(resolution: String?): Int {
            if (resolution == null) return Qualities.Unknown.value
            val res = resolution.lowercase()
            return when {
                res.contains("2160") || res.contains("4k") -> Qualities.P2160.value
                res.contains("1440") -> Qualities.P1440.value
                res.contains("1080") -> Qualities.P1080.value
                res.contains("720") -> Qualities.P720.value
                res.contains("480") -> Qualities.P480.value
                res.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }
        }

        fun parseSizeToBytes(sizeStr: String?, fallbackQuality: Int = 0): Long {
            if (!sizeStr.isNullOrBlank()) {
                // Remove whitespace inside numbers like "58 0 MB" -> "580 MB"
                val normalized = sizeStr.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
                val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\b""", RegexOption.IGNORE_CASE).find(normalized)
                if (match != null) {
                    val num = match.groupValues[1].toDoubleOrNull() ?: 0.0
                    val unit = match.groupValues[2].uppercase()
                    val bytes = when (unit) {
                        "TB", "TIB" -> (num * 1024L * 1024L * 1024L * 1024L).toLong()
                        "GB", "GIB" -> (num * 1024L * 1024L * 1024L).toLong()
                        "MB", "MIB" -> (num * 1024L * 1024L).toLong()
                        "KB", "KIB" -> (num * 1024L).toLong()
                        else -> num.toLong()
                    }
                    if (bytes > 0L) return bytes
                }
            }
            return when (fallbackQuality) {
                Qualities.P2160.value -> 8_000_000_000L
                Qualities.P1440.value -> 4_000_000_000L
                Qualities.P1080.value -> 2_500_000_000L
                Qualities.P720.value -> 1_000_000_000L
                Qualities.P480.value -> 450_000_000L
                Qualities.P360.value -> 250_000_000L
                else -> 0L
            }
        }

        fun cleanSizeString(rawSize: String?): String? {
            if (rawSize.isNullOrBlank()) return null
            val normalized = rawSize.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
            val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\b""", RegexOption.IGNORE_CASE).find(normalized)
            return match?.let {
                val num = it.groupValues[1]
                val unit = it.groupValues[2].uppercase()
                "$num $unit"
            }
        }

        fun formatLinkTitle(
            server: String,
            qualityStr: String?,
            sizeStr: String?,
            filenameOrTitle: String?
        ): Pair<String, Int> {
            val fnMatch = Regex("""\b([A-Za-z0-9._-]+\.(?:mkv|mp4))\b""", RegexOption.IGNORE_CASE).find(filenameOrTitle ?: "")
            val targetFilename = fnMatch?.groupValues?.get(1) ?: ""

            // 1. Resolution & QualityInt extraction
            // First check the clean video filename directly if present
            var resMatch = Regex("""\b(2160p|4k|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE).find(targetFilename)
            if (resMatch == null && !qualityStr.isNullOrBlank()) {
                val singleQualities = Regex("""\b(2160p|4k|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE).findAll(qualityStr).toList()
                if (singleQualities.size == 1) {
                    resMatch = singleQualities.first()
                }
            }
            if (resMatch == null && !filenameOrTitle.isNullOrBlank()) {
                val nonMulti = Regex("""\b(2160p|4k|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE).findAll(filenameOrTitle).toList()
                if (nonMulti.size == 1) {
                    resMatch = nonMulti.first()
                }
            }

            val resFound = resMatch?.value?.lowercase()
            var (resolutionText, qualityInt) = when {
                resFound?.contains("2160") == true || resFound?.contains("4k") == true -> Pair("4K", Qualities.P2160.value)
                resFound?.contains("1440") == true -> Pair("1440p", Qualities.P1440.value)
                resFound?.contains("1080") == true -> Pair("1080p", Qualities.P1080.value)
                resFound?.contains("720") == true -> Pair("720p", Qualities.P720.value)
                resFound?.contains("480") == true -> Pair("480p", Qualities.P480.value)
                resFound?.contains("360") == true -> Pair("360p", Qualities.P360.value)
                else -> Pair("", Qualities.Unknown.value)
            }

            // 2. Clean single file size & size-based validation / fallback
            val sizeClean = cleanSizeString(sizeStr) ?: cleanSizeString(filenameOrTitle)
            val sizeBytes = parseSizeToBytes(sizeClean)

            if (sizeBytes > 0L) {
                // Correct falsely inherited 1080p/720p on small files
                if (sizeBytes < 600_000_000L && qualityInt > Qualities.P480.value) {
                    qualityInt = Qualities.P480.value
                    resolutionText = "480p"
                } else if (sizeBytes < 1_100_000_000L && qualityInt > Qualities.P720.value) {
                    qualityInt = Qualities.P720.value
                    resolutionText = "720p"
                } else if (qualityInt == Qualities.Unknown.value) {
                    when {
                        sizeBytes < 600_000_000L -> {
                            qualityInt = Qualities.P480.value
                            resolutionText = "480p"
                        }
                        sizeBytes < 1_900_000_000L -> {
                            qualityInt = Qualities.P720.value
                            resolutionText = "720p"
                        }
                        sizeBytes < 5_500_000_000L -> {
                            qualityInt = Qualities.P1080.value
                            resolutionText = "1080p"
                        }
                        else -> {
                            val is4k = (filenameOrTitle ?: "").contains("4k", ignoreCase = true) || (filenameOrTitle ?: "").contains("2160", ignoreCase = true)
                            if (is4k) {
                                qualityInt = Qualities.P2160.value
                                resolutionText = "4K"
                            } else {
                                qualityInt = Qualities.P1080.value
                                resolutionText = "1080p"
                            }
                        }
                    }
                }
            }

            // 3. Codec / Video attributes
            val combined = "$qualityStr $filenameOrTitle"
            val codec = when {
                combined.contains("10bit", ignoreCase = true) && combined.contains("hevc", ignoreCase = true) -> "10Bit HEVC"
                combined.contains("10bit", ignoreCase = true) -> "10Bit"
                combined.contains("hevc", ignoreCase = true) || combined.contains("x265", ignoreCase = true) -> "HEVC"
                combined.contains("ds4k", ignoreCase = true) -> "DS4K"
                else -> null
            }

            // 4. Clean minimal title formatting without duplicate resolution
            val parts = mutableListOf<String>()
            parts.add(server.trim())
            if (codec != null) parts.add(codec)
            if (sizeClean != null) parts.add("[$sizeClean]")

            val linkTitle = parts.joinToString(" • ")
            return Pair(linkTitle, qualityInt)
        }

        suspend fun resolveHubCloud(
            hubUrl: String,
            linkName: String?,
            qualityStr: String?,
            sizeStr: String?,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return try {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )

                // If hubUrl is already a direct gateway link, use it directly
                val (nextUrl, initialHtml) = if (hubUrl.contains("hubcloud.php")) {
                    Pair(hubUrl, "")
                } else {
                    val resp1 = app.get(hubUrl, headers = headers, timeout = 12L)
                    val html1 = resp1.text

                    var gateway = Regex("""href=["'](https?://[^"'\s]*hubcloud\.php[^"'\s]*)["']""").find(html1)?.groupValues?.get(1)
                    if (gateway == null) {
                        gateway = Regex("""<a[^>]+id=["']download["'][^>]+href=["']([^"'\s]+)["']""").find(html1)?.groupValues?.get(1)
                    }
                    if (gateway == null) {
                        gateway = Regex("""<a[^>]+class=["'][^"']*btn-primary[^"']*["'][^>]+href=["']([^"'\s]+)["']""").find(html1)?.groupValues?.get(1)
                    }
                    if (gateway == null) return false

                    val fullGw = if (!gateway.startsWith("http")) {
                        if (gateway.startsWith("/")) {
                            val baseUri = java.net.URI(hubUrl)
                            "${baseUri.scheme}://${baseUri.host}$gateway"
                        } else {
                            "$hubUrl/$gateway"
                        }
                    } else {
                        gateway
                    }
                    Pair(fullGw, html1)
                }

                val resp2 = app.get(nextUrl, headers = headers + ("Referer" to hubUrl), timeout = 12L)
                val rawHtml2 = resp2.text
                val html2 = rawHtml2.replace(Regex("""<!--[\s\S]*?-->"""), "")

                // Extract file size from HTML if available
                val htmlSize = Regex("""(?i)<i[^>]*id=["']size["'][^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)<i[^>]*id=["']size["'][^>]*>([^<]+)</i>""").find(initialHtml)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)File Size\s*<i[^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)File Size\s*<i[^>]*>([^<]+)</i>""").find(initialHtml)?.groupValues?.get(1)?.trim()

                val effectiveSize = when {
                    !htmlSize.isNullOrBlank() && !htmlSize.equals("NAN", ignoreCase = true) -> htmlSize.trim()
                    !sizeStr.isNullOrBlank() -> sizeStr.trim()
                    else -> null
                }

                val pageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(rawHtml2)?.groupValues?.get(1)
                    ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(initialHtml)?.groupValues?.get(1)
                    ?: ""
                val filename = Regex("""filename=["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE).find(rawHtml2)?.groupValues?.get(1)
                    ?: Regex("""filename=["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE).find(initialHtml)?.groupValues?.get(1)
                    ?: ""
                val combinedFileInfo = "$linkName $pageTitle $filename"

                var foundAny = false
                val seenUrls = mutableSetOf<String>()

                // 1. Direct FSL Server (Cloudflare R2 Storage)
                val fslMatches = Regex("""href=["'](https?://[^"'\s]*(?:\.cloudflarestorage\.com|\.r2\.dev)[^"'\s]*)["']""").findAll(html2)
                for (match in fslMatches) {
                    val fslUrl = match.groupValues[1].replace("&amp;", "&")
                    if (seenUrls.add(fslUrl)) {
                        val (linkTitle, quality) = formatLinkTitle("HubCloud FSL", qualityStr, effectiveSize, combinedFileInfo)
                        callback(
                            newExtractorLink(
                                source = "HubCloud FSL",
                                name = linkTitle,
                                url = fslUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }
                }

                // 2. High-speed 10Gbps Server (Google UploadServer CDN via gpdl / pixel)
                val pixelHubMatches = Regex("""href=["'](https?://(?:pixel|gpdl)\.hubcloud\.[^"'\s]+)["']|href=["'](https?://[^"'\s]*hubcloud\.[^"'\s]*\?id=[^"'\s]+)["']""").findAll(html2)
                for (match in pixelHubMatches) {
                    val rawMatch = match.groupValues[1].ifBlank { match.groupValues[2] }
                    if (rawMatch.isBlank() || rawMatch.contains("/admin", ignoreCase = true) || rawMatch.contains("/tg/", ignoreCase = true) || rawMatch.contains("telegram", ignoreCase = true)) continue
                    val pixelHubUrl = rawMatch.replace("&amp;", "&")
                    try {
                        val pixelResp = app.get(pixelHubUrl, headers = headers, timeout = 12L)
                        val finalUrl = pixelResp.okhttpResponse.request.url.toString()
                        if (finalUrl.contains("t.me") || finalUrl.contains("telegram")) continue

                        val directGoogleUrl = when {
                            finalUrl.contains("link=") -> URLDecoder.decode(finalUrl.substringAfter("link=").substringBefore("&"), "UTF-8")
                            finalUrl.contains("url=") -> URLDecoder.decode(finalUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
                            else -> finalUrl
                        }

                        if (directGoogleUrl.startsWith("http") &&
                            !directGoogleUrl.contains("t.me") &&
                            !directGoogleUrl.contains("telegram") &&
                            (directGoogleUrl.contains("googleusercontent.com") || directGoogleUrl.contains("drive.google.com") || directGoogleUrl.contains(".mkv") || directGoogleUrl.contains(".mp4") || directGoogleUrl.contains("dl.php")) &&
                            seenUrls.add(directGoogleUrl)) {
                            val (linkTitle, quality) = formatLinkTitle("HubCloud 10Gbps", qualityStr, effectiveSize, combinedFileInfo)
                            callback(
                                newExtractorLink(
                                    source = "HubCloud 10Gbps",
                                    name = linkTitle,
                                    url = directGoogleUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                }
                            )
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }

                // 3. HBPlay direct link (Watch Online player base64)
                val hbMatch = Regex("""href=["']https?://(?:hbplay|stream|player)\.pages\.dev/\?u=([^"'\s&]+)""").find(html2)
                if (hbMatch != null) {
                    try {
                        val decoded = String(android.util.Base64.decode(hbMatch.groupValues[1], android.util.Base64.DEFAULT)).replace("&amp;", "&")
                        if (decoded.startsWith("http") && seenUrls.add(decoded)) {
                            val (linkTitle, quality) = formatLinkTitle("HubCloud Fast", qualityStr, effectiveSize, combinedFileInfo)
                            callback(
                                newExtractorLink(
                                    source = "HubCloud Stream",
                                    name = linkTitle,
                                    url = decoded,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                }
                            )
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }

                // 4. PixelDrain (with Pixeldrain Bypass support)
                val pxlMatch = Regex("""href=["'](https?://pixeldrain\.[^"'\s/]+/u/([a-zA-Z0-9]+))["']""").find(html2)
                if (pxlMatch != null) {
                    val fileId = pxlMatch.groupValues[2]
                    if (!fileId.equals("negn6f", ignoreCase = true) && fileId.length >= 6) {
                        // 4a. Pixeldrain Bypass CDN (Unlimited speed & bypasses daily limits)
                        val bypassUrl = "https://cdn.pixeldrain.eu.cc/$fileId?download"
                        if (seenUrls.add(bypassUrl)) {
                            val (linkTitle, quality) = formatLinkTitle("PixelDrain Fast", qualityStr, effectiveSize, combinedFileInfo)
                            callback(
                                newExtractorLink(
                                    source = "PixelDrain Fast",
                                    name = linkTitle,
                                    url = bypassUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                }
                            )
                            foundAny = true
                        }

                        // 4b. Direct PixelDrain API fallback
                        val directPxl = "https://pixeldrain.com/api/file/$fileId"
                        if (seenUrls.add(directPxl)) {
                            val (linkTitle, quality) = formatLinkTitle("PixelDrain", qualityStr, effectiveSize, combinedFileInfo)
                            callback(
                                newExtractorLink(
                                    source = "PixelDrain",
                                    name = linkTitle,
                                    url = directPxl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                }
                            )
                            foundAny = true
                        }
                    }
                }

                foundAny
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        suspend fun resolveGDFlix(
            gdflixUrl: String,
            linkName: String?,
            qualityStr: String?,
            sizeStr: String?,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return try {
                val headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )

                val resp = app.get(gdflixUrl, headers = headers, timeout = 12L)
                if (!resp.isSuccessful) return false
                val finalUrl = resp.okhttpResponse.request.url.toString()
                val rawHtml = resp.text
                val html = rawHtml.replace(Regex("""<!--[\s\S]*?-->"""), "")

                val pageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(rawHtml)?.groupValues?.get(1) ?: ""
                val filename = Regex("""(?i)(?:Name\s*:\s*|Download\s+|title>GDFlix\s*\|\s*)([A-Za-z0-9._-]+\.(?:mkv|mp4))""").find(rawHtml)?.groupValues?.get(1)
                    ?: Regex("""\b([A-Za-z0-9._-]+\.(?:mkv|mp4))\b""", RegexOption.IGNORE_CASE).find(rawHtml)?.groupValues?.get(1)
                    ?: ""
                val combinedFileInfo = "$linkName $pageTitle $filename"

                val htmlSize = Regex("""(?i)Size\s*:\s*([0-9.]+\s*(?:GB|MB|KB))""").find(html)?.groupValues?.get(1)?.trim()
                val effectiveSize = when {
                    !htmlSize.isNullOrBlank() && !htmlSize.equals("NAN", ignoreCase = true) -> htmlSize.trim()
                    !sizeStr.isNullOrBlank() -> sizeStr.trim()
                    else -> null
                }

                var foundAny = false
                val seenUrls = mutableSetOf<String>()

                // 1. Direct R2 / Cloudflare Storage links
                val r2PagesMatches = Regex("""href=["']https?://[^"'\s]*pages\.dev/\?url=([^"'\s&]+)""").findAll(html)
                for (match in r2PagesMatches) {
                    val decoded = URLDecoder.decode(match.groupValues[1], "UTF-8")
                    if (decoded.startsWith("http") && seenUrls.add(decoded)) {
                        val (linkTitle, quality) = formatLinkTitle("GDFlix R2", qualityStr, effectiveSize, combinedFileInfo)
                        callback(
                            newExtractorLink(
                                source = "GDFlix R2",
                                name = linkTitle,
                                url = decoded,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }
                }

                val directR2Matches = Regex("""href=["'](https?://[^"'\s]*(?:\.r2\.dev|\.cloudflarestorage\.com)[^"'\s]*)["']""").findAll(html)
                for (match in directR2Matches) {
                    val r2Url = match.groupValues[1].replace("&amp;", "&")
                    if (seenUrls.add(r2Url)) {
                        val (linkTitle, quality) = formatLinkTitle("GDFlix R2", qualityStr, effectiveSize, combinedFileInfo)
                        callback(
                            newExtractorLink(
                                source = "GDFlix R2",
                                name = linkTitle,
                                url = r2Url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }
                }

                // 2. Instant DL (10Gbps Google CDN via workers.dev or instadl.rest)
                val instantUrls = Regex("""href=["'](https?://[^"'\s]*(?:workers\.dev|instadl\.rest)[^"'\s]*)["']""").findAll(html)
                for (match in instantUrls) {
                    val instUrl = match.groupValues[1].replace("&amp;", "&")
                    try {
                        val instResp = app.get(instUrl, headers = headers + ("Referer" to finalUrl), timeout = 12L)
                        val instFinal = instResp.okhttpResponse.request.url.toString()
                        val googleUrl = when {
                            instFinal.contains("url=") -> URLDecoder.decode(instFinal.substringAfter("url=").substringBefore("&"), "UTF-8")
                            instFinal.contains("link=") -> URLDecoder.decode(instFinal.substringAfter("link=").substringBefore("&"), "UTF-8")
                            instFinal.contains("googleusercontent.com") -> instFinal
                            else -> null
                        }
                        if (googleUrl != null &&
                            googleUrl.startsWith("http") &&
                            !googleUrl.contains("t.me") &&
                            !googleUrl.contains("telegram") &&
                            seenUrls.add(googleUrl)) {
                            val (linkTitle, quality) = formatLinkTitle("GDFlix 10Gbps", qualityStr, effectiveSize, combinedFileInfo)
                            callback(
                                newExtractorLink(
                                    source = "GDFlix 10Gbps",
                                    name = linkTitle,
                                    url = googleUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = quality
                                    this.headers = mapOf("User-Agent" to USER_AGENT)
                                }
                            )
                            foundAny = true
                        }
                    } catch (_: Exception) {}
                }

                // 3. PixelDrain with Bypass Support
                val pxlMatches = Regex("""href=["'](https?://pixeldrain\.[^"'\s/]+/u/([a-zA-Z0-9]+))["']""").findAll(html)
                for (match in pxlMatches) {
                    val fileId = match.groupValues[2]
                    if (fileId.equals("negn6f", ignoreCase = true) || fileId.length < 6) continue

                    val bypassUrl = "https://cdn.pixeldrain.eu.cc/$fileId?download"
                    if (seenUrls.add(bypassUrl)) {
                        val (linkTitle, quality) = formatLinkTitle("PixelDrain Fast", qualityStr, effectiveSize, combinedFileInfo)
                        callback(
                            newExtractorLink(
                                source = "PixelDrain Fast",
                                name = linkTitle,
                                url = bypassUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }

                    val directPxl = "https://pixeldrain.com/api/file/$fileId"
                    if (seenUrls.add(directPxl)) {
                        val (linkTitle, quality) = formatLinkTitle("PixelDrain", qualityStr, effectiveSize, combinedFileInfo)
                        callback(
                            newExtractorLink(
                                source = "PixelDrain",
                                name = linkTitle,
                                url = directPxl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }
                }

                foundAny
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }
}

open class DesireMoviesGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.vip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        DesireMoviesHubCloud.resolveGDFlix(url, null, null, null, callback)
    }
}
