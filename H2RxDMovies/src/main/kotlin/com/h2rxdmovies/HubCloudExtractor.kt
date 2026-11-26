package com.h2rxdmovies

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.delay
import java.net.URI
import java.net.URLDecoder

open class HubCloud : ExtractorApi() {
    override val name = "HubCloud"
    override val mainUrl = "https://hubcloud.pw"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolveHubCloud(url, null, callback)
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val BASE_HEADERS = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        private val SIZE_REGEX =
            Regex("""([0-9]+(?:\.[0-9]+)?)\s*(TiB|GiB|MiB|KiB|TB|GB|MB|KB|B)\b""", RegexOption.IGNORE_CASE)
        private val RES_REGEX =
            Regex("""\b(2160p|4k|1440p|1080p|720p|480p|360p)\b""", RegexOption.IGNORE_CASE)

        suspend fun retryGet(
            url: String,
            headers: Map<String, String> = BASE_HEADERS,
            timeout: Long = 15L,
            retries: Int = 2
        ): NiceResponse? {
            var lastResponse: NiceResponse? = null
            for (attempt in 0..retries) {
                try {
                    val resp = app.get(url, headers = headers, timeout = timeout)
                    lastResponse = resp
                    // Do not retry deterministic 4xx responses (e.g., 403/404).
                    if (resp.isSuccessful || resp.code in 400..499) return resp
                } catch (_: Exception) {
                }
                if (attempt < retries) delay(300L * (attempt + 1))
            }
            return lastResponse
        }

        fun getQualityInt(resolution: String?): Int {
            if (resolution.isNullOrBlank()) return Qualities.Unknown.value
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
                val normalized = sizeStr.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
                val match = SIZE_REGEX.find(normalized)
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
            val match = SIZE_REGEX.find(normalized) ?: return null
            return "${match.groupValues[1]} ${match.groupValues[2].uppercase()}"
        }

        fun formatLinkTitle(
            server: String,
            downloadItem: DownloadItem?,
            effectiveSize: String?,
            combinedFileInfo: String
        ): Pair<String, Int> {
            val fnMatch = Regex("""\b([A-Za-z0-9._-]+\.(?:mkv|mp4|zip))\b""", RegexOption.IGNORE_CASE)
                .find(combinedFileInfo)
            val targetFilename = fnMatch?.groupValues?.get(1) ?: ""

            var resMatch = RES_REGEX.find(targetFilename)
            if (resMatch == null && !downloadItem?.resolution.isNullOrBlank()) {
                val singles = RES_REGEX.findAll(downloadItem!!.resolution!!).toList()
                if (singles.size == 1) resMatch = singles.first()
            }
            if (resMatch == null && combinedFileInfo.isNotBlank()) {
                val singles = RES_REGEX.findAll(combinedFileInfo).toList()
                if (singles.size == 1) resMatch = singles.first()
            }

            val resFound = resMatch?.value?.lowercase()
            var qualityInt = when {
                resFound?.contains("2160") == true || resFound?.contains("4k") == true -> Qualities.P2160.value
                resFound?.contains("1440") == true -> Qualities.P1440.value
                resFound?.contains("1080") == true -> Qualities.P1080.value
                resFound?.contains("720") == true -> Qualities.P720.value
                resFound?.contains("480") == true -> Qualities.P480.value
                resFound?.contains("360") == true -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            val sizeClean = cleanSizeString(effectiveSize) ?: cleanSizeString(combinedFileInfo)
            val sizeBytes = downloadItem?.size_bytes?.takeIf { it > 0L } ?: parseSizeToBytes(sizeClean)

            if (sizeBytes > 0L && qualityInt == Qualities.Unknown.value) {
                qualityInt = when {
                    sizeBytes < 600_000_000L -> Qualities.P480.value
                    sizeBytes < 1_900_000_000L -> Qualities.P720.value
                    sizeBytes < 5_500_000_000L -> Qualities.P1080.value
                    combinedFileInfo.contains("4k", ignoreCase = true) ||
                        combinedFileInfo.contains("2160", ignoreCase = true) -> Qualities.P2160.value
                    else -> Qualities.P1080.value
                }
            }

            val codecInfo = "${downloadItem?.codec.orEmpty()} $combinedFileInfo"
            val is10Bit = downloadItem?.is_10bit == 1 || codecInfo.contains("10bit", ignoreCase = true)
            val isHevc = codecInfo.contains("hevc", ignoreCase = true) ||
                codecInfo.contains("x265", ignoreCase = true) ||
                codecInfo.contains("h.265", ignoreCase = true)
            val codec = when {
                is10Bit && isHevc -> "10Bit HEVC"
                is10Bit -> "10Bit"
                isHevc -> "HEVC"
                codecInfo.contains("ds4k", ignoreCase = true) -> "DS4K"
                else -> null
            }

            val parts = mutableListOf(server.trim())
            if (codec != null) parts.add(codec)
            if (sizeClean != null) parts.add("[$sizeClean]")

            return Pair(parts.joinToString(" • "), qualityInt)
        }

        private fun resolveRelativeUrl(baseUrl: String, candidate: String): String {
            val clean = candidate.trim().replace("&amp;", "&")
            if (clean.startsWith("http")) return clean
            return try {
                URI(baseUrl).resolve(clean).toString()
            } catch (_: Exception) {
                if (clean.startsWith("/")) {
                    val uri = URI(baseUrl)
                    "${uri.scheme}://${uri.host}$clean"
                } else {
                    "${baseUrl.trimEnd('/')}/$clean"
                }
            }
        }

        private fun decodeBase64Flexible(encoded: String): String? {
            val trimmed = encoded.trim()
            if (trimmed.isEmpty()) return null
            for (flags in listOf(Base64.DEFAULT, Base64.URL_SAFE or Base64.NO_PADDING)) {
                try {
                    val decoded = String(Base64.decode(trimmed, flags)).replace("&amp;", "&").trim()
                    if (decoded.startsWith("http")) return decoded
                } catch (_: Exception) {
                }
            }
            return null
        }

        private suspend fun emitExtractorLink(
            source: String,
            url: String,
            downloadItem: DownloadItem?,
            effectiveSize: String?,
            combinedFileInfo: String,
            seenUrls: MutableSet<String>,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            val cleanUrl = url.trim().replace("&amp;", "&")
            if (!cleanUrl.startsWith("http") || !seenUrls.add(cleanUrl)) return false
            val (linkTitle, qualityInt) = formatLinkTitle(source, downloadItem, effectiveSize, combinedFileInfo)
            callback(
                newExtractorLink(
                    source = source,
                    name = linkTitle,
                    url = cleanUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = qualityInt
                    this.headers = mapOf("User-Agent" to USER_AGENT)
                }
            )
            return true
        }

        private suspend fun emitPixelDrainLinks(
            fileId: String,
            downloadItem: DownloadItem?,
            effectiveSize: String?,
            combinedFileInfo: String,
            seenUrls: MutableSet<String>,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            if (fileId.equals("negn6f", ignoreCase = true) || fileId.length < 6) return false
            var found = false
            val bypassUrl = "https://cdn.pixeldrain.eu.cc/$fileId?download"
            if (emitExtractorLink("PixelDrain Fast", bypassUrl, downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                found = true
            }
            val directUrl = "https://pixeldrain.com/api/file/$fileId"
            if (emitExtractorLink("PixelDrain", directUrl, downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                found = true
            }
            return found
        }

        suspend fun resolveHubCloud(
            hubUrl: String,
            downloadItem: DownloadItem?,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return try {
                val targetUrl = hubUrl.trim().replace("&amp;", "&")
                var refererUrl = targetUrl
                var initialHtml = ""
                var page2Url = targetUrl
                var html2Raw = ""

                if (!targetUrl.contains("hubcloud.php", ignoreCase = true)) {
                    val resp1 = retryGet(targetUrl, BASE_HEADERS, timeout = 15L) ?: return false
                    if (!resp1.isSuccessful) return false
                    refererUrl = resp1.okhttpResponse.request.url.toString()
                    initialHtml = resp1.text

                    var gateway = Regex("""href=["'](https?://[^"'\s]*hubcloud\.php[^"'\s]*)["']""", RegexOption.IGNORE_CASE)
                        .find(initialHtml)?.groupValues?.get(1)
                    if (gateway == null) {
                        val doc1 = resp1.document
                        gateway = doc1.selectFirst("a[href*='hubcloud.php'], a#download, a.btn-primary, a.btn-success")
                            ?.attr("abs:href")
                            ?.takeIf { it.isNotBlank() }
                    }

                    if (gateway != null) {
                        page2Url = resolveRelativeUrl(refererUrl, gateway)
                    } else {
                        // Some mirrors render final server buttons directly on the first page.
                        html2Raw = initialHtml
                    }
                }

                if (html2Raw.isEmpty()) {
                    val resp2 = retryGet(page2Url, BASE_HEADERS + ("Referer" to refererUrl), timeout = 15L)
                        ?: return false
                    if (!resp2.isSuccessful) return false
                    page2Url = resp2.okhttpResponse.request.url.toString()
                    html2Raw = resp2.text
                }

                val html2 = html2Raw.replace(Regex("""<!--[\s\S]*?-->"""), "")

                val htmlSize = Regex("""(?i)<i[^>]*id=["']size["'][^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)<i[^>]*id=["']size["'][^>]*>([^<]+)</i>""").find(initialHtml)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)File Size\s*<i[^>]*>([^<]+)</i>""").find(html2)?.groupValues?.get(1)?.trim()
                    ?: Regex("""(?i)File Size\s*<i[^>]*>([^<]+)</i>""").find(initialHtml)?.groupValues?.get(1)?.trim()

                val effectiveSize = when {
                    !htmlSize.isNullOrBlank() && !htmlSize.equals("NAN", ignoreCase = true) -> htmlSize
                    !downloadItem?.size.isNullOrBlank() -> downloadItem?.size
                    else -> null
                }

                val pageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html2Raw)?.groupValues?.get(1)
                    ?: Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(initialHtml)?.groupValues?.get(1)
                    ?: ""
                val filename = Regex("""filename=["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE).find(html2Raw)?.groupValues?.get(1)
                    ?: Regex("""filename=["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE).find(initialHtml)?.groupValues?.get(1)
                    ?: ""
                val combinedFileInfo = "${downloadItem?.file_name.orEmpty()} $pageTitle $filename"

                var foundAny = false
                val seenUrls = mutableSetOf<String>()

                // 1. Direct FSL Server (Cloudflare R2 Storage / pages.dev?url=)
                val fslMatches = Regex("""href=["'](https?://[^"'\s]*(?:\.cloudflarestorage\.com|\.r2\.dev)[^"'\s]*)["']""").findAll(html2)
                for (match in fslMatches) {
                    if (emitExtractorLink("HubCloud FSL", match.groupValues[1], downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                        foundAny = true
                    }
                }
                val r2PagesMatches = Regex("""href=["']https?://[^"'\s]*pages\.dev/\?url=([^"'\s&]+)""").findAll(html2)
                for (match in r2PagesMatches) {
                    val decoded = try { URLDecoder.decode(match.groupValues[1], "UTF-8") } catch (_: Exception) { "" }
                    if (emitExtractorLink("HubCloud FSL", decoded, downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                        foundAny = true
                    }
                }

                // 2. High-speed 10Gbps Server (Google UploadServer CDN via pixel / gpdl)
                val pixelHubMatches = Regex(
                    """href=["'](https?://(?:pixel|gpdl\d*|cdn\d*)\.hubcloud\.[^"'\s]+)["']|href=["'](https?://[^"'\s]*hubcloud\.[^"'\s]*\?id=[^"'\s]+)["']"""
                ).findAll(html2)
                for (match in pixelHubMatches) {
                    val rawMatch = match.groupValues[1].ifBlank { match.groupValues[2] }
                    if (rawMatch.isBlank() ||
                        rawMatch.contains("/admin", ignoreCase = true) ||
                        rawMatch.contains("/tg/", ignoreCase = true) ||
                        rawMatch.contains("telegram", ignoreCase = true)
                    ) continue

                    val pixelHubUrl = rawMatch.replace("&amp;", "&")
                    val pixelResp = retryGet(pixelHubUrl, BASE_HEADERS + ("Referer" to page2Url), timeout = 12L, retries = 1)
                    if (pixelResp == null || !pixelResp.isSuccessful) continue

                    val finalUrl = pixelResp.okhttpResponse.request.url.toString()
                    if (finalUrl.contains("t.me") || finalUrl.contains("telegram")) continue

                    val bodyText = pixelResp.text
                    if (bodyText.contains("Unable to Get Download Link", ignoreCase = true)) continue

                    var directGoogleUrl = when {
                        finalUrl.contains("link=") -> try {
                            URLDecoder.decode(finalUrl.substringAfter("link=").substringBefore("&"), "UTF-8")
                        } catch (_: Exception) { "" }
                        finalUrl.contains("url=") -> try {
                            URLDecoder.decode(finalUrl.substringAfter("url=").substringBefore("&"), "UTF-8")
                        } catch (_: Exception) { "" }
                        else -> finalUrl
                    }

                    if (!directGoogleUrl.contains("googleusercontent.com") &&
                        !directGoogleUrl.contains("drive.google.com") &&
                        !directGoogleUrl.contains(".r2.dev") &&
                        !directGoogleUrl.contains(".cloudflarestorage.com")
                    ) {
                        val bodyLink = Regex("""href=["'](https?://(?:video-downloads\.googleusercontent\.com|[^"'\s]*\.googleusercontent\.com|drive\.google\.com)[^"'\s]*)["']""")
                            .find(bodyText)?.groupValues?.get(1)
                        if (!bodyLink.isNullOrBlank()) {
                            directGoogleUrl = bodyLink
                        }
                    }

                    val isPlayable = directGoogleUrl.startsWith("http") &&
                        !directGoogleUrl.contains("t.me") &&
                        !directGoogleUrl.contains("telegram") &&
                        (directGoogleUrl.contains("googleusercontent.com") ||
                            directGoogleUrl.contains("drive.google.com") ||
                            directGoogleUrl.contains(".r2.dev") ||
                            directGoogleUrl.contains(".cloudflarestorage.com") ||
                            directGoogleUrl.contains(".mkv", ignoreCase = true) ||
                            directGoogleUrl.contains(".mp4", ignoreCase = true) ||
                            directGoogleUrl.contains(".zip", ignoreCase = true))

                    if (isPlayable && emitExtractorLink("HubCloud 10Gbps", directGoogleUrl, downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                        foundAny = true
                    }
                }

                // 3. HBPlay / Stream / Player direct link (Watch Online player base64)
                val hbMatches = Regex("""href=["']https?://(?:hbplay|stream|player)\.pages\.dev/\?u=([^"'\s&]+)""").findAll(html2)
                for (match in hbMatches) {
                    val decoded = decodeBase64Flexible(match.groupValues[1]) ?: continue
                    if (emitExtractorLink("HubCloud Fast", decoded, downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                        foundAny = true
                    }
                }

                // 4. PixelDrain (with bypass mirror & dummy ID filter)
                val pxlMatches = Regex("""href=["'](https?://pixeldrain\.[^"'\s/]+/u/([a-zA-Z0-9]+))["']""").findAll(html2)
                for (match in pxlMatches) {
                    if (emitPixelDrainLinks(match.groupValues[2], downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                        foundAny = true
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
            downloadItem: DownloadItem?,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            return try {
                val resp = retryGet(gdflixUrl.trim().replace("&amp;", "&"), BASE_HEADERS, timeout = 15L) ?: return false
                if (!resp.isSuccessful) return false
                val finalUrl = resp.okhttpResponse.request.url.toString()
                val rawHtml = resp.text
                val html = rawHtml.replace(Regex("""<!--[\s\S]*?-->"""), "")

                val pageTitle = Regex("""<title>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(rawHtml)?.groupValues?.get(1) ?: ""
                val filename = Regex("""(?i)(?:Name\s*:\s*|Download\s+|title>GDFlix\s*\|\s*)([A-Za-z0-9._-]+\.(?:mkv|mp4|zip))""").find(rawHtml)?.groupValues?.get(1)
                    ?: Regex("""\b([A-Za-z0-9._-]+\.(?:mkv|mp4|zip))\b""", RegexOption.IGNORE_CASE).find(rawHtml)?.groupValues?.get(1)
                    ?: ""
                val combinedFileInfo = "${downloadItem?.file_name.orEmpty()} $pageTitle $filename"

                val htmlSize = Regex("""(?i)Size\s*:\s*([0-9.]+\s*(?:GB|MB|KB))""").find(html)?.groupValues?.get(1)?.trim()
                val effectiveSize = when {
                    !htmlSize.isNullOrBlank() && !htmlSize.equals("NAN", ignoreCase = true) -> htmlSize
                    !downloadItem?.size.isNullOrBlank() -> downloadItem?.size
                    else -> null
                }

                var foundAny = false
                val seenUrls = mutableSetOf<String>()

                val r2PagesMatches = Regex("""href=["']https?://[^"'\s]*pages\.dev/\?url=([^"'\s&]+)""").findAll(html)
                for (match in r2PagesMatches) {
                    val decoded = try { URLDecoder.decode(match.groupValues[1], "UTF-8") } catch (_: Exception) { "" }
                    if (emitExtractorLink("GDFlix R2", decoded, downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                        foundAny = true
                    }
                }

                val directR2Matches = Regex("""href=["'](https?://[^"'\s]*(?:\.r2\.dev|\.cloudflarestorage\.com)[^"'\s]*)["']""").findAll(html)
                for (match in directR2Matches) {
                    if (emitExtractorLink("GDFlix R2", match.groupValues[1], downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
                        foundAny = true
                    }
                }

                val instantUrls = Regex("""href=["'](https?://[^"'\s]*(?:workers\.dev|instadl\.rest)[^"'\s]*)["']""").findAll(html)
                for (match in instantUrls) {
                    val instUrl = match.groupValues[1].replace("&amp;", "&")
                    val instResp = retryGet(instUrl, BASE_HEADERS + ("Referer" to finalUrl), timeout = 12L, retries = 1)
                    if (instResp == null || !instResp.isSuccessful) continue
                    val instFinal = instResp.okhttpResponse.request.url.toString()
                    val googleUrl = when {
                        instFinal.contains("url=") -> try { URLDecoder.decode(instFinal.substringAfter("url=").substringBefore("&"), "UTF-8") } catch (_: Exception) { null }
                        instFinal.contains("link=") -> try { URLDecoder.decode(instFinal.substringAfter("link=").substringBefore("&"), "UTF-8") } catch (_: Exception) { null }
                        instFinal.contains("googleusercontent.com") -> instFinal
                        else -> null
                    }
                    if (!googleUrl.isNullOrBlank() &&
                        !googleUrl.contains("t.me") &&
                        !googleUrl.contains("telegram") &&
                        emitExtractorLink("GDFlix 10Gbps", googleUrl, downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)
                    ) {
                        foundAny = true
                    }
                }

                val pxlMatches = Regex("""href=["'](https?://pixeldrain\.[^"'\s/]+/u/([a-zA-Z0-9]+))["']""").findAll(html)
                for (match in pxlMatches) {
                    if (emitPixelDrainLinks(match.groupValues[2], downloadItem, effectiveSize, combinedFileInfo, seenUrls, callback)) {
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

class HubCloudIst : HubCloud() {
    override val name = "HubCloud.ist"
    override val mainUrl = "https://hubcloud.ist"
}

class HubCloudMov : HubCloud() {
    override val name = "HubCloud.mov"
    override val mainUrl = "https://hubcloud.mov"
}

class HubCloudOne : HubCloud() {
    override val name = "HubCloud.one"
    override val mainUrl = "https://hubcloud.one"
}

class HubCloudCx : HubCloud() {
    override val name = "HubCloud.cx"
    override val mainUrl = "https://hubcloud.cx"
}

class HubCloudFoo : HubCloud() {
    override val name = "HubCloud.foo"
    override val mainUrl = "https://hubcloud.foo"
}

class HubCloudFans : HubCloud() {
    override val name = "HubCloud.fans"
    override val mainUrl = "https://hubcloud.fans"
}

class H2RxDMoviesGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.vip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        HubCloud.resolveGDFlix(url, null, callback)
    }
}
