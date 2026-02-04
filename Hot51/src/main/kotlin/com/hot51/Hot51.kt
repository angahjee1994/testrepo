package com.hot51

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Hot51 : MainAPI() {
    override var mainUrl = "https://hotlive11.com"
    override var name = "Hot51"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    // API Endpoints
    private val apiUrl = "https://api.fnccdn.com/501/api/plr/h5/v3"
    private val merchantId = "501"
    
    // Decryption Constants
    private val decryptKey = "1558668820991598"
    private val decryptIv = "0102030405060708"

    override suspend fun load(url: String): LoadResponse {
        val (rawId, title, poster, area) = url.split(";", limit = 4) + listOf("", "", "", "MY")
        val id = rawId.substringAfterLast("/").substringBefore("?") // Ensure we get only the numeric ID
        
        return newLiveStreamLoadResponse(
            name = title.ifEmpty { "Live Stream" },
            url = id,
            dataUrl = "$id;$area"
        ) {
            this.posterUrl = poster
        }
    }

    private fun decrypt(encrypted: String): String? {
        try {
            val keySpec = SecretKeySpec(decryptKey.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(decryptIv.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decodedBytes = android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            return String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun md5(input: String): String {
        return java.security.MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateSign(params: Map<String, String>): String {
        val sortedKeys = params.keys.sorted()
        val sb = StringBuilder()
        for (key in sortedKeys) {
            sb.append(key).append("=").append(params[key])
        }
        
        val salt = "rsba648b744646lkid9896bb1o7h9776"
        val firstHash = md5(sb.toString())
        return md5(firstHash + salt)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (anchorId, area) = data.split(";", limit = 2) + listOf("", "MY")
        val merchantId = "501"
        
        val paramMap = mapOf(
            "merchantId" to merchantId
        )
        val sign = generateSign(paramMap)

        val infoUrl = "https://api.fnccdn.com/501/api/plr/zbliv/h5/v3/public/live/room-info?merchantId=$merchantId"
        val body = mapOf("anchorId" to anchorId)
        
        val deviceId = java.util.UUID.randomUUID().toString()
        val headers = mapOf(
            "Authorization" to "Basic d2ViLXBsYXllcjp3ZWJQbGF5ZXIyMDIyKjk2My4hQCM=",
            "dev-type" to "H5",
            "sign" to "11f569ed792da4e0cff8a393534a5bf2",
            "merchantId" to merchantId,
            "device" to deviceId,
            "versionCode" to "101",
            "system-version" to "1.5.1",
            "time-zone" to "GMT+08:00",
            "Content-Type" to "application/json; charset=utf-8",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Origin" to "https://hotlive11.com",
            "Referer" to "https://hotlive11.com/",
            "area" to area
        )
        
        val response = app.post(infoUrl, headers = headers, json = body).parsedSafe<RoomInfoResponse>()
        
        // Try all possible sources: HLS, FLV, pullAddr, decrypted unlDefPa, or plain raw unlDefPa
        val streamUrl = response?.data?.pullUrl?.hls ?: response?.data?.pullUrl?.flv 
            ?: response?.data?.pullAddr
            ?: response?.data?.unlDefPa?.let { decrypt(it) }
            
        if (streamUrl == null) {
            throw Error("No link. Body: $body. Data: ${response?.data}")
        }

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
            val title = item.liveName ?: item.anchorNickname ?: "Unknown"
            val id = item.anchorId ?: item.id ?: ""
            val poster = item.coverUrl ?: item.avatar ?: ""
            
            newMovieSearchResponse(
                title,
                "$id;$title;$poster;$area",
                TvType.NSFW
            ) {
                this.posterUrl = poster
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
        @JsonProperty("pullAddr") val pullAddr: String?,
        @JsonProperty("unlDefPa") val unlDefPa: String?,
        @JsonProperty("pullUrl") val pullUrl: PullUrl? 
    )

    data class PullUrl(
        @JsonProperty("hls") val hls: String?,
        @JsonProperty("flv") val flv: String?
    )
}
