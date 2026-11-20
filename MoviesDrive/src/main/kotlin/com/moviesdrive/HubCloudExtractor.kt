package com.moviesdrive

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLDecoder

open class MoviesDriveHubCloud : ExtractorApi() {
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
                val match = Regex("""([0-9]+(?:\.[0-9]+)?)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\b""", RegexOption.IGNORE_CASE).find(sizeStr)
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
                val nextUrl = if (hubUrl.contains("hubcloud.php")) {
                    hubUrl
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

                    if (!gateway.startsWith("http")) {
                        if (gateway.startsWith("/")) {
                            val baseUri = java.net.URI(hubUrl)
                            "${baseUri.scheme}://${baseUri.host}$gateway"
                        } else {
                            "$hubUrl/$gateway"
                        }
                    } else {
                        gateway
                    }
                }

                val resp2 = app.get(nextUrl, headers = headers + ("Referer" to hubUrl), timeout = 12L)
                val html2 = resp2.text

                // Extract file size from HTML if not provided or to refine
                val htmlSize = Regex("""(?i)<i[^>]*id=["']size["'][^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)File Size\s*<i[^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                val effectiveSize = when {
                    !sizeStr.isNullOrBlank() -> sizeStr.trim()
                    !htmlSize.isNullOrBlank() && !htmlSize.equals("NAN", ignoreCase = true) -> htmlSize.trim()
                    else -> null
                }

                val quality = getQualityInt(qualityStr ?: linkName)
                val cleanLabel = linkName?.trim()?.ifBlank { null }
                val sizeTag = if (!effectiveSize.isNullOrBlank() && !(cleanLabel?.contains(effectiveSize, ignoreCase = true) ?: false)) " [$effectiveSize]" else ""
                val resPrefix = if (!qualityStr.isNullOrBlank() && !(cleanLabel?.contains(qualityStr, ignoreCase = true) ?: false)) "$qualityStr " else ""
                val displayTag = cleanLabel ?: "$resPrefix$sizeTag".trim()

                var foundAny = false
                val seenUrls = mutableSetOf<String>()

                // 1. Direct FSL Server (Cloudflare R2 Storage)
                val fslMatches = Regex("""href=["'](https?://[^"'\s]*(?:\.cloudflarestorage\.com|\.r2\.dev)[^"'\s]*)["']""").findAll(html2)
                for ((index, match) in fslMatches.withIndex()) {
                    val fslUrl = match.groupValues[1].replace("&amp;", "&")
                    if (seenUrls.add(fslUrl)) {
                        val serverTag = if (index == 0) "HubCloud FSL" else "HubCloud FSL ${index + 1}"
                        val linkTitle = if (cleanLabel != null) "$serverTag • $cleanLabel$sizeTag" else "$serverTag $displayTag"
                        callback(
                            newExtractorLink(
                                source = serverTag,
                                name = linkTitle.trim(),
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
                            val linkTitle = if (cleanLabel != null) "HubCloud 10Gbps • $cleanLabel$sizeTag" else "HubCloud 10Gbps $displayTag"
                            callback(
                                newExtractorLink(
                                    source = "HubCloud 10Gbps",
                                    name = linkTitle.trim(),
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
                            val linkTitle = if (cleanLabel != null) "HubCloud Fast • $cleanLabel$sizeTag" else "HubCloud Fast $displayTag"
                            callback(
                                newExtractorLink(
                                    source = "HubCloud Stream",
                                    name = linkTitle.trim(),
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
                    // Skip dead/dummy IDs
                    if (!fileId.equals("negn6f", ignoreCase = true) && fileId.length >= 6) {
                        // 4a. Pixeldrain Bypass CDN (Unlimited speed & bypasses daily limits)
                        val bypassUrl = "https://cdn.pixeldrain.eu.cc/$fileId?download"
                        if (seenUrls.add(bypassUrl)) {
                            val bypassTitle = if (cleanLabel != null) "PixelDrain Fast (Bypass) • $cleanLabel$sizeTag" else "PixelDrain Fast (Bypass) $displayTag"
                            callback(
                                newExtractorLink(
                                    source = "PixelDrain Fast",
                                    name = bypassTitle.trim(),
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
                            val linkTitle = if (cleanLabel != null) "PixelDrain • $cleanLabel$sizeTag" else "PixelDrain $displayTag"
                            callback(
                                newExtractorLink(
                                    source = "PixelDrain",
                                    name = linkTitle.trim(),
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
                val html = resp.text

                val quality = getQualityInt(qualityStr ?: linkName)
                val cleanLabel = linkName?.trim()?.ifBlank { null }
                val sizeTag = if (!sizeStr.isNullOrBlank() && !(cleanLabel?.contains(sizeStr, ignoreCase = true) ?: false)) " [$sizeStr]" else ""
                val resPrefix = if (!qualityStr.isNullOrBlank() && !(cleanLabel?.contains(qualityStr, ignoreCase = true) ?: false)) "$qualityStr " else ""
                val displayTag = cleanLabel ?: "$resPrefix$sizeTag".trim()

                var foundAny = false
                val seenUrls = mutableSetOf<String>()

                // 1. Direct R2 / Cloudflare Storage links (from CLOUD DOWNLOAD [R2] button: https://fastcdn-dl.pages.dev/?url=...)
                val r2PagesMatches = Regex("""href=["']https?://[^"'\s]*pages\.dev/\?url=([^"'\s&]+)""").findAll(html)
                for (match in r2PagesMatches) {
                    val decoded = URLDecoder.decode(match.groupValues[1], "UTF-8")
                    if (decoded.startsWith("http") && seenUrls.add(decoded)) {
                        val title = if (cleanLabel != null) "GDFlix R2 • $cleanLabel$sizeTag" else "GDFlix R2 $displayTag"
                        callback(
                            newExtractorLink(
                                source = "GDFlix R2",
                                name = title.trim(),
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
                        val title = if (cleanLabel != null) "GDFlix R2 • $cleanLabel$sizeTag" else "GDFlix R2 $displayTag"
                        callback(
                            newExtractorLink(
                                source = "GDFlix R2",
                                name = title.trim(),
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
                            val title = if (cleanLabel != null) "GDFlix 10Gbps • $cleanLabel$sizeTag" else "GDFlix 10Gbps $displayTag"
                            callback(
                                newExtractorLink(
                                    source = "GDFlix 10Gbps",
                                    name = title.trim(),
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

                    // Fast bypass mirror
                    val bypassUrl = "https://cdn.pixeldrain.eu.cc/$fileId?download"
                    if (seenUrls.add(bypassUrl)) {
                        val bypassTitle = if (cleanLabel != null) "GDFlix PixelDrain (Bypass) • $cleanLabel$sizeTag" else "GDFlix PixelDrain (Bypass) $displayTag"
                        callback(
                            newExtractorLink(
                                source = "PixelDrain Fast",
                                name = bypassTitle.trim(),
                                url = bypassUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = quality
                                this.headers = mapOf("User-Agent" to USER_AGENT)
                            }
                        )
                        foundAny = true
                    }

                    // Direct official link
                    val directPxl = "https://pixeldrain.com/api/file/$fileId"
                    if (seenUrls.add(directPxl)) {
                        val directTitle = if (cleanLabel != null) "GDFlix PixelDrain • $cleanLabel$sizeTag" else "GDFlix PixelDrain $displayTag"
                        callback(
                            newExtractorLink(
                                source = "PixelDrain",
                                name = directTitle.trim(),
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

open class MoviesDriveGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.vip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        MoviesDriveHubCloud.resolveGDFlix(url, null, null, null, callback)
    }
}
