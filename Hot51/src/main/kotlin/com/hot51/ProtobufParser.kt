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
}
