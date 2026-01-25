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
    private val clientToken = "v:1!r:80800!ur:GUEST_REGION!community:Malaysia%20Live!t:k!dt:PC!f:Astro_unmanaged!pd:CHROME-FF!pt:Adults"
    private val bearerToken = "eyJraWQiOiJjNWM3ZmFkMC1lZWZmLTRjNmItYTczMC0zNjc3YTBjMTgyODEiLCJqa3UiOiJodHRwczovL3NnLXNnLXNnLmFzdHJvLmNvbS5teTo5NDQzL29hdXRoMi9qd2tzP2tpZD1jNWM3ZmFkMC1lZWZmLTRjNmItYTczMC0zNjc3YTBjMTgyODEiLCJhbGciOiJSUzI1NiJ9.eyJpYXQiOjE3NjkzNDYyNjYsInN1YiI6IkdVRVNULjA4MTJkN2I3LWZiMmQtNDc0Ni1hYjZjLWRlOWQxNzI3N2Q4ZCIsImF1ZCI6Iml2cC5zZXNzaW9uZ3VhcmQiLCJleHAiOjE3NjkzNTcwNjYsInNlc3Npb25fZGF0YSI6eyJzZXNzaW9uIjp7ImRldklkIjoiR1VFU1QuQnJvd3Nlci1EZWZhdWx0LjA4MTJkN2I3LWZiMmQtNDc0Ni1hYjZjLWRlOWQxNzI3N2Q4ZCIsImd1ZXN0TW9kZSI6dHJ1ZSwiaGhJZCI6IkdVRVNULjA4MTJkN2I3LWZiMmQtNDc0Ni1hYjZjLWRlOWQxNzI3N2Q4ZCIsImJ1c1VuaXRJZCI6IkFTVFJPIn19LCJzY29wZSI6ImJyb3dzZSBwbGF5YmFjayB1cm46c3luYW1lZGlhOnZjczpvdnA6Z3Vlc3QtdXNlciIsInRva2VuX3R5cGUiOiJhY2Nlc3NfdG9rZW4iLCJzc2FfanRpIjoiYnJvd3NlciIsImNsaWVudF9pZCI6ImJyb3dzZXIiLCJqdGkiOiI0ZTkwOGI0NC00ZWJiLTRkZjUtOWVkZi1iOGI3MWM1NjQwYmQifQ.NXTOCiI4YePjbeIhN6ihx75NMPJIgls2GUC80OhMVfyRn8dBUINYRHuVD6NlbIWOCksYQWEBGpahKi764YBln8ZR2hnefSQ7tWXmoJhukzymuSs6dFn2wMymOKE3ke3efc4zpZ98zm9aSSOMA74u_1jYp9LZPG-9Zw-0x1AsnGw5skSIO82DTxWYHqyMSOpVT2lfhvhQwifJNW3DPU6izXFl9iz7DETmOGAxfj8QgEOr1fXeF_KJ8c2puftKZHZ6ueW9D_Kk3GiXhl3ZAOvu3Pjtv_3jXW3DymAKlHdked921EyAvz_YA046w4l3uy0m311LsKpH0OBNpAv03ZTr8A"

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
        // Sanitize the URL in case it includes the main URL
        val rawId = url.removePrefix(mainUrl).removePrefix("/")
        // Remove suffix for clean ID (used for checking types, etc)
        val cleanId = rawId.substringBefore("~")
        
        val encodedToken = java.net.URLEncoder.encode(clientToken, "UTF-8")
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )
        
        // Try contentInstances first (standard for Movies/Episodes)
        // Movies often require the full ID (with ~...)
        var detailUrl = "$apiUrl/contentInstances/$rawId"
        
        var response = try {
            app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
        } catch (e: Exception) { null }

        // If that failed or returned empty, try content/show (standard for TV Series)
        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/show/$cleanId"
             response = app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
        }

        if (response == null) throw ErrorLoadingException("Failed to load details")

        val title = response.title ?: "No Title"
        val plot = response.synopsis
        val poster = response.media?.firstOrNull()?.url
        val year = response.releaseDate?.substringBefore("-")?.toIntOrNull()

        // Parse Duration
        var durationMin: Int? = null
        if (response.duration != null) {
            val parts = response.duration.split(":")
            if (parts.size == 3) {
                 durationMin = (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            } else if (parts.size == 2) {
                 durationMin = (parts[0].toIntOrNull() ?: 0)
            } else {
                 durationMin = (response.duration.toIntOrNull() ?: 0) / 60
            }
        }

        // Parse Actors
        val actorsList = ArrayList<ActorData>()
        response.credits?.actors?.forEach { 
             actorsList.add(ActorData(Actor(it.trim(), image = null), role = ActorRole.Main)) 
        }
        response.credits?.directors?.forEach { 
             actorsList.add(ActorData(Actor(it.trim(), image = null), role = ActorRole.Main)) 
        }
        if (actorsList.isEmpty()) {
            response.cast?.forEach { member ->
                val name = member.name ?: return@forEach
                actorsList.add(ActorData(Actor(name, image = null), role = ActorRole.Main))
            }
        }
        if (actorsList.isEmpty()) {
            response.actors?.forEach { 
                actorsList.add(ActorData(Actor(it, image = null), role = ActorRole.Main)) 
            }
        }
        
        // Handle TV Series vs Movie
        // Astro contentType: "Movie", "Program" (Show), "Episode", "show", "Series"
        val type = response.contentType
        val isTv = type.equals("Program", true) || 
                   type.equals("TVShow", true) ||
                   type.equals("show", true) ||
                   type.equals("Series", true)

        val tags = response.genres?.mapNotNull { it.name }

        if (isTv) {
            val episodes = ArrayList<Episode>()
            // Ensure we don't double encode if user pasted an encoded token
            val rawToken = clientToken.replace("%20", " ")
            val encodedToken = java.net.URLEncoder.encode(rawToken, "UTF-8").replace("+", "%20")
            
            // Use the ID from the response, as it might be the canonical Show ID (e.g. PACK...) needed for both Seasons and Episodes
            // Ensure we strip any suffix for the Show ID used in 'shared/content' calls (Upin needs clean ID)
            val baseId = response.packId ?: response.externalId ?: response.id ?: cleanId
            val showId = baseId.substringBefore("~")

            // Helper to parse response
            suspend fun fetchContent(url: String): List<AstroContent>? {
                return try {
                    val text = app.get(url, headers = headers).text
                    // Try parsing as wrapper
                    val wrapper = AppUtils.parseJson<AstroResponse>(text)
                    if (!wrapper.content.isNullOrEmpty()) return wrapper.content
                    
                    // Try parsing as list
                    return AppUtils.parseJson<List<AstroContent>>(text)
                } catch (e: Exception) { 
                    null 
                }
            }

            // 1. Try to fetch Seasons first
            val seasonsUrl = "$apiUrl/shared/content?showId=$showId&sort=seasonNumber&limit=255&offset=0&isErotic=false&isAdult=false&source=vod&clientToken=$encodedToken"
            
            val seasonContent = fetchContent(seasonsUrl)
            
            // Assume any content returned by sort=seasonNumber IS a season (don't specific filter by type)
            if (!seasonContent.isNullOrEmpty()) {
                // It has seasons, fetch episodes for each
                seasonContent.forEach { season ->
                    // Use seasonId if available (clean ID without ~vod), else fallback to id
                    val seasonId = season.seasonId ?: season.id ?: return@forEach
                    val seasonNum = season.title?.filter { it.isDigit() }?.toIntOrNull() ?: season.seasonNumber ?: 1
                    
                    val episodesUrl = "$apiUrl/shared/content?seasonId=$seasonId&sort=episodeNumber&limit=255&offset=0&isErotic=false&isAdult=false&source=vod&clientToken=$encodedToken"
                    val episodesContent = fetchContent(episodesUrl)
                    
                    episodesContent?.forEach { ep ->
                        episodes.add(ep.toEpisode(seasonNum))
                    }
                }
            } else {
                // No seasons found, might be a flat list of episodes
                val flatEpisodesUrl = "$apiUrl/shared/content?showId=$showId&source=vod&limit=255&offset=0&sort=episodeNumber&isCollapsed=false&isErotic=false&isAdult=false&clientToken=$encodedToken"
                val flatContent = fetchContent(flatEpisodesUrl)
                    
                 if (!flatContent.isNullOrEmpty()) {
                     flatContent.forEach { ep ->
                         episodes.add(ep.toEpisode(1))
                     }
                 } else {
                     // Fallback: Try content/children (standard for some series like Pak Su Ammara)
                     val childrenUrl = "$apiUrl/content/$showId/children?clientToken=$encodedToken&limit=100&offset=0"
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
                this.backgroundPosterUrl = response.media?.find { it.url?.contains("LAND") == true || it.url?.contains("backdrop") == true }?.url
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = durationMin
                this.actors = actorsList
            }
        } else {
            return newMovieLoadResponse(title, cleanId, TvType.Movie, cleanId) {
                this.posterUrl = poster
                this.backgroundPosterUrl = response.media?.find { it.url?.contains("LAND") == true || it.url?.contains("backdrop") == true }?.url
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = durationMin
                this.actors = actorsList
            }
        }
    }
    
    private fun AstroContent.toEpisode(season: Int?): Episode {
        // Use metadata if available, fall back to heuristic or passed season
        val epVal = this.episodeNumber ?: this.title?.substringBefore(" ")?.toIntOrNull()
        val seasonVal = this.seasonNumber ?: season
        
        return newEpisode(this.id ?: "") {
            this.name = this@toEpisode.title
            this.season = seasonVal
            this.episode = epVal
            this.posterUrl = this@toEpisode.media?.firstOrNull()?.url
            this.description = this@toEpisode.synopsis
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
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: String? = null,
        @JsonProperty("genres") val genres: List<AstroGenre>? = null,
        @JsonProperty("cast") val cast: List<AstroCast>? = null,
        @JsonProperty("actors") val actors: List<String>? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("showId") val showId: String? = null,
        @JsonProperty("seasonId") val seasonId: String? = null,
        @JsonProperty("credits") val credits: AstroCredits? = null
    )
    
    data class AstroCredits(
        @JsonProperty("actors") val actors: List<String>? = null,
        @JsonProperty("directors") val directors: List<String>? = null
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
