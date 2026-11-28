package com.audiobookbay

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class AudioBookBayProvider(private val pluginContext: Context? = null) : MainAPI() {

    companion object {
        private const val PREFS_NAME = "AudioBookBayPrefs"
        private const val KEY_DOMAIN = "active_domain"
        const val DEFAULT_DOMAIN = "https://audiobookbay.lu"
        private const val FALLBACK_POSTER = "https://audiobookbay.lu/apple-touch-icon.png"

        private val AUDIO_FILE_ROW_REGEX = Regex(
            """^(.+\.(?:mp3|m4b|m4a|aac|ogg|opus|flac|wav|wma|mp4|mkv))\s+([\d.,]+\s*[KMGTP]?Bs?)$""",
            RegexOption.IGNORE_CASE
        )

        fun getSavedDomain(context: Context?): String {
            val saved = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_DOMAIN, DEFAULT_DOMAIN)
                ?.trim()
                ?.removeSuffix("/")
            return if (!saved.isNullOrBlank() && saved.startsWith("http")) saved else DEFAULT_DOMAIN
        }

        fun saveDomain(context: Context?, domain: String) {
            val clean = domain.trim().removeSuffix("/")
            context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(KEY_DOMAIN, clean)
                ?.apply()
        }
    }

    override var mainUrl = getSavedDomain(pluginContext)
    override var name = "AudioBookBay"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AudioBook, TvType.TvSeries, TvType.Torrent)
    override var lang = "en"

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    override val mainPage = mainPageOf(
        "/" to "Latest Audiobooks",
        "/audio-books/type/bestsellers/" to "Bestsellers",
        "/audio-books/type/sci-fi/" to "Sci-Fi",
        "/audio-books/type/fantasy/" to "Fantasy",
        "/audio-books/type/thriller/" to "Thriller",
        "/audio-books/type/mystery/" to "Mystery",
        "/audio-books/type/crime/" to "Crime",
        "/audio-books/type/action/" to "Action",
        "/audio-books/type/adventure/" to "Adventure",
        "/audio-books/type/horror/" to "Horror",
        "/audio-books/type/litrpg/" to "LitRPG",
        "/audio-books/type/light-novel/" to "Light Novel",
        "/audio-books/type/romance/" to "Romance",
        "/audio-books/type/general-fiction/" to "General Fiction",
        "/audio-books/type/historical-fiction/" to "Historical Fiction",
        "/audio-books/type/classic/" to "Classics",
        "/audio-books/type/full-cast/" to "Full Cast",
        "/audio-books/type/history/" to "History",
        "/audio-books/type/autobiography-biographies/" to "Biographies & Memoirs",
        "/audio-books/type/business/" to "Business",
        "/audio-books/type/self-help/" to "Self-Help",
        "/audio-books/type/science/" to "Science",
        "/audio-books/type/computer/" to "Computer & Tech",
        "/audio-books/tag/english/" to "English",
        "/audio-books/tag/spanish/" to "Spanish",
        "/audio-books/tag/french/" to "French",
        "/audio-books/tag/german/" to "German"
    )

    private fun cleanText(raw: String): String {
        return raw.replace("&#8217;", "'")
            .replace("&#8216;", "'")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
            .replace("&#8211;", "-")
            .replace("&#8212;", "—")
            .replace("&#038;", "&")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&hellip;", "...")
            .replace("\u00A0", " ")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
    }

    private fun resolveUrl(href: String): String {
        val base = mainUrl.removeSuffix("/")
        val trimmed = href.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$base$trimmed"
            else -> "$base/$trimmed"
        }
    }

    private fun rewriteToActiveHost(url: String): String {
        val base = mainUrl.removeSuffix("/")
        val path = url.replaceFirst(Regex("""^https?://[^/]+"""), "")
        return if (path.startsWith("/")) "$base$path" else "$base/$path"
    }

    private fun parsePosts(doc: Document): List<SearchResponse> {
        val items = mutableListOf<SearchResponse>()
        for (post in doc.select("div.post")) {
            val titleAnchor = post.selectFirst(".postTitle h2 a, .postTitle h1 a") ?: continue
            val rawTitle = cleanText(titleAnchor.text())
            val href = titleAnchor.attr("href").trim()
            if (rawTitle.isBlank() || href.isBlank()) continue

            val fullUrl = resolveUrl(href)
            val imgSrc = post.selectFirst(".postContent img")?.attr("src")?.trim()
            val poster = if (!imgSrc.isNullOrBlank()) resolveUrl(imgSrc) else FALLBACK_POSTER

            items.add(
                newMovieSearchResponse(rawTitle, fullUrl, TvType.AudioBook) {
                    this.posterUrl = poster
                }
            )
        }
        return items
    }

    private fun hasNextPage(doc: Document, currentPage: Int): Boolean {
        val navLinks = doc.select(".wp-pagenavi a")
        if (navLinks.isEmpty()) return false
        val nextPageStr = "/page/${currentPage + 1}"
        return navLinks.any { link ->
            val text = link.text().trim()
            val href = link.attr("href")
            text == "»" || text == "Next" || href.contains(nextPageStr)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val base = mainUrl.removeSuffix("/")
        val path = request.data.trim()
        val requestUrl = if (page <= 1) {
            if (path == "/") "$base/" else "$base${if (path.startsWith("/")) path else "/$path"}"
        } else {
            val cleanPath = path.trim('/')
            if (cleanPath.isEmpty()) "$base/page/$page/" else "$base/$cleanPath/page/$page/"
        }

        return try {
            val resp = app.get(requestUrl, headers = requestHeaders, timeout = 15L)
            if (!resp.isSuccessful) return null
            val doc = Jsoup.parse(resp.text, requestUrl)
            val items = parsePosts(doc)
            newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = hasNextPage(doc, page)
            )
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return newSearchResponseList(emptyList(), false)

        val base = mainUrl.removeSuffix("/")
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val searchUrl = if (page <= 1) {
            "$base/?s=$encoded"
        } else {
            "$base/page/$page/?s=$encoded"
        }

        return try {
            val resp = app.get(searchUrl, headers = requestHeaders, timeout = 15L)
            if (!resp.isSuccessful) return newSearchResponseList(emptyList(), false)
            val doc = Jsoup.parse(resp.text, searchUrl)
            val items = parsePosts(doc)
            newSearchResponseList(items, hasNextPage(doc, page))
        } catch (_: Exception) {
            newSearchResponseList(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        return search(query, 1)?.items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    private data class HtmlAudioFile(val fileName: String, val sizeLabel: String)

    override suspend fun load(url: String): LoadResponse? {
        val targetUrl = rewriteToActiveHost(url)
        val resp = try {
            app.get(targetUrl, headers = requestHeaders, timeout = 15L)
        } catch (_: Exception) {
            return null
        }
        if (!resp.isSuccessful) return null

        val doc = Jsoup.parse(resp.text, targetUrl)
        val post = doc.selectFirst("div.post") ?: return null

        val title = cleanText(
            post.selectFirst(".postTitle h1, .postTitle h2")?.text().orEmpty()
        ).ifBlank { "AudioBook" }

        val posterRaw = post.selectFirst(".postContent img[itemprop=image], .postContent .center img")?.attr("src")?.trim()
        val poster = if (!posterRaw.isNullOrBlank()) resolveUrl(posterRaw) else FALLBACK_POSTER

        val year = post.selectFirst("meta[itemprop=datePublished], meta[itemprop=uploadDate]")
            ?.attr("content")
            ?.take(4)
            ?.toIntOrNull()

        val author = post.selectFirst(".desc span.author, .desc span[itemprop=author]")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }
        val narrator = post.selectFirst(".desc span.narrator")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }
        val format = post.selectFirst(".desc span.format, .desc span[itemprop=encodingFormat]")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }
        val abridged = post.selectFirst(".desc span.is_abridged")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }
        val language = post.selectFirst(".postInfo span[itemprop=inLanguage]")?.text()?.let(::cleanText)?.takeIf { it.isNotBlank() }

        val categories = post.select(".postInfo a[rel=category tag]")
            .map { cleanText(it.text()) }
            .filter { it.isNotBlank() }

        // Parse torrent table: Info Hash, Trackers, File Size, and multifile entries
        var infoHash: String? = null
        var fileSizeLabel: String? = null
        val trackers = mutableListOf<String>()
        val htmlFiles = mutableListOf<HtmlAudioFile>()

        for (row in post.select("table tr")) {
            val cells = row.select("td")
            if (cells.size >= 2) {
                val label = cleanText(cells[0].text()).lowercase()
                val value = cleanText(cells[1].text())
                when {
                    label.contains("info hash") -> {
                        val candidate = value.replace(Regex("""\s+"""), "")
                        if ((candidate.length == 40 || candidate.length == 32) && candidate.all { it.isLetterOrDigit() }) {
                            infoHash = candidate
                        }
                    }
                    label.contains("tracker") || label.contains("announce url") -> {
                        if (value.startsWith("udp://") || value.startsWith("http://") || value.startsWith("https://")) {
                            trackers.add(value)
                        }
                    }
                    label.contains("file size") -> {
                        if (value.isNotBlank()) fileSizeLabel = value
                    }
                }
            } else if (cells.size == 1) {
                val text = cleanText(cells[0].text())
                if (text.isBlank() || text.startsWith(".pad", ignoreCase = true)) continue
                val match = AUDIO_FILE_ROW_REGEX.find(text)
                if (match != null) {
                    htmlFiles.add(
                        HtmlAudioFile(
                            fileName = match.groupValues[1].trim(),
                            sizeLabel = match.groupValues[2].trim()
                        )
                    )
                }
            }
        }

        if (infoHash.isNullOrBlank()) {
            // Fallback: scan raw HTML for 40-char hex hash near Info Hash
            val hashMatch = Regex("""(?i)Info\s*Hash\s*:?\s*</td>\s*<td[^>]*>\s*([0-9a-fA-F]{40}|[A-Za-z2-7]{32})""").find(resp.text)
            infoHash = hashMatch?.groupValues?.get(1)
        }

        val magnetUrl = infoHash?.let { TorrentStreamHelper.buildMagnet(it, title, trackers) } ?: ""

        // Build clean plot & tags
        val summaryParts = mutableListOf<String>()
        if (author != null) summaryParts.add("Author: $author")
        if (narrator != null) summaryParts.add("Narrator: $narrator")
        if (format != null) summaryParts.add("Format: $format" + (if (abridged != null) " ($abridged)" else ""))
        if (fileSizeLabel != null) summaryParts.add("Size: $fileSizeLabel")
        if (language != null) summaryParts.add("Language: $language")

        val synopsisParagraphs = post.select(".desc p")
            .filter { p ->
                val txt = p.text()
                !txt.contains("Written by", ignoreCase = true) &&
                    !txt.contains("Read by", ignoreCase = true) &&
                    p.selectFirst("span.author, span.narrator, span.format") == null
            }
            .map { cleanText(it.text()) }
            .filter { it.isNotBlank() }

        val plotBuilder = StringBuilder()
        if (summaryParts.isNotEmpty()) {
            plotBuilder.append(summaryParts.joinToString(" • "))
        }
        if (synopsisParagraphs.isNotEmpty()) {
            if (plotBuilder.isNotEmpty()) plotBuilder.append("\n\n")
            plotBuilder.append(synopsisParagraphs.joinToString("\n\n"))
        }
        val plotText = plotBuilder.toString().ifBlank { null }

        val tags = mutableListOf<String>()
        if (format != null) tags.add(if (fileSizeLabel != null) "$format • $fileSizeLabel" else format)
        else if (fileSizeLabel != null) tags.add(fileSizeLabel)
        if (language != null) tags.add(language)
        tags.addAll(categories)

        val actors = mutableListOf<ActorData>()
        if (author != null) actors.add(ActorData(Actor(author, null), roleString = "Author"))
        if (narrator != null) actors.add(ActorData(Actor(narrator, null), roleString = "Narrator"))

        // Resolve chapters via TorrServer when available, otherwise fallback to HTML file list
        if (magnetUrl.isNotBlank()) {
            val session = TorrentStreamHelper.resolveTorrServerSession(
                context = pluginContext,
                magnetUrl = magnetUrl,
                title = title,
                maxWaitSeconds = 6
            )

            if (session != null && session.files.size > 1) {
                val episodes = session.files.mapIndexed { idx, file ->
                    val sizeStr = TorrentStreamHelper.formatFileSize(file.length)
                    val epLabel = TorrentStreamHelper.cleanChapterDisplayName(file.path)
                    newEpisode("$magnetUrl#fileId=${file.id}") {
                        this.name = if (sizeStr.isNotBlank()) "$epLabel [$sizeStr]" else epLabel
                        this.episode = idx + 1
                    }
                }
                return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plotText
                    this.tags = tags.distinct()
                    this.actors = actors
                }
            }

            if (session != null && session.files.size == 1) {
                val singleFile = session.files.first()
                return newMovieLoadResponse(title, targetUrl, TvType.AudioBook, "$magnetUrl#fileId=${singleFile.id}") {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plotText
                    this.tags = tags.distinct()
                    this.actors = actors
                }
            }

            // TorrServer timed out or offline during load(): use HTML multifile table if >1 chapters exist
            val sortedHtmlFiles = htmlFiles.sortedWith { a, b ->
                TorrentStreamHelper.naturalOrderComparator.compare(a.fileName, b.fileName)
            }
            if (sortedHtmlFiles.size > 1) {
                val episodes = sortedHtmlFiles.mapIndexed { idx, hf ->
                    val epLabel = TorrentStreamHelper.cleanChapterDisplayName(hf.fileName)
                    val encodedName = URLEncoder.encode(hf.fileName, "UTF-8")
                    newEpisode("$magnetUrl#fileName=$encodedName") {
                        this.name = "$epLabel [${hf.sizeLabel}]"
                        this.episode = idx + 1
                    }
                }
                return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.year = year
                    this.plot = plotText
                    this.tags = tags.distinct()
                    this.actors = actors
                }
            }
        }

        return newMovieLoadResponse(title, targetUrl, TvType.AudioBook, magnetUrl) {
            this.posterUrl = poster
            this.year = year
            this.plot = plotText
            this.tags = tags.distinct()
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val cleanMagnet = data.substringBefore("#fileId=").substringBefore("#fileName=")
        val targetFileId = if (data.contains("#fileId=")) {
            data.substringAfter("#fileId=").toIntOrNull()
        } else {
            null
        }
        val targetFileName = if (data.contains("#fileName=")) {
            TorrentStreamHelper.decodeParam(data.substringAfter("#fileName="))
        } else {
            null
        }

        val session = TorrentStreamHelper.resolveTorrServerSession(
            context = pluginContext,
            magnetUrl = cleanMagnet,
            title = "AudioBookBay",
            maxWaitSeconds = 25
        )

        var emittedDirect = false
        if (session != null && session.files.isNotEmpty()) {
            val matchedFiles = when {
                targetFileId != null -> session.files.filter { it.id == targetFileId }.ifEmpty { session.files }
                targetFileName != null -> {
                    val baseTarget = targetFileName.substringAfterLast('/')
                    session.files.filter {
                        it.fileName.equals(baseTarget, ignoreCase = true) ||
                            it.path.equals(targetFileName, ignoreCase = true) ||
                            it.fileName.contains(baseTarget.substringBeforeLast('.'), ignoreCase = true)
                    }.ifEmpty { session.files }
                }
                else -> session.files
            }

            for (file in matchedFiles) {
                val streamUrl = TorrentStreamHelper.buildStreamUrl(
                    serverUrl = session.serverUrl,
                    fileName = file.fileName,
                    hash = session.hash,
                    fileId = file.id
                )
                val sizeStr = TorrentStreamHelper.formatFileSize(file.length)
                val label = TorrentStreamHelper.cleanChapterDisplayName(file.path)
                val displayName = if (sizeStr.isNotBlank()) "TorrServer • $label [$sizeStr]" else "TorrServer • $label"

                callback(
                    newExtractorLink(
                        source = this.name,
                        name = displayName,
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = ""
                    }
                )
                emittedDirect = true
            }
        }

        // Always provide direct Magnet fallback when TorrServer didn't resolve a direct stream,
        // or for single-link playback as an alternative option for external torrent clients (e.g. 1DM).
        if (!emittedDirect || (targetFileId == null && targetFileName == null)) {
            val magnetWithIndex = if (targetFileId != null) "$cleanMagnet&index=$targetFileId" else cleanMagnet
            callback(
                newExtractorLink(
                    source = this.name,
                    name = "Magnet (BitTorrent)",
                    url = magnetWithIndex,
                    type = ExtractorLinkType.MAGNET
                ) {
                    this.referer = ""
                }
            )
        }

        return true
    }
}
