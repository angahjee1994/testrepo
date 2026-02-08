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

    private val apiUrl = "https://api.fnccdn.com/501/api/plr/h5/v3"
    private val merchantId = "501"
    
    private val decryptKeyOld = "1558668820991598"
    private val decryptIvOld = "0102030405060708"
    
    private val decryptKeyNew = "star@livega*963."
    private val decryptIvNew = "0608040307010502"

    override suspend fun load(url: String): LoadResponse {
        val data = tryParseJson<LinkData>(url) ?: LinkData(url)
        
        val id = data.anchorId
        val title = data.title ?: "Live Stream"
        val poster = data.poster ?: ""
        val area = data.area ?: "MY"
        
        val infoUrl = "https://api.fnccdn.com/501/api/plr/zbliv/h5/v3/public/live/room-info?merchantId=$merchantId"
        val body = mapOf("anchorId" to id)

        val paramMap = mapOf("merchantId" to merchantId)
        val sign = generateSign(paramMap)
        
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
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Origin" to "https://hotlive11.com",
            "Referer" to "https://hotlive11.com/",
            "area" to area,
            "locale-language" to "ENU",
            "Accept" to "application/json, text/plain, */*"
        )
        
        var finalPoster = poster
        var plot = "Live Stream"
        var castList = emptyList<ActorData>()
        
        try {
            val response = app.post(infoUrl, headers = headers, json = body).parsedSafe<RoomInfoResponse>()
            response?.data?.let { room ->
                val nickname = room.anchorNickname ?: "Unknown"
                castList = listOf(ActorData(Actor(nickname, room.avatar ?: "")))
                plot = room.roomNotice ?: "No Notice"
            }
        } catch (e: Exception) {
            Log.e("Hot51", "Error fetching room info in load: ${e.message}")
        }
        
        val details = LinkData(id, area, title, finalPoster)
        
        return newLiveStreamLoadResponse(
            name = title,
            url = details.toJson(), 
            dataUrl = id 
        ) {
            this.posterUrl = finalPoster
            this.plot = plot
            this.actors = castList
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

    private fun decryptDebug(encrypted: String): String {
         return decrypt(encrypted) ?: "Decryption Failed"
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

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val linkData = tryParseJson<LinkData>(data) ?: LinkData(data)
        val anchorId = linkData.anchorId
        val area = linkData.area
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
            "area" to (area ?: "MY"),
            "locale-language" to "ENU",
            "Accept" to "application/json, text/plain, */*"
        )
        
        val response = app.post(infoUrl, headers = headers, json = body).parsedSafe<RoomInfoResponse>()
        
        var streamUrl = response?.data?.pullUrl?.hls ?: response?.data?.pullUrl?.flv 
            ?: response?.data?.pullAddr
            ?: response?.data?.unlDefPa?.let { decrypt(it) }
            ?: response?.data?.unlLowPa?.let { decrypt(it) }

        if (streamUrl == null) {
            val debugDef = response?.data?.unlDefPa?.let { decryptDebug(it) } ?: "N/A"
            val debugLow = response?.data?.unlLowPa?.let { decryptDebug(it) } ?: "N/A"
            throw Error("No link. Decrypt errors: Def=$debugDef, Low=$debugLow. Body: $body. Data: ${response?.data}")
        }
        
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
        
        // Special handling for Indonesia (Legacy Area-based) until we find its labelId
        val isAreaBased = data == "ID"
        val area = if (isAreaBased) "ID" else "MY"
        // For standard tabs, use the data as labelId directly. For ID, use empty labelId
        val labelId = if (isAreaBased) "" else data
        
        val homeLists = mutableListOf<HomePageList>()
        
        // Banners only on "Popular" (ID 1)
        if (page == 1 && data == "1") {
            try {
                val bannerUrl = "$apiUrl/public/banner/list?merchantId=$merchantId&area=$area"
                val bannerRes = app.get(bannerUrl).parsedSafe<BannerResponse>()
                val bannerItems = bannerRes?.map { banner ->
                    val bTitle = banner.title ?: "Hot51"
                    val bPoster = banner.imgUrl ?: ""
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
        
        val timestamp = System.currentTimeMillis() / 1000
        val baseUrl = "$apiUrl/public/live/lrl?pageNum=$page&pageSize=20&merchantId=$merchantId&area=$area&lang=ENU&t=$timestamp"
        val url = if (labelId.isNotEmpty()) "$baseUrl&labelId=$labelId" else baseUrl
        
        val response = app.get(url).parsedSafe<LiveCenterResponse>()
        
        val items = response?.records?.map { item ->
            val title = item.liveName ?: item.anchorNickname ?: "Unknown"
            val id = item.anchorId ?: item.id ?: ""
            val poster = item.coverUrl ?: item.avatar ?: ""
            
            newAnimeSearchResponse(
                title,
                LinkData(id, area, title, poster).toJson(),
                TvType.NSFW
            ) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        if (items.isNotEmpty()) {
            homeLists.add(
                HomePageList(
                    name = request.name,
                    list = items,
                    isHorizontalImages = false
                )
            )
        }

        return newHomePageResponse(homeLists, hasNext = (response?.current ?: 0) < (response?.pages ?: 0))
    }

    override val mainPage = mainPageOf(
        "1" to "Popular",
        "2" to "Toy",
        "1583463376967712769" to "Show",
        "ID" to "Indonesia",
        "1583463211715371010" to "Vietnam"
    )
}

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
    @JsonProperty("bauble") val bauble: Boolean? = false,
    @JsonProperty("area") val area: String?,
    @JsonProperty("gameName") val gameName: String?,
    @JsonProperty("gameIconUrl") val gameIconUrl: String?,
    @JsonProperty("gameType") val gameType: Int? = 0
)

data class RoomInfoResponse(
    @JsonProperty("code") val code: Int?,
    @JsonProperty("data") val data: RoomData?
)

data class RoomData(
    @JsonProperty("ann") val anchorNickname: String?,
    @JsonProperty("cu") val roomCover: String?,
    @JsonProperty("ahp") val avatar: String?,
    @JsonProperty("cyNts") val roomNotice: String?,
    @JsonProperty("la") val area: String?,
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