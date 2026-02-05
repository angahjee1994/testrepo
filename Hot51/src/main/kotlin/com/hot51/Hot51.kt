@file:Suppress("DEPRECATION")

package com.hot51

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import android.util.Log

class Hot51 : MainAPI() {
    override var mainUrl = "https://hotlive11.com"
    override var name = "Hot51"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.NSFW)

    // API Endpoints
    private val apiUrl = "https://api.fnccdn.com/501/api/plr/h5/v3"
    private val merchantId = "501"
    
    // Decryption Constants (Old/Common)
    private val decryptKeyOld = "1558668820991598"
    private val decryptIvOld = "0102030405060708"
    
    // Decryption Constants (New/Room Specific)
    private val decryptKeyNew = "star@livega*963."
    private val decryptIvNew = "0608040307010502"

    override suspend fun load(url: String): LoadResponse {
        val data = tryParseJson<LinkData>(url) ?: LinkData(url)
        
        val id = data.anchorId
        val title = data.title ?: "Live Stream"
        val poster = data.poster ?: ""
        val area = data.area ?: "MY"
        
        return newLiveStreamLoadResponse(
            name = title,
            url = id,
            dataUrl = url 
        ) {
            this.posterUrl = poster
        }
    }

    private fun decrypt(encrypted: String?): String? {
        if (encrypted.isNullOrEmpty()) return null
        
        val combinations = listOf(
            decryptKeyNew to decryptIvNew,
            decryptKeyOld to decryptIvOld,
            decryptKeyOld to decryptKeyOld // IV = Key fallback
        )
        
        for ((idx, combo) in combinations.withIndex()) {
            val (key, iv) = combo
            try {
                val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
                val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
                val decodedBytes = android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
                val decryptedBytes = cipher.doFinal(decodedBytes)
                val result = String(decryptedBytes, Charsets.UTF_8).trim()
                if (result.isNotEmpty()) {
                    Log.d("Hot51", "Decrypt success with combo ${idx + 1}")
                    return result
                }
            } catch (e: Exception) {
                Log.e("Hot51", "Decrypt attempt ${idx + 1} failed: ${e.message}")
            }
        }
        return null
    }

    // Diagnostic version of decrypt
    private fun decryptDebug(encrypted: String): String {
         return decrypt(encrypted) ?: "Decryption Failed"
    }

    private fun md5(input: String): String {
        return java.security.MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun generateSign(params: Map<String, String>): String {
        if (params.isEmpty()) {
            // md5(md5("")) = 11f569ed792da4e0cff8a393534a5bf2
            return "11f569ed792da4e0cff8a393534a5bf2"
        }
        val sortedKeys = params.keys.sorted()
        // The website uses values of sorted keys joined directly with no separator
        val payload = sortedKeys.joinToString("") { params[it] ?: "" }
        
        val salt = "rsba648b744646lkid9896bb1o7h9776"
        val firstHash = md5(payload)
        return md5(firstHash + salt)
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linkData = parseJson<LinkData>(data)
        val anchorId = linkData.anchorId
        val area = linkData.area
        val merchantId = "501"
        
        val paramMap = mapOf(
            "merchantId" to merchantId
        )
        // room-info uses an empty payload for its signature
        val sign = generateSign(emptyMap())

        val infoUrl = "https://api.fnccdn.com/501/api/plr/zbliv/h5/v3/public/live/room-info?merchantId=$merchantId"
        val body = mapOf("anchorId" to anchorId)
        
        val deviceId = java.util.UUID.randomUUID().toString()
        val headers = mapOf(
            "Authorization" to "Basic d2ViLXBsYXllcjp3ZWJQbGF5ZXIyMDIyKjk2My4hQCM=",
            "dev-type" to "H5",
            "sign" to sign,
            "merchantId" to merchantId,
            "device" to deviceId,
            "versionCode" to "101",
            "system-version" to "1.5.1",
            "time-zone" to "GMT+08:00",
            "Content-Type" to "application/json; charset=utf-8",
            "Origin" to "https://hotlive11.com",
            "Referer" to "https://hotlive11.com/",
            "area" to (area ?: "MY"),
            "locale-language" to "ENU"
        )
        
        val response = app.post(infoUrl, headers = headers, json = body).parsedSafe<RoomInfoResponse>()
        
        // Try all possible sources: HLS, FLV, pullAddr, decrypted unlDefPa, or unlLowPa
        var streamUrl = response?.data?.pullUrl?.hls ?: response?.data?.pullUrl?.flv 
            ?: response?.data?.pullAddr
            ?: response?.data?.unlDefPa?.let { decrypt(it) }
            ?: response?.data?.unlLowPa?.let { decrypt(it) }

        // Sanitize: If streamUrl is just a path, it might need a domain? 
        // Usually these are full URLs. If it starts with error, clear it.
        
        if (streamUrl == null) {
            // Debugging: Try to see WHY decryption failed
            val debugDef = response?.data?.unlDefPa?.let { decryptDebug(it) } ?: "N/A"
            val debugLow = response?.data?.unlLowPa?.let { decryptDebug(it) } ?: "N/A"
            throw Error("No link. Decrypt errors: Def=$debugDef, Low=$debugLow. Body: $body. Data: ${response?.data}")
        }
        
        // If it was a debug error message (shouldn't happen with decrypt(), but checking)
        if (streamUrl.startsWith("Error:")) {
             throw Error("Decryption failed: $streamUrl")
        }

        @Suppress("DEPRECATION")
        callback(
            newExtractorLink(
                "Hot51",
                "Hot51 Live",
                streamUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = "https://hotlive11.com/"
                this.quality = getQualityFromName("HD")
            }
        )
        return true
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = request.data ?: "1"
        val isCountry = data == "ID" || data == "VN"
        val area = if (isCountry) data else "MY"
        val labelId = if (isCountry || data == "1") "" else data
        
        val homeLists = mutableListOf<HomePageList>()
        
        // Fetch banners only on the first page of "Popular"
        if (page == 1 && data == "1") {
            try {
                val bannerUrl = "$apiUrl/public/banner/list?merchantId=$merchantId&area=$area"
                val bannerRes = app.get(bannerUrl).parsedSafe<BannerResponse>()
                val bannerItems = bannerRes?.map { banner ->
                    val bTitle = banner.title ?: "Hot51"
                    val bPoster = banner.imgUrl ?: ""
                    // For banners, businessId is often the anchorId if type is ROOM
                    val bId = banner.businessId ?: ""
                    
                    newAnimeSearchResponse(
                        bTitle,
                        LinkData(bId, area, bTitle, bPoster).toJson(),
                        TvType.NSFW
                    ) {
                        this.posterUrl = bPoster
                    }
                } 
                if (!bannerItems.isNullOrEmpty()) {
                    homeLists.add(HomePageList(name = "Featured", list = bannerItems, isHorizontalImages = false))
                }
            } catch (e: Exception) {
                Log.e("Hot51", "Error fetching banners: ${e.message}")
            }
        }
        
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val paramMap = mutableMapOf(
            "pageNum" to page.toString(),
            "pageSize" to "20",
            "merchantId" to merchantId,
            "lang" to "ENU",
            "t" to timestamp
        )
        if (labelId.isNotEmpty()) {
            paramMap["labelId"] = labelId
        } else {
            paramMap["labelId"] = "1" // Default to Popular if empty
        }
        
        val sign = generateSign(paramMap)
        val queryParams = paramMap.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "https://api.fnccdn.com/501/api/plr/zbliv/public/live/h5/liveCenter?$queryParams"
        
        val deviceId = java.util.UUID.randomUUID().toString()
        val response = app.get(
            url,
            headers = mapOf(
                "Authorization" to "Basic d2ViLXBsYXllcjp3ZWJQbGF5ZXIyMDIyKjk2My4hQCM=",
                "area" to area,
                "dev-type" to "H5",
                "sign" to sign,
                "device" to deviceId,
                "merchantId" to merchantId,
                "versionCode" to "101",
                "system-version" to "1.5.1",
                "time-zone" to "GMT+08:00",
                "Referer" to "https://hotlive11.com/",
                "Origin" to "https://hotlive11.com",
                "locale-language" to "ENU"
            )
        ).parsedSafe<LiveCenterResponse>()
        val items = response?.records?.map { item ->
            val title = item.liveName ?: item.anchorNickname ?: "Unknown"
            val id = item.anchorId ?: item.id ?: ""
            val poster = item.coverUrl ?: item.avatar ?: ""
            val itemArea = item.area ?: area
            
            newAnimeSearchResponse(
                title,
                LinkData(id, itemArea, title, poster).toJson(),
                TvType.NSFW
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        homeLists.add(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            )
        )

        return newHomePageResponse(homeLists, hasNext = (response?.current ?: 0) < (response?.pages ?: 0))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val deviceId = java.util.UUID.randomUUID().toString()
        
        val paramMap = mapOf(
            "pageNum" to "1",
            "pageSize" to "40",
            "searchText" to query,
            "merchantId" to merchantId,
            "lang" to "ENU",
            "t" to timestamp
        )
        
        val sign = generateSign(paramMap)
        val queryParams = paramMap.entries.joinToString("&") { "${it.key}=${it.value}" }
        val url = "https://api.fnccdn.com/501/api/plr/zbliv/public/live/h5/liveCenter?$queryParams"
        
        val response = app.get(
            url,
            headers = mapOf(
                "Authorization" to "Basic d2ViLXBsYXllcjp3ZWJQbGF5ZXIyMDIyKjk2My4hQCM=",
                "area" to "MY",
                "dev-type" to "H5",
                "sign" to sign,
                "device" to deviceId,
                "merchantId" to merchantId,
                "versionCode" to "101",
                "system-version" to "1.5.1",
                "time-zone" to "GMT+08:00",
                "Referer" to "https://hotlive11.com/",
                "Origin" to "https://hotlive11.com",
                "locale-language" to "ENU"
            )
        ).parsedSafe<LiveCenterResponse>()
        
        return response?.records?.map { item ->
            val title = item.liveName ?: item.anchorNickname ?: "Unknown"
            val id = item.anchorId ?: item.id ?: ""
            val poster = item.coverUrl ?: item.avatar ?: ""
            val itemArea = item.area ?: "MY"
            
            newAnimeSearchResponse(
                title,
                LinkData(id, itemArea, title, poster).toJson(),
                TvType.NSFW
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override val mainPage = mainPageOf(
        "1" to "Popular",
        "ID" to "Indonesia",
        "VN" to "Vietnam"
    )
}

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
    @JsonProperty("avatar") val avatar: String?,
    @JsonProperty("area") val area: String? = null
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
    @JsonProperty("unlLowPa") val unlLowPa: String?,
    @JsonProperty("pullUrl") val pullUrl: PullUrl? 
)

data class PullUrl(
    @JsonProperty("hls") val hls: String?,
    @JsonProperty("flv") val flv: String?
)
data class LinkData(
    @JsonProperty("anchorId") val anchorId: String,
    @JsonProperty("area") val area: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("poster") val poster: String? = null
)

typealias BannerResponse = List<BannerRecord>

data class BannerRecord(
    @JsonProperty("id") val id: String?,
    @JsonProperty("businessId") val businessId: String?,
    @JsonProperty("imgUrl") val imgUrl: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("jumpUrl") val jumpUrl: String?
)
