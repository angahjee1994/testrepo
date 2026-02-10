package com.hot51

import android.util.Log

object ProtobufParser {
    
    fun parseMessage(data: ByteArray): Pair<Int?, Map<String, Any?>?> {
        try {
            var pos = 0
            var cmd: Int? = null
            var chatData: Map<String, Any?>? = null
            var giftData: Map<String, Any?>? = null
            
            while (pos < data.size) {
                val (tag, wireType, newPos) = readTag(data, pos) ?: break
                pos = newPos
                
                when (tag) {
                    1 -> {
                        val (value, nextPos) = readVarint(data, pos)
                        cmd = value.toInt()
                        pos = nextPos
                    }
                    60 -> {
                        val (length, nextPos) = readVarint(data, pos)
                        val messageData = data.copyOfRange(nextPos, nextPos + length.toInt())
                        chatData = parseChatResult(messageData)
                        pos = nextPos + length.toInt()
                    }
                    72 -> {
                        val (length, nextPos) = readVarint(data, pos)
                        val messageData = data.copyOfRange(nextPos, nextPos + length.toInt())
                        giftData = parseGiftResult(messageData)
                        pos = nextPos + length.toInt()
                    }
                    else -> {
                        pos = skipField(data, pos, wireType)
                    }
                }
            }
            
            val dataMap = when {
                chatData != null -> chatData
                giftData != null -> giftData
                else -> null
            }
            return Pair(cmd, dataMap)
        } catch (e: Exception) {
            Log.e("Hot51Proto", "Parse error: ${e.message}")
            return Pair(null, null)
        }
    }
    
    private fun parseChatResult(data: ByteArray): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        var pos = 0
        
        while (pos < data.size) {
            val (tag, wireType, newPos) = readTag(data, pos) ?: break
            pos = newPos
            
            when (tag) {
                4 -> {
                    val (value, nextPos) = readString(data, pos)
                    result["nickname"] = value
                    pos = nextPos
                }
                6 -> {
                    val (value, nextPos) = readString(data, pos)
                    result["content"] = value
                    pos = nextPos
                }
                7 -> {
                    val (value, nextPos) = readString(data, pos)
                    result["avatar"] = value
                    pos = nextPos
                }
                else -> {
                    pos = skipField(data, pos, wireType)
                }
            }
        }
        return result
    }
    
    private fun parseGiftResult(data: ByteArray): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        var pos = 0
        
        while (pos < data.size) {
            val (tag, wireType, newPos) = readTag(data, pos) ?: break
            pos = newPos
            
            when (tag) {
                7 -> {
                    val (value, nextPos) = readString(data, pos)
                    result["giftId"] = value
                    pos = nextPos
                }
                9 -> {
                    val (value, nextPos) = readString(data, pos)
                    result["nickname"] = value
                    pos = nextPos
                }
                13 -> {
                    val (value, nextPos) = readVarint(data, pos)
                    result["giftCount"] = value.toInt()
                    pos = nextPos
                }
                14 -> {
                    val (length, nextPos) = readVarint(data, pos)
                    val giftInfoData = data.copyOfRange(nextPos, nextPos + length.toInt())
                    result["giftInfo"] = parseGiftInfo(giftInfoData)
                    pos = nextPos + length.toInt()
                }
                else -> {
                    pos = skipField(data, pos, wireType)
                }
            }
        }
        return result
    }
    
    private fun parseGiftInfo(data: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var pos = 0
        
        while (pos < data.size) {
            val (tag, wireType, newPos) = readTag(data, pos) ?: break
            pos = newPos
            
            when (tag) {
                2 -> {
                    val (value, nextPos) = readString(data, pos)
                    result["giftName"] = value
                    pos = nextPos
                }
                else -> {
                    pos = skipField(data, pos, wireType)
                }
            }
        }
        return result
    }
    
    private fun readTag(data: ByteArray, pos: Int): Triple<Int, Int, Int>? {
        if (pos >= data.size) return null
        val (value, newPos) = readVarint(data, pos)
        val tag = (value shr 3).toInt()
        val wireType = (value and 0x7).toInt()
        return Triple(tag, wireType, newPos)
    }
    
    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var pos = start
        var result = 0L
        var shift = 0
        
        while (pos < data.size) {
            val b = data[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return Pair(result, pos)
    }
    
    private fun readString(data: ByteArray, pos: Int): Pair<String, Int> {
        val (length, newPos) = readVarint(data, pos)
        val str = String(data, newPos, length.toInt(), Charsets.UTF_8)
        return Pair(str, newPos + length.toInt())
    }
    
    private fun skipField(data: ByteArray, pos: Int, wireType: Int): Int {
        return when (wireType) {
            0 -> readVarint(data, pos).second
            2 -> {
                val (length, newPos) = readVarint(data, pos)
                newPos + length.toInt()
            }
            else -> pos
        }
    }

    fun createHandshake(cmd: Int): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        writeTag(stream, 1, 0)
        writeVarint(stream, cmd.toLong())
        return stream.toByteArray()
    }

    fun createLogin(
        cmd: Int, 
        token: String, 
        visitorId: String,
        areaCode: String = "MY",
        locale: String = "ENU"
    ): ByteArray {
         val stream = java.io.ByteArrayOutputStream()
         writeTag(stream, 1, 0)
         writeVarint(stream, cmd.toLong())
         
         val loginStream = java.io.ByteArrayOutputStream()
         writeString(loginStream, 1, areaCode)
         writeString(loginStream, 2, locale)
         writeString(loginStream, 3, token)
         writeInt32(loginStream, 5, 7) // type=7 (Guest)
         writeInt32(loginStream, 6, 501) // merchantId
         writeString(loginStream, 7, visitorId)
         writeInt32(loginStream, 9, 3) // platform=3 (H5)
         
         writeTag(stream, 15, 2)
         writeVarint(stream, loginStream.size().toLong())
         stream.write(loginStream.toByteArray())
         
         return stream.toByteArray()
    }

    fun createEnterRoom(cmd: Int, anchorId: String): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        writeTag(stream, 1, 0)
        writeVarint(stream, cmd.toLong())
        
        val bodyStream = java.io.ByteArrayOutputStream()
        writeString(bodyStream, 1, anchorId)
        
        writeTag(stream, 19, 2)
        writeVarint(stream, bodyStream.size().toLong())
        stream.write(bodyStream.toByteArray())
        
        return stream.toByteArray()
    }

    private fun writeTag(stream: java.io.ByteArrayOutputStream, fieldNumber: Int, wireType: Int) {
        val key = (fieldNumber shl 3) or wireType
        writeVarint(stream, key.toLong())
    }

    private fun writeVarint(stream: java.io.ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            if ((v and 0x7F.inv()) == 0L) {
                stream.write(v.toInt())
                return
            } else {
                stream.write((v.toInt() and 0x7F) or 0x80)
                v = v ushr 7
            }
        }
    }
    
    private fun writeString(stream: java.io.ByteArrayOutputStream, fieldNumber: Int, value: String) {
        if (value.isEmpty()) return
        writeTag(stream, fieldNumber, 2)
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(stream, bytes.size.toLong())
        stream.write(bytes)
    }
    
    private fun writeInt32(stream: java.io.ByteArrayOutputStream, fieldNumber: Int, value: Int) {
        if (value == 0) return
        writeTag(stream, fieldNumber, 0)
        writeVarint(stream, value.toLong())
    }
}
