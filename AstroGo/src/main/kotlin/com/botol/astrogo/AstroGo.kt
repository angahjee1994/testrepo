package com.botol.astrogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class AstroGo : MainAPI() {
    override var mainUrl = "https://astrogo.astro.com.my"
    private val apiUrl = "https://sg-sg-sg.astro.com.my:9443/ctap/r1.6.0"
    override var name = "AstroGo"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.LiveTv, TvType.Movie, TvType.TvSeries)

    // Temp hardcoded token for testing (Captured from browser)
    private val clientToken = "v:1!r:80400!ur:NORTHERN!community:Malaysia Live!t:k!dt:PC!f:Astro_unmanaged!pd:CHROME-FF!pt:Adults"
    private val bearerToken = "eyJraWQiOiJjNWM3ZmFkMC1lZWZmLTRjNmItYTczMC0zNjc3YTBjMTgyODEiLCJqa3UiOiJodHRwczovL3NnLXNnLXNnLmFzdHJvLmNvbS5teTo5NDQzL29hdXRoMi9qd2tzP2tpZD1jNWM3ZmFkMC1lZWZmLTRjNmItYTczMC0zNjc3YTBjMTgyODEiLCJhbGciOiJSUzI1NiJ9.eyJpYXQiOjE3NjkyMDgxMDgsInN1YiI6Ijg2MjIwMzM4IiwiYXVkIjoiaXZwLnNlc3Npb25ndWFyZCIsImV4cCI6MTc2OTIxODkwOCwic2Vzc2lvbl9kYXRhIjp7InNlc3Npb24iOnsiZGV2SWQiOiI4NjIyMDMzOC41MWIyNjBlNy02NzEyLTQ4OWQtYTQ5Yy1hN2ViMDU4YTA1NzYiLCJndWVzdE1vZGUiOmZhbHNlLCJoaElkIjoiODYyMjAzMzgiLCJidXNVbml0SWQiOiJBU1RSTyJ9fSwiZGV2aWNlRnVsbFR5cGUiOiJCcm93c2VyLURlZmF1bHQiLCJzY29wZSI6ImJyb3dzZXIiLCJqdGkiOiIzMjZmNGYxYy03NzdhLTRlNjItOTNhMS1hMGQyNGMxNWNjZjEifQ.i4sQfmgp3HHi2ujKXbUEQfZxFN-lTNvLq2HVaJc7hrJDyHRmQBh1yOQ1y-yuFNkNXfumCOMKJB4hj8zWPEtDebc4itWtYYy3K9BCgcPbqBKwEnseu1SHupca_mbAUWWAh2Ise5G29kSAmuKkU1937zFgdzaJBUSa5NgmwhDZxme3Gk51l_4obY9_YtapPOHGUrsK_YowaSjZjBhshKSsN_aj9yoirBSpDt1ucupYDzsgtQB5R_JHS5MuJ0Vj4ZjQvSOb7FDttCqTUOAQlmLp6HKryhngpRHMPiKO8OvsnIVB3JSXi4bWzPwtONor4-jOUjJ_jcDigASXiJ1yv9c8PQ"

    override val mainPage = mainPageOf(
        "shared/bulkContent/node:IVP:Home" to "Home",
        "shared/bulkContent/IVP:TVShow" to "TV Shows",
        "shared/bulkContent/IVP:Movie" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$apiUrl/${request.data}?clientToken=$clientToken"
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )

        try {
            val response = app.get(url, headers = headers).parsedSafe<AstroResponse>()
            val items = ArrayList<HomePageList>()

            response?.categories?.forEach { category ->
                val title = category.title ?: "Untitled"
                val contents = category.content?.mapNotNull { it.toSearchResponse() }
                
                if (!contents.isNullOrEmpty()) {
                    items.add(HomePageList(title, contents))
                }
            }

            // Also check root level content if categories are empty but content exists
            if (response?.content != null) {
                 val contents = response.content.mapNotNull { it.toSearchResponse() }
                 if (contents.isNotEmpty()) {
                     items.add(HomePageList(request.name, contents))
                 }
            }

            return HomePageResponse(items)
        } catch (e: Exception) {
            e.printStackTrace()
            return HomePageResponse(emptyList())
        }
    }

    private fun AstroContent.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val title = this.title ?: return null
        // Find the best quality poster
        val poster = this.media?.firstOrNull()?.url

        return newMovieSearchResponse(title, id, TvType.Movie) {
            this.posterUrl = poster
            this.plot = this@toSearchResponse.synopsis
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Implement search based on finding the proper endpoint
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // url here acts as the ID from toSearchResponse
        // We need to fetch details. For now, basic placeholder.
        return newTokenLoadResponse("Details Placeholder", url, TvType.Movie) {
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }

    // JSON Data Models
    data class AstroResponse(
        @JsonProperty("categories") val categories: List<AstroCategory>? = null,
        @JsonProperty("content") val content: List<AstroContent>? = null
    )

    data class AstroCategory(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("content") val content: List<AstroContent>? = null
    )

    data class AstroContent(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("media") val media: List<AstroMedia>? = null,
        @JsonProperty("contentType") val contentType: String? = null
    )

    data class AstroMedia(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null
    )
}
