package com.botol

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.utils.Qualities

class Melolo : MainAPI() {
    override var mainUrl = "https://melolo-api-azure.vercel.app"
    override var name = "Melolo"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/api/melolo/latest" to "Latest",
        "$mainUrl/api/melolo/trending" to "Trending",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val res = app.get(request.data).parsedSafe<MeloloListResponse>() 
            ?: throw ErrorLoadingException("Gagal mengambil data halaman utama")
        
        val home = res.books?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/melolo/search?query=$query&limit=10&offset=0"
        val res = app.get(url).parsedSafe<MeloloSearchResponse>() ?: return emptyList()
        return res.searchData?.flatMap { group ->
            group.books?.mapNotNull { it.toSearchResponse(this) } ?: emptyList()
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfterLast("/")
        val detailUrl = "$mainUrl/api/melolo/detail/$bookId"
        
        val res = app.get(detailUrl).parsedSafe<MeloloDetailResponse>() 
            ?: throw ErrorLoadingException("Gagal memuat detail drama")
            
        val videoData = res.data?.videoData ?: throw ErrorLoadingException("Data tidak ditemukan")

        val episodes = videoData.videoList?.map { ep ->
            val epData = MeloloLinkData(ep.vid ?: "").toJson()
            newEpisode(epData) {
                this.name = ep.title
                this.episode = ep.vidIndex
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(videoData.seriesTitle ?: "No Title", url, TvType.AsianDrama, episodes) {
            this.posterUrl = videoData.seriesCover ?: res.data?.bookData?.cover
            this.plot = videoData.seriesIntro ?: res.data?.bookData?.intro
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val lid = parseJson<MeloloLinkData>(data)
        val streamUrl = "$mainUrl/api/melolo/stream/${lid.id}"
        val res = app.get(streamUrl).parsedSafe<MeloloStreamResponse>() ?: return false
        
        val videoModelJson = res.data?.videoModel ?: return false
        
        try {
            val videoModel = parseJson<MeloloVideoModel>(videoModelJson)
            val videos = videoModel.videoInfo?.data ?: return false

            videos.forEach { (qualityKey, videoInfo) ->
                val mainUrl = videoInfo.mainUrl?.decodeBase64() ?: return@forEach
                val quality = when(qualityKey) {
                    "10" -> Qualities.P360.value
                    "20" -> Qualities.P480.value
                    "30" -> Qualities.P720.value
                    else -> Qualities.Unknown.value
                }

                callback.invoke(
                    newExtractorLink(
                        name,
                        "Main $qualityKey",
                        mainUrl,
                        INFER_TYPE
                    ) {
                        this.quality = quality
                    }
                )

                videoInfo.backupUrl?.decodeBase64()?.let { backup ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Backup $qualityKey",
                            backup,
                            INFER_TYPE
                        ) {
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (e: Exception) {
            return false
        }

        return true
    }
    
    private fun String.decodeBase64(): String? {
        return try {
            val decoded = android.util.Base64.decode(this, android.util.Base64.DEFAULT)
            String(decoded, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // Data Classes
    data class MeloloLinkData(val id: String)

    data class MeloloListResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("books") val books: List<MeloloBook>? = null
    )

    data class MeloloSearchResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("search_data") val searchData: List<MeloloSearchGroup>? = null
    )

    data class MeloloSearchGroup(
        @JsonProperty("books") val books: List<MeloloBook>? = null
    )

    data class MeloloBook(
        @JsonProperty("book_id") val bookId: String? = null,
        @JsonProperty("book_name") val bookName: String? = null,
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("intro") val intro: String? = null
    ) {
        fun toSearchResponse(api: Melolo): SearchResponse? {
            val id = bookId ?: return null
            val title = bookName ?: return null
            return api.newAnimeSearchResponse(title, "${api.mainUrl}/$id", TvType.AsianDrama, false) {
                 this.posterUrl = thumbUrl ?: cover
            }
        }
    }

    data class MeloloDetailResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("data") val data: MeloloDetailData? = null
    )

    data class MeloloDetailData(
        @JsonProperty("video_data") val videoData: MeloloVideoData? = null,
        @JsonProperty("book_data") val bookData: MeloloBook? = null
    )

    data class MeloloVideoData(
        @JsonProperty("series_title") val seriesTitle: String? = null,
        @JsonProperty("series_intro") val seriesIntro: String? = null,
        @JsonProperty("series_cover") val seriesCover: String? = null,
        @JsonProperty("video_list") val videoList: List<MeloloEpisode>? = null
    )

    data class MeloloEpisode(
        @JsonProperty("vid") val vid: String? = null,
        @JsonProperty("vid_index") val vidIndex: Int? = null,
        @JsonProperty("title") val title: String? = null
    )
    
    data class MeloloStreamResponse(
         @JsonProperty("status") val status: Boolean? = null,
         @JsonProperty("data") val data: MeloloStreamResult? = null
    )
    
    data class MeloloStreamResult(
        @JsonProperty("video_model") val videoModel: String? = null
    )

    data class MeloloVideoModel(
        @JsonProperty("video_info") val videoInfo: MeloloVideoInfoWrapper? = null
    )

    data class MeloloVideoInfoWrapper(
        @JsonProperty("data") val data: Map<String, MeloloVideoSource>? = null
    )

    data class MeloloVideoSource(
        @JsonProperty("main_url") val mainUrl: String? = null,
        @JsonProperty("backup_url_1") val backupUrl: String? = null
    )
}
