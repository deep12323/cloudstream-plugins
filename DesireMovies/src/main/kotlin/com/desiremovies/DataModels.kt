package com.desiremovies

import com.fasterxml.jackson.annotation.JsonProperty

data class MovieDownloadLink(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("size") val size: String? = null
)

data class EpisodeDownloadLink(
    @JsonProperty("name") val name: String,
    @JsonProperty("url") val url: String,
    @JsonProperty("quality") val quality: String? = null,
    @JsonProperty("size") val size: String? = null
)

data class CategoryItem(
    val name: String,
    val slug: String,
    var isEnabled: Boolean = true,
    val isCustom: Boolean = false
)

data class WpRendered(
    @JsonProperty("rendered") val rendered: String? = null
)

data class WpPost(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("date") val date: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("title") val title: WpRendered? = null,
    @JsonProperty("content") val content: WpRendered? = null
)
