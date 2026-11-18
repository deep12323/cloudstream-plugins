package com.nextup.ai

import android.util.Log
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections

@JsonIgnoreProperties(ignoreUnknown = true)
data class GeminiRecommendation(
    @JsonProperty("title") val title: String,
    @JsonProperty("year") val year: Int? = null,
    @JsonProperty("type") val type: String = "movie",
    @JsonProperty("reason") val reason: String? = null,
    @JsonProperty("category") val category: String = "top_picks"
)

object GeminiClient {

    private const val TAG = "GeminiClient"
    private const val GEMINI_API_HOST = "https://generativelanguage.googleapis.com/v1beta"
    const val DEFAULT_MODEL = "gemini-1.5-flash"
    const val FALLBACK_MODEL = "gemini-2.0-flash"

    // In-memory set of recently recommended titles to instruct Gemini to generate fresh, non-repeating recommendations
    private val recentRecommendationTitles = Collections.synchronizedSet(mutableSetOf<String>())

    fun resolveModelEndpoint(modelName: String?): String {
        val trimmed = modelName?.trim() ?: ""
        val lower = trimmed.lowercase()
        return when {
            lower.isEmpty() || lower.startsWith("auto") -> "models/$DEFAULT_MODEL"
            lower.startsWith("tunedmodels/") -> trimmed
            lower.startsWith("models/") -> trimmed
            lower.startsWith("gemini-") -> "models/$trimmed"
            else -> "tunedModels/$trimmed"
        }
    }

    suspend fun getAvailableModels(apiKey: String): List<String> {
        if (apiKey.isBlank()) return listOf(DEFAULT_MODEL, FALLBACK_MODEL)
        return try {
            val url = "$GEMINI_API_HOST/models?key=$apiKey"
            val resp = app.get(url, timeout = 10L).text
            val json = JSONObject(resp)
            val modelsArr = json.optJSONArray("models") ?: JSONArray()
            val list = mutableListOf<String>()
            for (i in 0 until modelsArr.length()) {
                val m = modelsArr.getJSONObject(i)
                val name = m.optString("name", "").removePrefix("models/")
                val supportedMethods = m.optJSONArray("supportedGenerationMethods")
                var canGenerate = false
                if (supportedMethods != null) {
                    for (j in 0 until supportedMethods.length()) {
                        if (supportedMethods.getString(j) == "generateContent") {
                            canGenerate = true
                            break
                        }
                    }
                }
                if (canGenerate && !name.contains("embedding") && !name.contains("aqa")) {
                    list.add(name)
                }
            }
            if (list.isEmpty()) listOf(DEFAULT_MODEL, FALLBACK_MODEL) else list
        } catch (_: Throwable) {
            listOf(DEFAULT_MODEL, FALLBACK_MODEL)
        }
    }

    suspend fun generateRecommendations(
        apiKey: String,
        modelName: String?,
        watchedContext: String,
        vibe: String = "Balanced"
    ): List<GeminiRecommendation> {
        if (apiKey.isBlank()) return emptyList()

        val endpointModel = resolveModelEndpoint(modelName)
        val isTunedModel = endpointModel.startsWith("tunedModels/")

        val sessionSeed = (System.currentTimeMillis() % 1000000).toString()
        val recentAvoidText = if (recentRecommendationTitles.isNotEmpty()) {
            val avoidList = recentRecommendationTitles.toList().takeLast(40).joinToString(", ")
            "\nRECENTLY SHOWN TITLES TO AVOID (Strictly do not repeat any of these titles):\n$avoidList\n"
        } else ""

        val systemPrompt = """
You are NextUp AI, a world-class film, TV & anime curator integrated into CloudStream.
Your mission is to examine the user's movie, series, and anime watch history and generate uncanny, hyper-personalized recommendations tailored to their taste.

User's Watch History & Taste Profile:
$watchedContext

Requested Vibe / Direction:
$vibe
$recentAvoidText
Curator Session Randomizer Seed: $sessionSeed

OUTPUT RULES:
- Output MUST be a valid JSON array of objects.
- Each object MUST contain:
  "title": Exact official title (e.g. "Inception", "Severance", "Steins;Gate")
  "year": Release year as integer (e.g. 2010)
  "type": "movie", "tv", or "anime"
  "reason": 1 punchy sentence explaining why this matches their taste
  "category": One of these 6 category keys:
     - "top_picks" : 🌟 Top Picks For You (highest quality cinematic picks tailored to their overall taste)
     - "because_watched" : 🎯 Because You Watched (inspired by specific titles in their watch history)
     - "hidden_gems" : 💎 Hidden Gems & Underrated (exceptional sleeper hits, indie or cult masterpieces)
     - "movies" : 🍿 Recommended Movies (stellar feature films)
     - "shows" : 📺 Binge-Worthy Series (compelling multi-season drama, mystery, or thriller series)
     - "anime" : ⛩️ Personalized Anime (acclaimed anime movies or series)
- Provide exactly 5 to 6 distinct, highly relevant titles per category (around 30 to 36 total).
- CRITICAL DEDUPLICATION: Every single title across the entire array MUST be completely unique. Never repeat any title!
- CRITICAL WATCHED FILTER: DO NOT recommend ANY title listed in the user's watch history above! The user has already watched them. Only recommend titles the user has NOT watched.
- VARIETY & NOVELTY: Provide fresh, surprising, high-quality discoveries across unexpected gems, international cinema, cult classics, and acclaimed titles.
- Output ONLY pure JSON. No markdown formatting, no code blocks, no explanations.
""".trimIndent()

        val requestBodyJson = JSONObject().apply {
            val contentsArr = JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemPrompt)
                        })
                    })
                })
            }
            put("contents", contentsArr)
            put("generationConfig", JSONObject().apply {
                put("temperature", 1.0)
                // Tuned models in Google API reject response_mime_type with 400 Bad Request
                if (!isTunedModel) {
                    put("response_mime_type", "application/json")
                }
            })
        }.toString()

        val url = "$GEMINI_API_HOST/$endpointModel:generateContent?key=$apiKey"
        val mediaType = "application/json".toMediaTypeOrNull()

        return try {
            Log.i(TAG, "Requesting recommendations from model: $endpointModel")
            val responseText = app.post(
                url,
                headers = mapOf("Content-Type" to "application/json"),
                requestBody = requestBodyJson.toRequestBody(mediaType),
                timeout = 25L
            ).text

            val results = parseGeminiResponse(responseText)
            if (results.isNotEmpty()) {
                return results
            }
            throw IllegalStateException("Empty parsed recommendations from $endpointModel")
        } catch (t: Throwable) {
            Log.w(TAG, "Model $endpointModel failed: ${t.message}. Retrying with $DEFAULT_MODEL...")

            val standardConfigJson = JSONObject().apply {
                val contentsArr = JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", systemPrompt)
                            })
                        })
                    })
                }
                put("contents", contentsArr)
                put("generationConfig", JSONObject().apply {
                    put("temperature", 1.0)
                    put("response_mime_type", "application/json")
                })
            }.toString()

            // Graceful fallback 1: DEFAULT_MODEL (gemini-1.5-flash)
            if (endpointModel != "models/$DEFAULT_MODEL") {
                try {
                    val fallbackUrl = "$GEMINI_API_HOST/models/$DEFAULT_MODEL:generateContent?key=$apiKey"
                    val fallbackResp = app.post(
                        fallbackUrl,
                        headers = mapOf("Content-Type" to "application/json"),
                        requestBody = standardConfigJson.toRequestBody(mediaType),
                        timeout = 25L
                    ).text
                    val fallbackResults = parseGeminiResponse(fallbackResp)
                    if (fallbackResults.isNotEmpty()) return fallbackResults
                } catch (e: Throwable) {
                    Log.w(TAG, "Fallback to $DEFAULT_MODEL failed: ${e.message}")
                }
            }

            // Secondary fallback 2: FALLBACK_MODEL (gemini-2.0-flash)
            if (endpointModel != "models/$FALLBACK_MODEL") {
                try {
                    val fallbackUrl2 = "$GEMINI_API_HOST/models/$FALLBACK_MODEL:generateContent?key=$apiKey"
                    val fallbackResp2 = app.post(
                        fallbackUrl2,
                        headers = mapOf("Content-Type" to "application/json"),
                        requestBody = standardConfigJson.toRequestBody(mediaType),
                        timeout = 25L
                    ).text
                    val fallbackResults2 = parseGeminiResponse(fallbackResp2)
                    if (fallbackResults2.isNotEmpty()) return fallbackResults2
                } catch (e: Throwable) {
                    Log.w(TAG, "Fallback to $FALLBACK_MODEL failed: ${e.message}")
                }
            }
            emptyList()
        }
    }

    fun mapCategory(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains("top") || lower.contains("pick") -> "top_picks"
            lower.contains("watched") || lower.contains("because") -> "because_watched"
            lower.contains("gem") || lower.contains("hidden") || lower.contains("underrated") -> "hidden_gems"
            lower.contains("anime") -> "anime"
            lower.contains("show") || lower.contains("series") || lower.contains("binge") -> "shows"
            lower.contains("movie") || lower.contains("film") -> "movies"
            else -> "top_picks"
        }
    }

    private fun parseGeminiResponse(responseJson: String): List<GeminiRecommendation> {
        return try {
            val root = JSONObject(responseJson)
            val candidates = root.optJSONArray("candidates") ?: return emptyList()
            if (candidates.length() == 0) return emptyList()

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return emptyList()
            val parts = content.optJSONArray("parts") ?: return emptyList()
            if (parts.length() == 0) return emptyList()

            val rawText = parts.getJSONObject(0).optString("text", "").trim()
            val list = mutableListOf<GeminiRecommendation>()
            val seenTitles = mutableSetOf<String>()

            fun addObj(obj: JSONObject, defaultCat: String) {
                val title = obj.optString("title", "").trim()
                if (title.isBlank()) return
                val norm = TasteProfileManager.normalizeTitle(title)
                if (norm.isBlank() || seenTitles.contains(norm)) return
                seenTitles.add(norm)

                val year = if (obj.has("year") && !obj.isNull("year")) obj.optInt("year") else null
                val type = obj.optString("type", "movie").lowercase()
                val reason = obj.optString("reason", "")
                val rawCategory = if (obj.has("category")) obj.optString("category", defaultCat) else defaultCat
                val category = mapCategory(rawCategory)

                list.add(
                    GeminiRecommendation(
                        title = title,
                        year = year,
                        type = type,
                        reason = reason,
                        category = category
                    )
                )
            }

            val firstBracket = rawText.indexOf('[')
            val firstBrace = rawText.indexOf('{')

            if (firstBracket != -1 && (firstBrace == -1 || firstBracket < firstBrace)) {
                val lastBracket = rawText.lastIndexOf(']')
                if (lastBracket > firstBracket) {
                    val arrayJson = rawText.substring(firstBracket, lastBracket + 1)
                    val arr = JSONArray(arrayJson)
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        addObj(item, "top_picks")
                    }
                }
            } else if (firstBrace != -1) {
                val lastBrace = rawText.lastIndexOf('}')
                if (lastBrace > firstBrace) {
                    val objectJson = rawText.substring(firstBrace, lastBrace + 1)
                    val obj = JSONObject(objectJson)
                    val recsArr = obj.optJSONArray("recommendations") ?: obj.optJSONArray("items")
                    if (recsArr != null) {
                        for (i in 0 until recsArr.length()) {
                            val item = recsArr.optJSONObject(i) ?: continue
                            addObj(item, "top_picks")
                        }
                    } else {
                        val keys = obj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val catArr = obj.optJSONArray(k)
                            if (catArr != null) {
                                for (i in 0 until catArr.length()) {
                                    val item = catArr.optJSONObject(i) ?: continue
                                    addObj(item, k)
                                }
                            }
                        }
                    }
                }
            }

            // Track recent titles to ensure diversity on next refresh
            if (list.isNotEmpty()) {
                recentRecommendationTitles.addAll(list.map { it.title.lowercase() })
                if (recentRecommendationTitles.size > 150) {
                    recentRecommendationTitles.clear()
                }
            }

            list
        } catch (t: Throwable) {
            Log.w(TAG, "parseGeminiResponse failed: ${t.message}")
            emptyList()
        }
    }
}
