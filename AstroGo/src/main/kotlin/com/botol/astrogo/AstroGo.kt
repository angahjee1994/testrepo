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
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    // Temp hardcoded token for testing (Captured from browser)
    private val clientToken = "v:1!r:80400!ur:NORTHERN!community:Malaysia Live!t:k!dt:PC!f:Astro_unmanaged!pd:CHROME-FF!pt:Adults"
    private val bearerToken = "eyJraWQiOiI0OGNiOWNjNy03YmVlLTRkNGMtYTU0OS02YzVlYmI2NGQ4YmIiLCJqa3UiOiJodHRwczovL3NnLXNnLXNnLmFzdHJvLmNvbS5teTo5NDQzL29hdXRoMi9qd2tzP2tpZD00OGNiOWNjNy03YmVlLTRkNGMtYTU0OS02YzVlYmI2NGQ4YmIiLCJhbGciOiJSUzI1NiJ9.eyJpYXQiOjE3NjkyMDk4MjIsInN1YiI6Ijg2MjIwMzM4IiwiYXVkIjoiaXZwLnNlc3Npb25ndWFyZCIsImV4cCI6MTc2OTIyMDYyMiwic2Vzc2lvbl9kYXRhIjp7InNlc3Npb24iOnsiZGV2SWQiOiI4NjIyMDMzOC41MWIyNjBlNy02NzEyLTQ4OWQtYTQ5Yy1hN2ViMDU4YTA1NzYiLCJndWVzdE1vZGUiOmZhbHNlLCJoaElkIjoiODYyMjAzMzgiLCJidXNVbml0SWQiOiJBU1RSTyJ9fSwiZGV2aWNlRnVsbFR5cGUiOiJCcm93c2VyLURlZmF1bHQiLCJzY29wZSI6ImJyb3dzZSBwbGF5YmFjayIsInRva2VuX3R5cGUiOiJhY2Nlc3NfdG9rZW4iLCJzc2FfanRpIjoiYnJvd3NlciIsImNsaWVudF9pZCI6ImJyb3dzZXIiLCJqdGkiOiI3NjQ5MWExYi0wYTZkLTRlNTAtOTgwYi0yNGNlNjlmNWM5NTQifQ.fAXuNJ5gYJrG2IKJ3SEj9WS8qd3DEOMMg3rx4hRTiV-i8ayCAFrPEuMWqWhOGkKuw3sde7TX0Z6nY5IMzGIzSuj2tM1AWCobCM1anK_UTbI6sgGwnlJ3XI94tvSho5sK8BtRDA-mLSDT1rjdrBlogYgNSUHS82y2wNw4RqCrtQRLs6bf6Aa00A8ZYwmPn_zK9QpRMgTYWAqWH5ouWePTyiXTpNJQnLkioozHnwCX9Ww1tHGvdDI-USivCOxTIARwgKZR3HJjoAc3X4G7cueduSButa4rcW8-TZotLleSRwV-bEgBnRT4cLCf2_CHb7QgmVLr0vau6sNeznrz6N88JA"

    override val mainPage = mainPageOf(
        "IVP:Home" to "Home",
        "IVP:TVShow" to "TV Shows",
        "node:IVP:Movies" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Prevent infinite loading/duplication by only loading page 1 for now
        if (page > 1) return newHomePageResponse(emptyList())

        val path = if (request.data.startsWith("node:")) request.data 
                  else if (request.data.contains("Home")) "node:${request.data}" 
                  else request.data
        val endpoint = "shared/bulkContent/$path"
        val url = "$apiUrl/$endpoint?clientToken=$clientToken"
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )

        try {
            val response = app.get(url, headers = headers).parsedSafe<AstroResponse>()
            val items = ArrayList<HomePageList>()
            val addedTitles = HashSet<String>()

            response?.categories?.forEach { category ->
                var title = category.title ?: request.name
                if (addedTitles.contains(title)) {
                    title = "$title (More)"
                }
                
                val contents = category.content?.mapNotNull { it.toSearchResponse() }
                
                if (!contents.isNullOrEmpty()) {
                    items.add(HomePageList(title, contents))
                    addedTitles.add(title)
                }
            }

            // Also check root level content
            if (response?.content != null) {
                 val contents = response.content.mapNotNull { it.toSearchResponse() }
                 if (contents.isNotEmpty()) {
                     // If we already have categories, this might be a "Featured" section or similar.
                     // If it's the ONLY thing (like in Movies tab potentially), use the request name.
                     var title = if (items.isEmpty()) request.name else "Featured"
                     if (addedTitles.contains(title)) title = "$title List"
                     
                     items.add(HomePageList(title, contents))
                 }
            }

            return newHomePageResponse(items)
        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(emptyList())
        }
    }

    private fun AstroContent.toSearchResponse(): SearchResponse? {
        val id = this.id ?: return null
        val title = this.title ?: return null
        // Find the best quality poster
        val poster = this.media?.firstOrNull()?.url

        return newMovieSearchResponse(title, id, TvType.Movie) {
            this.posterUrl = poster
            // Plot is often not available or settable in search response builder in some versions,
            // or requires specific casting. Omitting for now to fix build.
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Implement search based on finding the proper endpoint
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        // url here acts as the ID from toSearchResponse
        // We need to fetch details. For now, basic placeholder.
        return newMovieLoadResponse("Details Placeholder", url, TvType.Movie, url) {
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
