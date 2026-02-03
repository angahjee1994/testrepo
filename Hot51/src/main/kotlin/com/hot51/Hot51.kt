package com.hot51

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class Hot51 : MainAPI() {
    override var mainUrl = "https://hotlive11.com"
    override var name = "Hot51"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    // API Endpoints
    private val apiUrl = "https://api.fnccdn.com/501/api/plr/zbliv"
    private val merchantId = "501"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val labelId = if (request.data == "popular") "1" else request.data
        val url = "$apiUrl/public/live/h5/liveCenter?pageNum=$page&pageSize=50&labelId=$labelId&merchantId=$merchantId&lang=ENU&t=${System.currentTimeMillis()}"
        
        val response = app.get(url).parsedSafe<LiveCenterResponse>()
        val items = response?.records?.map { item ->
            newMovieSearchResponse(
                item.liveName ?: item.anchorNickname ?: "Unknown",
                item.anchorId ?: item.id ?: "",
                TvType.NSFW
            ) {
                this.posterUrl = item.coverUrl ?: item.avatar
            }
        } ?: emptyList()

        return newHomePageResponse(HomePageList(request.name, items, (response?.current ?: 0) < (response?.pages ?: 0)))
    }

    // ... (Main Page definition) ...

    // Data Classes
    data class LiveCenterResponse(
        @JsonProperty("records") val records: List<LiveRecord>?,
        @JsonProperty("current") val current: Int?,
        @JsonProperty("pages") val pages: Int?
    )

    data class LiveRecord(
        @JsonProperty("id") val id: String?,
        @JsonProperty("anchorId") val anchorId: String?,
        @JsonProperty("anchorNickname") val anchorNickname: String?,
        @JsonProperty("liveName") val liveName: String?,
        @JsonProperty("coverUrl") val coverUrl: String?,
        @JsonProperty("avatar") val avatar: String?
    )

    data class RoomInfoResponse(
        @JsonProperty("code") val code: Int?,
        @JsonProperty("data") val data: RoomData?
    )

    data class RoomData(
        @JsonProperty("anchorNickname") val anchorNickname: String?,
        @JsonProperty("roomCover") val roomCover: String?,
        @JsonProperty("avatar") val avatar: String?,
        @JsonProperty("roomNotice") val roomNotice: String?,
        @JsonProperty("area") val area: String?,
        @JsonProperty("pullUrl") val pullUrl: PullUrl?
    )

    data class PullUrl(
        @JsonProperty("hls") val hls: String?,
        @JsonProperty("flv") val flv: String?
    )
}
