package com.botol.astrogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class AstroGo : MainAPI() {
    override var mainUrl = "https://astrogo.astro.com.my"
    private val apiUrl = "https://sg-sg-sg.astro.com.my/ctap/r1.6.0"
    override var name = "AstroGo"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    // Temp hardcoded token for testing (Captured from browser)
    private val clientToken = "v:1!r:80400!ur:NORTHERN!community:Malaysia Live!t:k!dt:PC!f:Astro_unmanaged!pd:CHROME-FF!pt:Adults"
    private val bearerToken = "eyJraWQiOiIyOGI4YTM0OC0wNWUxLTQxMWMtYWY4Ny03YWZhNjg5NTA5NzQiLCJqa3UiOiJodHRwczovL3NnLXNnLXNnLmFzdHJvLmNvbS5teTo5NDQzL29hdXRoMi9qd2tzP2tpZD0yOGI4YTM0OC0wNWUxLTQxMWMtYWY4Ny03YWZhNjg5NTA5NzQiLCJhbGciOiJSUzI1NiJ9.eyJpYXQiOjE3NjkyMTE5MzksInN1YiI6Ijg2MjIwMzM4IiwiYXVkIjoiaXZwLnNlc3Npb25ndWFyZCIsImV4cCI6MTc2OTIyMjczOSwic2Vzc2lvbl9kYXRhIjp7InNlc3Npb24iOnsiZGV2SWQiOiI4NjIyMDMzOC4wODEyZDdiNy1mYjJkLTQ3NDYtYWI2Yy1kZTlkMTcyNzdkOGQiLCJndWVzdE1vZGUiOmZhbHNlLCJoaElkIjoiODYyMjAzMzgiLCJidXNVbml0SWQiOiJBU1RSTyJ9fSwiZGV2aWNlRnVsbFR5cGUiOiJCcm93c2VyLURlZmF1bHQiLCJzY29wZSI6ImJyb3dzZSBwbGF5YmFjayIsInRva2VuX3R5cGUiOiJhY2Nlc3NfdG9rZW4iLCJzc2FfanRpIjoiYnJvd3NlciIsImNsaWVudF9pZCI6ImJyb3dzZXIiLCJqdGkiOiIzYTA4N2VlNC02ZTUyLTRhYzAtYjY1Mi1jYjc5NTJhZDQ0NWIifQ.hBaFxA-rSm56RZYunE-BUEVHxNrf09019OIIQ2BrkJePcqKmXwmQr5ZlJFCOzyOS_BJr_qbluC4tPbHC2naQi-77PahnR1p9s-UTiA92vn5_VMFKjHrZwAEQ6KAo1rqZCyirJ36m1se6GvOEgkdUISSM4znrA3LF91awcICsudbD9Ut_kds3xPErAh8eowmHIFgXDC9l6tdCvttPEa61wwNtvEJDDNJXLFPyMIiiiv9ZeZYeQ86IQcY1GhBBbwJIJapnK6uYMn8Wv7VUoPC8j6ywzs_0R9hhhRfpDOzT5VEe03Uz6WP-g2W9Yq8LAopn4MELyI2t1-LlCwFeeHZ0vg"

    override val mainPage = mainPageOf(
        "node:IVP:Home:VodForYou" to "Home",
        "IVP:TVShow,-date" to "TV Shows",
        "node:IVP:Movies,-date" to "Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val offset = (page - 1) * 20
        
        val dataParts = request.data.split(",")
        var dataPath = dataParts[0]
        val sort = dataParts.getOrNull(1)

        if (sort != null && dataPath == "IVP:TVShow") {
            dataPath += ":All"
        }

        val encodedToken = java.net.URLEncoder.encode(clientToken, "UTF-8")
        val encodedPath = java.net.URLEncoder.encode(dataPath, "UTF-8")
        val url: String
        
        if (dataPath.contains("Home")) {
             // Home aggregation endpoint
             url = "$apiUrl/agg/content?categoryId=$encodedPath&limit=20"
        } else if (sort != null) {
             // Sorted content lists (Movies/TV)
             url = "$apiUrl/shared/content?categoryId=$encodedPath&clientToken=$encodedToken&offset=$offset&limit=20&sort=$sort"
        } else {
             // Fallback for standard swimlanes
             val endpoint = "shared/bulkContent/$encodedPath"
             url = "$apiUrl/$endpoint?clientToken=$encodedToken"
        }
        
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
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        // Based on browser capture, search uses agg/content with source=vod
        val url = "$apiUrl/agg/content?limit=40&q=$encodedQuery&source=vod&sort=relevancy&isErotic=false&isAdult=false"
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )

        return try {
            val response = app.get(url, headers = headers).parsedSafe<AstroResponse>()
            response?.content?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val encodedToken = java.net.URLEncoder.encode(clientToken, "UTF-8")
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )
        
        // Try contentInstances first (standard for Movies/Episodes)
        var detailUrl = "$apiUrl/contentInstances/$url?clientToken=$encodedToken"
        
        var response = try {
            app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
        } catch (e: Exception) { null }

        // If that failed or returned empty, try content/show (standard for TV Series)
        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/show/$url?clientToken=$encodedToken"
             response = app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
        }

        if (response == null) throw ErrorLoadingException("Failed to load details")

        val title = response.title ?: "No Title"
        val plot = response.synopsis
        val poster = response.media?.firstOrNull()?.url
        val year = response.releaseDate?.substringBefore("-")?.toIntOrNull()
        
        // Handle TV Series vs Movie
        // Astro contentType: "Movie", "Program" (Show), "Episode"
        val isTv = response.contentType?.equals("Program", true) == true || 
                   response.contentType?.equals("TVShow", true) == true

        if (isTv) {
            val episodes = ArrayList<Episode>()
            val encodedToken = java.net.URLEncoder.encode(clientToken, "UTF-8")
            
            // 1. Try to fetch Seasons first
            val seasonsUrl = "$apiUrl/shared/content?showId=$url&sort=seasonNumber&limit=255&offset=0&clientToken=$encodedToken"
            var seasonResponse = try {
                 app.get(seasonsUrl, headers = headers).parsedSafe<AstroResponse>()
            } catch (e: Exception) { null }
            
            val validSeasons = seasonResponse?.content?.filter { 
                it.contentType?.contains("Season", true) == true 
            }

            if (!validSeasons.isNullOrEmpty()) {
                // It has seasons, fetch episodes for each
                validSeasons.forEach { season ->
                    val seasonId = season.id ?: return@forEach
                    val seasonNum = season.title?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                    
                    val episodesUrl = "$apiUrl/shared/content?seasonId=$seasonId&sort=episodeNumber&limit=255&offset=0&clientToken=$encodedToken"
                    val episodesResp = try {
                         app.get(episodesUrl, headers = headers).parsedSafe<AstroResponse>()
                    } catch (e: Exception) { null }
                    
                    episodesResp?.content?.forEach { ep ->
                        episodes.add(ep.toEpisode(seasonNum))
                    }
                }
            } else {
                // No seasons found, might be a flat list of episodes (like Running Man) or just episodes directly
                 val flatEpisodesUrl = "$apiUrl/shared/content?showId=$url&source=vod&limit=255&offset=0&sort=episodeNumber&isCollapsed=false&clientToken=$encodedToken"
                 val flatResp = try {
                         app.get(flatEpisodesUrl, headers = headers).parsedSafe<AstroResponse>()
                    } catch (e: Exception) { null }
                    
                 flatResp?.content?.forEach { ep ->
                     episodes.add(ep.toEpisode(1))
                 }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }
    
    private fun AstroContent.toEpisode(season: Int?): Episode {
        val epNum = this.title?.substringBefore(" ")?.toIntOrNull() // Heuristic if title is "1. Episode Name"
        // Better to use metadata if available, but for now map basic fields
        return newEpisode(this.id ?: "") {
            this.name = this@toEpisode.title
            this.season = season
            this.posterUrl = this@toEpisode.media?.firstOrNull()?.url
            this.description = this@toEpisode.synopsis
            // this.episode = epNum // If we can parse it reliably
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Placeholder for next step
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
        @JsonProperty("contentType") val contentType: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: String? = null
    )

    data class AstroMedia(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null
    )
}
