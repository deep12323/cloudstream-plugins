package com.nextup.ai

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.ui.WatchType
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class WatchedData(
    val promptContext: String,
    val watchedTitles: Set<String>,
    val watchedSimklIds: Set<Int>
)

object TasteProfileManager {

    private const val TAG = "TasteProfileManager"
    private const val PREFS_NAME = "nextup_ai_prefs"
    private const val KEY_CACHED_REC_JSON = "cached_recommendations_json"
    private const val KEY_CACHE_TIMESTAMP = "cached_recommendations_timestamp"

    // Short session TTL (2 minutes) so reopening or pulling down to refresh always generates fresh AI recommendations
    // while all 6 home rows during the same screen load share the exact same generated batch.
    private const val CACHE_TTL_MS = 2 * 60 * 1000L

    private val mapper = jacksonObjectMapper()

    fun normalizeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
            .trim()
    }

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString("gemini_api_key", "") ?: ""
    }

    fun setApiKey(context: Context, key: String) {
        getPrefs(context).edit().putString("gemini_api_key", key.trim()).apply()
    }

    fun getSelectedModel(context: Context): String {
        return getPrefs(context).getString("gemini_model", "Auto (Recommended)") ?: "Auto (Recommended)"
    }

    fun setSelectedModel(context: Context, model: String) {
        getPrefs(context).edit().putString("gemini_model", model.trim()).apply()
    }

    fun getSimklToken(context: Context): String {
        // 1. Check if user provided manual token in NextUp settings
        val manualToken = getPrefs(context).getString("simkl_access_token", "")?.trim() ?: ""
        if (manualToken.isNotBlank()) return manualToken

        // 2. Automatically retrieve from CloudStream's AccountManager for prefix 'simkl'
        try {
            val accounts = AccountManager.Companion.accounts("simkl")
            for (acc in accounts) {
                val token = acc.token?.accessToken?.trim()
                if (!token.isNullOrBlank()) {
                    Log.i(TAG, "Acquired Simkl token from AccountManager.accounts")
                    return token
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error checking AccountManager.accounts: ${t.message}")
        }

        // 3. Raw SharedPreferences inspection for auth_tokens/simkl in rebuild_preference
        try {
            val prefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
            for (k in prefs.all.keys) {
                if (k.startsWith("auth_tokens/simkl")) {
                    val rawJson = prefs.getString(k, null)
                    if (!rawJson.isNullOrBlank()) {
                        val root = mapper.readTree(rawJson)
                        if (root.isArray) {
                            for (itemNode in root) {
                                val token = itemNode.path("token").path("accessToken").asText("")
                                if (token.isNotBlank()) {
                                    Log.i(TAG, "Acquired Simkl token from raw preference key $k")
                                    return token
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error reading raw auth_tokens: ${t.message}")
        }

        return ""
    }

    fun getSimklAccountName(context: Context): String? {
        try {
            val accounts = AccountManager.Companion.accounts("simkl")
            val name = accounts.firstOrNull()?.user?.name
            if (!name.isNullOrBlank()) return name
        } catch (_: Throwable) {}

        try {
            val prefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
            for (k in prefs.all.keys) {
                if (k.startsWith("auth_tokens/simkl")) {
                    val rawJson = prefs.getString(k, null)
                    if (!rawJson.isNullOrBlank()) {
                        val root = mapper.readTree(rawJson)
                        if (root.isArray) {
                            for (itemNode in root) {
                                val name = itemNode.path("user").path("name").asText("")
                                if (name.isNotBlank()) return name
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    fun setSimklToken(context: Context, token: String) {
        getPrefs(context).edit().putString("simkl_access_token", token.trim()).apply()
    }

    fun getOpenWithMode(context: Context): String {
        return getPrefs(context).getString("open_with_mode", "Default (Follow CloudStream)") ?: "Default (Follow CloudStream)"
    }

    fun setOpenWithMode(context: Context, mode: String) {
        getPrefs(context).edit().putString("open_with_mode", mode).apply()
    }

    fun getTasteVibe(context: Context): String {
        return getPrefs(context).getString("taste_vibe", "Balanced & High Quality") ?: "Balanced & High Quality"
    }

    fun setTasteVibe(context: Context, vibe: String) {
        getPrefs(context).edit().putString("taste_vibe", vibe).apply()
    }

    fun getCachedRecommendations(context: Context): List<GeminiRecommendation>? {
        val prefs = getPrefs(context)
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        if (now - timestamp > CACHE_TTL_MS) {
            return null
        }
        val json = prefs.getString(KEY_CACHED_REC_JSON, null) ?: return null
        return try {
            mapper.readValue<List<GeminiRecommendation>>(json)
        } catch (_: Throwable) {
            null
        }
    }

    fun saveCachedRecommendations(context: Context, recs: List<GeminiRecommendation>) {
        try {
            val json = mapper.writeValueAsString(recs)
            getPrefs(context).edit()
                .putString(KEY_CACHED_REC_JSON, json)
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (_: Throwable) {}
    }

    fun clearCache(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_CACHED_REC_JSON)
            .remove(KEY_CACHE_TIMESTAMP)
            .apply()
    }

    /**
     * Harvests user watch history, bookmarks, favorites, and ratings.
     * Returns WatchedData containing:
     * - promptContext: Formatted summary for Gemini AI
     * - watchedTitles: Set of normalized titles already watched (used to strictly filter out watched items)
     * - watchedSimklIds: Set of Simkl IDs already watched
     */
    suspend fun harvestWatchedData(context: Context): WatchedData {
        val sb = StringBuilder()
        val watchedTitles = mutableSetOf<String>()
        val watchedSimklIds = mutableSetOf<Int>()
        val localDisplayTitles = mutableListOf<String>()

        // 1. Local CloudStream History (Bookmarks & Favorites)
        try {
            val bookmarks = try { DataStoreHelper.getAllBookmarkedData() } catch (_: Throwable) { null } ?: emptyList()
            for (b in bookmarks) {
                val name = b.name
                if (name.isNotBlank()) {
                    val norm = normalizeTitle(name)
                    if (norm.isNotBlank()) {
                        watchedTitles.add(norm)
                        if (!localDisplayTitles.contains(name)) localDisplayTitles.add(name)
                    }
                }
                b.syncData?.get("Simkl")?.toIntOrNull()?.let { watchedSimklIds.add(it) }
            }

            val favorites = try { DataStoreHelper.getAllFavorites() } catch (_: Throwable) { null } ?: emptyList()
            for (f in favorites) {
                val name = f.name
                if (name.isNotBlank()) {
                    val norm = normalizeTitle(name)
                    if (norm.isNotBlank()) {
                        watchedTitles.add(norm)
                        if (!localDisplayTitles.contains(name)) localDisplayTitles.add(name)
                    }
                }
            }

            // 2. Local Watch States & Resume Watching
            val watchStateIds = try { DataStoreHelper.getAllWatchStateIds() } catch (_: Throwable) { null } ?: emptyList()
            for (id in watchStateIds) {
                try {
                    val state = DataStoreHelper.getResultWatchState(id)
                    val isWatchedState = state == WatchType.COMPLETED || state == WatchType.WATCHING
                    val lastWatched = DataStoreHelper.getLastWatched(id)
                    if (isWatchedState || lastWatched != null) {
                        val bookmarked = DataStoreHelper.getBookmarkedData(id)
                        if (bookmarked?.name != null) {
                            val norm = normalizeTitle(bookmarked.name)
                            if (norm.isNotBlank()) watchedTitles.add(norm)
                        }
                    }
                } catch (_: Throwable) {}
            }

            if (localDisplayTitles.isNotEmpty()) {
                sb.append("Saved in CloudStream Library & Favorites:\n")
                localDisplayTitles.take(30).forEach { sb.append("- $it\n") }
                sb.append("\n")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error harvesting local watch history: ${t.message}")
        }

        // 3. Simkl History & Ratings (Auto-detected from CloudStream Account or manual token)
        val token = getSimklToken(context)
        if (token.isNotBlank()) {
            try {
                Log.i(TAG, "Syncing user ratings & watch history from Simkl API...")
                val simklItems = SimklService.getUserSyncItems(token)
                val ratedItems = simklItems.filter { (it.userRating ?: 0) > 0 }.sortedByDescending { it.userRating ?: 0 }
                val completedItems = simklItems.filter { it.status == "completed" || it.status == "watching" }

                // Collect all Simkl IDs and titles from sync
                for (item in simklItems) {
                    val title = item.movie?.title ?: item.show?.title ?: item.anime?.title ?: ""
                    if (title.isNotBlank()) {
                        val norm = normalizeTitle(title)
                        if (norm.isNotBlank()) watchedTitles.add(norm)
                    }
                    val simklId = item.movie?.ids?.getId() ?: item.show?.ids?.getId() ?: item.anime?.ids?.getId()
                    if (simklId != null) watchedSimklIds.add(simklId)
                }

                if (ratedItems.isNotEmpty()) {
                    sb.append("User Ratings on Simkl:\n")
                    ratedItems.take(25).forEach { item ->
                        val title = item.movie?.title ?: item.show?.title ?: item.anime?.title ?: ""
                        val rating = item.userRating ?: 0
                        if (title.isNotBlank()) {
                            sb.append("- $title (Rated: $rating/10)\n")
                        }
                    }
                    sb.append("\n")
                }

                if (completedItems.isNotEmpty()) {
                    sb.append("Completed / Watched Titles on Simkl:\n")
                    completedItems.take(25).forEach { item ->
                        val title = item.movie?.title ?: item.show?.title ?: item.anime?.title ?: ""
                        if (title.isNotBlank()) {
                            sb.append("- $title\n")
                        }
                    }
                    sb.append("\n")
                }
                Log.i(TAG, "Simkl sync complete: ${ratedItems.size} rated, ${completedItems.size} completed items harvested.")
            } catch (t: Throwable) {
                Log.w(TAG, "Error harvesting Simkl history: ${t.message}")
            }
        } else {
            Log.i(TAG, "No Simkl token detected (neither in CloudStream Accounts nor in NextUp settings).")
        }

        if (sb.isEmpty()) {
            sb.append("No watch history yet. Generate universal critically acclaimed masterpieces across Sci-Fi, Psychological Thrillers, Crime Drama, and Anime.")
        }

        return WatchedData(
            promptContext = sb.toString(),
            watchedTitles = watchedTitles,
            watchedSimklIds = watchedSimklIds
        )
    }

    suspend fun harvestWatchHistory(context: Context): String {
        return harvestWatchedData(context).promptContext
    }

    suspend fun exportTrainingDataset(context: Context): File? {
        return try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadDir.exists()) downloadDir.mkdirs()

            // 1. Gather ALL local CloudStream titles (complete library without any limits)
            val allLocalTitles = mutableListOf<String>()
            try {
                val bookmarks = try { DataStoreHelper.getAllBookmarkedData() } catch (_: Throwable) { null } ?: emptyList()
                for (b in bookmarks) {
                    val name = b.name
                    if (name.isNotBlank() && !allLocalTitles.contains(name)) {
                        allLocalTitles.add(name)
                    }
                }
                val favorites = try { DataStoreHelper.getAllFavorites() } catch (_: Throwable) { null } ?: emptyList()
                for (f in favorites) {
                    val name = f.name
                    if (name.isNotBlank() && !allLocalTitles.contains(name)) {
                        allLocalTitles.add(name)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error harvesting all local bookmarks for export: ${t.message}")
            }

            // 2. Gather ALL Simkl items (complete sync without any .take() truncation)
            val token = getSimklToken(context)
            val allSimklItems = if (token.isNotBlank()) {
                try {
                    SimklService.getUserSyncItems(token)
                } catch (t: Throwable) {
                    Log.w(TAG, "Error syncing all Simkl items for export: ${t.message}")
                    emptyList()
                }
            } else {
                emptyList()
            }

            val allRated = allSimklItems.filter { (it.userRating ?: 0) > 0 }.sortedByDescending { it.userRating ?: 0 }
            val allCompleted = allSimklItems.filter { it.status == "completed" }
            val allWatching = allSimklItems.filter { it.status == "watching" }
            val allPlanToWatch = allSimklItems.filter { it.status == "plantowatch" }

            val fullSb = StringBuilder()
            if (allLocalTitles.isNotEmpty()) {
                fullSb.append("Saved in CloudStream Library & Bookmarks (${allLocalTitles.size} titles):\n")
                allLocalTitles.forEach { fullSb.append("- $it\n") }
                fullSb.append("\n")
            }
            if (allRated.isNotEmpty()) {
                fullSb.append("User Ratings on Simkl (${allRated.size} titles):\n")
                allRated.forEach { item ->
                    val title = item.movie?.title ?: item.show?.title ?: item.anime?.title ?: ""
                    if (title.isNotBlank()) {
                        fullSb.append("- $title (Rated: ${item.userRating}/10)\n")
                    }
                }
                fullSb.append("\n")
            }
            if (allCompleted.isNotEmpty()) {
                fullSb.append("Completed / Watched Titles on Simkl (${allCompleted.size} titles):\n")
                allCompleted.forEach { item ->
                    val title = item.movie?.title ?: item.show?.title ?: item.anime?.title ?: ""
                    if (title.isNotBlank()) {
                        fullSb.append("- $title\n")
                    }
                }
                fullSb.append("\n")
            }
            if (allWatching.isNotEmpty()) {
                fullSb.append("Currently Watching on Simkl (${allWatching.size} titles):\n")
                allWatching.forEach { item ->
                    val title = item.movie?.title ?: item.show?.title ?: item.anime?.title ?: ""
                    if (title.isNotBlank()) {
                        fullSb.append("- $title\n")
                    }
                }
                fullSb.append("\n")
            }
            if (allPlanToWatch.isNotEmpty()) {
                fullSb.append("Plan to Watch / Watchlist on Simkl (${allPlanToWatch.size} titles):\n")
                allPlanToWatch.forEach { item ->
                    val title = item.movie?.title ?: item.show?.title ?: item.anime?.title ?: ""
                    if (title.isNotBlank()) {
                        fullSb.append("- $title\n")
                    }
                }
                fullSb.append("\n")
            }

            if (fullSb.isEmpty()) {
                fullSb.append("User has clean history. Focus on universal critically acclaimed productions.")
            }

            val fullHistory = fullSb.toString()
            val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val fileName = "nextup_training_data_${sdf.format(Date())}.jsonl"
            val file = File(downloadDir, fileName)

            file.bufferedWriter().use { writer ->
                // Example 1: Complete taste profile recommendation
                writer.write(mapper.writeValueAsString(mapOf(
                    "input" to "User Complete Taste Profile:\n$fullHistory\nRecommend 10 personalized movies, shows, and anime tailored strictly to this user's preferences.",
                    "output" to "Based on your verified watch history, high ratings, and library favorites, here is your personalized curation emphasizing compelling storytelling, psychological depth, and premium production quality."
                )))
                writer.newLine()

                // Example 2: Preferred themes & aesthetic analysis
                writer.write(mapper.writeValueAsString(mapOf(
                    "input" to "Analyze the user's entertainment preferences and preferred storytelling tropes based on their ratings and watch list:\n$fullHistory",
                    "output" to "The user demonstrates a distinct preference for cerebral thrillers, character-driven narrative arcs, high-stakes psychological tension, and richly world-built anime and fantasy."
                )))
                writer.newLine()

                // Example 3: Top Rated Focus (What they rated 10/10)
                val topTenTitles = allRated.filter { (it.userRating ?: 0) >= 9 }.mapNotNull { it.movie?.title ?: it.show?.title ?: it.anime?.title }
                if (topTenTitles.isNotEmpty()) {
                    writer.write(mapper.writeValueAsString(mapOf(
                        "input" to "The user gave a 10/10 rating to these titles: ${topTenTitles.joinToString(", ")}. What makes these appeal to the user?",
                        "output" to "These 10/10 masterworks share common threads: unmatched emotional resonance, innovative narrative structure, high cinematic caliber, and deeply memorable protagonists."
                    )))
                    writer.newLine()
                }

                // Example 4: Structured JSON curation matching NextUp's engine schema
                writer.write(mapper.writeValueAsString(mapOf(
                    "input" to "System: You are an expert AI curator for movies, TV series, and anime. Analyze the user's taste and output recommendations in JSON format.\nUser History:\n$fullHistory",
                    "output" to "{\n  \"top_picks\": [\"Inception\", \"Interstellar\", \"Severance\"],\n  \"because_watched\": [\"Chernobyl\", \"True Detective\", \"Mindhunter\"],\n  \"hidden_gems\": [\"Coherence\", \"Predestination\", \"The Fall\"],\n  \"movies\": [\"Arrival\", \"Sicario\", \"Dune: Part Two\"],\n  \"shows\": [\"Dark\", \"Succession\", \"Fargo\"],\n  \"anime\": [\"Steins;Gate\", \"Monster\", \"Death Note\", \"Vinland Saga\", \"Psycho-Pass\"]\n}"
                )))
                writer.newLine()
            }

            Log.i(TAG, "Full training dataset exported successfully (${allSimklItems.size} Simkl items, ${allLocalTitles.size} local bookmarks): ${file.absolutePath}")
            file
        } catch (t: Throwable) {
            Log.e(TAG, "Error exporting complete training dataset: ${t.message}", t)
            null
        }
    }
}
