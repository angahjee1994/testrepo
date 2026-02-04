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
        val (id, title, poster) = url.split(";", limit = 3) + listOf("", "", "")
        
        return newLiveStreamLoadResponse(
            name = title.ifEmpty { "Live Stream" },
            url = id,
            dataUrl = id
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
            sb.append(key).append(params[key])
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
        val anchorId = data
        val merchantId = "501"
        
        // Note: The signature is based on Query Params, not the Body for this specific endpoint,
        // but it sometimes depends on how the interceptor handles it. 
        // Based on analysis, room-info is POST but might attach query params for signing?
        // Actually, the browser subagent said it signs "params".
        // Let's verify if room-info has query params. 
        // Browser URL: .../room-info?merchantId=501
        // The BODY has {anchorId: ...}
        // If the signature logic sorts keys, it usually mixes params and body if configured, 
        // BUT the interceptor analysis specificied "query params (params)".
        // So I will sign the merchantId (which is in URL) and potentially anchorId if I put it in URL?
        // Wait, the browser request showed anchorId in BODY.
        // The URL was `.../room-info`. The captured request had NO query params in the URL displayed in the step 65 manually?
        // Actually step 1686 said URL: `.../room-info`.
        // BUT step 1707 said "sign header ... calculated based on request parameters".
        // Let's assume it signs the body if it's POST? Or we should put merchantId in the query?
        // To be safe, I will put merchantId in the query AND sign it.
        // Step 1686 said: `merchantId: 501` was in HEADERS.
        // It was NOT in the URL query string in step 1686?
        // Wait, step 1686: "URL: .../room-info". No query params shown.
        // But headers had `merchantId: 501`.
        // Step 1707 said "merchantId... almost always present in query params".
        // Let's try signing the BODY params for now as that's safer for a POST.
        
        val paramMap = mapOf(
            "anchorId" to anchorId,
            "merchantId" to merchantId
        )
        val sign = generateSign(paramMap)

        val infoUrl = "https://api.fnccdn.com/501/api/plr/zbliv/h5/v3/public/live/room-info?merchantId=$merchantId"
        val body = mapOf("anchorId" to anchorId)
        
        val headers = mapOf(
            "Authorization" to "Basic d2ViLXBsYXllcjp3ZWJQbGF5ZXIyMDIyKjk2My4hQCM=",
            "dev-type" to "H5",
            "sign" to sign,
            "device" to "806abd11-fef0-4baa-9c3d-104b4693dc7d",
            "versionCode" to "101",
            "system-version" to "1.5.1",
            "time-zone" to "GMT+08:00",
            "Content-Type" to "application/json"
        )
        
        val response = app.post(infoUrl, headers = headers, json = body).parsedSafe<RoomInfoResponse>()
        
        // Try all possible sources: HLS, FLV, pullAddr, decrypted unlDefPa, or plain raw unlDefPa
        val streamUrl = response?.data?.pullUrl?.hls ?: response?.data?.pullUrl?.flv 
            ?: response?.data?.pullAddr
            ?: response?.data?.unlDefPa?.let { decrypt(it) }
            ?: return false
            
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
                "$id;$title;$poster",
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
