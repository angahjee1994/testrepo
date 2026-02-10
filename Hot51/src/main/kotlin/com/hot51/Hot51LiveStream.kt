package com.hot51

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import okhttp3.Request
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.util.Log
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.nio.charset.StandardCharsets
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class Hot51LiveStream(val app: com.lagradost.nicehttp.Requests) {

    companion object {
        private const val TAG = "Hot51LiveStream"
        private var persistentVisitorId: String? = null
        
        private fun getVisitorId(): String {
            if (persistentVisitorId == null) {
                persistentVisitorId = java.util.UUID.randomUUID().toString()
            }
            return persistentVisitorId!!
        }
    }

    private val decryptKey = "9216345272696329"
    private val decryptIv = "0507060302080104"

    // Data classes for API responses
    data class RoomInfoResponse(@JsonProperty("data") val data: RoomInfoData)
    data class RoomInfoData(
        @JsonProperty("wsu") val wsu: String?, 
        @JsonProperty("atr") val atr: String?,
        @JsonProperty("lid") val lid: String?,
        @JsonProperty("gid") val gid: String?
    )

    data class GiftListResponse(@JsonProperty("data") val data: List<GiftItem>?)
    data class GiftItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("giftName") val giftName: String,
        @JsonProperty("giftIcon") val giftIcon: String,
        @JsonProperty("price") val price: Int
    )

    // Simplified WebSocket message structure
    data class WsMessage(@JsonProperty("cmd") val cmd: Int?, @JsonProperty("data") val data: Any?, @JsonProperty("result") val result: Any?)
    
    // Data classes exposed to Hot51.kt
    data class LiveComment(
        val username: String,
        val message: String,
        val timestamp: Long,
        val avatarUrl: String? = null,
    )

    data class LiveGift(
        val username: String,
        val giftName: String,
        val giftIconUrl: String,
        val giftCount: Int,
        val timestamp: Long,
        val animationUrl: String? = null,
    )

    // Cache explicitly fetched gift list
    private var giftMap: Map<String, GiftItem> = emptyMap()

    // Shared Flow for WebSocket events
    private val _wsEvents = MutableSharedFlow<WsMessage>(
        replay = 0, 
        extraBufferCapacity = 64, 
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    private var currentWebSocket: WebSocket? = null
    private var activeRoomId: String? = null
    private var listenersCount = 0
    private val connectionLock = Any()
    @Volatile private var isConnecting = false
    private var lastCookies: String? = null
    private var currentAnchorId: String = "" 
    private var currentRoomInfo: RoomInfoData? = null
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }

    private fun md5(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        return java.math.BigInteger(1, md.digest(input.toByteArray())).toString(16).padStart(32, '0')
    }

    private fun generateSign(params: Map<String, Any>): String {
        val sortedKeys = params.keys.sorted()
        val sb = StringBuilder()
        for (key in sortedKeys) {
            val value = params[key]
            if (value != null) {
                sb.append(value.toString())
            }
        }
        val a = sb.toString()
        val secret = "rsba648b744646lkid9896bb1o7h9776"
        return md5(md5(a) + secret)
    }

    suspend fun fetchRoomInfo(roomId: String, anchorId: String): RoomInfoData? {
        val url = "https://api.fnccdn.com/501/api/plr/zbliv/h5/v3/public/live/room-info?merchantId=501"
        val payload = mapOf("anchorId" to anchorId)
        val sign = generateSign(payload)
        val visitorId = getVisitorId()
        
        val headers = mapOf(
            "Authorization" to "Basic d2ViLXBsYXllcjp3ZWJQbGF5ZXIyMDIyKjk2My4hQCM=",
            "dev-type" to "H5",
            "sign" to sign,
            "merchantId" to "501",
            "device" to visitorId,
            "versionCode" to "101",
            "system-version" to "1.5.1",
            "time-zone" to "GMT+08:00",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
            "Origin" to "https://hotlive11.com",
            "Referer" to "https://hotlive11.com/",
            "area" to "MY",
            "locale-language" to "ENU",
            "Accept" to "application/json, text/plain, */*",
            "Cache-Control" to "max-age=0",
            "Content-Type" to "application/json;charset=utf-8"
        )
        return try {
            val response = app.post(url, json = payload, headers = headers)
            Log.d(TAG, "RoomInfo Raw: ${response.text}")
            
            val cookieList = response.okhttpResponse.headers("Set-Cookie")
            if (cookieList.isNotEmpty()) {
                lastCookies = cookieList.joinToString("; ") { it.substringBefore(";") }
                Log.d(TAG, "Captured cookies: $lastCookies")
            }
            
            response.parsedSafe<RoomInfoResponse>()?.data
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching room info: ${e.message}")
            null
        }
    }

    suspend fun fetchGiftList() {
        if (giftMap.isNotEmpty()) return
        val timestamp = System.currentTimeMillis() / 1000
        val url = "https://api.fnccdn.com/501/api/plr/financemo/vips/v2/h5/search"
        val params = mapOf("merchantId" to 501, "t" to timestamp)
        val sign = generateSign(params)
        
        val fullUrl = "$url?merchantId=501&t=$timestamp"
        
        val headers = mapOf(
            "Authorization" to "Basic d2ViLXBsYXllcjp3ZWJQbGF5ZXIyMDIyKjk2My4hQCM=",
            "merchantId" to "501",
            "device" to "806abd11-fef0-4baa-9c3d-104b4693dc7d", 
            "versionCode" to "101",
            "dev-type" to "H5",
            "system-version" to "1.5.1",
            "time-zone" to "GMT+08:00",
            "sign" to sign
        )
        try {
            val response = app.get(fullUrl, headers = headers).parsedSafe<GiftListResponse>()
            giftMap = response?.data?.associateBy { it.id } ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching gift list: ${e.message}")
        }
    }
    
    private fun decryptWsu(encrypted: String?): String? {
        if (encrypted.isNullOrEmpty()) return null
        try {
            val keySpec = SecretKeySpec(decryptKey.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(decryptIv.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decodedBytes = Base64.decode(encrypted, Base64.DEFAULT)
            val decryptedBytes = cipher.doFinal(decodedBytes)
            val result = String(decryptedBytes, Charsets.UTF_8).trim()
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Failed check WSU: ${e.message}")
            return null
        }
    }

    private fun encryptToken(data: String, key: String): String? {
        try {
            val iv = "0507060302080104" // Hardcoded IV from JS
            val keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(encryptedBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encrypt token failed: ${e.message}")
            return null
        }
    }

    private suspend fun connectWebSocket(roomId: String, anchorId: String) {
        synchronized(connectionLock) {
            if (activeRoomId != roomId) {
                currentWebSocket?.close(1000, "Switching room")
                currentWebSocket = null
                _wsEvents.tryEmit(WsMessage(null, null, null))
                listenersCount = 0
                activeRoomId = roomId
            }
            listenersCount++
            
            if (currentWebSocket != null || isConnecting) return
            isConnecting = true
        }
        
        try {
            Log.d(TAG, "Fetching room info for room=$roomId anchor=$anchorId")
            val roomInfo = fetchRoomInfo(roomId, anchorId) ?: return
            Log.d(TAG, "Encrypted wsu: ${roomInfo.wsu}")
            val wsuUrl = decryptWsu(roomInfo.wsu) ?: roomInfo.wsu
            currentAnchorId = anchorId
            
            if (wsuUrl != null) {
                connectWebSocket(wsuUrl, roomInfo)
            } else {
                Log.e(TAG, "Failed to decrypt WSU")
            }
        } finally {
            isConnecting = false
        }
    }

    private fun connectWebSocket(url: String, roomInfo: RoomInfoData) {
        currentRoomInfo = roomInfo
        val request = Request.Builder()
            .url(url)
            .addHeader("Origin", "https://hotlive11.com")
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")
            .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Pragma", "no-cache")
            .apply {
                if (!lastCookies.isNullOrEmpty()) {
                    addHeader("Cookie", lastCookies!!)
                    Log.d(TAG, "Added Cookie header to WS: $lastCookies")
                }
            }
            .build()
            
        Log.e(TAG, "Starting WebSocket connection to: $url")
        currentWebSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.e(TAG, "WebSocket Opened Successfully")
                val handshakeBytes = ProtobufParser.createHandshake(10000)
                Log.d(TAG, "Sending Handshake (10000)")
                webSocket.send(okio.ByteString.of(*handshakeBytes))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received text message: $text")
                try {
                    val msg = AppUtils.tryParseJson<WsMessage>(text)
                    if (msg != null) {
                        Log.d(TAG, "Parsed message cmd=${msg.cmd}")
                        if (msg.cmd == 10000 && msg.result != null) {
                             Log.d(TAG, "Received Handshake Response (Text): ${msg.result}")
                             val key = msg.result.toString()
                             sendLogin(key)
                        }
                        _wsEvents.tryEmit(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}")
                }
            }
            
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received binary message: ${bytes.size} bytes")
                try {
                    val data = bytes.toByteArray()
                    val (cmd, dataMap) = ProtobufParser.parseMessage(data)
                    if (cmd != null) {
                        Log.d(TAG, "Parsed protobuf: cmd=$cmd data=$dataMap")
                        when (cmd) {
                            10000 -> {
                                Log.d(TAG, "Received Handshake Response (10000)")
                                handleHandshakeResponse(dataMap)
                            }
                            10001 -> {
                                Log.d(TAG, "Received Login Response (10001)")
                                if (currentAnchorId.isNotEmpty()) {
                                    val enterRoomBytes = ProtobufParser.createEnterRoom(10004, currentAnchorId)
                                    Log.d(TAG, "Sending EnterRoom (10004) for anchor $currentAnchorId")
                                    webSocket.send(okio.ByteString.of(*enterRoomBytes))
                                }
                            }
                        }
                        
                        val msg = WsMessage(cmd, dataMap, null)
                        _wsEvents.tryEmit(msg)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing binary message: ${e.message}")
                }
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code reason=$reason")
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
            }
        })
    }
    
    private fun handleHandshakeResponse(dataMap: Map<String, Any?>?) {
        if (dataMap == null) return
        
        var dynamicKey: String? = null
        
        fun searchKey(map: Map<String, Any?>) {
            for ((k, v) in map) {
                if (v is String) {
                    if (v.length == 16) { 
                        dynamicKey = v
                        Log.d(TAG, "Found candidate Key: $v")
                        return
                    }
                } else if (v is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    searchKey(v as Map<String, Any?>)
                    if (dynamicKey != null) return
                }
            }
        }
        searchKey(dataMap)
        
        if (dynamicKey != null) {
            sendLogin(dynamicKey!!)
        } else {
            Log.e(TAG, "Failed to find Dynamic Key in Handshake Response")
        }
    }
    
    private fun sendLogin(key: String) {
        val salt = "JPHJyYUJ^&*(743&%kgfXyb84d"
        val jti = "null" 
        val rawToken = jti + salt
        val token = encryptToken(rawToken, key) ?: ""
        val visitorId = getVisitorId()
        
        Log.d(TAG, "Sending Login (10001) with Key=$key Token=$token")

        val loginBytes = ProtobufParser.createLogin(10001, token, visitorId)
        currentWebSocket?.send(okio.ByteString.of(*loginBytes))
    }
    
    private fun disconnectWebSocket() {
        synchronized(connectionLock) {
            listenersCount--
            if (listenersCount <= 0) {
                listenersCount = 0
                activeRoomId = null
                currentWebSocket?.close(1000, "No listeners")
                currentWebSocket = null
            }
        }
    }

    fun getComments(roomId: String, anchorId: String): Flow<LiveComment> = callbackFlow {
        connectWebSocket(roomId, anchorId)
        
        val job = launch {
            _wsEvents.collect { msg ->
                try {
                    val dataMap = msg.data as? Map<String, Any> ?: emptyMap()
                    
                    if (dataMap.containsKey("content") && dataMap.containsKey("nickname")) {
                        val comment = LiveComment(
                            username = dataMap["nickname"] as? String ?: "User",
                            message = dataMap["content"] as? String ?: "",
                            timestamp = System.currentTimeMillis(),
                            avatarUrl = dataMap["avatar"] as? String
                        )
                        trySend(comment)
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }
        
        awaitClose { 
            job.cancel()
            disconnectWebSocket()
        }
    }

    fun getGifts(roomId: String, anchorId: String): Flow<LiveGift> = callbackFlow {
        fetchGiftList()
        connectWebSocket(roomId, anchorId)
        
        val job = launch {
            _wsEvents.collect { msg ->
                try {
                    val dataMap = msg.data as? Map<String, Any> ?: emptyMap()
                    
                    if (dataMap.containsKey("giftId")) {
                        val giftId = dataMap["giftId"].toString()
                        val count = (dataMap["giftCount"] as? Number)?.toInt() ?: 1
                        val sender = dataMap["nickname"] as? String ?: "User"
                        
                        val giftItem = giftMap[giftId]
                        if (giftItem != null) {
                            val gift = LiveGift(
                                username = sender,
                                giftName = giftItem.giftName,
                                giftIconUrl = giftItem.giftIcon,
                                giftCount = count,
                                timestamp = System.currentTimeMillis()
                            )
                            trySend(gift)
                        }
                    }
                } catch (e: Exception) { }
            }
        }
        
        awaitClose { 
            job.cancel() 
            disconnectWebSocket()
        }
    }
}
