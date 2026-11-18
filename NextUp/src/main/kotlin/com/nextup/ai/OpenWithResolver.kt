package com.nextup.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.ui.quicksearch.QuickSearchFragment
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.util.regex.Pattern

object OpenWithResolver {

    private const val TAG = "OpenWithResolver"
    private val SIMKL_URL_REGEX = Pattern.compile("https://simkl\\.com/([^/]+)/(\\d+)")
    private val mapper = jacksonObjectMapper()

    /**
     * Reads CloudStream's own Library "Open with" setting directly from DataStore SharedPreferences.
     * Stored under folder: "<account>/library_folder"
     * Key: "Simkl" or fallback "default"
     * Value: JSON object {"openType":"Provider","providerData":{"apiName":"CineSimkl"}}
     */
    fun getCloudStreamLibraryOpener(context: Context): Pair<String, String?>? {
        return try {
            val account = try { DataStoreHelper.getCurrentAccount() } catch (_: Throwable) { "0" }
            val folder = "$account/library_folder"
            val prefs = context.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)

            // Direct raw SharedPreferences lookup avoids Kotlinx SerializationException
            val rawSimkl = prefs.getString("$folder/Simkl", null)
                ?: prefs.getString("$folder/default", null)
                ?: prefs.getString("library_folder/Simkl", null)
                ?: prefs.getString("library_folder/default", null)

            if (!rawSimkl.isNullOrBlank()) {
                val map = mapper.readValue<Map<String, Any?>>(rawSimkl)
                val openType = map["openType"] as? String ?: ""
                val providerData = map["providerData"] as? Map<*, *>
                val apiName = providerData?.get("apiName") as? String
                Log.i(TAG, "Found CloudStream LibraryOpener: openType=$openType, apiName=$apiName")
                Pair(openType, apiName)
            } else {
                null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Could not read CloudStream LibraryOpener: ${t.message}")
            null
        }
    }

    fun findApi(name: String): MainAPI? {
        return APIHolder.getApiFromNameNull(name)
            ?: APIHolder.apis.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: if (name.contains("cinesimkl", ignoreCase = true)) {
                APIHolder.apis.firstOrNull { it.name.contains("cinesimkl", ignoreCase = true) }
            } else null
    }

    fun findCineSimklApi(): MainAPI? {
        return APIHolder.apis.firstOrNull { it.name.contains("cinesimkl", ignoreCase = true) }
            ?: APIHolder.getApiFromNameNull("CineSimkl")
    }

    fun normalizeSimklUrl(url: String): String {
        val matcher = SIMKL_URL_REGEX.matcher(url)
        if (!matcher.find()) return url
        val rawType = matcher.group(1)?.lowercase() ?: "movies"
        val id = matcher.group(2) ?: return url
        val canonicalType = when {
            rawType.contains("anime") -> "anime"
            rawType.contains("tv") || rawType.contains("show") -> "tv"
            else -> "movies"
        }
        return "https://simkl.com/$canonicalType/$id"
    }

    suspend fun resolveLoad(
        context: Context,
        url: String,
        nextUpFallback: suspend () -> LoadResponse?
    ): LoadResponse? {
        var mode = TasteProfileManager.getOpenWithMode(context)
        var targetProvider: String? = null

        // 1. If mode is Default (Follow CloudStream Setting), read CloudStream's library setting
        if (mode.equals("Default", ignoreCase = true) || mode.startsWith("Default")) {
            val csOpener = getCloudStreamLibraryOpener(context)
            if (csOpener != null) {
                val (csOpenType, csProvider) = csOpener
                when (csOpenType) {
                    "Provider" -> {
                        mode = csProvider ?: "CineSimkl"
                        targetProvider = csProvider
                    }
                    "Search" -> mode = "Search"
                    "Browser" -> mode = "Browser"
                    "None" -> mode = "None"
                    else -> mode = "CineSimkl"
                }
            } else {
                // If library opener is unassigned or default, check if CineSimkl is installed
                val cineSimklApi = findCineSimklApi()
                if (cineSimklApi != null) {
                    mode = "CineSimkl"
                    targetProvider = cineSimklApi.name
                } else {
                    mode = "NextUp"
                }
            }
        }

        // 2. Execute the resolved action
        when {
            mode.equals("None", ignoreCase = true) -> {
                Log.i(TAG, "OpenWith: None selected. Discarding open action.")
                return null
            }

            mode.equals("Browser", ignoreCase = true) -> {
                Log.i(TAG, "OpenWith: Browser selected for $url")
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to launch browser: ${t.message}")
                }
                return null
            }

            mode.equals("Search", ignoreCase = true) || mode.startsWith("Search") -> {
                Log.i(TAG, "OpenWith: Universal Multi-Provider Search selected for $url")
                val title = extractTitleOrFetch(url)
                if (title.isNotBlank()) {
                    val act = CommonActivity.activity
                    if (act != null) {
                        act.runOnUiThread {
                            try {
                                QuickSearchFragment.pushSearch(act, title)
                            } catch (_: Throwable) {
                                QuickSearchFragment.pushSearch(title)
                            }
                        }
                    }
                }
                return null
            }

            mode.contains("CineSimkl", ignoreCase = true) || targetProvider != null -> {
                val providerName = targetProvider ?: "CineSimkl"
                val targetApi = findApi(providerName) ?: findCineSimklApi()
                if (targetApi != null) {
                    Log.i(TAG, "Delegating load directly to ${targetApi.name}: $url")
                    val normalizedUrl = normalizeSimklUrl(url)

                    // Attempt 1: Direct load on targetApi with normalized canonical URL
                    var response: LoadResponse? = try {
                        targetApi.load(normalizedUrl)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Direct load on ${targetApi.name} threw error: ${t.message}")
                        null
                    }

                    // Attempt 2: If direct URL load was null, search targetApi for title and load matching result
                    if (response == null) {
                        try {
                            val title = extractTitleOrFetch(url)
                            if (title.isNotBlank()) {
                                Log.i(TAG, "Searching ${targetApi.name} for fallback: $title")
                                val searchResults = targetApi.search(title) ?: emptyList()
                                val match = searchResults.firstOrNull {
                                    it.name.equals(title, ignoreCase = true)
                                } ?: searchResults.firstOrNull()

                                if (match != null) {
                                    response = targetApi.load(match.url)
                                }
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "Search fallback on ${targetApi.name} threw error: ${t.message}")
                        }
                    }

                    if (response != null) {
                        return response
                    }
                    Log.w(TAG, "${targetApi.name} returned null for $url, falling back to NextUp native")
                } else {
                    Log.w(TAG, "Plugin $providerName not found in APIHolder, falling back to NextUp native")
                }
            }
        }

        // 3. NextUp Native fallback: Simkl overview page
        return nextUpFallback()
    }

    private suspend fun extractTitleOrFetch(url: String): String {
        return try {
            val matcher = SIMKL_URL_REGEX.matcher(url)
            if (matcher.find()) {
                val type = matcher.group(1) ?: "movies"
                val simklId = matcher.group(2)?.toIntOrNull()
                if (simklId != null) {
                    val details = SimklService.getDetails(simklId, type)
                    return details?.title ?: ""
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }
}
