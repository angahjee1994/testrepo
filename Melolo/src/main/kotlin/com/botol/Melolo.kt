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
            
        val data = res.data?.videoData ?: throw ErrorLoadingException("Data video tidak ditemukan")

        val episodes = data.videoList?.map { ep ->
            newEpisode(ep.vid ?: "") {
                this.name = ep.title
                this.episode = ep.vidIndex
                // Duration is in seconds, convert if needed or ignore
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(data.seriesTitle ?: "No Title", url, TvType.AsianDrama, episodes) {
            this.posterUrl = data.seriesCover ?: res.data.bookData?.cover
            this.plot = data.seriesIntro ?: res.data.bookData?.intro
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data here is the vid_id passed from load()
        val vidId = data.substringAfterLast("/")
        val streamUrl = "$mainUrl/api/melolo/stream/$vidId"
        val res = app.get(streamUrl).parsedSafe<MeloloStreamResponse>() ?: return false
        
        val streamResult = res.data ?: return false
        val videoModelJson = streamResult.videoModel ?: return false
        
        try {
            val videoModel = parseJson<MeloloVideoModel>(videoModelJson)
            val videos = videoModel.videoInfo?.data ?: return false

            videos.forEach { (qualityName, videoInfo) ->
                val mainUrl = videoInfo.mainUrl?.decodeBase64()
                val backupUrl = videoInfo.backupUrl?.decodeBase64()
                val quality = getQualityFromName(qualityName)

                if (mainUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Main $qualityName",
                            mainUrl,
                            INFER_TYPE
                        ) {
                            this.quality = quality
                        }
                    )
                }

                if (backupUrl != null) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "Backup $qualityName",
                            backupUrl,
                            INFER_TYPE
                        ) {
                            this.quality = quality
                        }
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }
    
    // Helper extension
    private fun String.decodeBase64(): String {
        return try {
            String(android.util.Base64.decode(this, android.util.Base64.DEFAULT))
        } catch (e: Exception) {
            this
        }
    }

    // Data Classes

    data class MeloloListResponse(
        @JsonProperty("status") val status: Boolean? = null,
        @JsonProperty("message") val message: String? = null,
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
        @JsonProperty("abstract") val abstract: String? = null, // Plot?
        @JsonProperty("thumb_url") val thumbUrl: String? = null,
        @JsonProperty("cover") val cover: String? = null, // Search results might use this?
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
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("duration") val duration: Int? = null
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
