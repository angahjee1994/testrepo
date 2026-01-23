package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class Dramabox : MainAPI() {
    override var mainUrl = "https://dramabox.sansekai.my.id"
    override var name = "Dramabox"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/api/dramabox/foryou" to "For You",
        "$mainUrl/api/dramabox/trending" to "Trending",
        "$mainUrl/api/dramabox/latest" to "Latest",
        "$mainUrl/api/dramabox/dubindo?classify=terpopuler" to "Dub Indo (Populer)",
        "$mainUrl/api/dramabox/dubindo?classify=terbaru" to "Dub Indo (Terbaru)",
        "$mainUrl/api/dramabox/vip" to "VIP",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val isPaginated = request.data.contains("dubindo")
        
        if (!isPaginated && page > 1) {
            return newHomePageResponse(request.name, emptyList())
        }

        val url = if (isPaginated) {
            if (request.data.contains("?")) {
                "${request.data}&page=$page"
            } else {
                "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val text = app.get(url).text
        val items = try { 
            parseJson<List<DramaItem>>(text) 
        } catch (e: Exception) {
            try {
                // Handle VIP/Nested structure
                val response = parseJson<VipResponse>(text)
                response.sectionList?.flatMap { it.dataList ?: emptyList() } ?: emptyList()
            } catch (e2: Exception) {
                emptyList()
            }
        }

        val home = items.filter { !it.bookName.isNullOrEmpty() && !it.bookId.isNullOrEmpty() }.map {
            it.toSearchResponse(this)
        }

        return newHomePageResponse(request.name, home)
    }

    data class VipResponse(
        @JsonProperty("sectionList") val sectionList: List<SectionItem>? = null
    )

    data class SectionItem(
        @JsonProperty("dataList") val dataList: List<DramaItem>? = null
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/dramabox/search?query=$query"
        val text = app.get(url).text
        val items = try {
            parseJson<List<DramaItem>>(text)
        } catch (e: Exception) {
            emptyList()
        }
        return items.map { it.toSearchResponse(this) }
    }

    override suspend fun load(url: String): LoadResponse {
        val bookId = url.substringAfter("bookId=")
        val detailUrl = "$mainUrl/api/dramabox/detail?bookId=$bookId"
        val detailText = app.get(detailUrl).text
        val detail = try {
            parseJson<DramaItem>(detailText)
        } catch (e: Exception) {
            throw ErrorLoadingException("Failed to load detail: ${e.message}")
        }

        val episodesUrl = "$mainUrl/api/dramabox/allepisode?bookId=$bookId"
        val episodesText = app.get(episodesUrl).text
        val episodesList = try {
            parseJson<List<EpisodeItem>>(episodesText)
        } catch (e: Exception) {
            emptyList()
        }

        val episodes = episodesList.map { ep ->
            newEpisode(ep.toJson()) {
                this.name = ep.chapterName
                this.episode = ep.chapterIndex
                this.posterUrl = ep.chapterImg
            }
        }

        return newTvSeriesLoadResponse(
            detail.bookName ?: "",
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = detail.coverWap
            this.plot = detail.introduction
            this.tags = detail.tags
            this.year = detail.shelfTime?.substringBefore("-")?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episode = parseJson<EpisodeItem>(data)

        episode.cdnList?.forEach { cdn ->
            cdn.videoPathList?.forEach { video ->
                val quality = video.quality ?: 0
                val url = video.videoPath ?: return@forEach
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "Dramabox ${quality}p",
                        url,
                        INFER_TYPE
                    ) {
                        this.quality = quality
                    }
                )
            }
        }
        return true
    }

    data class DramaItem(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("coverWap") val coverWap: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("shelfTime") val shelfTime: String? = null,
    ) {
        fun toSearchResponse(provider: Dramabox): SearchResponse {
            return provider.newTvSeriesSearchResponse(
                bookName ?: "",
                "${provider.mainUrl}/api/dramabox/detail?bookId=$bookId", // Internal URL identifier
                TvType.TvSeries
            ) {
                this.posterUrl = coverWap
            }
        }
    }

    data class EpisodeItem(
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        @JsonProperty("chapterImg") val chapterImg: String? = null,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null,
    )

    data class CdnItem(
        @JsonProperty("videoPathList") val videoPathList: List<VideoItem>? = null,
    )

    data class VideoItem(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("videoPath") val videoPath: String? = null,
    )
}
