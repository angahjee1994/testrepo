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

    // configuration
    private var clientToken = ""
    private var bearerToken = ""

    override val mainPage = mainPageOf(
        "node:IVP:Home:VodForYou" to "Home",
        "node:IVP:TVShow,-date" to "TV Shows",
        "node:IVP:Movies,-date" to "Movies"
    )

    private suspend fun refreshAccessToken() {
        try {
            // 1. Scrape Client Token if missing
            if (clientToken.isEmpty()) {
                val mainPageResp = app.get("https://astrogo.astro.com.my").text
                val pattern = "v:1!r:[^\"']+".toRegex()
                val match = pattern.find(mainPageResp)
                if (match != null) {
                    clientToken = match.value
                }
            }

            // 2. Initiate OAuth2 Guest Flow directly (bypassing JS logic on main page)
            // URL discovered via browser analysis
            val authUrl = "https://sg-sg-sg.astro.com.my:9443/oauth2/authorize?client_id=browser&state=guestUserLogin&redirect_uri=https://astrogo.astro.com.my&response_type=token&prompt=none&scope=urn:synamedia:vcs:ovp:guest-user"
            
            var currentUrl = authUrl
            var attempts = 0
            while (attempts < 10) {
                // Cloudfront/providers might require a browser UA
                val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                
                val response = app.get(currentUrl, headers = headers, allowRedirects = false)
                if (response.code in 300..308) {
                    val location = response.headers["Location"] ?: break
                    if (location.contains("access_token")) {
                        val token = location.substringAfter("access_token=").substringBefore("&")
                        if (token.isNotEmpty()) {
                            bearerToken = token
                            break
                        }
                    }
                    
                    // Handle relative redirects
                    val nextUrl = if (location.startsWith("/")) {
                        val uri = java.net.URI(currentUrl)
                        "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}$location"
                    } else {
                        location
                    }
                    currentUrl = nextUrl
                    attempts++
                } else {
                    // Non-redirect response? Maybe we reached the end or blocked
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Refresh token if needed (simple check: always try once per session or just lazy)
        // For now, let's try refreshing if the hardcoded one looks old or just always?
        // Always refreshing might slow down. Let's try it.
        // Actually, better to only refresh if API fails?
        // But mainPage is the entry point.
        refreshAccessToken()
        
        val offset = (page - 1) * 20
        
        val dataParts = request.data.split(",")
        val dataPath = dataParts[0]
        val sort = dataParts.getOrNull(1)

        val encodedToken = java.net.URLEncoder.encode(clientToken, "UTF-8")
        val encodedPath = java.net.URLEncoder.encode(dataPath, "UTF-8")
        val url: String
        
        if (dataPath.contains("Home")) {
             // Home aggregation endpoint
             url = "$apiUrl/agg/content?categoryId=$encodedPath&limit=20&clientToken=$encodedToken"
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
        val id = this.packId ?: this.externalId ?: this.id ?: return null
        val title = this.title ?: return null
        val poster = this.media?.firstOrNull()?.url
        
        // Pass minimal metadata in URL to handle "No Title" fallback
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedPoster = if (poster != null) java.net.URLEncoder.encode(poster, "UTF-8") else ""
        // Pass synopsis as well (truncated to avoid URL length issues)
        val synopsis = this.synopsis ?: ""
        val encodedPlot = if (synopsis.isNotEmpty()) java.net.URLEncoder.encode(synopsis.take(800), "UTF-8") else ""
        
        val data = "$id?title=$encodedTitle&poster=$encodedPoster&plot=$encodedPlot"

        return newMovieSearchResponse(title, data, TvType.Movie) {
            this.posterUrl = poster
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
        // Parse metadata from URL params
        val baseId = url.substringBefore("?")
        val titleParam = if (url.contains("title=")) java.net.URLDecoder.decode(url.substringAfter("title=").substringBefore("&"), "UTF-8") else null
        val posterParam = if (url.contains("poster=")) java.net.URLDecoder.decode(url.substringAfter("poster=").substringBefore("&"), "UTF-8") else null
        val plotParam = if (url.contains("plot=")) java.net.URLDecoder.decode(url.substringAfter("plot=").substringBefore("&"), "UTF-8") else null

        // Sanitize the URL in case it includes the main URL
        val rawId = baseId.removePrefix(mainUrl).removePrefix("/")
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
        
        var response: AstroContent? = null
        try {
            val rawText = app.get(detailUrl, headers = headers).text
            val tree = mapper.readTree(rawText)
            
            // Heuristic to find the content node
            // 1. Check "response" field
            // 2. Check "content" field
            // 3. Use root
            var node = tree.get("response") ?: tree.get("content") ?: tree
            
            // If node is an array, take the first element (if not empty)
            if (node.isArray && node.size() > 0) {
                 node = node.get(0)
            } else if (node.isArray) {
                 // Empty array
                 node = null
            }
            
            if (node != null && !node.isEmpty) {
                 response = mapper.treeToValue(node, AstroContent::class.java)
            }
        } catch (e: Exception) { 
            response = null
            e.printStackTrace()
        }

        // If that failed or returned empty, try content/show (standard for TV Series)
        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/show/$cleanId"
             response = try {
                 app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
             } catch (e: Exception) { null }
        }
        

        
        // Parse Actors (Early check for sparsity)
        val actorsList = ArrayList<ActorData>()
        try {
            val creds = response?.credits
            if (creds is Map<*, *>) {
                val actors = creds["actors"] as? List<*>
                actors?.forEach { 
                    if (it is String) actorsList.add(ActorData(Actor(it.trim(), image = null), roleString = "Actor"))
                }
                
                val directors = creds["directors"] as? List<*>
                directors?.forEach { 
                    if (it is String) actorsList.add(ActorData(Actor(it.trim(), image = null), roleString = "Director"))
                }
                
                val cast = creds["cast"] as? List<*>
                cast?.forEach { 
                    if (it is Map<*, *>) {
                        val name = it["name"] as? String ?: it["originalName"] as? String
                        val role = it["character"] as? String ?: "Actor"
                        val image = it["profilePath"] as? String
                        if (name != null) {
                             actorsList.add(ActorData(Actor(name.trim(), image = image), roleString = role))
                        }
                    }
                }
            }
        } catch (e: Exception) { }


        
        if (response == null && titleParam != null) {
            // Create dummy response from params to avoid error
            response = AstroContent(id = rawId, title = titleParam, synopsis = plotParam, contentType = "Movie") 
        }

        if (response == null) throw ErrorLoadingException("Failed to load details")

        val title = response.title ?: response.name ?: titleParam ?: "No Title"
        val plot = response.synopsis ?: plotParam
        val poster = response.media?.firstOrNull()?.url ?: posterParam
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
        @JsonProperty("name") val name: String? = null,
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
        @JsonProperty("credits") val credits: Any? = null
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
