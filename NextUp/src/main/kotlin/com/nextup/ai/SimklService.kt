package com.nextup.ai

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import java.net.URLEncoder

@JsonIgnoreProperties(ignoreUnknown = true)
data class SimklIds(
    @JsonProperty("simkl_id") val simklId: Int? = null,
    @JsonProperty("simkl") val simkl: Int? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("tmdb") val tmdb: String? = null,
    @JsonProperty("imdb") val imdb: String? = null
) {
    fun getId(): Int? = simklId ?: simkl
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class SimklItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("endpoint_type") val endpointType: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("fanart") val fanart: String? = null,
    @JsonProperty("ids") val ids: SimklIds? = null,
    @JsonProperty("ratings") val ratings: Map<String, Any?>? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("overview") val overview: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SimklEpisodeItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("img") val img: String? = null,
    @JsonProperty("date") val date: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SimklSyncResponse(
    @JsonProperty("movies") val movies: List<SimklSyncItem>? = null,
    @JsonProperty("shows") val shows: List<SimklSyncItem>? = null,
    @JsonProperty("anime") val anime: List<SimklSyncItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SimklSyncItem(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("user_rating") val userRating: Int? = null,
    @JsonProperty("last_watched_at") val lastWatchedAt: String? = null,
    @JsonProperty("movie") val movie: SimklItem? = null,
    @JsonProperty("show") val show: SimklItem? = null,
    @JsonProperty("anime") val anime: SimklItem? = null
)

object SimklService {

    const val SIMKL_API_BASE = "https://api.simkl.com"
    const val CLIENT_ID = "39f470a9f2ec1aa2383269ca831bc7be0e47da48d6d708ccad9bed4e1a60993e"
    private val mapper = jacksonObjectMapper()

    fun getPosterUrl(posterHash: String?): String? {
        if (posterHash.isNullOrBlank()) return null
        return if (posterHash.startsWith("http")) posterHash else "https://simkl.in/posters/${posterHash}_m.webp"
    }

    fun getFanartUrl(fanartHash: String?): String? {
        if (fanartHash.isNullOrBlank()) return null
        return if (fanartHash.startsWith("http")) fanartHash else "https://simkl.in/fanart/${fanartHash}_medium.webp"
    }

    private fun getHeaders(token: String? = null): Map<String, String> {
        val headers = mutableMapOf(
            "simkl-api-key" to CLIENT_ID,
            "User-Agent" to "CloudStream/3.0 NextUp"
        )
        if (!token.isNullOrBlank()) {
            headers["Authorization"] = "Bearer $token"
        }
        return headers
    }

    private fun <T> parseArray(json: String, clazz: Class<Array<T>>): List<T> {
        return try {
            mapper.readValue(json, clazz).toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun search(query: String, type: String? = null): List<SimklItem> {
        return try {
            val endpoint = when (type?.lowercase()) {
                "show", "tv", "series" -> "tv"
                "anime" -> "anime"
                else -> "movie"
            }
            val encoded = URLEncoder.encode(query, "UTF-8")
            val url = "$SIMKL_API_BASE/search/$endpoint?q=$encoded&client_id=$CLIENT_ID"
            val text = app.get(url, headers = getHeaders(), timeout = 8L).text
            parseArray(text, Array<SimklItem>::class.java)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun searchAllTypes(query: String): List<SimklItem> {
        val results = mutableListOf<SimklItem>()
        for (endpoint in listOf("movie", "tv", "anime")) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = "$SIMKL_API_BASE/search/$endpoint?q=$encoded&client_id=$CLIENT_ID"
                val text = app.get(url, headers = getHeaders(), timeout = 6L).text
                val items = parseArray(text, Array<SimklItem>::class.java)
                results.addAll(items)
            } catch (_: Throwable) {}
        }
        return results
    }

    suspend fun getDetails(simklId: Int, type: String): SimklItem? {
        return try {
            val endpoint = when (type.lowercase()) {
                "show", "shows", "tv", "series" -> "tv"
                "anime" -> "anime"
                else -> "movies"
            }
            val url = "$SIMKL_API_BASE/$endpoint/$simklId?extended=full&client_id=$CLIENT_ID"
            val text = app.get(url, headers = getHeaders(), timeout = 10L).text
            mapper.readValue(text, SimklItem::class.java)
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getEpisodes(simklId: Int, type: String): List<SimklEpisodeItem> {
        return try {
            val endpoint = when (type.lowercase()) {
                "anime" -> "anime"
                else -> "tv"
            }
            val url = "$SIMKL_API_BASE/$endpoint/episodes/$simklId?extended=full&client_id=$CLIENT_ID"
            val text = app.get(url, headers = getHeaders(), timeout = 10L).text
            parseArray(text, Array<SimklEpisodeItem>::class.java)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getCategoryTrending(categoryKey: String): List<SimklItem> {
        return try {
            val endpoint = when (categoryKey) {
                "top_picks" -> "/movies/genres/all"
                "because_watched" -> "/tv/genres/all"
                "hidden_gems" -> "/movies/genres/thriller"
                "movies" -> "/movies/genres/sci-fi"
                "shows" -> "/tv/best"
                "anime" -> "/anime/best"
                else -> "/movies/genres/all"
            }
            val headers = getHeaders().toMutableMap().apply {
                put("Cache-Control", "no-cache")
                put("Pragma", "no-cache")
            }
            val url = "$SIMKL_API_BASE$endpoint?client_id=$CLIENT_ID"
            val text = app.get(url, headers = headers, timeout = 8L).text
            parseArray(text, Array<SimklItem>::class.java)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getTrending(type: String): List<SimklItem> {
        return try {
            val url = when (type.lowercase()) {
                "anime" -> "$SIMKL_API_BASE/anime/best?client_id=$CLIENT_ID"
                "show", "shows", "tv", "series" -> "$SIMKL_API_BASE/tv/best?client_id=$CLIENT_ID"
                else -> "$SIMKL_API_BASE/movies/genres/all?client_id=$CLIENT_ID"
            }
            val text = app.get(url, headers = getHeaders(), timeout = 8L).text
            parseArray(text, Array<SimklItem>::class.java)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getUserSyncItems(token: String?): List<SimklSyncItem> {
        if (token.isNullOrBlank()) return emptyList()
        return try {
            val url = "$SIMKL_API_BASE/sync/all-items/all"
            val text = app.get(url, headers = getHeaders(token), timeout = 10L).text
            val resp = mapper.readValue(text, SimklSyncResponse::class.java)
            val all = mutableListOf<SimklSyncItem>()
            resp?.movies?.let { all.addAll(it) }
            resp?.shows?.let { all.addAll(it) }
            resp?.anime?.let { all.addAll(it) }
            all
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun MainAPI.toSearchResponse(anyItem: Any?): SearchResponse? {
        val item = when (anyItem) {
            is SimklItem -> anyItem
            is Map<*, *> -> {
                try {
                    mapper.convertValue(anyItem, SimklItem::class.java)
                } catch (_: Throwable) {
                    null
                }
            }
            else -> null
        } ?: return null

        val title = item.title ?: return null
        val id = item.ids?.getId() ?: return null
        val poster = getPosterUrl(item.poster)
        val rawType = (item.endpointType ?: item.type ?: "movies").lowercase()

        // Canonical plural paths matching Simkl web and CineSimkl regexes
        val canonicalType = when {
            rawType.contains("anime") -> "anime"
            rawType.contains("tv") || rawType.contains("show") -> "tv"
            else -> "movies"
        }

        val simklUrl = "https://simkl.com/$canonicalType/$id"

        return when (canonicalType) {
            "anime" -> {
                newAnimeSearchResponse(title, simklUrl, TvType.Anime) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            }
            "tv" -> {
                newTvSeriesSearchResponse(title, simklUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            }
            else -> {
                newMovieSearchResponse(title, simklUrl, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = item.year
                }
            }
        }
    }
}
