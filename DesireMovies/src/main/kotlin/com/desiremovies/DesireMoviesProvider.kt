package com.desiremovies

import android.content.Context
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Collections

class DesireMoviesProvider(private val pluginContext: Context? = null) : MainAPI() {
    override var mainUrl = DesireMoviesPreferences.getSavedDomain(pluginContext)
    override var name = "DesireMovies"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    )

    override val mainPage: List<MainPageData>
        get() = DesireMoviesPreferences.getActiveCategories(pluginContext, mainUrl)

    // ==========================================
    // AUTO DOMAIN DISCOVERY & FALLBACK SYSTEM
    // ==========================================

    private suspend fun verifyDomain(candidateUrl: String): Boolean {
        val cleanUrl = candidateUrl.trim().removeSuffix("/")
        if (!cleanUrl.startsWith("http")) return false
        return try {
            val resp = app.get("$cleanUrl/wp-json/", headers = requestHeaders, timeout = 6L)
            resp.isSuccessful && (resp.text.contains("desiremovies", ignoreCase = true) || resp.text.contains("name", ignoreCase = true))
        } catch (_: Exception) {
            try {
                val resp2 = app.get("$cleanUrl/", headers = requestHeaders, timeout = 6L)
                resp2.isSuccessful && resp2.text.contains("Desiremovies", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun resolveActiveDomain(): String {
        val current = mainUrl.removeSuffix("/")

        // 1. Verify if current domain is healthy
        if (verifyDomain(current)) {
            return current
        }

        // 2. Check GitHub Remote Config file for immediate instant domain migration
        try {
            val remoteResp = app.get(
                "https://raw.githubusercontent.com/deep12323/cloudstream-plugins/master/desiremovies_domain.txt",
                timeout = 5L
            )
            if (remoteResp.isSuccessful) {
                val candidate = remoteResp.text.trim().removeSuffix("/")
                if (candidate.startsWith("http") && candidate != current && verifyDomain(candidate)) {
                    updateDomain(candidate)
                    return candidate
                }
            }
        } catch (_: Exception) {}

        // 3. Official Landing Hub Auto Discovery (desiremovies.my)
        try {
            val landingResp = app.get(DesireMoviesPreferences.LANDING_DOMAIN, headers = requestHeaders, timeout = 8L)
            if (landingResp.isSuccessful) {
                val html = landingResp.text
                val extractedDomain = Regex("""(?i)href=["'](https?://[^"'\s]*desiremovies[^"'\s]*)["']""").find(html)?.groupValues?.get(1)?.trim()?.removeSuffix("/")
                if (extractedDomain != null && extractedDomain != current && verifyDomain(extractedDomain)) {
                    updateDomain(extractedDomain)
                    return extractedDomain
                }
            }
        } catch (_: Exception) {}

        // 4. Sequential Mirror Probing (1desiremovies.xyz -> 2desiremovies.xyz, 3desiremovies.xyz, etc.)
        val numMatch = Regex("""(?i)https?://(\d+)desiremovies""").find(current)
        val currentNum = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        for (nextNum in (currentNum + 1)..(currentNum + 5)) {
            val candidate = "https://${nextNum}desiremovies.xyz"
            if (verifyDomain(candidate)) {
                updateDomain(candidate)
                return candidate
            }
        }

        // 5. Alternate TLD mirrors
        val alternateMirrors = listOf(
            "https://1desiremovies.beauty",
            "https://1desiremovies.org",
            "https://1desiremovies.trade",
            "https://1desiremovies.vip"
        )
        for (mirror in alternateMirrors) {
            if (mirror != current && verifyDomain(mirror)) {
                updateDomain(mirror)
                return mirror
            }
        }

        return current
    }

    private fun updateDomain(newDomain: String) {
        val clean = newDomain.trim().removeSuffix("/")
        mainUrl = clean
        DesireMoviesPreferences.setSavedDomain(pluginContext, clean)
    }

    // ==========================================
    // TITLE CLEANER & METADATA PARSING
    // ==========================================

    private fun cleanTitle(raw: String): String {
        var t = raw.replace("&amp;", "&")
            .replace("&#8211;", "-")
            .replace("&#8217;", "'")
            .replace("&#038;", "&")
            .replace("&hellip;", "...")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("–", "-")
            .replace("—", "-")

        // Remove [18+]
        t = t.replace(Regex("""(?i)^\s*\[?18\+\]?\s*"""), "")

        // Remove episode notifications e.g. (S20 Ep76 Added) or [EP 76 ADDED]
        t = t.replace(Regex("""(?i)\s*\[?\b(?:EP\s*\d+\s*ADDED|S\d+\s*Ep\d+\s*Added)\b\]?"""), "")

        // Remove site branding & full movie / series markers
        t = t.replace(Regex("""(?i)\s*[-–—|]?\s*DesireMovies?(\.my|\.xyz|\.beauty|\.trade|\.org|\.vip)?.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Full Movie.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Complete Web Series.*$"""), "")
            .replace(Regex("""(?i)\bComplete\s*(?:Web\s*)?Series\b"""), "")
            .replace(Regex("""(?i)\bFull\s*Movie\b"""), "")

        // Remove bracketed audio info
        t = t.replace(Regex("""(?i)\[(?:Dual|Multi|Hindi|English|Tamil|Telugu|Malayalam|Kannada|Chinese|Korean|Japanese|Odia|Bengali|Punjabi|Marathi|Gujarati|Bhojpuri)[^\]]*\]"""), "")
        t = t.replace(Regex("""(?i)\{(?:Dual|Multi|Hindi|English|Tamil|Telugu|Malayalam|Kannada|Chinese|Korean|Japanese|Odia|Bengali|Punjabi|Marathi|Gujarati|Bhojpuri)[^}]*\}"""), "")
        t = t.replace(Regex("""(?i)\[[xX]264(?:\|[xX]265)?\]"""), "")
        t = t.replace(Regex("""(?i)\[ESubs?\]"""), "")

        // Remove video quality, codecs, sources
        t = t.replace(Regex("""(?i)\b(?:480p|720p|1080p|2160p|4K|SDR|HDR|DV|10Bit|x264|x265|HEVC|DS4K|WEB-DL|WEBRip|HDRip|WEB-HDRip|BluRay|HDTV|HQ|IMAX|60FPS)\b"""), "")
        t = t.replace(Regex("""(?i)\b(?:Dual\s*Audio|Multi\s*Audio|Hindi\s*Dubbed|UnOfficial\s*Dubbed|ORG\s*DD\s*5\.1|ORG\s*DD\s*2\.0)\b"""), "")
        t = t.replace(Regex("""(?i)\bSeries\b"""), "")

        // Remove standalone release year (preserves Season number)
        t = t.replace(Regex("""\b(19\d\d|20\d\d)\b"""), "")

        // Clean empty brackets and leftover punctuation
        t = t.replace(Regex("""\[\s*\]|\(\s*\)"""), "")
            .replace(Regex("""[\s|/•\-–—]+$"""), "")
            .replace(Regex("""^[\s|/•\-–—]+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        return t.ifBlank { raw.trim() }
    }

    private fun isTvSeries(title: String, url: String): Boolean {
        val combined = "$title $url".lowercase()
        return combined.contains("season") ||
                combined.contains("series") ||
                combined.contains("episode") ||
                Regex("""\bs\d+\b""").containsMatchIn(combined)
    }

    private fun extractYear(title: String): Int? {
        val match = Regex("""\((\d{4})\)""").find(title)
            ?: Regex("""\b(19\d\d|20\d\d)\b""").find(title)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun parsePosterCard(element: Element): SearchResponse? {
        val linkElem = element.selectFirst("h3.entry-title a, h2.entry-title a, a.mh-loop-thumb, figure.mh-loop-thumb a")
            ?: element.selectFirst("a[href]")
            ?: return null
        val rawHref = linkElem.attr("href").trim().takeIf { it.isNotBlank() } ?: return null
        val itemUrl = if (rawHref.startsWith("http")) rawHref else "$mainUrl${if (rawHref.startsWith("/")) "" else "/"}$rawHref"

        val imgElem = element.selectFirst("figure.mh-loop-thumb img, img")
        val rawTitle = element.selectFirst("h3.entry-title a, h2.entry-title a")?.text()
            ?: imgElem?.attr("alt")
            ?: linkElem.text()
        if (rawTitle.isBlank()) return null

        val title = cleanTitle(rawTitle)
        val posterUrl = imgElem?.attr("src")?.takeIf { it.isNotBlank() }
            ?: imgElem?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElem?.attr("data-original")
        val year = extractYear(rawTitle)
        val isSeries = isTvSeries(rawTitle, itemUrl)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val baseTarget = request.data.removeSuffix("/")
        val currentBase = mainUrl.removeSuffix("/")
        val activeTarget = if (baseTarget.contains("desiremovies.")) {
            baseTarget.replace(Regex("""https?://[^/]+"""), currentBase)
        } else {
            baseTarget
        }

        val url = if (activeTarget.equals(currentBase, ignoreCase = true) || activeTarget.isEmpty()) {
            if (page <= 1) "$currentBase/" else "$currentBase/page/$page/"
        } else {
            if (page <= 1) "$activeTarget/" else "$activeTarget/page/$page/"
        }

        try {
            var response = app.get(url, headers = requestHeaders, timeout = 12L)
            if (!response.isSuccessful) {
                val newDomain = resolveActiveDomain()
                if (newDomain != currentBase) {
                    val retryUrl = url.replace(currentBase, newDomain)
                    response = app.get(retryUrl, headers = requestHeaders, timeout = 12L)
                }
            }
            if (!response.isSuccessful) return null

            val doc = response.document
            val cards = doc.select("article.mh-loop-item, article.type-post, article")
            val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }

            val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next, .mh-pagination a.next, a:contains(Next)") != null || items.size >= 20

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            val newDomain = resolveActiveDomain()
            if (newDomain != currentBase) {
                try {
                    val retryUrl = url.replace(currentBase, newDomain)
                    val retryResp = app.get(retryUrl, headers = requestHeaders, timeout = 12L)
                    if (retryResp.isSuccessful) {
                        val doc = retryResp.document
                        val cards = doc.select("article.mh-loop-item, article.type-post, article")
                        val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }
                        val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next, .mh-pagination a.next, a:contains(Next)") != null || items.size >= 20
                        return newHomePageResponse(HomePageList(request.name, items, false), hasNext)
                    }
                } catch (_: Exception) {}
            }
            e.printStackTrace()
            return null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // 1. Primary: High-speed WordPress REST API
        val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$encodedQuery&page=$page&per_page=20"
        try {
            val response = app.get(wpApiUrl, headers = mapOf("User-Agent" to requestHeaders["User-Agent"]!!, "Accept" to "application/json"), timeout = 12L)
            if (response.isSuccessful && response.text.trim().startsWith("[")) {
                val posts: List<WpPost> = mapper.readValue(response.text)
                val items = posts.mapNotNull { post ->
                    val rawTitle = post.title?.rendered ?: return@mapNotNull null
                    val link = post.link ?: return@mapNotNull null
                    val title = cleanTitle(rawTitle)
                    val isSeries = isTvSeries(rawTitle, link)
                    val year = extractYear(rawTitle)

                    val contentHtml = post.content?.rendered ?: ""
                    val posterMatch = Regex("""<img[^>]+src=["'](https?://[^"'\s]*tmdb\.org[^"'\s]*)["']""").find(contentHtml)
                        ?: Regex("""<img[^>]+src=["'](https?://[^"'\s]+\.(?:jpg|jpeg|png|webp))["']""").find(contentHtml)
                    val posterUrl = posterMatch?.groupValues?.get(1)

                    if (isSeries) {
                        newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(title, link, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    }
                }

                val totalPages = response.headers["x-wp-totalpages"]?.toIntOrNull() ?: 1
                val hasNext = page < totalPages

                if (items.isNotEmpty()) {
                    return newSearchResponseList(items, hasNext)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Secondary: HTML Clean Search via /search/{query}/page/{page}/
        val htmlUrl = if (page <= 1) "$mainUrl/search/$encodedQuery/" else "$mainUrl/search/$encodedQuery/page/$page/"
        try {
            var response = app.get(htmlUrl, headers = requestHeaders + ("Referer" to "$mainUrl/"), timeout = 15L)
            if (!response.isSuccessful) {
                val newDomain = resolveActiveDomain()
                if (newDomain != mainUrl) {
                    val retryUrl = htmlUrl.replace(mainUrl, newDomain)
                    response = app.get(retryUrl, headers = requestHeaders + ("Referer" to "$newDomain/"), timeout = 15L)
                }
            }
            if (!response.isSuccessful) return newSearchResponseList(emptyList(), false)

            val doc = response.document
            val cards = doc.select("article.mh-loop-item, article.type-post, article")
            val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }
            val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next, .mh-pagination a.next") != null || items.size >= 20

            return newSearchResponseList(items, hasNext)
        } catch (e: Exception) {
            e.printStackTrace()
            return newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        try {
            val currentBase = mainUrl.removeSuffix("/")
            // Auto-rewrite any legacy or stale domain to active working mainUrl
            val targetUrl = if (url.contains("desiremovies.")) {
                url.replace(Regex("""https?://[^/]+"""), currentBase)
            } else {
                url
            }

            var response = app.get(targetUrl, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) {
                val newDomain = resolveActiveDomain()
                if (newDomain != currentBase) {
                    val retryUrl = targetUrl.replace(currentBase, newDomain)
                    response = app.get(retryUrl, headers = requestHeaders, timeout = 15L)
                }
            }
            if (!response.isSuccessful) return null
            val doc = response.document

            val rawTitle = doc.selectFirst("h1.entry-title, h1.post-title, h1, title")?.text() ?: "Unknown"
            val title = cleanTitle(rawTitle)
            val year = extractYear(rawTitle)
            val isSeries = isTvSeries(rawTitle, targetUrl)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            // Poster extraction
            val posterUrl = doc.selectFirst("img[src*='tmdb.org']")?.attr("src")
                ?: doc.selectFirst(".entry-content img, figure.entry-thumbnail img, .page-body img")?.attr("src")

            // 1. Audio Language Tag extraction
            val docHtml = doc.html()
            val langMatch = Regex("""(?i)(?<!Original\s)(?<!Native\s)\b(?:Language|Audio)\s*:\s*([^<\n\r]+|<[^>]+>[^<]+</[^>]+>)""").find(docHtml)
            var rawLang = langMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), " ") ?: ""
            if (rawLang.isBlank()) {
                val tMatch = Regex("""(?i)\[((?:Hindi|English|Tamil|Telugu|Malayalam|Kannada|Chinese|Korean|Japanese|Odia|Bengali|Marathi|Punjabi|Gujarati|Bhojpuri|Dual|Multi)[^\]]*)\]""").find(rawTitle)
                if (tMatch != null) rawLang = tMatch.groupValues[1]
            }
            if (rawLang.isBlank()) {
                val cMatch = Regex("""\{([^}]+)\}""").find(rawTitle)
                if (cMatch != null) rawLang = cMatch.groupValues[1]
            }
            val cleanLang = rawLang
                .replace("&#038;", "&")
                .replace("&amp;", "&")
                .replace("&#8211;", "-")
                .replace(Regex("""(?i)\s*/\s*ESubs?.*$"""), "")
                .replace(Regex("""^[\[\{]+|[\]\}]+$"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            val audioTag = if (cleanLang.isNotBlank() && cleanLang.length < 60) "Audio: $cleanLang" else null

            // 2. Plot extraction
            val rawPlot = doc.selectFirst(
                "h3:contains(Plot) + p, h3:contains(Plot) + div, h4:contains(Plot) + p, h4:contains(Plot) + div, " +
                "h3:contains(Storyline) + p, h3:contains(Storyline) + div, h4:contains(Storyline) + p, h4:contains(Storyline) + div, " +
                "p:contains(Plot :), p:contains(Plot:), p:contains(Storyline :), p:contains(Storyline:)"
            )?.text() ?: run {
                val storyHeading = doc.selectFirst("h1:contains(Plot), h2:contains(Plot), h3:contains(Plot), h4:contains(Plot), h1:contains(Storyline), h2:contains(Storyline), h3:contains(Storyline), h4:contains(Storyline)")
                storyHeading?.nextElementSiblings()?.firstOrNull { it.text().isNotBlank() && !it.tagName().startsWith("h") }?.text()
            }

            val cleanPlotBody = rawPlot?.let { raw ->
                var p = raw.replace("&#038;", "&").replace("&amp;", "&")
                    .replace(Regex("""^(?:[\s♦:–-]+|Storyline\s*:?-?\s*|Synopsis\s*:?-?\s*|Plot\s*:?-?\s*)+""", RegexOption.IGNORE_CASE), "")

                // Remove leading repeated title or year
                val escapedTitle = Regex.escape(title.trim())
                p = p.replace(Regex("""^(?:$escapedTitle)?\s*(?:\((?:19|20)\d{2}\)|\b(?:19|20)\d{2}\b)?\s*[-:–—]*\s*""", RegexOption.IGNORE_CASE), "")
                p = p.replace(Regex("""^[^\w]*(?:[A-Za-z0-9\s:’'.-]+\((?:19|20)\d{2}\)|\b(?:19|20)\d{2}\b)[-:–—\s]*"""), "")

                // Cut off trailing screenshots or download clutter
                p = p.replace(Regex("""(?i)(?:\b(?:Screen-?Shots|Screenshots|Download\s*Links|DOWNLOAD\s*LINKS|Full\s*Movie|Watch\s*Online|VISIT\s*&\s*SHARE)\b|>{2,}|-{2,}).*$"""), "")
                p.replace(Regex("""\s{2,}"""), " ").trim()
            }?.takeIf { it.isNotBlank() }

            val plot = when {
                audioTag != null && cleanPlotBody != null -> "🔊 $audioTag\n\n$cleanPlotBody"
                audioTag != null -> "🔊 $audioTag"
                else -> cleanPlotBody
            }

            // 3. Rating
            val ratingText = doc.selectFirst("div:contains(iMDB), p:contains(iMDB), div:contains(IMDb), p:contains(IMDb)")?.text()
            val ratingMatch = Regex("""(?i)IMDb\s*:\s*(\d+(?:\.\d+)?)\s*(?:/\s*10)?""").find(ratingText ?: "")
            val scoreVal = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

            // 4. Tags / Genres
            val genreMatch = Regex("""(?i)\b(?:All\s*Genres|Genre|Genres)\s*:\s*([^<\n\r]+|<[^>]+>[^<]+</[^>]+>)""").find(docHtml)
            var rawGenre = genreMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), " ") ?: ""
            for (stop in listOf("Country:", "Language:", "Stars:", "Cast:", "Quality:", "Director:", "Writer:", "Plot:", "Storyline:")) {
                if (rawGenre.contains(stop, ignoreCase = true)) {
                    rawGenre = rawGenre.substring(0, rawGenre.indexOf(stop, ignoreCase = true))
                }
            }
            val genres = rawGenre.split(",")
                .map { it.replace(Regex("""^[^\w\p{L}]+"""), "").trim() }
                .filter { it.isNotBlank() && it.length in 2..30 }

            val cleanTags = mutableListOf<String>()
            if (audioTag != null) cleanTags.add(audioTag)
            cleanTags.addAll(genres)

            // 5. Actors
            val starsMatch = Regex("""(?i)\b(?:Stars|Cast)\s*:\s*([^<\n\r]+|<[^>]+>[^<]+</[^>]+>)""").find(docHtml)
            var rawStars = starsMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), " ") ?: ""
            for (stop in listOf("Language:", "Quality:", "Format:", "Storyline:", "Plot:", "Country:", "Director:", "Writer:")) {
                if (rawStars.contains(stop, ignoreCase = true)) {
                    rawStars = rawStars.substring(0, rawStars.indexOf(stop, ignoreCase = true))
                }
            }
            val actors = rawStars.split(",")
                .map { it.replace(Regex("""^[^\w\p{L}]+"""), "").trim() }
                .filter { it.isNotBlank() && it.length in 2..50 }
                .map { ActorData(Actor(it, null)) }

            // Determine initial season from title or url
            val urlSeason = Regex("""(?i)season[-_ ]*0*(\d+)""").find(targetUrl)?.groupValues?.get(1)?.toIntOrNull()
            val titleSeason = Regex("""(?i)\b(?:Season|S)\s*0*(\d+)\b""").find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
            var currentSeason = titleSeason ?: urlSeason ?: 1

            // Parse download blocks and season context
            val contentElem = doc.selectFirst(".entry-content, .page-body") ?: doc
            val elements = contentElem.select("h1, h2, h3, h4, h5, h6, p, div")

            data class ScrapedDownload(
                val season: Int,
                val epNum: Int,
                val epName: String,
                val text: String,
                val url: String,
                val isZip: Boolean,
                val quality: String?,
                val size: String?
            )

            val scrapedDownloads = mutableListOf<ScrapedDownload>()
            val seenHrefs = mutableSetOf<String>()

            var currentEpName = "Download"
            var currentEpNum = 1
            var currentQuality: String? = null
            var currentSize: String? = null

            for (elem in elements) {
                val text = elem.text().trim()
                if (text.isBlank()) continue

                // Check season heading
                val seasonMatch = Regex("""(?i)\bSeason\s*0*(\d+)\b""").find(text)
                if (seasonMatch != null && !text.contains("Single Episode", ignoreCase = true) && !text.contains("Zip", ignoreCase = true)) {
                    currentSeason = seasonMatch.groupValues[1].toIntOrNull() ?: currentSeason
                }

                // Check episode heading: e.g. "EP 1 TO 4", "EP 76 (20 NOV 2026)", "EP 01", "Episode 1"
                val epMatch = Regex("""(?i)\b(?:EP|Episode|E)\s*(\d+)(?:\s*(?:TO|-)\s*(\d+))?""").find(text)
                if (epMatch != null && text.length < 50 && !text.contains("Download", ignoreCase = true) && !text.contains("Release", ignoreCase = true)) {
                    if (epMatch.groupValues[2].isNotBlank()) {
                        currentEpName = "Episodes ${epMatch.groupValues[1]}-${epMatch.groupValues[2]}"
                        currentEpNum = epMatch.groupValues[1].toIntOrNull() ?: 1
                    } else {
                        currentEpName = "Episode ${epMatch.groupValues[1]}"
                        currentEpNum = epMatch.groupValues[1].toIntOrNull() ?: 1
                    }
                }

                // Track quality and size in heading/paragraph text (if no download anchor is directly in this element)
                val anchors = elem.select("a[href*='gyanigurus.'], a[href*='hubcloud.'], a[href*='hubcloud'], a[href*='hubc.'], a[href*='gdflix.'], a[href*='hubdrive.'], a[href*='multicloudlinks.'], a[href*='only-apis.com']")
                if (anchors.isEmpty()) {
                    val qm = Regex("""\b(2160p|4k|1080p|720p|480p|360p|hevc)\b""", RegexOption.IGNORE_CASE).find(text)?.value
                    if (qm != null) currentQuality = qm

                    // Clean spaces inside size numbers: e.g. "58 0 MB" -> "580 MB"
                    val normalizedText = text.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
                    val sm = Regex("""\[\s*([0-9.]+\s*(?:GB|MB|KB))\s*\]""", RegexOption.IGNORE_CASE).find(normalizedText)?.groupValues?.get(1)
                    if (sm != null) currentSize = sm
                } else {
                    for (anchor in anchors) {
                        val href = anchor.attr("href").trim()
                        val linkText = anchor.text().trim()
                        if (href.isBlank() || !href.startsWith("http") || !seenHrefs.add(href)) continue

                        val isZip = linkText.contains("Zip", ignoreCase = true) ||
                                href.contains("zip", ignoreCase = true) ||
                                text.contains("Zip", ignoreCase = true) ||
                                text.contains("Complete Zip", ignoreCase = true)

                        val linkQm = Regex("""\b(2160p|4k|1080p|720p|480p|360p|hevc)\b""", RegexOption.IGNORE_CASE).find(linkText)?.value
                            ?: Regex("""\b(2160p|4k|1080p|720p|480p|360p|hevc)\b""", RegexOption.IGNORE_CASE).find(text)?.value
                            ?: currentQuality

                        val normalizedLink = "$linkText $text".replace(Regex("""(\d)\s+(\d)"""), "$1$2")
                        val linkSm = Regex("""\[\s*([0-9.]+\s*(?:GB|MB|KB))\s*\]""", RegexOption.IGNORE_CASE).find(normalizedLink)?.groupValues?.get(1)
                            ?: currentSize

                        scrapedDownloads.add(
                            ScrapedDownload(
                                season = currentSeason,
                                epNum = currentEpNum,
                                epName = currentEpName,
                                text = linkText.ifBlank { "Download" },
                                url = href,
                                isZip = isZip,
                                quality = linkQm,
                                size = linkSm
                            )
                        )
                    }
                }
            }

            if (isSeries) {
                val episodesList = mutableListOf<Episode>()

                // Group non-zip downloads by season and episode number
                val epBySeasonAndNum = scrapedDownloads.filter { !it.isZip }.groupBy { Pair(it.season, it.epNum) }

                for ((pair, links) in epBySeasonAndNum) {
                    val (seasonNum, epNum) = pair
                    val epDisplayName = links.firstOrNull()?.epName ?: "Episode $epNum"

                    val downloadItems = links.map {
                        MovieDownloadLink(
                            name = it.quality ?: it.text,
                            url = it.url,
                            quality = it.quality,
                            size = it.size
                        )
                    }.sortedWith(
                        compareByDescending<MovieDownloadLink> {
                            DesireMoviesHubCloud.parseSizeToBytes(it.size ?: it.name, DesireMoviesHubCloud.getQualityInt(it.quality ?: it.name))
                        }.thenByDescending {
                            DesireMoviesHubCloud.getQualityInt(it.quality ?: it.name)
                        }
                    )

                    val payload = mapper.writeValueAsString(downloadItems)
                    episodesList.add(
                        newEpisode(payload) {
                            this.name = epDisplayName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = posterUrl
                        }
                    )
                }

                // Add Zip packs in Season 0 / Specials, clearly displaying the Season name!
                val zipPacks = scrapedDownloads.filter { it.isZip }.sortedWith(
                    compareByDescending<ScrapedDownload> { it.season }
                        .thenByDescending {
                            DesireMoviesHubCloud.parseSizeToBytes(it.size ?: it.text, DesireMoviesHubCloud.getQualityInt(it.quality ?: it.text))
                        }
                )
                for ((idx, pack) in zipPacks.withIndex()) {
                    val packPayload = mapper.writeValueAsString(
                        listOf(MovieDownloadLink(name = "Zip Pack", url = pack.url, quality = pack.quality, size = pack.size))
                    )
                    val seasonLabel = if (pack.season > 0) "Season ${pack.season}" else "Full Series"
                    val resStr = if (!pack.quality.isNullOrBlank()) " • ${pack.quality}" else ""
                    val sizeStr = if (!pack.size.isNullOrBlank()) " [${pack.size}]" else ""
                    episodesList.add(
                        newEpisode(packPayload) {
                            this.name = "📦 [ZIP PACK] • $seasonLabel$resStr$sizeStr".trim()
                            this.season = 0
                            this.episode = idx + 1
                            this.posterUrl = posterUrl
                        }
                    )
                }

                val sortedEpisodes = episodesList.sortedWith(compareBy({ it.season ?: 1 }, { it.episode ?: 1 }))

                return newTvSeriesLoadResponse(title, targetUrl, type, sortedEpisodes) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = cleanTags.ifEmpty { null }
                    this.actors = actors.ifEmpty { null }
                    if (scoreVal != null) this.score = Score.from10(scoreVal)
                }
            } else {
                // Movie: Gather all download items sorted by decreasing file size
                val downloadItems = scrapedDownloads.filter { !it.isZip }.map {
                    MovieDownloadLink(name = it.quality ?: "Download", url = it.url, quality = it.quality, size = it.size)
                }.sortedWith(
                    compareByDescending<MovieDownloadLink> {
                        DesireMoviesHubCloud.parseSizeToBytes(it.size ?: it.name, DesireMoviesHubCloud.getQualityInt(it.quality ?: it.name))
                    }.thenByDescending {
                        DesireMoviesHubCloud.getQualityInt(it.quality ?: it.name)
                    }
                )
                val moviePayload = mapper.writeValueAsString(downloadItems)

                return newMovieLoadResponse(title, targetUrl, type, moviePayload) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.plot = plot
                    this.tags = cleanTags.ifEmpty { null }
                    this.actors = actors.ifEmpty { null }
                    if (scoreVal != null) this.score = Score.from10(scoreVal)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val downloadItems: List<MovieDownloadLink> = try {
            mapper.readValue(data)
        } catch (e: Exception) {
            return false
        }

        if (downloadItems.isEmpty()) return false

        // Sort download items by decreasing file size
        val sortedItems = downloadItems.sortedWith(
            compareByDescending<MovieDownloadLink> {
                DesireMoviesHubCloud.parseSizeToBytes(it.size ?: it.name, DesireMoviesHubCloud.getQualityInt(it.quality ?: it.name))
            }.thenByDescending {
                DesireMoviesHubCloud.getQualityInt(it.quality ?: it.name)
            }
        )

        data class ExtLinkWithSize(
            val link: ExtractorLink,
            val sizeBytes: Long,
            val serverPriority: Int
        )

        val collectedLinks = Collections.synchronizedList(mutableListOf<ExtLinkWithSize>())

        fun getServerPriority(source: String): Int {
            val s = source.lowercase()
            return when {
                s.contains("10gbps") -> 5
                s.contains("fsl") || s.contains("r2") -> 4
                s.contains("fast") || s.contains("stream") -> 3
                s.contains("bypass") -> 2
                s.contains("pixeldrain") -> 1
                else -> 0
            }
        }

        coroutineScope {
            sortedItems.map { item ->
                async {
                    val itemBytes = DesireMoviesHubCloud.parseSizeToBytes(
                        item.size ?: item.name,
                        DesireMoviesHubCloud.getQualityInt(item.quality ?: item.name)
                    )

                    val itemCollector: (ExtractorLink) -> Unit = { extLink ->
                        val linkBytes = DesireMoviesHubCloud.parseSizeToBytes(extLink.name, extLink.quality).takeIf { it > 0L } ?: itemBytes
                        val priority = getServerPriority(extLink.source)
                        collectedLinks.add(ExtLinkWithSize(extLink, linkBytes, priority))
                    }

                    val itemUrl = item.url
                    if (itemUrl.contains("gyanigurus.") || itemUrl.contains("/view/")) {
                        try {
                            val viewResp = app.get(itemUrl, headers = requestHeaders, timeout = 12L)
                            if (viewResp.isSuccessful) {
                                val viewDoc = viewResp.document
                                val hubLinks = viewDoc.select("a[href*='hubcloud.'], a[href*='hubc.']")
                                val gdflixLinks = viewDoc.select("a[href*='gdflix.'], a[href*='only-apis']")
                                val hubdriveLinks = viewDoc.select("a[href*='hubdrive.']")
                                val seenInGateway = mutableSetOf<String>()

                                coroutineScope {
                                    val t1 = async {
                                        for (hubLink in hubLinks) {
                                            val hubUrl = hubLink.attr("href").trim()
                                            if (hubUrl.isNotBlank() && seenInGateway.add(hubUrl)) {
                                                DesireMoviesHubCloud.resolveHubCloud(hubUrl, item.name, item.quality, item.size, itemCollector)
                                            }
                                        }
                                    }
                                    val t2 = async {
                                        for (gdfLink in gdflixLinks) {
                                            val gdfUrl = gdfLink.attr("href").trim()
                                            if (gdfUrl.isNotBlank() && seenInGateway.add(gdfUrl)) {
                                                DesireMoviesHubCloud.resolveGDFlix(gdfUrl, item.name, item.quality, item.size, itemCollector)
                                            }
                                        }
                                    }
                                    val t3 = async {
                                        for (hdLink in hubdriveLinks) {
                                            val hdUrl = hdLink.attr("href").trim()
                                            if (hdUrl.isNotBlank() && seenInGateway.add(hdUrl)) {
                                                try {
                                                    val hdResp = app.get(hdUrl, headers = requestHeaders, timeout = 10L)
                                                    if (hdResp.isSuccessful) {
                                                        val hubInHd = hdResp.document.selectFirst("a[href*='hubcloud']")?.attr("href")?.trim()
                                                        if (!hubInHd.isNullOrBlank() && seenInGateway.add(hubInHd)) {
                                                            DesireMoviesHubCloud.resolveHubCloud(hubInHd, item.name, item.quality, item.size, itemCollector)
                                                        }
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                    t1.await()
                                    t2.await()
                                    t3.await()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (itemUrl.contains("hubcloud") || itemUrl.contains("hubc")) {
                        DesireMoviesHubCloud.resolveHubCloud(itemUrl, item.name, item.quality, item.size, itemCollector)
                    } else if (itemUrl.contains("gdflix") || itemUrl.contains("only-apis")) {
                        DesireMoviesHubCloud.resolveGDFlix(itemUrl, item.name, item.quality, item.size, itemCollector)
                    } else {
                        loadExtractor(itemUrl, subtitleCallback, itemCollector)
                    }
                }
            }.awaitAll()
        }

        // Sort all extracted links strictly based on DECREASING file size, then quality, then server priority
        val sortedLinks = collectedLinks
            .distinctBy { it.link.url }
            .sortedWith(
                compareByDescending<ExtLinkWithSize> { it.sizeBytes }
                    .thenByDescending { it.link.quality }
                    .thenByDescending { it.serverPriority }
            )

        for (item in sortedLinks) {
            callback(item.link)
        }

        return sortedLinks.isNotEmpty()
    }
}
