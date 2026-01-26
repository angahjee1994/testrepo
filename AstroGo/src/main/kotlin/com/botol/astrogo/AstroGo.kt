package com.botol.astrogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.UUID

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
        if (bearerToken.isNotEmpty()) return // Simple cache
        
        try {
            if (clientToken.isEmpty()) {
                clientToken = generateClientToken()
            }

            // URL discovered via user feedback (port 9443 removed)
            val authUrl = "https://sg-sg-sg.astro.com.my/oauth2/authorize?client_id=browser&state=guestUserLogin&redirect_uri=https://astrogo.astro.com.my&response_type=token&prompt=none&scope=urn:synamedia:vcs:ovp:guest-user"
            
            var currentUrl = authUrl
            var attempts = 0
            while (attempts < 5) {
                // Cloudfront/providers might require a browser UA or just default.
                // Reverting header to minimal to check if UA was the blocker.
                val headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                
                val response = app.get(currentUrl, headers = headers, allowRedirects = false)
                System.out.println("DEBUG AstroGo Auth: ${response.code} ${response.url}")
                
                // Check if we have the token in the URL fragment (e.g. from Location header or current response URL)
                val location = response.headers["Location"]
                val targetUrl = location ?: response.url
                
                if (targetUrl.contains("access_token=")) {
                    // Extract from hash (#) or query (?)
                    val token = targetUrl.substringAfter("access_token=").substringBefore("&").substringBefore("#")
                    if (token.isNotEmpty()) {
                        bearerToken = token
                        System.out.println("DEBUG AstroGo Auth: Got token ${token.take(10)}...")
                        break
                    }
                }
                
                if (response.code in 300..308 && location != null) {
                    currentUrl = if (location.startsWith("/")) {
                        val uri = java.net.URI(currentUrl)
                        "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}$location"
                    } else {
                        location
                    }
                    attempts++
                } else {
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
        refreshAccessToken()
        
        val offset = (page - 1) * 20
        
        val dataParts = request.data.split(",")
        val dataPath = dataParts[0]
        val sort = dataParts.getOrNull(1)

        val encodedToken = java.net.URLEncoder.encode(clientToken, "UTF-8")
        val encodedPath = java.net.URLEncoder.encode(dataPath, "UTF-8")
        val url: String
        
        if (dataPath.contains("Home")) {
             url = "$apiUrl/agg/content?categoryId=$encodedPath&limit=20&clientToken=$encodedToken"
        } else if (sort != null) {
             url = "$apiUrl/shared/content?categoryId=$encodedPath&clientToken=$encodedToken&offset=$offset&limit=20&sort=$sort"
        } else {
             val endpoint = "shared/bulkContent/$encodedPath"
             url = "$apiUrl/$endpoint?clientToken=$encodedToken"
        }
        
        System.out.println("DEBUG AstroGo Main Page URL: $url")
        
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

            if (response?.content != null) {
                 val contents = response.content.mapNotNull { it.toSearchResponse() }
                 if (contents.isNotEmpty()) {
                     var title = if (items.isEmpty()) request.name else "Featured"
                     if (addedTitles.contains(title)) title = "$title List"
                     
                     items.add(HomePageList(title, contents))
                 }
            }
            
            System.out.println("DEBUG AstroGo Main Page Items: ${items.size}")
            return newHomePageResponse(items)
        } catch (e: Exception) {
            e.printStackTrace()
            System.out.println("DEBUG AstroGo Main Page Error: ${e.message}")
            return newHomePageResponse(emptyList())
        }
    }

    private fun AstroContent.toSearchResponse(): SearchResponse? {
        val id = this.packId ?: this.externalId ?: this.id ?: return null
        val title = this.title ?: return null
        val poster = this.media?.firstOrNull()?.url
        
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedPoster = if (poster != null) java.net.URLEncoder.encode(poster, "UTF-8") else ""
        val synopsis = this.synopsis ?: ""
        val encodedPlot = if (synopsis.isNotEmpty()) java.net.URLEncoder.encode(synopsis.take(800), "UTF-8") else ""
        
        val data = "$id?title=$encodedTitle&poster=$encodedPoster&plot=$encodedPlot"

        return newMovieSearchResponse(title, data, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/agg/content?limit=40&q=$encodedQuery&source=vod&sort=relevancy&isErotic=false&isAdult=false"
        
        System.out.println("DEBUG AstroGo Search URL: $url")
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )

        return try {
            val response = app.get(url, headers = headers).parsedSafe<AstroResponse>()
            val results = response?.content?.mapNotNull { it.toSearchResponse() } ?: emptyList()
            System.out.println("DEBUG AstroGo Search Results: ${results.size}")
            results
        } catch (e: Exception) {
            e.printStackTrace()
            System.out.println("DEBUG AstroGo Search Error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val baseId = url.substringBefore("?")
        val titleParam = if (url.contains("title=")) java.net.URLDecoder.decode(url.substringAfter("title=").substringBefore("&"), "UTF-8") else null
        val posterParam = if (url.contains("poster=")) java.net.URLDecoder.decode(url.substringAfter("poster=").substringBefore("&"), "UTF-8") else null
        val plotParam = if (url.contains("plot=")) java.net.URLDecoder.decode(url.substringAfter("plot=").substringBefore("&"), "UTF-8") else null

        val rawId = baseId.removePrefix(mainUrl).removePrefix("/")
        val cleanId = rawId.substringBefore("~")
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )
        
        var detailUrl = "$apiUrl/contentInstances/$rawId"
        
        var response: AstroContent? = null
        try {
            val rawText = app.get(detailUrl, headers = headers).text
            val tree = mapper.readTree(rawText)
            var node = tree.get("response") ?: tree.get("content") ?: tree
            
            if (node.isArray && node.size() > 0) {
                 node = node.get(0)
            } else if (node.isArray) {
                 node = null
            }
            
            if (node != null && !node.isEmpty) {
                 response = mapper.treeToValue(node, AstroContent::class.java)
            }
        } catch (e: Exception) { 
            response = null
            e.printStackTrace()
        }

        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/show/$cleanId"
             response = try {
                 app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
             } catch (e: Exception) { null }
        }
        
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
            response = AstroContent(id = rawId, title = titleParam, synopsis = plotParam, contentType = "Movie") 
        }

        if (response == null) throw ErrorLoadingException("Failed to load details")

        val title = response.title ?: response.name ?: titleParam ?: "No Title"
        val plot = response.synopsis ?: plotParam
        val poster = response.media?.firstOrNull()?.url ?: posterParam
        val year = response.releaseDate?.substringBefore("-")?.toIntOrNull()

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
        
        val type = response.contentType
        val isTv = type.equals("Program", true) || 
                   type.equals("TVShow", true) ||
                   type.equals("show", true) ||
                   type.equals("Series", true)

        val tags = response.genres?.mapNotNull { it.name }

        if (isTv) {
            val episodes = ArrayList<Episode>()
            val rawToken = clientToken.replace("%20", " ")
            val encodedToken = java.net.URLEncoder.encode(rawToken, "UTF-8").replace("+", "%20")
            
            val baseIdShow = response.packId ?: response.externalId ?: response.id ?: cleanId
            val showId = baseIdShow.substringBefore("~")

            suspend fun fetchContent(url: String): List<AstroContent>? {
                return try {
                    val text = app.get(url, headers = headers).text
                    val wrapper = AppUtils.parseJson<AstroResponse>(text)
                    if (!wrapper.content.isNullOrEmpty()) return wrapper.content
                    return AppUtils.parseJson<List<AstroContent>>(text)
                } catch (e: Exception) { null }
            }

            val seasonsUrl = "$apiUrl/shared/content?showId=$showId&sort=seasonNumber&limit=255&offset=0&isErotic=false&isAdult=false&source=vod&clientToken=$encodedToken"
            
            val seasonContent = fetchContent(seasonsUrl)
            
            if (!seasonContent.isNullOrEmpty()) {
                seasonContent.forEach { season ->
                    val seasonId = season.seasonId ?: season.id ?: return@forEach
                    val seasonNum = season.title?.filter { it.isDigit() }?.toIntOrNull() ?: season.seasonNumber ?: 1
                    
                    val episodesUrl = "$apiUrl/shared/content?seasonId=$seasonId&sort=episodeNumber&limit=255&offset=0&isErotic=false&isAdult=false&source=vod&clientToken=$encodedToken"
                    val episodesContent = fetchContent(episodesUrl)
                    
                    episodesContent?.forEach { ep ->
                        episodes.add(ep.toEpisode(seasonNum))
                    }
                }
            } else {
                val flatEpisodesUrl = "$apiUrl/shared/content?showId=$showId&source=vod&limit=255&offset=0&sort=episodeNumber&isCollapsed=false&isErotic=false&isAdult=false&clientToken=$encodedToken"
                val flatContent = fetchContent(flatEpisodesUrl)
                    
                 if (!flatContent.isNullOrEmpty()) {
                     flatContent.forEach { ep ->
                         episodes.add(ep.toEpisode(1))
                     }
                 } else {
                     val childrenUrl = "$apiUrl/content/$showId/children?clientToken=$encodedToken&limit=100&offset=0"
                     try {
                         val asRepo = app.get(childrenUrl, headers = headers).parsedSafe<AstroResponse>()
                         if (asRepo?.content?.isNotEmpty() == true) {
                             asRepo.content.forEach { ep ->
                                 episodes.add(ep.toEpisode(1))
                             }
                         } else {
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
            
            return newTvSeriesLoadResponse(title, rawId, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = response.media?.find { it.url?.contains("LAND") == true || it.url?.contains("backdrop") == true }?.url
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = durationMin
                this.actors = actorsList
            }
        } else {
            return newMovieLoadResponse(title, rawId, TvType.Movie, response.id ?: rawId) {
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
        refreshAccessToken()
        
        val baseId = data.substringBefore("?")
        System.out.println("DEBUG AstroGo LoadLinks: BaseId=$baseId Token=${bearerToken.take(10)}")
        
        // Define profiles to try: 2 (Web), 100 (Standard)
        // val profiles = listOf("2", "100") // Legacy

        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "X-VGE-Service-ID" to "AstroGo",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json"
        )

        try {
            // Revert to SM endpoint
            val smUrl = "https://sg-sg-sg.astro.com.my:9443/sm/vod/streamingSession"
            
            // Explicitly set Content-Type to application/json in headers
            val jsonHeaders = headers + mapOf("Content-Type" to "application/json")

            // Minimal payload based on success example
            val payload = mapOf(
                "contentId" to baseId,
                "trickModes" to mapOf("restricted" to false)
            )
            System.out.println("DEBUG AstroGo SM URL: $smUrl")
            System.out.println("DEBUG AstroGo SM Payload: $payload")
            
            // Send as JSON payload
            val response = app.post(smUrl, headers = jsonHeaders, json = payload).text
            System.out.println("DEBUG AstroGo SM Response: $response")
            
            val json = mapper.readTree(response)
            val streamUrl = json.get("_links")?.get("playUrl")?.get("href")?.asText()
            val drmToken = json.get("drmProperties")?.get("blob")?.asText()

            if (!streamUrl.isNullOrEmpty()) {
                if (!drmToken.isNullOrEmpty()) {
                    callback.invoke(
                        newDrmExtractorLink(
                            source = "AstroGo",
                            name = "AstroGo",
                            url = streamUrl,
                            type = ExtractorLinkType.DASH,
                            uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
                        ) {
                            this.referer = mainUrl
                            this.licenseUrl = "https://sg-sg-sg.astro.com.my/vgemultidrm/v1/widevine/license"
                            this.headers = mapOf(
                                "X-VGE-DRM-Token" to drmToken,
                                "X-VGE-DRM-Content-ID" to baseId,
                                "X-VGE-DRM-Oauth-Token" to bearerToken
                            )
                        }
                    )
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = "AstroGo",
                            name = "AstroGo",
                            url = streamUrl,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
                return true
            }
        } catch (e: Exception) {
            System.out.println("DEBUG AstroGo Session Error: ${e.message}")
            e.printStackTrace()
        }
        
        return false
    }

    private fun generateClientToken(): String {
        val version = "1"
        val regionId = "80200"
        val userRegion = "SARAWAK"
        val community = "Malaysia%20Live"
        val type = "k"
        val deviceType = "PC"
        val fleet = "Astro_unmanaged"
        val platformDevice = "CHROME-FF"
        val profileType = "Adults"

        return "v:$version!r:$regionId!ur:$userRegion!community:$community!t:$type!dt:$deviceType!f:$fleet!pd:$platformDevice!pt:$profileType"
    }

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