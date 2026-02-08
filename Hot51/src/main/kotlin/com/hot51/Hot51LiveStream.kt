package com.hot51

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
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

class Hot51LiveStream(val app: com.lagradost.nicehttp.Requests) {

    // Data classes for API responses
    data class RoomInfoResponse(@JsonProperty("data") val data: RoomInfoData)
    data class RoomInfoData(
        @JsonProperty("wsu") val wsu: String?, 
        @JsonProperty("atr") val atr: String?,
        @JsonProperty("lid") val lid: String?
    )

    data class GiftListResponse(@JsonProperty("data") val data: List<GiftItem>?)
    data class GiftItem(
        @JsonProperty("id") val id: String,
        @JsonProperty("giftName") val giftName: String,
        @JsonProperty("giftIcon") val giftIcon: String,
        @JsonProperty("price") val price: Int
    )

    // Simplified WebSocket message structure
    data class WsMessage(@JsonProperty("cmd") val cmd: Int?, @JsonProperty("data") val data: Any?)
    data class WsChatData(
        @JsonProperty("nickname") val nickname: String?, 
        @JsonProperty("avatar") val avatar: String?,
        @JsonProperty("content") val content: String?
    )
    data class WsGiftData(
        @JsonProperty("nickname") val nickname: String?,
        @JsonProperty("giftId") val giftId: String?,
        @JsonProperty("giftCount") val giftCount: Int?
    )

    // Cache explicitly fetched gift list
    private var giftMap: Map<String, GiftItem> = emptyMap()

    suspend fun fetchRoomInfo(roomId: String, anchorId: String): RoomInfoData? {
        val url = "https://api.fnccdn.com/501/api/plr/zbliv/h5/v3/public/live/room-info"
        val payload = mapOf("roomId" to roomId, "anchorId" to anchorId, "merchantId" to 501)
        return try {
            app.post(url, json = payload).parsedSafe<RoomInfoResponse>()?.data
        } catch (e: Exception) {
            Log.e("Hot51LiveStream", "Error fetching room info: ${e.message}")
            null
        }
    }

    suspend fun fetchGiftList() {
        if (giftMap.isNotEmpty()) return
        val url = "https://api.fnccdn.com/501/api/plr/live/gift/v2/get/list?merchantId=501"
        try {
            val response = app.get(url).parsedSafe<GiftListResponse>()
            giftMap = response?.data?.associateBy { it.id } ?: emptyMap()
        } catch (e: Exception) {
            Log.e("Hot51LiveStream", "Error fetching gift list: ${e.message}")
        }
    }
    

    // Encryption keys extracted from Hot51.kt (Updated from website inspection)
    private val decryptKey = "9216345272696329"
    private val decryptIv = "0507060302080104"

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
            Log.e("Hot51LiveStream", "Decryption failed: ${e.message}")
            return null
        }
    }


    // Local definition since extension doesn't see Core's MainAPI update
    data class LiveComment(
        val username: String,
        val message: String,
        val timestamp: Long,
        val avatarUrl: String? = null,
    )

    data class LiveGift(
        val senderName: String,
        val giftName: String,
        val giftIconUrl: String,
        val count: Int,
        val timestamp: Long,
        val animationUrl: String? = null,
    )

    // Shared Flow for WebSocket events to avoid multiple connections
    private val _wsEvents = MutableSharedFlow<WsMessage>(
        replay = 0, 
        extraBufferCapacity = 64, 
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    
    // Keep track of current connection
    private var currentWebSocket: WebSocket? = null
    private var connectionJob: Job? = null
    private var activeRoomId: String? = null
    private var listenersCount = 0
    private val connectionLock = Any() // Simple synchronization
    
    private suspend fun connectWebSocket(roomId: String, anchorId: String) {
        synchronized(connectionLock) {
            if (activeRoomId != roomId) {
                // Changing room, close old one
                currentWebSocket?.close(1000, "Switching room")
                currentWebSocket = null
                _wsEvents.tryEmit(WsMessage(null, null)) // Clear buffer? No, just let it be
                listenersCount = 0
                activeRoomId = roomId
            }
            listenersCount++
        }
        
        if (currentWebSocket != null) return
        
        val roomInfo = fetchRoomInfo(roomId, anchorId) ?: return
        val wsu = decryptWsu(roomInfo.wsu) ?: roomInfo.wsu
        
        if (wsu == null) {
            Log.e("Hot51", "Failed to get WSU for room $roomId")
            return
        }
        
        // Connect
        val request = okhttp3.Request.Builder().url(wsu).build()
        currentWebSocket = app.baseClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = tryParseJson<WsMessage>(text)
                    if (msg != null) _wsEvents.tryEmit(msg)
                } catch (e: Exception) {}
            }
            
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // If closed by server, we might want to reconnect or just null it
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Handle failure
            }
        })
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
    
    private fun startHeartbeat(webSocket: WebSocket) {
        // We can't easily launch a coroutine from here without a scope.
        // But since we are in Hot51LiveStream which is a class, we don't have a scope.
        // We can create a thread or use GlobalScope (bad practice but effective for this simple loop).
        // Better: use the 'connectionJob' we defined.
        
        // Actually, let's just use a Thread for the heartbeat to be simple and robust against scope cancellation issues
        // or rely on the fact that if the app dies, the thread dies.
        Thread {
            try {
                while (currentWebSocket != null) {
                    // Send heartbeat cmd: 0 or 999
                    // JSON format: {"cmd":0,"data":{}} ??
                    // Based on typical implementation for these apps:
                    val heartbeat = "{\"cmd\":0}" 
                    webSocket.send(heartbeat)
                    Thread.sleep(10000) // 10 seconds
                }
            } catch (e: Exception) {
                // End loop
            }
        }.start()
    }

    fun getComments(roomId: String, anchorId: String): Flow<LiveComment> = callbackFlow {
        // Connect if needed
        connectWebSocket(roomId, anchorId)
        
        val job = launch {
            _wsEvents.collect { msg ->
                try {
                    // Log the raw data for debugging
                    // Log.d("Hot51", "WS RAW: ${msg.data}")
                    
                    val dataMap = msg.data as? Map<String, Any> ?: emptyMap()
                    val cmd = msg.cmd
                    
                    // CMD 20 = Chat? 
                    // Let's look for known fields.
                    if (dataMap.containsKey("content") && dataMap.containsKey("nickname")) {
                        val comment = LiveComment(
                            username = dataMap["nickname"] as? String ?: "User",
                            message = dataMap["content"] as? String ?: "",
                            avatarUrl = dataMap["avatar"] as? String,
                            timestamp = System.currentTimeMillis()
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
                    
                    // Identify Gift
                    if (dataMap.containsKey("giftId")) {
                        val giftId = dataMap["giftId"].toString()
                        val count = (dataMap["giftCount"] as? Number)?.toInt() ?: 1
                        val sender = dataMap["nickname"] as? String ?: "User"
                        
                        val giftItem = giftMap[giftId]
                        if (giftItem != null) {
                            val gift = LiveGift(
                                senderName = sender,
                                giftName = giftItem.giftName,
                                giftIconUrl = giftItem.giftIcon,
                                count = count,
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
