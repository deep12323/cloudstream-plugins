package com.nextup.ai

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.nextup.ai.SimklService.toSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.regex.Pattern

class NextUpProvider(private val pluginContext: Context) : MainAPI() {

    override var name = "NextUp"
    override var mainUrl = "https://simkl.com"
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override var supportedSyncNames = setOf(SyncIdName.Simkl)
    override var hasMainPage = true

    companion object {
        private const val TAG = "NextUpProvider"
        private val mapper = jacksonObjectMapper()
        private val SIMKL_URL_REGEX = Pattern.compile("https://simkl\\.com/([^/]+)/(\\d+)")

        // Session Coordinator: ensures all 6 rows share the same fresh batch and don't duplicate work or items
        private val batchMutex = Mutex()
        private var sessionBatch: Map<String, List<SearchResponse>>? = null
        private var sessionTimestamp: Long = 0L
        private val consumedCategories = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        private const val SESSION_TTL_MS = 15 * 1000L // 15 seconds batch lifetime

        fun invalidateSession() {
            sessionBatch = null
            sessionTimestamp = 0L
            consumedCategories.clear()
        }
    }

    override val mainPage: List<MainPageData> = mainPageOf(
        "top_picks" to "🌟 Top Picks For You",
        "because_watched" to "🎯 Because You Watched",
        "hidden_gems" to "💎 Hidden Gems & Underrated",
        "movies" to "🍿 Recommended Movies",
        "shows" to "📺 Binge-Worthy Series",
        "anime" to "⛩️ Personalized Anime"
    )

    private fun isItemWatched(
        title: String?,
        simklId: Int?,
        watchedTitles: Set<String>,
        watchedSimklIds: Set<Int>
    ): Boolean {
        if (simklId != null && watchedSimklIds.contains(simklId)) return true
        if (title != null) {
            val norm = TasteProfileManager.normalizeTitle(title)
            if (norm.isNotBlank() && watchedTitles.contains(norm)) return true
        }
        return false
    }

    private suspend fun getOrBuildSessionBatch(forceRefresh: Boolean = false): Map<String, List<SearchResponse>> {
        val now = System.currentTimeMillis()
        if (!forceRefresh && sessionBatch != null && (now - sessionTimestamp) < SESSION_TTL_MS) {
            return sessionBatch!!
        }

        return batchMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!forceRefresh && sessionBatch != null && (lockedNow - sessionTimestamp) < SESSION_TTL_MS) {
                return@withLock sessionBatch!!
            }

            consumedCategories.clear()
            Log.i(TAG, "Building fresh AI recommendation catalogue (forceRefresh=$forceRefresh)...")
            val apiKey = TasteProfileManager.getApiKey(pluginContext)
            val vibe = TasteProfileManager.getTasteVibe(pluginContext)
            val selectedModel = TasteProfileManager.getSelectedModel(pluginContext)
            val watchedData = TasteProfileManager.harvestWatchedData(pluginContext)

            // Global seen tracking across ALL rows in the catalogue to prevent any duplicate movies
            val globalSeenIds = mutableSetOf<Int>()
            val globalSeenTitles = mutableSetOf<String>()

            // 1. Fetch fresh recommendations from Gemini
            var aiRecs: List<GeminiRecommendation> = emptyList()
            if (apiKey.isNotBlank()) {
                try {
                    aiRecs = GeminiClient.generateRecommendations(
                        apiKey = apiKey,
                        modelName = selectedModel,
                        watchedContext = watchedData.promptContext,
                        vibe = vibe
                    )
                } catch (t: Throwable) {
                    Log.w(TAG, "Gemini generation error: ${t.message}")
                }
            }

            // 2. Pre-fetch Simkl search results concurrently for all AI recommendations
            val resolvedAiItems = mutableMapOf<String, MutableList<SearchResponse>>()
            mainPage.forEach { resolvedAiItems[it.data] = mutableListOf() }

            if (aiRecs.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        val deferredList = aiRecs.map { rec ->
                            async {
                                try {
                                    val normTitle = TasteProfileManager.normalizeTitle(rec.title)
                                    if (normTitle.isNotBlank() && watchedData.watchedTitles.contains(normTitle)) {
                                        return@async null // Skip watched
                                    }
                                    val simklResults = SimklService.search(rec.title, rec.type)
                                    val best = simklResults.firstOrNull {
                                        rec.year == null || it.year == null || Math.abs((it.year ?: 0) - rec.year) <= 1
                                    } ?: simklResults.firstOrNull()
                                    if (best != null) {
                                        Pair(rec.category, best)
                                    } else null
                                } catch (_: Throwable) {
                                    null
                                }
                            }
                        }

                        val pairs = deferredList.awaitAll().filterNotNull()
                        for ((catKey, simklItem) in pairs) {
                            val id = simklItem.ids?.getId()
                            val title = simklItem.title ?: ""
                            val normTitle = TasteProfileManager.normalizeTitle(title)

                            // Strict watched check & global deduplication check
                            if (isItemWatched(title, id, watchedData.watchedTitles, watchedData.watchedSimklIds)) continue
                            if (id != null && globalSeenIds.contains(id)) continue
                            if (normTitle.isNotBlank() && globalSeenTitles.contains(normTitle)) continue

                            val resp = toSearchResponse(simklItem)
                            if (resp != null) {
                                if (id != null) globalSeenIds.add(id)
                                if (normTitle.isNotBlank()) globalSeenTitles.add(normTitle)
                                val targetList = resolvedAiItems[catKey] ?: resolvedAiItems["top_picks"]
                                targetList?.add(resp)
                            }
                        }
                    }
                }
            }

            // 3. Guarantee that EVERY row has 15-20 rich, distinct items by supplementing with curated Simkl items
            val finalBatch = mutableMapOf<String, List<SearchResponse>>()
            for (pageData in mainPage) {
                val catKey = pageData.data
                val currentList = resolvedAiItems[catKey] ?: mutableListOf()

                if (currentList.size < 15) {
                    try {
                        val trendingItems = SimklService.getCategoryTrending(catKey).shuffled()
                        for (item in trendingItems) {
                            if (currentList.size >= 20) break
                            val id = item.ids?.getId()
                            val title = item.title ?: ""
                            val normTitle = TasteProfileManager.normalizeTitle(title)

                            if (isItemWatched(title, id, watchedData.watchedTitles, watchedData.watchedSimklIds)) continue
                            if (id != null && globalSeenIds.contains(id)) continue
                            if (normTitle.isNotBlank() && globalSeenTitles.contains(normTitle)) continue

                            val resp = toSearchResponse(item)
                            if (resp != null) {
                                if (id != null) globalSeenIds.add(id)
                                if (normTitle.isNotBlank()) globalSeenTitles.add(normTitle)
                                currentList.add(resp)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Error supplementing category $catKey: ${t.message}")
                    }
                }

                finalBatch[catKey] = currentList
            }

            sessionBatch = finalBatch
            sessionTimestamp = System.currentTimeMillis()
            finalBatch
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val catKey = request.data
        val now = System.currentTimeMillis()

        // If this category was already consumed, or if more than 10s passed since session began,
        // it means the user triggered a reload / pull-to-refresh / new navigation!
        val isReload = consumedCategories.contains(catKey) || (now - sessionTimestamp > 10_000L)

        val batch = getOrBuildSessionBatch(forceRefresh = isReload)
        consumedCategories.add(catKey)
        val items = batch[catKey] ?: emptyList()

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = false)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val simklItems = SimklService.searchAllTypes(query)
        val list = mutableListOf<SearchResponse>()
        for (item in simklItems) {
            val resp = toSearchResponse(item)
            if (resp != null) {
                list.add(resp)
            }
        }
        return list
    }

    override suspend fun load(url: String): LoadResponse? {
        return OpenWithResolver.resolveLoad(pluginContext, url) {
            loadNativeSimkl(url)
        }
    }

    private suspend fun loadNativeSimkl(url: String): LoadResponse? {
        val matcher = SIMKL_URL_REGEX.matcher(url)
        if (!matcher.find()) return null

        val type = matcher.group(1) ?: "movies"
        val simklId = matcher.group(2)?.toIntOrNull() ?: return null

        val details = SimklService.getDetails(simklId, type) ?: return null
        val title = details.title ?: "Unknown"
        val poster = SimklService.getPosterUrl(details.poster)
        val fanart = SimklService.getFanartUrl(details.fanart)
        val year = details.year
        val plot = details.overview
        val tags = details.genres ?: emptyList()

        val rawRatings = details.ratings
        val simklScore = (rawRatings?.get("simkl") as? Map<*, *>)?.get("rating")?.toString()?.toDoubleOrNull()
        val scoreObj = simklScore?.let { Score.from10(it.toFloat()) }

        return when {
            type.contains("anime", ignoreCase = true) -> {
                val epItems = SimklService.getEpisodes(simklId, "anime")
                val episodes = epItems.map { ep ->
                    val dataPayload = mapper.writeValueAsString(
                        mapOf(
                            "title" to title,
                            "year" to year,
                            "type" to "anime",
                            "simklId" to simklId,
                            "season" to (ep.season ?: 1),
                            "episode" to (ep.episode ?: 1)
                        )
                    )
                    newEpisode(dataPayload) {
                        this.name = ep.title
                        this.season = ep.season ?: 1
                        this.episode = ep.episode
                        this.posterUrl = SimklService.getPosterUrl(ep.img)
                        this.description = ep.description
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = fanart
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    if (scoreObj != null) this.score = scoreObj
                }
            }
            type.contains("tv", ignoreCase = true) || type.contains("show", ignoreCase = true) -> {
                val epItems = SimklService.getEpisodes(simklId, "tv")
                val episodes = epItems.map { ep ->
                    val dataPayload = mapper.writeValueAsString(
                        mapOf(
                            "title" to title,
                            "year" to year,
                            "type" to "tv",
                            "simklId" to simklId,
                            "season" to (ep.season ?: 1),
                            "episode" to (ep.episode ?: 1)
                        )
                    )
                    newEpisode(dataPayload) {
                        this.name = ep.title
                        this.season = ep.season ?: 1
                        this.episode = ep.episode
                        this.posterUrl = SimklService.getPosterUrl(ep.img)
                        this.description = ep.description
                    }
                }
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = fanart
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    if (scoreObj != null) this.score = scoreObj
                }
            }
            else -> {
                val dataPayload = mapper.writeValueAsString(
                    mapOf(
                        "title" to title,
                        "year" to year,
                        "type" to "movie",
                        "simklId" to simklId
                    )
                )
                newMovieLoadResponse(title, url, TvType.Movie, dataPayload) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = fanart
                    this.year = year
                    this.plot = plot
                    this.tags = tags
                    if (scoreObj != null) this.score = scoreObj
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Direct delegation to CineSimkl if present
        return try {
            val cineSimkl = APIHolder.getApiFromNameNull("CineSimkl")
            if (cineSimkl != null) {
                cineSimkl.loadLinks(data, isCasting, subtitleCallback, callback)
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}
