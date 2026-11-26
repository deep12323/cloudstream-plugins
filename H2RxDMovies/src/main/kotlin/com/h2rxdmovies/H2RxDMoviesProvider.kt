package com.h2rxdmovies

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.util.Collections

class H2RxDMoviesProvider : MainAPI() {
    override var mainUrl = "https://h2r-xdmovies.pages.dev"
    override var name = "H2RxDMovies"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val apiUrl = "https://xd-api.hdmovielover.workers.dev"
    private val mapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    override val mainPage = mainPageOf(
        "$apiUrl/api/v1/catalog?sort=lastmod" to "Home",
        "$apiUrl/api/v1/catalog?sort=downloads" to "Featured",
        "$apiUrl/api/v1/movies?sort=lastmod" to "Latest Movies",
        "$apiUrl/api/v1/series?sort=lastmod" to "Latest TV Series",
        "$apiUrl/api/v1/movies?sort=rating" to "Top Rated Movies",
        "$apiUrl/api/v1/series?sort=rating" to "Top Rated TV Series",
        "$apiUrl/api/v1/catalog?source=Netflix" to "Netflix",
        "$apiUrl/api/v1/catalog?source=Amazon" to "Amazon Prime",
        "$apiUrl/api/v1/catalog?source=JioHotstar" to "JioHotstar",
        "$apiUrl/api/v1/catalog?source=Zee5" to "Zee5",
        "$apiUrl/api/v1/catalog?source=SonyLiv" to "SonyLIV",
        "$apiUrl/api/v1/catalog?source=AppleTVPlus" to "Apple TV+",
        "$apiUrl/api/v1/catalog?audio=Hindi" to "Hindi",
        "$apiUrl/api/v1/catalog?audio=Tamil" to "Tamil",
        "$apiUrl/api/v1/catalog?audio=Telugu" to "Telugu",
        "$apiUrl/api/v1/catalog?audio=Malayalam" to "Malayalam",
        "$apiUrl/api/v1/catalog?audio=English" to "English",
        "$apiUrl/api/v1/catalog?audio=Korean" to "Korean (K-Drama)",
        "$apiUrl/api/v1/catalog?genre=Action" to "Action",
        "$apiUrl/api/v1/catalog?genre=Thriller" to "Thriller & Crime",
        "$apiUrl/api/v1/catalog?genre=Comedy" to "Comedy",
        "$apiUrl/api/v1/catalog?genre=Romance" to "Romance",
        "$apiUrl/api/v1/catalog?genre=Horror" to "Horror",
        "$apiUrl/api/v1/catalog?genre=Science+Fiction" to "Sci-Fi & Fantasy",
        "$apiUrl/api/v1/catalog?genre=Animation" to "Animation"
    )

    private val requestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json"
    )

    private fun MediaItem.toSearchResponse(): SearchResponse? {
        val title = this.title ?: return null
        val isSeries = this.type.equals("series", ignoreCase = true)
        val itemUrl = "$mainUrl/media/${this.id}"
        val imgUrl = this.poster?.takeIf { it.isNotBlank() } ?: this.backdrop

        return if (isSeries) {
            newTvSeriesSearchResponse(title, itemUrl, TvType.TvSeries) {
                this.posterUrl = imgUrl
                this.year = this@toSearchResponse.release_year
            }
        } else {
            newMovieSearchResponse(title, itemUrl, TvType.Movie) {
                this.posterUrl = imgUrl
                this.year = this@toSearchResponse.release_year
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val requestUrl = if (request.data.contains("?")) {
            "${request.data}&page=$page&limit=20"
        } else {
            "${request.data}?page=$page&limit=20"
        }

        try {
            val response = HubCloud.retryGet(requestUrl, headers = requestHeaders, timeout = 20L)
            if (response == null || !response.isSuccessful) return null

            val catalog: CatalogResponse = mapper.readValue(response.text)
            val items = catalog.results.mapNotNull { it.toSearchResponse() }
            val hasNext = catalog.page < catalog.totalPages

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                ),
                hasNext = hasNext
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$apiUrl/api/v1/search?q=$encodedQuery&page=$page&limit=25"

        try {
            val response = HubCloud.retryGet(searchUrl, headers = requestHeaders, timeout = 20L)
            if (response == null || !response.isSuccessful) return newSearchResponseList(emptyList(), false)

            val catalog: CatalogResponse = mapper.readValue(response.text)
            val items = catalog.results.mapNotNull { it.toSearchResponse() }
            val hasNext = catalog.page < catalog.totalPages

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
        val id = url.substringAfterLast("/").trim().toIntOrNull() ?: return null
        val detailUrl = "$apiUrl/api/v1/media/$id"

        try {
            val detailResp = HubCloud.retryGet(detailUrl, headers = requestHeaders, timeout = 20L)
            if (detailResp == null || !detailResp.isSuccessful) return null

            val mediaDetail: MediaDetailResponse = mapper.readValue(detailResp.text)
            val media = mediaDetail.data ?: return null

            val title = media.title ?: "Unknown"
            val isSeries = media.type.equals("series", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie

            if (isSeries) {
                val dlResp = HubCloud.retryGet("$apiUrl/api/v1/series/$id/downloads", headers = requestHeaders, timeout = 20L)
                val episodesList = mutableListOf<Episode>()
                var packEpisodeIndex = 1

                if (dlResp != null && dlResp.isSuccessful) {
                    val seriesData: SeriesDownloadsResponse = mapper.readValue(dlResp.text)
                    for (season in seriesData.seasons) {
                        val seasonNum = season.season

                        for (ep in season.episodes) {
                            val epNum = ep.episode
                            val epPayload = mapper.writeValueAsString(ep.downloads)
                            episodesList.add(
                                newEpisode(epPayload) {
                                    this.name = "Episode $epNum"
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = media.poster
                                }
                            )
                        }

                        // Place season zip/batch packs in Specials (Season 0) with unique episode indices.
                        for (pack in season.packs) {
                            val cleanName = pack.file_name ?: "Season Pack"
                            val sizeStr = if (!pack.size.isNullOrBlank()) " • ${pack.size}" else ""
                            val resStr = if (!pack.resolution.isNullOrBlank()) " • ${pack.resolution}" else ""
                            val packPayload = mapper.writeValueAsString(listOf(pack))
                            episodesList.add(
                                newEpisode(packPayload) {
                                    this.name = "📦 [ZIP PACK] $cleanName$resStr$sizeStr"
                                    this.season = 0
                                    this.episode = packEpisodeIndex++
                                    this.posterUrl = media.poster
                                }
                            )
                        }
                    }
                }

                return newTvSeriesLoadResponse(title, url, type, episodesList) {
                    this.posterUrl = media.poster
                    this.backgroundPosterUrl = media.backdrop
                    this.year = media.release_year
                    this.plot = media.overview
                    this.tags = media.genres
                    this.actors = media.cast?.map { ActorData(Actor(it, null)) }
                    this.score = Score.from10(media.rating_num)
                }
            } else {
                val dlResp = HubCloud.retryGet("$apiUrl/api/v1/movies/$id/downloads", headers = requestHeaders, timeout = 20L)
                val downloads = if (dlResp != null && dlResp.isSuccessful) {
                    val dlData: MovieDownloadsResponse = mapper.readValue(dlResp.text)
                    dlData.downloads
                } else {
                    emptyList()
                }

                val moviePayload = mapper.writeValueAsString(downloads)

                return newMovieLoadResponse(title, url, type, moviePayload) {
                    this.posterUrl = media.poster
                    this.backgroundPosterUrl = media.backdrop
                    this.year = media.release_year
                    this.plot = media.overview
                    this.tags = media.genres
                    this.actors = media.cast?.map { ActorData(Actor(it, null)) }
                    this.score = Score.from10(media.rating_num)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun getServerPriority(source: String): Int {
        val s = source.lowercase()
        return when {
            s.contains("10gbps") -> 5
            s.contains("fsl") || s.contains("r2") -> 4
            s.contains("fast") || s.contains("stream") -> 3
            s.contains("pixeldrain fast") -> 2
            s.contains("pixeldrain") -> 1
            else -> 0
        }
    }

    private suspend fun resolveCandidateUrl(
        targetUrl: String,
        download: DownloadItem,
        subtitleCallback: (SubtitleFile) -> Unit,
        collector: (ExtractorLink) -> Unit
    ): Boolean {
        val url = targetUrl.trim()
        if (url.isBlank()) return false
        val lower = url.lowercase()
        var resolved = false
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            resolved = true
            collector(link)
        }

        when {
            lower.contains("hubcloud") || lower.contains("hubc") || lower.contains("gamerxyt") -> {
                HubCloud.resolveHubCloud(url, download, trackingCallback)
            }
            lower.contains("gdflix") || lower.contains("only-apis") -> {
                HubCloud.resolveGDFlix(url, download, trackingCallback)
            }
            lower.contains("link.xdmovies.wtf") -> {
                // Monetization shortener gated behind Cloudflare challenge; cannot resolve non-interactively.
                return false
            }
            else -> {
                loadExtractor(url, subtitleCallback, trackingCallback)
            }
        }
        return resolved
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val downloads: List<DownloadItem> = try {
            mapper.readValue(data)
        } catch (e: Exception) {
            return false
        }

        if (downloads.isEmpty()) return false

        data class ExtLinkWithSize(
            val link: ExtractorLink,
            val sizeBytes: Long,
            val serverPriority: Int
        )

        val collectedLinks = Collections.synchronizedList(mutableListOf<ExtLinkWithSize>())

        coroutineScope {
            downloads.map { download ->
                async {
                    val itemBytes = download.size_bytes?.takeIf { it > 0L }
                        ?: HubCloud.parseSizeToBytes(
                            download.size ?: download.file_name,
                            HubCloud.getQualityInt(download.resolution ?: download.file_name)
                        )

                    val collector: (ExtractorLink) -> Unit = { extLink ->
                        val linkBytes = HubCloud.parseSizeToBytes(extLink.name, extLink.quality)
                            .takeIf { it > 0L } ?: itemBytes
                        collectedLinks.add(ExtLinkWithSize(extLink, linkBytes, getServerPriority(extLink.source)))
                    }

                    val candidates = listOfNotNull(
                        download.hubcloud_url?.trim()?.takeIf { it.isNotBlank() },
                        download.download_url?.trim()?.takeIf { it.isNotBlank() }
                    ).distinct()

                    for (candidate in candidates) {
                        if (resolveCandidateUrl(candidate, download, subtitleCallback, collector)) {
                            break
                        }
                    }
                }
            }.awaitAll()
        }

        val seenUrls = mutableSetOf<String>()
        val sortedLinks = collectedLinks
            .filter { seenUrls.add(it.link.url) }
            .sortedWith(
                compareByDescending<ExtLinkWithSize> { it.sizeBytes }
                    .thenByDescending { it.link.quality }
                    .thenByDescending { it.serverPriority }
            )

        for (entry in sortedLinks) {
            callback(entry.link)
        }

        return sortedLinks.isNotEmpty()
    }
}
