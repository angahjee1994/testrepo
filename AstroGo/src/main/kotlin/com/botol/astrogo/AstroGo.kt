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
    private val bearerToken = "eyJraWQiOiJjNWM3ZmFkMC1lZWZmLTRjNmItYTczMC0zNjc3YTBjMTgyODEiLCJqa3UiOiJodHRwczovL3NnLXNnLXNnLmFzdHJvLmNvbS5teTo5NDQzL29hdXRoMi9qd2tzP2tpZD1jNWM3ZmFkMC1lZWZmLTRjNmItYTczMC0zNjc3YTBjMTgyODEiLCJhbGciOiJSUzI1NiJ9.eyJpYXQiOjE3NjkzMzcyNTgsInN1YiI6IkdVRVNULjA4MTJkN2I3LWZiMmQtNDc0Ni1hYjZjLWRlOWQxNzI3N2Q4ZCIsImF1ZCI6Iml2cC5zZXNzaW9uZ3VhcmQiLCJleHAiOjE3NjkzNDgwNTgsInNlc3Npb25fZGF0YSI6eyJzZXNzaW9uIjp7ImRldklkIjoiR1VFU1QuQnJvd3Nlci1EZWZhdWx0LjA4MTJkN2I3LWZiMmQtNDc0Ni1hYjZjLWRlOWQxNzI3N2Q4ZCIsImd1ZXN0TW9kZSI6dHJ1ZSwiaGhJZCI6IkdVRVNULjA4MTJkN2I3LWZiMmQtNDc0Ni1hYjZjLWRlOWQxNzI3N2Q4ZCIsImJ1c1VuaXRJZCI6IkFTVFJPIn19LCJzY29wZSI6ImJyb3dzZSBwbGF5YmFjayB1cm46c3luYW1lZGlhOnZjczpvdnA6Z3Vlc3QtdXNlciIsInRva2VuX3R5cGUiOiJhY2Nlc3NfdG9rZW4iLCJzc2FfanRpIjoiYnJvd3NlciIsImNsaWVudF9pZCI6ImJyb3dzZXIiLCJqdGkiOiJkYjkwZjAyMC01ODUxLTQ0MTEtYjQxMy1lYmNkMjNiOWJlOWQifQ.DR4zb8jHBQMnFUHir_dF1qbkTEbiZFrkEhRv7R8oP33AGIvo8zgO2TrinnL5SZvqPCzYFpMkcitB1uNMCbgyFFH3fuijQl3b6xLOGjUBgUGnrLT47nv9HywujKndnqALK6wAGopovDi9Jz6o3nPtslczhtuPkoCqVBLgE6cUGX5zQKzlZclS0HNvWCwI1fSq9frz8vSKK_J09NVfume5zP-ZByLG4DaCt77S3wE6mus-h_na-TfX8pLadGu3J3lf3-KNNoHE0YKG2lDJa347fzSWDCG8KPLnZ68z9q8bifZdFP1QQ8Lh9_gN9P-uAB194G8UyAtxHmFu_TNqsEtWNA"

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
        // Prefer externalId (UUID) if available, as contentInstances endpoint likes it better.
        // Fallback to id (PACK ID)
        val id = this.externalId ?: this.id ?: return null
        val title = this.title ?: return null
        // Find the best quality poster
        val poster = this.media?.firstOrNull()?.url
        
        val typeStr = this.showType ?: this.contentType
        val type = if (typeStr?.equals("Series", true) == true || 
                       typeStr?.equals("Program", true) == true ||
                       typeStr?.equals("TVShow", true) == true) TvType.TvSeries else TvType.Movie

        if (type == TvType.TvSeries) {
             return newTvSeriesSearchResponse(title, id, type) {
                 this.posterUrl = poster
             }
        } else {
             return newMovieSearchResponse(title, id, type) {
                 this.posterUrl = poster
             }
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
        // Sanitize the URL in case it includes the main URL
        // Remove everything before the last slash (e.g. series/, actionMenu/, movie/)
        // Then remove suffix if present (e.g. ~vod)
        val cleanId = url.substringAfterLast("/").substringBefore("~")
        
        val encodedToken = java.net.URLEncoder.encode(clientToken, "UTF-8")
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )
        
        // Try contentInstances first (standard for Movies/Episodes with UUID)
        var detailUrl = "$apiUrl/contentInstances/$cleanId"
        
        var response = try {
            app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
        } catch (e: Exception) { null }

        // If that failed or returned empty title, try content/show (standard for TV Series PACK ID)
        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/show/$cleanId"
             response = try { app.get(detailUrl, headers = headers).parsedSafe<AstroContent>() } catch(e: Exception) { null }
        }

        // Fallback: content/series (Sometimes PACK ID works here)
        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/series/$cleanId"
             response = try { app.get(detailUrl, headers = headers).parsedSafe<AstroContent>() } catch(e: Exception) { null }
        }

        // Fallback: content/movie (Sometimes TITL/PACK ID works here)
        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/movie/$cleanId"
             response = try { app.get(detailUrl, headers = headers).parsedSafe<AstroContent>() } catch(e: Exception) { null }
        }
        
        // Fallback 2: shared/content (for Movies/Content where we only have PACK ID)
        if (response == null || response.title == null) {
             val sharedUrl = "$apiUrl/shared/content?showId=$cleanId&source=vod&limit=1&offset=0&clientToken=$encodedToken"
             val sharedResp = try { app.get(sharedUrl, headers = headers).parsedSafe<AstroResponse>() } catch(e: Exception) { null }
             response = sharedResp?.content?.firstOrNull()
        }

        // Fallback 3: shared/content with 'id' (for UUIDs if showId failed)
        if (response == null || response.title == null) {
             val sharedUrl = "$apiUrl/shared/content?id=$cleanId&source=vod&limit=1&offset=0&clientToken=$encodedToken"
             val sharedResp = try { app.get(sharedUrl, headers = headers).parsedSafe<AstroResponse>() } catch(e: Exception) { null }
             response = sharedResp?.content?.firstOrNull()
        }

        // Fallback 4: shared/content with 'packId'
        if (response == null || response.title == null) {
             val sharedUrl = "$apiUrl/shared/content?packId=$cleanId&source=vod&limit=1&offset=0&clientToken=$encodedToken"
             val sharedResp = try { app.get(sharedUrl, headers = headers).parsedSafe<AstroResponse>() } 
             catch(e: Exception) { 
                 System.out.println("AstroGo Debug: Fallback 7 (shared/content packId) failed for $cleanId : ${e.message}")
                 null 
             }
             response = sharedResp?.content?.firstOrNull()
        }

        // Fallback 5: shared/content with 'externalId'
        if (response == null || response.title == null) {
             val sharedUrl = "$apiUrl/shared/content?externalId=$cleanId&source=vod&limit=1&offset=0&clientToken=$encodedToken"
             val sharedResp = try { app.get(sharedUrl, headers = headers).parsedSafe<AstroResponse>() } 
             catch(e: Exception) { 
                 System.out.println("AstroGo Debug: Fallback 8 (shared/content externalId) failed for $cleanId : ${e.message}")
                 null 
             }
             response = sharedResp?.content?.firstOrNull()
        }

        if (response == null) {
            System.out.println("AstroGo Debug: ALL fallbacks failed for $cleanId")
            throw ErrorLoadingException("Failed to load details")
        }

        val title = response.title ?: "No Title"
        val plot = response.synopsis ?: ""
        val poster = response.media?.firstOrNull()?.url
        val backdrop = response.media?.firstOrNull { it.width == 1920 }?.url ?: poster
        
        System.out.println("AstroGo Debug: Loaded $title. ContentType=${response.contentType} ShowType=${response.showType}")

        // Determine type based on contentType or showType
        // Astro contentType: "Movie", "Program", "Episode", "Series"
        // showType: "Series", "Movie"
        val typeStr = response.showType ?: response.contentType
        val type = if (typeStr?.equals("Series", true) == true || 
                       typeStr?.equals("Program", true) == true ||
                       typeStr?.equals("TVShow", true) == true) TvType.TvSeries else TvType.Movie
        
        // Use the ID from response if it looks like a PACK ID (preferred for episodes), otherwise cleanId
        // This handles cases where we loaded details via UUID but need PACK ID for episodes.
        val responseId = response.id
        val episodeQueryId = if (responseId != null && responseId.startsWith("astro", ignoreCase = true)) responseId else cleanId

        if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // 1. Try to fetch Seasons first
            val seasonsUrl = "$apiUrl/shared/content?showId=$episodeQueryId&sort=seasonNumber&limit=255&offset=0&source=vod&clientToken=$encodedToken"
            var seasonResponse = try {
                 app.get(seasonsUrl, headers = headers).parsedSafe<AstroResponse>()
            } catch (e: Exception) { 
                e.printStackTrace()
                null 
            }
            
            val validSeasons = seasonResponse?.content?.filter { 
                it.contentType?.contains("Season", true) == true 
            }

            if (!validSeasons.isNullOrEmpty()) {
                // It has seasons, fetch episodes for each
                validSeasons.forEach { season ->
                    val seasonId = season.id ?: return@forEach
                    val seasonNum = season.title?.filter { it.isDigit() }?.toIntOrNull() ?: 1
                    
                    val episodesUrl = "$apiUrl/shared/content?seasonId=$seasonId&sort=episodeNumber&limit=255&offset=0&source=vod&clientToken=$encodedToken"
                    val episodesResp = try {
                         app.get(episodesUrl, headers = headers).parsedSafe<AstroResponse>()
                    } catch (e: Exception) { null }
                    
                    episodesResp?.content?.forEach { ep ->
                        episodes.add(ep.toEpisode(seasonNum))
                    }
                }
            } else {
                // No seasons found, might be a flat list of episodes (like Running Man) or just episodes directly
                // Use the ID from the response, as it might be the canonical Show ID (e.g. PACK...) needed for episodes
                val showId = response.packId ?: response.externalId ?: response.id ?: cleanId
                
                 val flatEpisodesUrl = "$apiUrl/shared/content?showId=$showId&source=vod&limit=255&offset=0&sort=episodeNumber&isCollapsed=false&clientToken=$encodedToken"
                 val flatResp = try {
                         app.get(flatEpisodesUrl, headers = headers).parsedSafe<AstroResponse>()
                    } catch (e: Exception) { null }
                    
                 if (flatResp?.content?.isNotEmpty() == true) {
                     flatResp.content.forEach { ep ->
                         episodes.add(ep.toEpisode(1))
                     }
                 } else {
                     // Fallback: Try content/children (standard for some series like Pak Su Ammara)
                     val childrenUrl = "$apiUrl/content/$showId/children?clientToken=$encodedToken&limit=100&offset=0&source=vod"
                     try {
                         // Parse as a wrapper first (if it has 'content' field)
                         val asRepo = app.get(childrenUrl, headers = headers).parsedSafe<AstroResponse>()
                         if (asRepo?.content?.isNotEmpty() == true) {
                             asRepo.content.forEach { ep ->
                                 episodes.add(ep.toEpisode(1))
                             }
                         } else {
                             // Try parsing as a direct List
                             val asList = app.get(childrenUrl, headers = headers).parsedSafe<List<AstroContent>>()
                             asList?.forEach { ep ->
                                 episodes.add(ep.toEpisode(1))
                             }
                         }
                     } catch (e: Exception) { 
                         e.printStackTrace()
                     }
                 }
            }
            
            return newTvSeriesLoadResponse(title, cleanId, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                // this.year = year
                // this.tags = tags
                this.actors = response.cast?.mapNotNull { member ->
                    val name = member.name ?: return@mapNotNull null
                    ActorData(Actor(name, image = null), role = ActorRole.Main)
                } ?: response.actors?.map { ActorData(Actor(it, image = null), role = ActorRole.Main) }
            }
        } else {
            return newMovieLoadResponse(title, cleanId, TvType.Movie, cleanId) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                // this.year = year
                // this.tags = tags
                this.actors = response.cast?.mapNotNull { member ->
                    val name = member.name ?: return@mapNotNull null
                    ActorData(Actor(name, image = null), role = ActorRole.Main)
                } ?: response.actors?.map { ActorData(Actor(it, image = null), role = ActorRole.Main) }
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
        @JsonProperty("packId") val packId: String? = null,
        @JsonProperty("externalId") val externalId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("media") val media: List<AstroMedia>? = null,
        @JsonProperty("contentType") val contentType: String? = null,
        @JsonProperty("showType") val showType: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: String? = null,
        @JsonProperty("genres") val genres: List<AstroGenre>? = null,
        @JsonProperty("cast") val cast: List<AstroCast>? = null,
        @JsonProperty("actors") val actors: List<String>? = null
    )
    
    data class AstroCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("role") val role: String? = null
    )

    data class AstroGenre(
        @JsonProperty("name") val name: String? = null
    )

    data class AstroMedia(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("type") val type: String? = null
    )
}
