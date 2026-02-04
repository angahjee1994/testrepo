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
    private val apiUrl = "https://api.fnccdn.com/501/api/plr/h5/v3"
    private val merchantId = "501"

    override suspend fun load(url: String): LoadResponse {
        val id = url.substringAfterLast("/")
        return newLiveStreamLoadResponse(
            name = "Live Stream",
            url = id,
            dataUrl = id
        ) {
            this.posterUrl = "$mainUrl/assets/img/logo.png" // Fallback or could fetch
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val anchorId = data
        val infoUrl = "$apiUrl/public/live/room-info?merchantId=$merchantId"
        val body = mapOf("anchorId" to anchorId)
        val response = app.post(infoUrl, json = body).parsedSafe<RoomInfoResponse>()
        val streamUrl = response?.data?.pullUrl?.hls ?: response?.data?.pullUrl?.flv ?: return false
        callback(
            newExtractorLink(
                this.name,
                this.name,
                streamUrl,
                ExtractorLinkType.M3U8
            )
        )
        return true
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data ?: "1"
        val isCountry = data == "ID" || data == "VN"
        
        val area = if (isCountry) data else "MY"
        val labelId = if (isCountry || data == "1") "" else data
        
        val timestamp = System.currentTimeMillis() / 1000
        
        val baseUrl = "$apiUrl/public/live/lrl?pageNum=$page&pageSize=20&merchantId=$merchantId&area=$area&lang=ENU&t=$timestamp"
        val url = if (labelId.isNotEmpty()) "$baseUrl&labelId=$labelId" else baseUrl
        
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

    override val mainPage = mainPageOf(
        "1" to "Popular",
        "ID" to "Indonesia",
        "VN" to "Vietnam"
    )

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
