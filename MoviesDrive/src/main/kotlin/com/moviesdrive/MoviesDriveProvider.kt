package com.moviesdrive

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

class MoviesDriveProvider(private val pluginContext: Context? = null) : MainAPI() {
    override var mainUrl = MoviesDrivePreferences.getSavedDomain(pluginContext)
    override var name = "MoviesDrive"
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
        get() = MoviesDrivePreferences.getActiveCategories(pluginContext, mainUrl)

    // ==========================================
    // AUTO DOMAIN DISCOVERY & HEALTH VERIFICATION
    // ==========================================

    private suspend fun verifyDomain(candidateUrl: String): Boolean {
        val cleanUrl = candidateUrl.trim().removeSuffix("/")
        if (!cleanUrl.startsWith("http")) return false
        return try {
            val resp = app.get("$cleanUrl/wp-json/", headers = requestHeaders, timeout = 5L)
            resp.isSuccessful && (resp.text.contains("moviesdrive", ignoreCase = true) || resp.text.contains("name", ignoreCase = true))
        } catch (_: Exception) {
            try {
                val resp2 = app.get("$cleanUrl/", headers = requestHeaders, timeout = 5L)
                resp2.isSuccessful && resp2.text.contains("MoviesDrive", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
        }
    }

    private suspend fun resolveActiveDomain(): String {
        val current = mainUrl.removeSuffix("/")

        // 1. Verify if current domain is still healthy
        if (verifyDomain(current)) {
            return current
        }

        // 2. Check GitHub Remote Config file for immediate instant domain migration
        try {
            val remoteResp = app.get(
                "https://raw.githubusercontent.com/deep12323/cloudstream-plugins/master/moviesdrive_domain.txt",
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

        // 3. Sequential Domain Probing (e.g. new4 -> new5, new6, new7, new8, new9, new10)
        val numMatch = Regex("""(?i)https?://new(\d+)\.moviesdrive""").find(current)
        val currentNum = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 4
        for (nextNum in (currentNum + 1)..(currentNum + 6)) {
            val candidate = "https://new$nextNum.moviesdrive.cyou"
            if (verifyDomain(candidate)) {
                updateDomain(candidate)
                return candidate
            }
        }

        // 4. Check official redirect mirrors (moviesdrives.mov, moviedrive.org, moviesdrives.cfd)
        val mirrors = listOf(
            "https://moviesdrives.mov",
            "https://moviedrive.org",
            "https://moviesdrives.cfd"
        )
        for (mirror in mirrors) {
            try {
                val resp = app.get(mirror, headers = requestHeaders, timeout = 5L)
                val finalUrl = resp.okhttpResponse.request.url.toString().removeSuffix("/")
                if (finalUrl.startsWith("http") && !finalUrl.contains("moviesdrives.bid") && verifyDomain(finalUrl)) {
                    updateDomain(finalUrl)
                    return finalUrl
                }
                val extractedDomain = Regex("""(?i)https?://(?:new\d*|www\.)?moviesdrive\.[a-z]{2,6}""").find(resp.text)?.value
                if (extractedDomain != null && extractedDomain != current && verifyDomain(extractedDomain)) {
                    updateDomain(extractedDomain)
                    return extractedDomain
                }
            } catch (_: Exception) {}
        }

        return current
    }

    private fun updateDomain(newDomain: String) {
        val clean = newDomain.trim().removeSuffix("/")
        mainUrl = clean
        MoviesDrivePreferences.setSavedDomain(pluginContext, clean)
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

        // Remove episode notifications e.g. (S20 Ep76 Added)
        t = t.replace(Regex("""(?i)\s*\([S\d\s]+Ep\d+\s*Added\)"""), "")

        // Remove site branding & full movie / series markers
        t = t.replace(Regex("""(?i)\s*[-–—|]?\s*MoviesDrives?(\.bid|\.cyou|\.cfd|\.mov|\.org)?.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Full Movie.*$"""), "")
            .replace(Regex("""(?i)\s*\|\s*Complete Web Series.*$"""), "")
            .replace(Regex("""(?i)\bComplete\s*(?:Web\s*)?Series\b"""), "")
            .replace(Regex("""(?i)\bFull\s*Movie\b"""), "")

        // Remove language names that appear right before OTT / format tags
        t = t.replace(Regex("""(?i)\b(?:Hindi|English|Tamil|Telugu|Malayalam|Kannada|Chinese|Korean|Japanese|Odia|Bengali|Dual|Multi)\s*(?=(?:Atrangii|Netflix|Hotstar|Amazon|Zee5|SonyLiv|Series|WEB-DL|480p|720p|1080p|2160p|Complete|Full))"""), "")

        // Remove OTT platforms e.g. Netflix, JioHotstar, etc.
        t = t.replace(Regex("""(?i)\b(?:NETFLIX|Netflix|JioHotstar|Hotstar|Atrangii|Amazon(?:\s*Prime)?|Zee5|SonyLiv|Apple\s*TV\+?)\s*(?:Series|Korean Drama|Drama)?\b"""), "")

        // Remove bracketed audio info
        t = t.replace(Regex("""(?i)\[(?:Hindi|English|Tamil|Telugu|Malayalam|Kannada|Chinese|Dutch|Korean|Japanese|Odia|Bengali|Punjabi|Marathi|Gujarati|Russian|Spanish|French|German|Italian|Thai|Indonesian|Turkish|Dual|Multi)[^\]]*\]"""), "")
        t = t.replace(Regex("""(?i)\{(?:Hindi|English|Tamil|Telugu|Malayalam|Kannada|Chinese|Dutch|Korean|Japanese|Odia|Bengali|Punjabi|Marathi|Gujarati|Russian|Spanish|French|German|Italian|Thai|Indonesian|Turkish|Dual|Multi)[^}]*\}"""), "")
        t = t.replace(Regex("""(?i)\[[xX]264/ESubs?\]"""), "")
        t = t.replace(Regex("""(?i)\[ESubs?\]"""), "")

        // Remove video quality, codecs, sources
        t = t.replace(Regex("""(?i)\b(?:480p|720p|1080p|2160p|4K|SDR|HDR|DV|10Bit|x264|x265|HEVC|DS4K|WEB-DL|WEBRip|HDRip|BluRay|HDTV)\b"""), "")
        t = t.replace(Regex("""(?i)\b(?:Dual\s*Audio|Multi\s*Audio|Hindi\s*Dubbed|UnOfficial\s*Dubbed|Television|Korean\s*Drama)\b"""), "")
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
        val linkElem = when {
            element.tagName().equals("a", ignoreCase = true) -> element
            element.parent()?.tagName()?.equals("a", ignoreCase = true) == true -> element.parent()
            else -> element.selectFirst("a") ?: element.closest("a") ?: element.parents().firstOrNull { it.tagName().equals("a", ignoreCase = true) }
        }
        val rawHref = linkElem?.attr("href")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val itemUrl = if (rawHref.startsWith("http")) rawHref else "$mainUrl${if (rawHref.startsWith("/")) "" else "/"}$rawHref"

        val imgElem = element.selectFirst("img") ?: linkElem.selectFirst("img")
        val rawTitle = element.selectFirst("p.poster-title, .poster-title")?.text()
            ?: linkElem.selectFirst("p.poster-title, .poster-title")?.text()
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
        val activeTarget = if (baseTarget.contains("moviesdrive.")) {
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
            val cards = doc.select(".movies-grid > a, div.poster-card")
            val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }

            val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next, .pagination a.next-btn, a:contains(Next)") != null || items.size >= 24

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
                        val cards = doc.select(".movies-grid > a, div.poster-card")
                        val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }
                        val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next, .pagination a.next-btn, a:contains(Next)") != null || items.size >= 24
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

        fun parseTypesense(body: String): SearchResponseList? {
            if (!body.trim().startsWith("{")) return null
            val tsData: TypesenseResponse = mapper.readValue(body)
            val hits = tsData.hits ?: emptyList()
            val items = hits.mapNotNull { hit ->
                val doc = hit.document ?: return@mapNotNull null
                val rawTitle = doc.post_title ?: return@mapNotNull null
                val permalink = doc.permalink ?: return@mapNotNull null
                val fullUrl = if (permalink.startsWith("http")) permalink else "$mainUrl${if (permalink.startsWith("/")) "" else "/"}$permalink"
                val title = cleanTitle(rawTitle)
                val isSeries = isTvSeries(rawTitle, fullUrl) ||
                        (doc.category?.any { it.contains("series", ignoreCase = true) || it.contains("web", ignoreCase = true) } == true)
                val year = extractYear(rawTitle)
                val posterUrl = doc.post_thumbnail

                if (isSeries) {
                    newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                        this.year = year
                    }
                } else {
                    newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                        this.posterUrl = posterUrl
                        this.year = year
                    }
                }
            }
            if (items.isEmpty()) return null
            val found = tsData.found ?: 0
            return newSearchResponseList(items, (page * 15) < found)
        }

        // 1. Primary: Fast Typesense JSON search
        val currentBase = mainUrl.removeSuffix("/")
        val typesenseUrl = "$currentBase/search.php?q=$encodedQuery&page=$page"
        try {
            var response = app.get(typesenseUrl, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) {
                val newDomain = resolveActiveDomain()
                if (newDomain != currentBase) {
                    response = app.get("$newDomain/search.php?q=$encodedQuery&page=$page", headers = requestHeaders, timeout = 15L)
                }
            }
            if (response.isSuccessful) {
                parseTypesense(response.text)?.let { return it }
            }
        } catch (e: Exception) {
            val newDomain = resolveActiveDomain()
            if (newDomain != currentBase) {
                try {
                    val retryResp = app.get("$newDomain/search.php?q=$encodedQuery&page=$page", headers = requestHeaders, timeout = 15L)
                    if (retryResp.isSuccessful) {
                        parseTypesense(retryResp.text)?.let { return it }
                    }
                } catch (_: Exception) {}
            }
            e.printStackTrace()
        }

        // 2. Secondary: WordPress REST API Search
        val wpApiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$encodedQuery&page=$page&per_page=20"
        try {
            val response = app.get(wpApiUrl, headers = mapOf("User-Agent" to requestHeaders["User-Agent"]!!, "Accept" to "application/json"), timeout = 15L)
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

                return newSearchResponseList(items, hasNext)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Fallback: Official mirror search (moviesdrives.bid)
        val mirrorBase = "https://moviesdrives.bid"
        try {
            val mirrorTs = "$mirrorBase/search.php?q=$encodedQuery&page=$page"
            val response = app.get(mirrorTs, headers = requestHeaders, timeout = 12L)
            if (response.isSuccessful && response.text.trim().startsWith("{")) {
                val tsData: TypesenseResponse = mapper.readValue(response.text)
                val hits = tsData.hits ?: emptyList()
                val items = hits.mapNotNull { hit ->
                    val doc = hit.document ?: return@mapNotNull null
                    val rawTitle = doc.post_title ?: return@mapNotNull null
                    val permalink = doc.permalink ?: return@mapNotNull null
                    val fullUrl = if (permalink.startsWith("http")) permalink else "$mainUrl${if (permalink.startsWith("/")) "" else "/"}$permalink"
                    val title = cleanTitle(rawTitle)
                    val isSeries = isTvSeries(rawTitle, fullUrl)
                    val year = extractYear(rawTitle)
                    val posterUrl = doc.post_thumbnail

                    if (isSeries) {
                        newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    } else {
                        newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                            this.posterUrl = posterUrl
                            this.year = year
                        }
                    }
                }
                if (items.isNotEmpty()) {
                    val found = tsData.found ?: 0
                    return newSearchResponseList(items, (page * 15) < found)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Fallback to HTML search (with auto-domain recovery)
        val htmlUrl = if (page <= 1) "$mainUrl/?s=$encodedQuery" else "$mainUrl/page/$page/?s=$encodedQuery"
        try {
            var response = app.get(htmlUrl, headers = requestHeaders, timeout = 15L)
            if (!response.isSuccessful) {
                val newDomain = resolveActiveDomain()
                if (newDomain != mainUrl) {
                    val retryUrl = htmlUrl.replace(mainUrl, newDomain)
                    response = app.get(retryUrl, headers = requestHeaders, timeout = 15L)
                }
            }
            if (!response.isSuccessful) return newSearchResponseList(emptyList(), false)

            val doc = response.document
            val cards = doc.select(".movies-grid > a, div.poster-card")
            val items = cards.mapNotNull { parsePosterCard(it) }.distinctBy { it.url }
            val hasNext = doc.selectFirst("a.next-btn, a.next, .pagination a.next") != null || items.size >= 24

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
            val targetUrl = if (url.contains("moviesdrive.")) {
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
                ?: doc.selectFirst(".entry-content img, .page-body img")?.attr("src")

            // 1. Audio Language Tag extraction
            val docHtml = doc.html()
            val langMatch = Regex("""(?i)(?<!Original\s)(?<!Native\s)\b(?:Language|Audio)\s*:\s*([^<\n\r]+|<[^>]+>[^<]+</[^>]+>)""").find(docHtml)
            var rawLang = langMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), " ") ?: ""
            if (rawLang.isBlank()) {
                val tMatch = Regex("""(?i)\[((?:Hindi|English|Tamil|Telugu|Malayalam|Kannada|Chinese|Korean|Japanese|Odia|Bengali|Marathi|Punjabi|Dual|Multi)[^\]]*)\]""").find(rawTitle)
                if (tMatch != null) rawLang = tMatch.groupValues[1]
            }
            if (rawLang.isBlank()) {
                val cMatch = Regex("""\{([^}]+)\}""").find(rawTitle)
                if (cMatch != null) rawLang = cMatch.groupValues[1]
            }
            val cleanLang = rawLang
                .replace("&#038;", "&")
                .replace("&amp;", "&")
                .replace(Regex("""(?i)\s*/\s*ESubs?.*$"""), "")
                .replace(Regex("""^[\[\{]+|[\]\}]+$"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
            val audioTag = if (cleanLang.isNotBlank() && cleanLang.length < 60) "Audio: $cleanLang" else null

            // 2. Plot extraction
            val rawPlot = doc.selectFirst(
                "h3:contains(Storyline) + p, h3:contains(Storyline) + div, h3:contains(Storyline) + h5, " +
                "h4:contains(Storyline) + p, h4:contains(Storyline) + div, h4:contains(Storyline) + h5, " +
                "h5:contains(Storyline) + p, h5:contains(Storyline) + div, " +
                "p:contains(Storyline)"
            )?.text() ?: run {
                val storyHeading = doc.selectFirst("h1:contains(Storyline), h2:contains(Storyline), h3:contains(Storyline), h4:contains(Storyline), h5:contains(Storyline)")
                storyHeading?.nextElementSiblings()?.firstOrNull { it.text().isNotBlank() && !it.tagName().startsWith("h") }?.text()
            }

            val plot = rawPlot?.let { raw ->
                var p = raw.replace("&#038;", "&").replace("&amp;", "&")
                    .replace(Regex("""^(?:[\s♦:–-]+|Storyline\s*:?-?\s*|Synopsis\s*:?-?\s*|Plot\s*:?-?\s*)+""", RegexOption.IGNORE_CASE), "")

                // Remove leading repeated title or year: e.g. 'Buddha 2: The Endless Journey (2014) '
                val escapedTitle = Regex.escape(title.trim())
                p = p.replace(Regex("""^(?:$escapedTitle)?\s*(?:\((?:19|20)\d{2}\)|\b(?:19|20)\d{2}\b)?\s*[-:–—]*\s*""", RegexOption.IGNORE_CASE), "")
                p = p.replace(Regex("""^[^\w]*(?:[A-Za-z0-9\s:’'.-]+\((?:19|20)\d{2}\)|\b(?:19|20)\d{2}\b)[-:–—\s]*"""), "")

                // Cut off trailing screenshots or download clutter
                p = p.replace(Regex("""(?i)(?:\b(?:Screen-?Shots|Screenshots|Download\s*Links|DOWNLOAD\s*LINKS|Full\s*Movie|Watch\s*Online)\b|>{2,}|-{2,}).*$"""), "")
                p.replace(Regex("""\s{2,}"""), " ").trim()
            }?.takeIf { it.isNotBlank() }

            // 3. Rating
            val ratingText = doc.selectFirst("div:contains(iMDB Rating), p:contains(iMDB Rating)")?.text()
            val ratingMatch = Regex("""(\d+(?:\.\d+)?)\s*/\s*10""").find(ratingText ?: "")
            val scoreVal = ratingMatch?.groupValues?.get(1)?.toDoubleOrNull()

            // 4. Tags / Genres
            val genreMatch = Regex("""(?i)\bGenre\s*:\s*([^<\n\r]+|<[^>]+>[^<]+</[^>]+>)""").find(docHtml)
            var rawGenre = genreMatch?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), " ") ?: ""
            for (stop in listOf("Country:", "Language:", "Stars:", "Quality:", "Director:", "Writer:", "Format:")) {
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
            for (stop in listOf("Language:", "Quality:", "Format:", "Storyline:", "Country:", "Director:", "Writer:")) {
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

            // Parse download anchors and season context
            val contentElem = doc.selectFirst(".entry-content, .page-body, .post-layout, .post-content") ?: doc
            val allElements = contentElem.select("h1, h2, h3, h4, h5, h6, p")

            data class ScrapedDownload(
                val season: Int,
                val text: String,
                val url: String,
                val isSingleEpisode: Boolean,
                val isZip: Boolean,
                val quality: String?,
                val size: String?
            )

            val scrapedDownloads = mutableListOf<ScrapedDownload>()
            val seenHrefs = mutableSetOf<String>()
            var currentHeadingQuality: String? = null

            for (elem in allElements) {
                val text = elem.text().trim()
                if (text.isBlank()) continue

                val seasonMatch = Regex("""(?i)\bSeason\s*0*(\d+)\b""").find(text)
                if (seasonMatch != null && !text.contains("Single Episode", ignoreCase = true) && !text.contains("Zip", ignoreCase = true)) {
                    currentSeason = seasonMatch.groupValues[1].toIntOrNull() ?: currentSeason
                }

                val singleQualities = Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).findAll(text).toList()
                if (singleQualities.size == 1 && (elem.tagName().matches(Regex("h[1-6]")) || !text.contains("Download", ignoreCase = true))) {
                    currentHeadingQuality = singleQualities.first().value
                }

                val anchors = elem.select("a[href*='mdrive.'], a[href*='hubcloud.'], a[href*='hubcloud'], a[href*='hubc.'], a[href*='gdflix.'], a[href*='only-apis.com'], a[href*='only-apis']")
                for (anchor in anchors) {
                    val href = anchor.attr("href").trim()
                    val linkText = anchor.text().trim()
                    if (href.isBlank() || !href.startsWith("http") || !seenHrefs.add(href)) continue

                    val isSingle = linkText.contains("Single Episode", ignoreCase = true) || href.contains("single", ignoreCase = true)
                    val isZip = linkText.contains("Zip", ignoreCase = true) || href.contains("zip", ignoreCase = true)

                    val linkQuality = Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).find(linkText)?.value
                        ?: Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).find(text)?.value
                        ?: currentHeadingQuality
                    val linkSize = Regex("""\[([0-9.]+\s*(?:GB|MB))\]""", RegexOption.IGNORE_CASE).find(linkText)?.groupValues?.get(1)
                        ?: Regex("""\[([0-9.]+\s*(?:GB|MB))\]""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

                    scrapedDownloads.add(
                        ScrapedDownload(
                            season = currentSeason,
                            text = linkText.ifBlank { "Download" },
                            url = href,
                            isSingleEpisode = isSingle,
                            isZip = isZip,
                            quality = linkQuality,
                            size = linkSize
                        )
                    )
                }
            }

            // Fallback: check any remaining download anchors outside standard h1-h6/p tags
            val remainingAnchors = contentElem.select("a[href*='mdrive.'], a[href*='hubcloud.'], a[href*='hubcloud'], a[href*='hubc.'], a[href*='gdflix.'], a[href*='only-apis.com'], a[href*='only-apis']")
            for (anchor in remainingAnchors) {
                val href = anchor.attr("href").trim()
                val linkText = anchor.text().trim()
                val parentText = anchor.parent()?.text()?.trim() ?: ""
                val combinedText = "$linkText $parentText"
                if (href.isBlank() || !href.startsWith("http") || !seenHrefs.add(href)) continue

                val isSingle = combinedText.contains("Single Episode", ignoreCase = true) || href.contains("single", ignoreCase = true)
                val isZip = combinedText.contains("Zip", ignoreCase = true) || href.contains("zip", ignoreCase = true)

                val linkQuality = Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).find(combinedText)?.value
                    ?: currentHeadingQuality
                val linkSize = Regex("""\[([0-9.]+\s*(?:GB|MB))\]""", RegexOption.IGNORE_CASE).find(combinedText)?.groupValues?.get(1)

                scrapedDownloads.add(
                    ScrapedDownload(
                        season = currentSeason,
                        text = linkText.ifBlank { "Download" },
                        url = href,
                        isSingleEpisode = isSingle,
                        isZip = isZip,
                        quality = linkQuality,
                        size = linkSize
                    )
                )
            }

            if (isSeries) {
                val episodesList = mutableListOf<Episode>()

                // Group all single episode archive links by season
                val singleEpBySeason = scrapedDownloads.filter { it.isSingleEpisode }.groupBy { it.season }

                if (singleEpBySeason.isNotEmpty()) {
                    coroutineScope {
                        singleEpBySeason.map { (seasonNum, seasonLinks) ->
                            async {
                                val seasonEpDownloadsMap = mutableMapOf<Int, MutableList<MovieDownloadLink>>()
                                val seasonEpQualitiesMap = mutableMapOf<Int, MutableSet<String>>()

                                // Concurrently fetch ALL quality archives for this season
                                coroutineScope {
                                    seasonLinks.map { archive ->
                                        async {
                                            try {
                                                val epResp = app.get(archive.url, headers = requestHeaders, timeout = 12L)
                                                if (epResp.isSuccessful) {
                                                    val epDoc = epResp.document
                                                    val seenLinksInArchive = mutableSetOf<String>()

                                                    // Method 1: Split archive by <hr> tags (standard layout on mdrive.lol archives)
                                                    val sections = epDoc.html().split(Regex("""(?i)<hr[^>]*>"""))
                                                    for (sec in sections) {
                                                        val secDoc = Jsoup.parseBodyFragment(sec)
                                                        val secText = secDoc.text()
                                                        val epMatch = Regex("""(?i)(?:^|\b)(?:EP|Episode|E)[\s.-]*0*(\d+)\b""").find(secText)
                                                        if (epMatch != null) {
                                                            val epNum = epMatch.groupValues[1].toIntOrNull() ?: continue
                                                            val qMatch = Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).find(secText)?.value
                                                            val sMatch = Regex("""\[([0-9.]+\s*(?:GB|MB))\]""", RegexOption.IGNORE_CASE).find(secText)?.groupValues?.get(1)
                                                            val links = secDoc.select("a[href*='hubcloud.'], a[href*='hubcloud'], a[href*='hubc.'], a[href*='gdflix.'], a[href*='only-apis.com'], a[href*='only-apis']")
                                                            for (link in links) {
                                                                val h = link.attr("href").trim()
                                                                if (h.isNotBlank() && h.startsWith("http") && seenLinksInArchive.add(h)) {
                                                                    val itemDl = MovieDownloadLink(
                                                                        name = "Episode $epNum",
                                                                        url = h,
                                                                        quality = qMatch ?: archive.quality,
                                                                        size = sMatch ?: archive.size
                                                                    )
                                                                    synchronized(seasonEpDownloadsMap) {
                                                                        seasonEpDownloadsMap.getOrPut(epNum) { mutableListOf() }.add(itemDl)
                                                                        val q = qMatch ?: archive.quality
                                                                        if (q != null) seasonEpQualitiesMap.getOrPut(epNum) { mutableSetOf() }.add(q)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }

                                                    // Method 2: Fallback to leaf elements (excluding container divs) if <hr> didn't catch links
                                                    if (seenLinksInArchive.isEmpty()) {
                                                        val epContent = epDoc.selectFirst(".entry-content, .page-body") ?: epDoc
                                                        val leafElements = epContent.select("h1, h2, h3, h4, h5, h6, p").filter { it.select("h1, h2, h3, h4, h5, h6, p").isEmpty() }
                                                        var currentEpNum = 1
                                                        var currentQuality = archive.quality
                                                        var currentSize = archive.size

                                                        for (el in leafElements) {
                                                            val elText = el.text()
                                                            val epMatch = Regex("""(?i)(?:^|\b)(?:EP|Episode|E)[\s.-]*0*(\d+)\b""").find(elText)
                                                            if (epMatch != null) {
                                                                currentEpNum = epMatch.groupValues[1].toIntOrNull() ?: currentEpNum
                                                                val qMatch = Regex("""\b(2160p|4k|1080p|720p|480p)\b""", RegexOption.IGNORE_CASE).find(elText)?.value
                                                                if (qMatch != null) currentQuality = qMatch
                                                                val sMatch = Regex("""\[([0-9.]+\s*(?:GB|MB))\]""", RegexOption.IGNORE_CASE).find(elText)?.groupValues?.get(1)
                                                                if (sMatch != null) currentSize = sMatch
                                                            }

                                                            val links = el.select("a[href*='hubcloud.'], a[href*='hubcloud'], a[href*='hubc.'], a[href*='gdflix.'], a[href*='only-apis.com'], a[href*='only-apis']")
                                                            for (link in links) {
                                                                val h = link.attr("href").trim()
                                                                if (h.isNotBlank() && h.startsWith("http") && seenLinksInArchive.add(h)) {
                                                                    val itemDl = MovieDownloadLink(
                                                                        name = "Episode $currentEpNum",
                                                                        url = h,
                                                                        quality = currentQuality,
                                                                        size = currentSize
                                                                    )
                                                                    synchronized(seasonEpDownloadsMap) {
                                                                        seasonEpDownloadsMap.getOrPut(currentEpNum) { mutableListOf() }.add(itemDl)
                                                                        if (currentQuality != null) {
                                                                            seasonEpQualitiesMap.getOrPut(currentEpNum) { mutableSetOf() }.add(currentQuality)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }

                                                    // Method 3: Fallback if no EP headings in doc: map sequentially
                                                    if (seasonEpDownloadsMap.isEmpty()) {
                                                        val allEpAnchors = epDoc.select("a[href*='hubcloud.'], a[href*='hubcloud'], a[href*='hubc.'], a[href*='gdflix.'], a[href*='only-apis.com'], a[href*='only-apis']")
                                                        for ((idx, anchor) in allEpAnchors.withIndex()) {
                                                            val h = anchor.attr("href").trim()
                                                            if (h.isNotBlank() && h.startsWith("http") && seenLinksInArchive.add(h)) {
                                                                val epN = idx + 1
                                                                val itemDl = MovieDownloadLink(
                                                                    name = "Episode $epN",
                                                                    url = h,
                                                                    quality = archive.quality,
                                                                    size = archive.size
                                                                )
                                                                synchronized(seasonEpDownloadsMap) {
                                                                    seasonEpDownloadsMap.getOrPut(epN) { mutableListOf() }.add(itemDl)
                                                                    if (archive.quality != null) {
                                                                        seasonEpQualitiesMap.getOrPut(epN) { mutableSetOf() }.add(archive.quality)
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        }
                                    }.awaitAll()
                                }

                                seasonEpDownloadsMap.toSortedMap().forEach { (epNum, linkList) ->
                                    val sortedLinkList = linkList.sortedWith(
                                        compareByDescending<MovieDownloadLink> {
                                            MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.name, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name))
                                        }.thenByDescending {
                                            MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name)
                                        }
                                    )
                                    val epPayload = mapper.writeValueAsString(sortedLinkList)

                                    synchronized(episodesList) {
                                        episodesList.add(
                                            newEpisode(epPayload) {
                                                this.name = "Episode $epNum"
                                                this.season = seasonNum
                                                this.episode = epNum
                                                this.posterUrl = posterUrl
                                            }
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }

                // If no single episode archives existed (e.g. mini-series with direct links), add direct downloads as episodes
                if (episodesList.isEmpty()) {
                    val directDownloads = scrapedDownloads.filter { !it.isZip }.sortedWith(
                        compareByDescending<ScrapedDownload> {
                            MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.text, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.text))
                        }.thenByDescending {
                            MoviesDriveHubCloud.getQualityInt(it.quality ?: it.text)
                        }
                    )
                    for ((idx, dl) in directDownloads.withIndex()) {
                        val payload = mapper.writeValueAsString(
                            listOf(MovieDownloadLink(name = "Episode ${idx + 1}", url = dl.url, quality = dl.quality, size = dl.size))
                        )
                        episodesList.add(
                            newEpisode(payload) {
                                this.name = "Episode ${idx + 1}"
                                this.season = dl.season
                                this.episode = idx + 1
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }

                // Add Zip packs in Season 0 / Specials, clearly displaying the Season name!
                val zipPacks = scrapedDownloads.filter { it.isZip }.sortedWith(
                    compareByDescending<ScrapedDownload> { it.season }
                        .thenByDescending {
                            MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.text, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.text))
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
                        MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.name, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name))
                    }.thenByDescending {
                        MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name)
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
                MoviesDriveHubCloud.parseSizeToBytes(it.size ?: it.name, MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name))
            }.thenByDescending {
                MoviesDriveHubCloud.getQualityInt(it.quality ?: it.name)
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
                    val itemBytes = MoviesDriveHubCloud.parseSizeToBytes(
                        item.size ?: item.name,
                        MoviesDriveHubCloud.getQualityInt(item.quality ?: item.name)
                    )

                    val itemCollector: (ExtractorLink) -> Unit = { extLink ->
                        val linkBytes = MoviesDriveHubCloud.parseSizeToBytes(extLink.name, extLink.quality).takeIf { it > 0L } ?: itemBytes
                        val priority = getServerPriority(extLink.source)
                        collectedLinks.add(ExtLinkWithSize(extLink, linkBytes, priority))
                    }

                    val itemUrl = item.url
                    if (itemUrl.contains("mdrive.")) {
                        try {
                            val archiveResp = app.get(itemUrl, headers = requestHeaders, timeout = 12L)
                            if (archiveResp.isSuccessful) {
                                val archiveDoc = archiveResp.document
                                val hubLinks = archiveDoc.select("a[href*='hubcloud'], a[href*='hubc']")
                                val gdflixLinks = archiveDoc.select("a[href*='gdflix'], a[href*='only-apis']")
                                val seenInArchive = mutableSetOf<String>()

                                coroutineScope {
                                    val t1 = async {
                                        for (hubLink in hubLinks) {
                                            val hubUrl = hubLink.attr("href").trim()
                                            if (hubUrl.isNotBlank() && seenInArchive.add(hubUrl)) {
                                                MoviesDriveHubCloud.resolveHubCloud(hubUrl, item.name, item.quality, item.size, itemCollector)
                                            }
                                        }
                                    }
                                    val t2 = async {
                                        for (gdfLink in gdflixLinks) {
                                            val gdfUrl = gdfLink.attr("href").trim()
                                            if (gdfUrl.isNotBlank() && seenInArchive.add(gdfUrl)) {
                                                MoviesDriveHubCloud.resolveGDFlix(gdfUrl, item.name, item.quality, item.size, itemCollector)
                                            }
                                        }
                                    }
                                    t1.await()
                                    t2.await()
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    } else if (itemUrl.contains("hubcloud") || itemUrl.contains("hubc")) {
                        MoviesDriveHubCloud.resolveHubCloud(itemUrl, item.name, item.quality, item.size, itemCollector)
                    } else if (itemUrl.contains("gdflix") || itemUrl.contains("only-apis")) {
                        MoviesDriveHubCloud.resolveGDFlix(itemUrl, item.name, item.quality, item.size, itemCollector)
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
