package com.desiremovies

import android.content.Context
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.mainPageOf

object DesireMoviesPreferences {
    private const val PREFS_NAME = "desiremovies_plugin_prefs"
    private const val KEY_CATEGORIES = "catalogue_categories"
    private const val KEY_ACTIVE_DOMAIN = "active_domain"
    const val DEFAULT_DOMAIN = "https://1desiremovies.xyz"
    const val LANDING_DOMAIN = "https://desiremovies.my"
    private val mapper = jacksonObjectMapper()

    val DEFAULT_CATEGORIES = listOf(
        CategoryItem("Latest Releases", "home", isEnabled = true),
        CategoryItem("Bollywood Movies", "bollywood-movies-desiremovie", isEnabled = true),
        CategoryItem("South Movies (Hindi)", "south-movieshindi", isEnabled = true),
        CategoryItem("Hollywood (Hindi Dubbed)", "hollywood-movies-download-hindi", isEnabled = true),
        CategoryItem("Hollywood (English)", "hollywood-movies-download", isEnabled = true),
        CategoryItem("Web Series", "web-series", isEnabled = true),
        CategoryItem("Korean Movie (Hindi)", "korean-movie-hindi", isEnabled = true),
        CategoryItem("Korean Show (Hindi)", "korean-show-in-hindi", isEnabled = true),
        CategoryItem("English TV Show (Hindi)", "english-tv-show-hindi", isEnabled = true),
        CategoryItem("Punjabi Movies", "punjabi-movies", isEnabled = true),
        CategoryItem("Gujarati Movies", "gujarati-movies", isEnabled = true),
        CategoryItem("Bhojpuri Movies", "bhojpuri-movies", isEnabled = true),
        CategoryItem("Old is Gold Movies", "old-is-gold-movies", isEnabled = true)
    )

    fun getSavedDomain(context: Context?): String {
        if (context == null) return DEFAULT_DOMAIN
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_DOMAIN, DEFAULT_DOMAIN) ?: DEFAULT_DOMAIN
    }

    fun setSavedDomain(context: Context?, domain: String) {
        if (context == null) return
        val clean = domain.trim().removeSuffix("/")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ACTIVE_DOMAIN, clean).apply()
    }

    fun getCategories(context: Context?): List<CategoryItem> {
        if (context == null) return DEFAULT_CATEGORIES
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_CATEGORIES, null) ?: return DEFAULT_CATEGORIES
        return try {
            mapper.readValue<List<CategoryItem>>(json)
        } catch (e: Exception) {
            DEFAULT_CATEGORIES
        }
    }

    fun saveCategories(context: Context?, categories: List<CategoryItem>) {
        if (context == null) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        try {
            val json = mapper.writeValueAsString(categories)
            prefs.edit().putString(KEY_CATEGORIES, json).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleCategory(context: Context?, slug: String, isEnabled: Boolean) {
        val list = getCategories(context).toMutableList()
        val index = list.indexOfFirst { it.slug == slug }
        if (index != -1) {
            list[index] = list[index].copy(isEnabled = isEnabled)
            saveCategories(context, list)
        }
    }

    fun addCustomCategory(context: Context?, name: String, slug: String): Boolean {
        val cleanName = name.trim()
        val cleanSlug = slug.trim().lowercase().removePrefix("/category/").removePrefix("/").removeSuffix("/")
        if (cleanName.isBlank() || cleanSlug.isBlank()) return false

        val list = getCategories(context).toMutableList()
        if (list.any { it.slug == cleanSlug }) return false

        list.add(CategoryItem(cleanName, cleanSlug, isEnabled = true, isCustom = true))
        saveCategories(context, list)
        return true
    }

    fun removeCategory(context: Context?, slug: String) {
        val list = getCategories(context).toMutableList()
        list.removeAll { it.slug == slug }
        saveCategories(context, list)
    }

    fun resetToDefaults(context: Context?) {
        saveCategories(context, DEFAULT_CATEGORIES)
    }

    fun getActiveCategories(context: Context?, baseUrl: String = getSavedDomain(context)): List<MainPageData> {
        val categories = getCategories(context).filter { it.isEnabled }
        val list = if (categories.isEmpty()) DEFAULT_CATEGORIES else categories

        val cleanBase = baseUrl.removeSuffix("/")
        val pairs = list.map { item ->
            val data = if (item.slug.equals("home", ignoreCase = true)) {
                cleanBase
            } else if (item.slug.startsWith("http://") || item.slug.startsWith("https://")) {
                item.slug
            } else {
                "$cleanBase/${item.slug}"
            }
            data to item.name
        }

        return mainPageOf(*pairs.toTypedArray())
    }
}
