package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.nicehttp.RequestBodyTypes
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class Moviebox : MainAPI() {
    override var mainUrl = "https://moviebox.ph"
    private val mainAPIUrl = "https://h5-api.aoneroom.com"
    private val secondAPIUrl = "https://filmboom.top"
    override val instantLinkLoading = true
    override var name = "Moviebox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "en"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage: List<MainPageData> = mainPageOf(
        "4123278689004061520" to "Trending Movie",
        "6766346312503248424" to "Trending Series",
        "channelId=1,country=Malaysia,sort=latest" to "Malaysia Movies",
        "channelId=2,country=Malaysia,sort=latest" to "Malaysia Series",
        "channelId=1,country=Indonesia,sort=latest" to "Indonesia Movies",
        "channelId=2,country=Indonesia,sort=latest" to "Indonesia Series",
        "997144265920760504" to "USA Movies",
        "channelId=2,country=United States,sort=latest" to "USA Series",
        "channelId=1,country=Korea,sort=latest" to "Korean Movies",
        "channelId=2,country=Korea,sort=latest" to "Korean Series",
        "channelId=1,country=China,sort=latest" to "China Movies",
        "channelId=2,country=China,sort=latest" to "China Series",
        "606779077307122552" to "Philippines Movies",
        "channelId=2,country=Philippines,sort=latest" to "Philippines Series",
        "channelId=1,country=Thailand,sort=latest" to "Thailand Movies",
        "channelId=2,country=Thailand,sort=latest" to "Thailand Series",
        "channelId=1,country=Japan,sort=latest" to "Japan Movies",
        "channelId=2,country=Japan,sort=latest" to "Japan Series",
        "channelId=1,country=India,sort=latest" to "Indian Movies",
        "channelId=2,country=India,sort=latest" to "Indian Series",
        "channelId=1,country=Turkey,sort=latest" to "Turkey Movies",
        "channelId=2,country=Turkey,sort=latest" to "Turkey Series",
        "8617025562613270856" to "Anime",
        "channelId=1,genre=Animation,sort=latest" to "Cartoon",
        "channelId=1,genre=Action,sort=latest" to "Action Movies",
        "channelId=1,genre=Animation,sort=latest" to "Animation Movies",
        "channelId=1,genre=Comedy,sort=latest" to "Comedy Movies",
        "channelId=1,genre=Family,sort=latest" to "Family Movies",
        "channelId=1,genre=Horror,sort=latest" to "Horror Movies",
        "channelId=1,genre=Mystery,sort=latest" to "Mystery Movies",
        "channelId=1,genre=Sci-Fi,sort=latest" to "Sci-Fi Movies",
        "channelId=1,genre=Thriller,sort=latest" to "Thriller Movies",
        "channelId=1,genre=War,sort=latest" to "War Movies",
        "channelId=1,genre=short,sort=latest" to "Short Movies",
        "channelId=2,genre=short,sort=latest" to "Short Series",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {

        val home = mutableListOf<SearchResponse>()

        if(!request.data.contains(",")) {
            val url = "$mainAPIUrl/wefeed-h5api-bff/ranking-list/content?id=${request.data}&page=$page&perPage=12"

            val index = app.get(url).parsedSafe<Media>()?.data?.subjectList?.map {
                it.toSearchResponse(this)
            } ?: throw ErrorLoadingException("No Data Found")

            home.addAll(index)
        } else {
            val params = request.data.split(",")
            val body = if (request.data.contains("=")) {
                params.associate {
                    val (key, value) = it.split("=")
                    key to value
                }.toMutableMap().apply {
                    put("page", page.toString())
                    putIfAbsent("perPage", "28")
                }
            } else {
                mapOf(
                    "channelId" to params.first(),
                    "page" to page,
                    "perPage" to "28",
                    "sort" to params.last()
                )
            }.toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())

            val index = app.post("$mainAPIUrl/wefeed-h5api-bff/subject/filter", requestBody = body)
                .parsedSafe<Media>()?.data?.items
                ?.filterNot { item ->
                    val title = item.title.orEmpty()
                    val corner = item.corner.orEmpty()
                    val banned = listOf(
                        "English", "French", "Hindi", "Bengali", "Urdu", "Punjabi",
                        "Tamil", "Telugu", "Malayalam", "Kannada", "Arabic",
                        "Tagalog", "Indonesian", "Russian", "Kurdish"
                    )
                    banned.any { lang ->
                        corner.equals(lang, true) ||
                                title.contains("$lang Dub", true) ||
                                title.contains("$lang Sub", true) ||
                                title.contains("[$lang]", true)
                    }
                }?.map {
                    it.toSearchResponse(this)
                } ?: throw ErrorLoadingException("No Data Found")

            home.addAll(index)
        }


        return newHomePageResponse(request.name, home)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        return app.post(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/search", requestBody = mapOf(
                "keyword" to query,
                "page" to "1",
                "perPage" to "0",
                "subjectType" to "0",
            ).toJson().toRequestBody(RequestBodyTypes.JSON.toMediaTypeOrNull())
        ).parsedSafe<Media>()?.data?.items?.map { it.toSearchResponse(this) }
            ?: throw ErrorLoadingException()
    }

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        val document = app.get("$secondAPIUrl/wefeed-h5-bff/web/subject/detail?subjectId=$id")
            .parsedSafe<MediaDetail>()?.data
        val subject = document?.subject
        val title = subject?.title ?: ""
        val poster = subject?.cover?.url
        val tags = subject?.genre?.split(",")?.map { it.trim() }

        val year = subject?.releaseDate?.substringBefore("-")?.toIntOrNull()
        val tvType = if (subject?.subjectType == 2) TvType.TvSeries else TvType.Movie
        val description = subject?.description
        val trailer = subject?.trailer?.videoAddress?.url
        val rating = subject?.imdbRatingValue?.toIntOrNull()
        val actors = document?.stars?.mapNotNull { cast ->
            ActorData(
                Actor(
                    cast.name ?: return@mapNotNull null,
                    cast.avatarUrl
                ),
                roleString = cast.character
            )
        }?.distinctBy { it.actor }

        val recommendations =
            app.get("$mainUrl/wefeed-h5-bff/web/subject/detail-rec?subjectId=$id&page=1&perPage=12")
                .parsedSafe<Media>()?.data?.items?.map {
                    it.toSearchResponse(this)
                }

        return if (tvType == TvType.TvSeries) {
            val episode = document?.resource?.seasons?.map { seasons ->
                (if (seasons.allEp.isNullOrEmpty()) (1..seasons.maxEp!!) else seasons.allEp.split(",")
                    .map { it.toInt() })
                    .map { episode ->
                        newEpisode(
                            LoadData(
                                id,
                                seasons.se,
                                episode,
                                subject?.detailPath
                            ).toJson()
                        ) {
                            this.season = seasons.se
                            this.episode = episode
                        }
                    }
            }?.flatten() ?: emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episode) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        } else {
            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                LoadData(id, detailPath = subject?.detailPath).toJson()
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.actors = actors
                this.recommendations = recommendations
                addTrailer(trailer, addRaw = true)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val media = parseJson<LoadData>(data)
        val referer = "$secondAPIUrl/spa/videoPlayPage/movies/${media.detailPath}?id=${media.id}&type=/movie/detail&lang=en"

        val streams = app.get(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/play?subjectId=${media.id}&se=${media.season ?: 0}&ep=${media.episode ?: 0}",
            referer = referer
        ).parsedSafe<Media>()?.data?.streams

        streams?.reversed()?.distinctBy { it.url }?.map { source ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    source.url ?: return@map,
                    INFER_TYPE
                ) {
                    this.referer = "$secondAPIUrl/"
                    this.quality = getQualityFromName(source.resolutions)
                }
            )
        }

        val id = streams?.first()?.id
        val format = streams?.first()?.format

        app.get(
            "$secondAPIUrl/wefeed-h5-bff/web/subject/caption?format=$format&id=$id&subjectId=${media.id}",
            referer = referer
        ).parsedSafe<Media>()?.data?.captions?.map { subtitle ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    subtitle.lanName ?: "",
                    subtitle.url ?: return@map
                )
            )
        }

        return true
    }

    data class LoadData(
        val id: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val detailPath: String? = null,
    )

    data class Media(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subjectList") val subjectList: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("items") val items: ArrayList<Items>? = arrayListOf(),
            @JsonProperty("streams") val streams: ArrayList<Streams>? = arrayListOf(),
            @JsonProperty("captions") val captions: ArrayList<Captions>? = arrayListOf(),
        ) {
            data class Streams(
                @JsonProperty("id") val id: String? = null,
                @JsonProperty("format") val format: String? = null,
                @JsonProperty("url") val url: String? = null,
                @JsonProperty("resolutions") val resolutions: String? = null,
            )

            data class Captions(
                @JsonProperty("lan") val lan: String? = null,
                @JsonProperty("lanName") val lanName: String? = null,
                @JsonProperty("url") val url: String? = null,
            )
        }
    }

    data class MediaDetail(
        @JsonProperty("data") val data: Data? = null,
    ) {
        data class Data(
            @JsonProperty("subject") val subject: Items? = null,
            @JsonProperty("stars") val stars: ArrayList<Stars>? = arrayListOf(),
            @JsonProperty("resource") val resource: Resource? = null,
        ) {
            data class Stars(
                @JsonProperty("name") val name: String? = null,
                @JsonProperty("character") val character: String? = null,
                @JsonProperty("avatarUrl") val avatarUrl: String? = null,
            )

            data class Resource(
                @JsonProperty("seasons") val seasons: ArrayList<Seasons>? = arrayListOf(),
            ) {
                data class Seasons(
                    @JsonProperty("se") val se: Int? = null,
                    @JsonProperty("maxEp") val maxEp: Int? = null,
                    @JsonProperty("allEp") val allEp: String? = null,
                )
            }
        }
    }

    data class Items(
        @JsonProperty("subjectId") val subjectId: String? = null,
        @JsonProperty("subjectType") val subjectType: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: Long? = null,
        @JsonProperty("genre") val genre: String? = null,
        @JsonProperty("cover") val cover: Cover? = null,
        @JsonProperty("imdbRatingValue") val imdbRatingValue: String? = null,
        @JsonProperty("countryName") val countryName: String? = null,
        @JsonProperty("corner") val corner: String? = null,
        @JsonProperty("trailer") val trailer: Trailer? = null,
        @JsonProperty("detailPath") val detailPath: String? = null,
    ) {

        fun toSearchResponse(provider: Moviebox): SearchResponse {
            return provider.newMovieSearchResponse(
                title ?: "",
                subjectId ?: "",
                if (subjectType == 1) TvType.Movie else TvType.TvSeries,
                false
            ) {
                this.posterUrl = cover?.url
            }
        }

        data class Cover(
            @JsonProperty("url") val url: String? = null,
        )

        data class Trailer(
            @JsonProperty("videoAddress") val videoAddress: VideoAddress? = null,
        ) {
            data class VideoAddress(
                @JsonProperty("url") val url: String? = null,
            )
        }
    }

}
