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
        val streamUrl = "$mainUrl/api/melolo/stream/$data"
        val res = app.get(streamUrl).parsedSafe<MeloloStreamResponse>() ?: return false
        
        val streamData = res.data ?: return false
        
        streamData.mainUrl?.let { url ->
             callback.invoke(
                newExtractorLink(
                    name,
                    "Main",
                    url,
                    INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        
        streamData.backupUrl?.let { url ->
             callback.invoke(
                newExtractorLink(
                    name,
                    "Backup",
                    url,
                    INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        return true
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
        // book_data might exist based on observation pattern
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
         @JsonProperty("data") val data: MeloloStreamData? = null
    )
    
    data class MeloloStreamData(
        @JsonProperty("main_url") val mainUrl: String? = null,
        @JsonProperty("backup_url") val backupUrl: String? = null
    )
}
