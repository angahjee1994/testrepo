package com.teraboxvirals

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE

class Terabox : ExtractorApi() {
    override val name = "Terabox"
    override val mainUrl = "https://www.terabox.com"
    override val requiresReferer = false

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "ts")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val rawSurl = if (url.contains("surl=")) {
            url.substringAfter("surl=").substringBefore("&")
        } else {
            url.substringAfter("/s/").substringBefore("?").substringBefore("&")
        }
        val surl = if (rawSurl.startsWith("1")) rawSurl.substring(1) else rawSurl
        
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        
        val domain = if (url.contains("1024tera")) "www.1024tera.com" else "www.terabox.com"

        val browserId = buildString {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            repeat(48) { append(chars.random()) }
            append("=")
        }

        val initHeaders = mapOf(
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "accept-language" to "en-US,en;q=0.9",
            "user-agent" to userAgent,
            "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "document",
            "sec-fetch-mode" to "navigate",
            "sec-fetch-site" to "none",
            "sec-fetch-user" to "?1",
            "upgrade-insecure-requests" to "1",
            "cookie" to "browserid=$browserId; lang=en"
        )

        var sharePageResponse: com.lagradost.nicehttp.NiceResponse? = null
        val sharePageUrl = "https://$domain/sharing/link?surl=$surl"
        for (attempt in 1..3) {
            val res = app.get(sharePageUrl, headers = initHeaders)
            if (res.text.contains("need verify")) continue
            sharePageResponse = res
            break
        }
        if (sharePageResponse == null) return

        val pageRes = sharePageResponse.text
        val responseCookies = sharePageResponse.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }
            .map { it.second.substringBefore(";") }
        val cookieMap = mutableMapOf("browserid" to browserId, "lang" to "en")
        responseCookies.forEach { cookie ->
            val kv = cookie.split("=", limit = 2)
            if (kv.size == 2) cookieMap[kv[0].trim()] = kv[1].trim()
        }

        val jsToken = Regex("fn%28%22(.*?)%22%29").find(pageRes)?.groupValues?.get(1)
            ?: Regex("fn\\(\"(.*?)\"\\)").find(pageRes)?.groupValues?.get(1)
        if (jsToken != null) cookieMap["ndus"] = jsToken

        val randsk = cookieMap["TSID"]?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            ?: cookieMap["randsk"]?.let { java.net.URLEncoder.encode(it, "UTF-8") }
            ?: ""

        var uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1) ?: ""
        var shareid = Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1)
            ?: Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1) ?: ""
        val sign = Regex("\"sign\":\\s*\"([a-f0-9]+)\"").find(pageRes)?.groupValues?.get(1)
        val timestamp = Regex("\"timestamp\":\\s*(\\d+)").find(pageRes)?.groupValues?.get(1)

        val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val headers = mapOf(
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "en-US,en;q=0.9",
            "user-agent" to userAgent,
            "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "referer" to "https://$domain/sharing/link?surl=$surl",
            "cookie" to cookieString
        )

        val foundDlinks = mutableSetOf<String>()
        val foundVideoFsids = mutableListOf<String>()
        val listBaseParams = "app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}&shorturl=$surl&uk=$uk&shareid=$shareid"

        suspend fun scanDir(dirPath: String, depth: Int = 0) {
            if (depth > 5) return
            val root = if (dirPath == "/") "1" else "0"
            val randskParam = if (dirPath != "/") "&randsk=$randsk" else ""
            val apiUrl = "https://$domain/share/list?$listBaseParams&dir=${java.net.URLEncoder.encode(dirPath, "UTF-8")}&root=$root$randskParam"
            try {
                val res = app.get(apiUrl, headers = headers).text
                if (!res.contains("\"errno\":0") && !res.contains("\"list\":[")) return

                if (uk.isEmpty()) uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(res)?.groupValues?.get(1) ?: ""
                if (shareid.isEmpty()) shareid = Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(res)?.groupValues?.get(1)
                    ?: Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(res)?.groupValues?.get(1) ?: ""

                Regex("\"dlink\":\\s*\"(.*?)\"").findAll(res).forEach { match ->
                    val dlink = match.groupValues[1].replace("\\/", "/")
                    if (dlink.isNotEmpty()) foundDlinks.add(dlink)
                }

                val items = res.split("\"fs_id\":")
                items.drop(1).forEach { chunk ->
                    val isDir = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: "0"
                    val path = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                    val itemFsid = Regex("^\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1)
                    val filename = Regex("\"server_filename\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1) ?: ""
                    if (isDir == "1" && !path.isNullOrEmpty() && path != dirPath) {
                        scanDir(path, depth + 1)
                    } else if (itemFsid != null) {
                        val ext = filename.substringAfterLast(".", "").lowercase()
                        if (videoExtensions.contains(ext)) foundVideoFsids.add(itemFsid)
                    }
                }
            } catch (_: Exception) {}
        }

        scanDir("/")

        foundDlinks.forEach { dlink ->
            callback.invoke(
                newExtractorLink(this.name, this.name, dlink, INFER_TYPE) {
                    this.referer = url
                    this.headers = headers
                }
            )
        }

        if (foundDlinks.isEmpty() && sign != null && timestamp != null && foundVideoFsids.isNotEmpty()) {
            for (fsid in foundVideoFsids) {
                for (streamType in listOf("M3U8_AUTO_480", "M3U8_AUTO_720", "M3U8_AUTO_240", "M3U8_FLV_264_480")) {
                    val streamUrl = "https://$domain/share/streaming?uk=$uk&shareid=$shareid&type=$streamType&fid=$fsid&sign=$sign&timestamp=$timestamp&app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}"
                    try {
                        val streamRes = app.get(streamUrl, headers = headers).text
                        if (streamRes.contains("need verify") || streamRes.contains("\"errno\":400141")) continue
                        if (streamRes.contains("\"errno\"") && !streamRes.contains("\"errno\":0")) continue

                        if (streamRes.trimStart().startsWith("#EXTM3U")) {
                            callback.invoke(
                                newExtractorLink(this.name, "$name $streamType", streamUrl, INFER_TYPE) {
                                    this.headers = headers
                                }
                            )
                            return
                        }

                        val m3u8Link = Regex("\"lurl\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            ?: Regex("\"mlink\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            ?: Regex("(https?://[^\"]+\\.m3u8[^\"]*)").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (m3u8Link != null) {
                            callback.invoke(
                                newExtractorLink(this.name, "$name $streamType", m3u8Link, INFER_TYPE) {
                                    this.headers = headers
                                }
                            )
                            return
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
