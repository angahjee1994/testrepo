package com.botol

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class Dramabox : MainAPI() {
    override var mainUrl = "https://dracin-web-eight.vercel.app"
    override var name = "Dramabox"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "application/json",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    override val mainPage = mainPageOf(
        "$mainUrl/api/dramabox/foryou" to "Untuk Kamu",
        "$mainUrl/api/dramabox/latest" to "Terbaru",
        "$mainUrl/api/dramabox/trending" to "Trending",
        "$mainUrl/api/dramabox/dubindo?classify=terbaru" to "Sulih Suara"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        val res = app.get(url, headers = baseHeaders).parsedSafe<Array<DramaboxMedia>>() 
            ?: throw ErrorLoadingException("Gagal memuat data: Hubungi Admin (Format HTML diterima)")
        val home = res.map { it.toSearchResponse(this) }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/dramabox/search?query=$query"
        val res = app.get(url, headers = baseHeaders).parsedSafe<Array<DramaboxMedia>>() ?: return emptyList()
        return res.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url
        val detailUrl = "$mainUrl/api/dramabox/detail/$bookId"
        val episodesUrl = "$mainUrl/api/dramabox/allepisode/$bookId"

        val detail = app.get(detailUrl, headers = baseHeaders).parsedSafe<DramaboxMedia>() 
            ?: throw ErrorLoadingException("Detail Drama tidak tersedia (Server Sibuk)")

        val episodes = app.get(episodesUrl, headers = baseHeaders).parsedSafe<Array<DramaboxEpisode>>() 
            ?: throw ErrorLoadingException("Episode tidak tersedia (Server Sibuk)")

        val epData = episodes.map { ep ->
            val data = LoadLinksData(bookId, ep.chapterId ?: "").toJson()
            newEpisode(data) {
                this.name = ep.chapterName
                this.episode = ep.chapterIndex?.plus(1)
                this.posterUrl = ep.chapterImg
            }
        }

        return newTvSeriesLoadResponse(detail.bookName ?: "No Name", url, TvType.AsianDrama, epData) {
            this.posterUrl = detail.coverWap
            this.plot = detail.introduction
            this.tags = detail.tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadLinksData>(data)
        val episodesUrl = "$mainUrl/api/dramabox/allepisode/${loadData.bookId}"
        val episodes = app.get(episodesUrl, headers = baseHeaders).parsedSafe<Array<DramaboxEpisode>>() ?: return false
        val chapter = episodes.find { it.chapterId == loadData.chapterId } ?: return false

        chapter.cdnList?.forEach { cdn ->
            cdn.videoPathList?.forEach { video ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        video.videoPath ?: return@forEach,
                        INFER_TYPE,
                    ) {
                        this.quality = getQualityFromName(video.quality.toString())
                    }
                )
            }
        }
        return true
    }

    data class LoadLinksData(
        @JsonProperty("bookId") val bookId: String,
        @JsonProperty("chapterId") val chapterId: String
    )

    data class DramaboxMedia(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("coverWap") val coverWap: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
    ) {
        fun toSearchResponse(api: Dramabox) = api.newAnimeSearchResponse(
            bookName ?: "",
            bookId ?: "",
            TvType.AsianDrama,
            false
        ) {
            this.posterUrl = coverWap
        }
    }

    data class DramaboxEpisode(
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("chapterImg") val chapterImg: String? = null,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null,
    )

    data class CdnItem(
        @JsonProperty("cdnDomain") val cdnDomain: String? = null,
        @JsonProperty("videoPathList") val videoPathList: List<VideoPath>? = null,
    )

    data class VideoPath(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("videoPath") val videoPath: String? = null,
    )
}
