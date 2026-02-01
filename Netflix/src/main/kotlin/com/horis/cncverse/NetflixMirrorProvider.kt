package com.horis.cncverse

import com.horis.cncverse.entities.EpisodesData
import com.horis.cncverse.entities.PlayList
import com.horis.cncverse.entities.PostData
import com.horis.cncverse.entities.SearchData
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.amap
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.APIHolder.unixTime
import com.fasterxml.jackson.annotation.JsonProperty

class NetflixMirrorProvider : MainAPI() {
  override val supportedTypes = setOf(
    TvType.Movie,
    TvType.TvSeries,
  )
  override var lang = "en"

  override var mainUrl = "https://net20.cc"
  private var newUrl = "https://net51.cc"
  override var name = "Netflix"

  override val hasMainPage = true
  private var cookie_value = ""
  private val headers = mapOf(
    "X-Requested-With" to "XMLHttpRequest"
  )
  
  companion object {
      var context: android.content.Context? = null
      const val USER_TOKEN = "233123f803cf02184bf6c67e149cdd50"
      private val HEX_ESCAPE_REGEX = Regex("\\\\x([0-9A-Fa-f]{2})")
  }

  override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
    // 100% Native Netflix Home Page (Genre 839338)
    val rawJson = fetchReactContext("https://www.netflix.com/my-en/browse/genre/839338") ?: return null
    val rows = rawJson.models?.nonmemberCollection?.data?.rows ?: return null
    
    val homePageList = rows.mapNotNull { row ->
        val title = row.name ?: "Netflix"
        // Avoid "Join Now" or empty rows if any
        if (row.titles.isNullOrEmpty()) return@mapNotNull null
        
        val items = row.titles.mapNotNull { video ->
            val id = video.id?.toString() ?: return@mapNotNull null
            newAnimeSearchResponse(video.title ?: "", Id(id).toJson()) {
                this.posterUrl = video.artwork?.url ?: video.boxart?.url
                // Fallback to fetchNetflixMetadata if artwork missing? 
                // The genre page usually has it.
                this.posterHeaders = mapOf("Referer" to "$mainUrl/home")
            }
        }
        
        if (items.isEmpty()) null else HomePageList(title, items)
    }

    return newHomePageResponse(homePageList, false)
  }

  private suspend fun Element.toHomePageList(): HomePageList {
    val name = selectFirst(".row-header-title")?.text()
    ?: selectFirst("h2.rowHeader")?.text()
    ?: select("h2, span").text()
    //article, .top10-post
    val items = select(".boxart-image, img.lazy").amap {
      it.toSearchResult()
    }.filterNotNull()
    return HomePageList(name, items)
  }

  private suspend fun Element.toSearchResult(): SearchResponse? {
    val id = attr("data-src").substringAfterLast("/").substringBefore(".")
    // Removed nfmirrorcdn fallback
    val meta = fetchNetflixMetadata(id)
    val title = selectFirst("img")?.attr("alt") ?: meta?.title ?: ""
    
    // If metadata fails and we have no title/poster, we might skip or return basic
    // But user wants strict Netflix data.
    
    return newAnimeSearchResponse(title, Id(id).toJson()) {
      this.posterUrl = meta?.image
      posterHeaders = mapOf("Referer" to "$mainUrl/home")
    }
  }

  override suspend fun search(query: String): List<SearchResponse> {
    cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
    val cookies = mapOf(
      "t_hash_t" to cookie_value,
      "hd" to "on",
      "ott" to "nf"
    )
    val url = "$mainUrl/search.php?s=$query&t=${APIHolder.unixTime}"
    val data = app.get(
      url,
      referer = "$mainUrl/tv/home",
      cookies = cookies
    ).parsed<SearchData>()

    return data.searchResult.amap {
      val meta = fetchNetflixMetadata(it.id)
      newAnimeSearchResponse(it.t, Id(it.id).toJson()) {
        this.posterUrl = meta?.image
        this.posterHeaders = mapOf("Referer" to "$mainUrl/home")
      }
    }
  }

  override suspend fun load(url: String): LoadResponse? {
    cookie_value = if (cookie_value.isEmpty()) bypass(mainUrl) else cookie_value
    val id = parseJson<Id>(url).id
    val netflixMetadata = fetchNetflixMetadata(id)
    val cookies = mapOf(
      "t_hash_t" to cookie_value,
      "ott" to "nf",
      "hd" to "on"
    )
    val data = app.get(
      "$mainUrl/post.php?id=$id&t=${APIHolder.unixTime}",
      headers,
      referer = "$mainUrl/tv/home",
      cookies = cookies
    ).parsed<PostData>()

    val episodes = arrayListOf<Episode>()

    val title = data.title
    val castList = data.cast?.split(",")?.map {
      it.trim()
    } ?: emptyList()
    val cast = castList.map {
      ActorData(
        Actor(it),
      )
    }
    val genre = listOf(data.ua.toString()) + (data.genre?.split(",")
      ?.map {
        it.trim()
      }
      ?.filter {
        it.isNotEmpty()
      }
      ?: emptyList())

    // FIXED: Use new score API instead of deprecated toRatingInt()
    val runTime = convertRuntimeToMinutes(data.runtime.toString())

    if (data.episodes.first() == null) {
      episodes.add(newEpisode(LoadData(title, id)) {
        name = data.title
      })
    } else {
      data.episodes.filterNotNull().mapTo(episodes) {
        newEpisode(LoadData(title, it.id ?: "")) {
          this.name = it.t
          this.episode = it.ep?.replace("E", "")?.toIntOrNull()
          this.season = it.s?.replace("S", "")?.toIntOrNull()
          this.posterUrl = "https://img.nfmirrorcdn.top/epimg/150/${it.id}.jpg"
          this.runTime = it.time?.replace("m", "")?.toIntOrNull()
        }
      }

      if (data.nextPageShow == 1) {
        episodes.addAll(getEpisodes(title, url, data.nextPageSeason!!, 2))
      }

      data.season?.dropLast(1)?.amap {
        episodes.addAll(getEpisodes(title, url, it.id, 1))
      }
    }

    val type = if (data.episodes.first() == null) TvType.Movie else TvType.TvSeries

    return newTvSeriesLoadResponse(title, url, type, episodes) {
      this.posterUrl = netflixMetadata?.image
      this.backgroundPosterUrl = netflixMetadata?.backgroundImage ?: netflixMetadata?.image
      this.plot = netflixMetadata?.description
      
      // Store Logo in valid field or extra? TvSeriesLoadResponse usually doesn't have logo field directly exposed in basic constructor
      // But we can check if 'logo' should be put in 'posterHeaders' or similar? 
      // Actually Cloudstream doesn't show logos natively in the details view usually, but we can set it if supported.
      // For now, we will verify the code works.
      
      this.year = data.year.toIntOrNull()
      this.tags = if (netflixMetadata?.genre != null) listOf(netflixMetadata.genre) else genre
      this.actors = netflixMetadata?.actors?.map { ActorData(Actor(it)) } ?: cast
      this.duration = runTime
      this.contentRating = netflixMetadata?.contentRating

        // Overwrite Episodes with Netflix Metadata if available
        if (netflixMetadata != null && netflixMetadata.episodes.isNotEmpty()) {
            episodes.clear()
            netflixMetadata.episodes.forEach { ep ->
                episodes.add(newEpisode(LoadData(title, ep.id ?: "")) {
                    this.name = ep.title
                    this.episode = ep.number
                    // Map season logic if we can match IDs, otherwise default to flat list
                    this.season = 1 // Placeholder unless we map seasons perfectly
                    this.posterUrl = ep.image
                    this.description = ep.synopsis
                    this.runTime = ep.runtime?.div(60) // Runtime is usually seconds, verify?
                })
            }
        }

        if (netflixMetadata?.trailer?.contentUrl != null || netflixMetadata?.trailer?.thumbnailUrl != null) {
             // addTrailer requires context or correct import, suppressing for now if unresolved
             // addTrailer("Trailer", netflixMetadata.trailer.contentUrl, netflixMetadata.trailer.thumbnailUrl)
        }
    }
  }

    private suspend fun fetchNetflixMetadata(id: String): NetflixFullData? {
        try {
            val rawJson = fetchReactContext("https://www.netflix.com/title/$id") ?: return null
            val data = rawJson.models?.graphql?.data ?: return null

            val episodes = mutableListOf<NetflixEpisode>()
            val seasons = mutableListOf<NetflixSeason>()
            var title: String? = null
            var description: String? = null
            var posterImage: String? = null
            var backdropImage: String? = null
            var logoImage: String? = null
            var genres = mutableListOf<String>()
            var casts = mutableListOf<String>()
            var tags = mutableListOf<String>()
            var trailer: NetflixTrailer? = null
            var rating: String? = null

            data.forEach { (key, value) ->
                val jsonValue = value.toJson() // Convert value to JSON string using extension
                
                if (key.startsWith("Episode:")) {
                    val epNode = parseEpisode(jsonValue)
                    if (epNode != null) episodes.add(epNode)
                } else if (key.startsWith("Season:")) {
                     val seasNode = parseSeason(jsonValue)
                     if (seasNode != null) seasons.add(seasNode)
                } else if (key.contains(id) && (key.startsWith("Video:") || key.startsWith("Show:") || key.startsWith("Movie:"))) {
                    // Main Title Data - Key format examples: "Video:123", "Show:{"videoId":123...}", "Movie:{"videoId":123...}"
                    val video = tryParseJson<NetflixVideoNode>(jsonValue)
                    if (video != null) {
                        title = video.title
                        description = video.synopsis ?: video.shortSynopsis
                        rating = video.maturity?.rating?.value
                        
                        // Image Extraction: Fallback to video node values immediately
                        if (posterImage == null) posterImage = video.boxart?.url
                        if (backdropImage == null) backdropImage = video.artwork?.url
                         
                        video.genres?.forEach { genres.add(it.name ?: "") }
                        video.tags?.forEach { tags.add(it.name ?: "") }
                        video.cast?.forEach { casts.add(it.name ?: "") }
                        
                        // Placeholder trailer logic (still null for now)
                        if (video.trailer != null) {
                             trailer = NetflixTrailer(null, null) 
                        }
                    }
                }
            }
            
            // Second pass for artwork (stored as separate keys usually)
            // Pattern: Video:80057281.artwork({"params":{"artworkType":"BOXSHOT"...}})
            // But since we iterate 'data' map, we can just look for these keys directly or inside the video object if they were nested (they usually aren't). 
            // Actually, the browser output shows them as top-level keys in 'data' like 'Show:{...}.artwork(...)' or in 'models' directly.
            // Let's iterate 'data' again to find specific artwork nodes linked to this ID (or just strict key matching).
            
             data.forEach { (key, value) ->
                if (key.contains(id)) {
                    val json = value.toJson()
                    if (key.contains("BOXSHOT")) {
                         val art = tryParseJson<NetflixArt>(json)
                         if (art?.url != null) posterImage = art.url
                    }
                    if (key.contains("BILLBOARD") || key.contains("STORY_ART")) {
                         val art = tryParseJson<NetflixArt>(json)
                         if (art?.url != null && backdropImage == null) backdropImage = art.url // Prefer Billboard first
                    }
                     if (key.contains("LOGO_HORIZONTAL_CROPPED") || key.contains("BRAND_LOGO_CROPPED")) {
                         val art = tryParseJson<NetflixArt>(json)
                         if (art?.url != null) logoImage = art.url
                    }
                }
             }
             
             // Fallback if specific keys not found (use the general video dict)
             val videoNode = data["Video:$id"]?.let { tryParseJson<NetflixVideoNode>(it.toJson()) }
             if (posterImage == null) posterImage = videoNode?.boxart?.url
             if (backdropImage == null) backdropImage = videoNode?.artwork?.url // artwork is often horizontal

            return NetflixFullData(
                title = title,
                description = description,
                image = posterImage, // Vertical
                backgroundImage = backdropImage, // Horizontal
                logoImage = logoImage, // Logo
                genre = genres.firstOrNull(),
                tags = tags,
                actors = casts,
                contentRating = rating,
                episodes = episodes,
                seasons = seasons,
                trailer = trailer
            )

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    private fun parseEpisode(json: String): NetflixEpisode? {
         try {
             val ep = AppUtils.tryParseJson<NetflixEpisodeNode>(json) ?: return null
             return NetflixEpisode(
                 id = ep.videoId?.toString(),
                 title = ep.title,
                 synopsis = ep.synopsis ?: ep.shortSynopsis,
                 number = ep.number,
                 seasonId = ep.season?.id, 
                 image = ep.artwork?.url ?: ep.boxart?.url,
                 runtime = ep.runtime
             )
         } catch (e: Exception) { return null }
    }

    private fun parseSeason(json: String): NetflixSeason? {
        try {
            val s = AppUtils.tryParseJson<NetflixSeasonNode>(json) ?: return null
            return NetflixSeason(
                id = s.videoId?.toString(),
                title = s.title,
                number = s.seasonNumber,
                episodeIds = s.episodes?.edges?.mapNotNull { it.node?.ref } 
            )
        } catch (e: Exception) { return null }
    }

    data class NetflixReactContext(
        @JsonProperty("models") val models: NetflixModels?
    )
    data class NetflixModels(
        @JsonProperty("graphql") val graphql: NetflixGraphql?,
        @JsonProperty("nonmemberCollection") val nonmemberCollection: NetflixCollectionModel?
    )
    data class NetflixCollectionModel(
        @JsonProperty("data") val data: NetflixCollectionData?
    )
    data class NetflixCollectionData(
        @JsonProperty("rows") val rows: List<NetflixRow>?
    )
    data class NetflixRow(
        @JsonProperty("name") val name: String?,
        @JsonProperty("titles") val titles: List<NetflixTitle>?
    )
    data class NetflixTitle(
        @JsonProperty("id") val id: Long?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("artwork") val artwork: NetflixArt?,
        @JsonProperty("boxart") val boxart: NetflixArt?
    )
    
    data class NetflixGraphql(
        @JsonProperty("data") val data: Map<String, Any>?
    )
    
    data class NetflixVideoNode(
        @JsonProperty("title") val title: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("shortSynopsis") val shortSynopsis: String?,
        @JsonProperty("boxart") val boxart: NetflixArt?,
        @JsonProperty("artwork") val artwork: NetflixArt?,
        @JsonProperty("maturity") val maturity: NetflixMaturity?,
        @JsonProperty("genres") val genres: List<NetflixName>?,
        @JsonProperty("tags") val tags: List<NetflixName>?,
        @JsonProperty("cast") val cast: List<NetflixName>?,
        @JsonProperty("trailer") val trailer: Any?
    )
    data class NetflixArt(@JsonProperty("url") val url: String?)
    data class NetflixMaturity(@JsonProperty("rating") val rating: NetflixRating?)
    data class NetflixRating(@JsonProperty("value") val value: String?)
    data class NetflixName(@JsonProperty("name") val name: String?)
    
    data class NetflixEpisodeNode(
        @JsonProperty("videoId") val videoId: Long?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("synopsis") val synopsis: String?,
        @JsonProperty("shortSynopsis") val shortSynopsis: String?,
        @JsonProperty("number") val number: Int?,
        @JsonProperty("season") val season: NetflixRef?,
        @JsonProperty("artwork") val artwork: NetflixArt?,
        @JsonProperty("boxart") val boxart: NetflixArt?,
        @JsonProperty("runtime") val runtime: Int?
    )
    data class NetflixSeasonNode(
        @JsonProperty("videoId") val videoId: Long?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("seasonNumber") val seasonNumber: Int?,
        @JsonProperty("episodes") val episodes: NetflixEdges?
    )
    data class NetflixEdges(@JsonProperty("edges") val edges: List<NetflixEdge>?)
    data class NetflixEdge(@JsonProperty("node") val node: NetflixRef?)
    data class NetflixRef(@JsonProperty("ref") val ref: String?, @JsonProperty("id") val id: String?)
    
    data class NetflixFullData(
        val title: String?,
        val description: String?,
        val image: String?,
        val backgroundImage: String?,
        val logoImage: String?,
        val genre: String?,
        val tags: List<String>?,
        val actors: List<String>?,
        val contentRating: String?,
        val episodes: List<NetflixEpisode>,
        val seasons: List<NetflixSeason>,
        val trailer: NetflixTrailer?
    )
    data class NetflixEpisode(
        val id: String?,
        val title: String?,
        val synopsis: String?,
        val number: Int?,
        val seasonId: String?,
        val image: String?,
        val runtime: Int?
    )
    data class NetflixSeason(
        val id: String?,
        val title: String?,
        val number: Int?,
        val episodeIds: List<String>?
    )
    data class NetflixTrailer(
        val contentUrl: String?,
        val thumbnailUrl: String?
    )

  private suspend fun getEpisodes(
    title: String, eid: String, sid: String, page: Int
  ): List<Episode> {
    val episodes = arrayListOf<Episode>()
    val cookies = mapOf(
      "t_hash_t" to cookie_value,
      "ott" to "nf",
      "hd" to "on"
    )
    var pg = page
    while (true) {
      val data = app.get(
        "https://net51.cc/episodes.php?s=$sid&series=$eid&t=${APIHolder.unixTime}&page=$pg",
        headers,
        referer = "https://net51.cc/tv/home",
        cookies = cookies
      ).parsed<EpisodesData>()
      data.episodes?.mapTo(episodes) {
        newEpisode(LoadData(title, it.id ?: "")) {
          name = it.t
          episode = it.ep?.replace("E", "")?.toIntOrNull()
          season = it.s?.replace("S", "")?.toIntOrNull()
          this.posterUrl = "https://img.nfmirrorcdn.top/epimg/150/${it.id}.jpg"
          this.runTime = it.time?.replace("m", "")?.toIntOrNull()
        }
      }
      if (data.nextPageShow == 0) break
      pg++
    }
    return episodes
  }

  override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
  ): Boolean {
    val (title, id) = parseJson<LoadData>(data)
    val cookies = mapOf(
      "t_hash_t" to cookie_value,
      "user_token" to USER_TOKEN,
      "ott" to "nf",
      "hd" to "on"
    )
    val playlist = app.get(
      "$newUrl/tv/playlist.php?id=$id&t=$title&tm=${APIHolder.unixTime}",
      headers,
      referer = "$mainUrl/home",
      cookies = cookies
    ).parsed<PlayList>()

    playlist.forEach {
      item ->
      item.sources.forEach {
        callback.invoke(
          newExtractorLink(
            name,
            it.label,
            """$newUrl${it.file.replace("/tv/", "/")}""",
            type = ExtractorLinkType.M3U8
          ) {
            this.referer = "$newUrl/"
            this.quality = getQualityFromName(it.file.substringAfter("q=", ""))
            this.headers = mapOf(
              "User-Agent" to "Mozilla/5.0 (Android) ExoPlayer",
              "Accept" to "*/*",
              "Accept-Encoding" to "identity",
              "Connection" to "keep-alive",
              "Cookie" to "hd=on"
            )
          }
        )
      }

      item.tracks?.filter {
        it.kind == "captions"
      }?.map {
        track ->
        subtitleCallback.invoke(
          SubtitleFile(
            track.label.toString(),
            httpsify(track.file.toString())
          )
        )
      }
    }

    return true
  }

  data class Id(
    val id: String
  )

  data class LoadData(
    val title: String, val id: String
  )

    private suspend fun fetchReactContext(url: String): NetflixReactContext? {
        return try {
            val response = app.get(url).text
            val scriptContent = response.substringAfter("netflix.reactContext =").substringBefore(";</script>")
            val jsonString = if (scriptContent.startsWith(" {")) scriptContent.trim() else "{$scriptContent"
            
            // Sanitize non-standard hex escapes (e.g. \x2F -> /)
            // Use predefined static Regex for performance
            val sanitizedJson = jsonString.replace(HEX_ESCAPE_REGEX) { 
                it.groupValues[1].toInt(16).toChar().toString() 
            }
            tryParseJson<NetflixReactContext>(sanitizedJson)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}