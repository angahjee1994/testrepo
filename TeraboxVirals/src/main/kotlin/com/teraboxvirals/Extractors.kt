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
        
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val foundDlinks = mutableSetOf<String>()
        
        val domains = listOf("www.terabox.com", "www.1024tera.com")
        
        for (domain in domains) {
            val apiReferer = "https://$domain/"
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to apiReferer,
                "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"
            )
            
            suspend fun scanDir(dirPath: String, depth: Int = 0) {
                if (depth > 5) return
                val root = if (dirPath == "/") "1" else "0"
                val apiUrl = "https://$domain/share/list?shorturl=$surl&dir=${java.net.URLEncoder.encode(dirPath, "UTF-8")}&root=$root&web=1&channel=dubox&clienttype=0&app_id=250528"
                try {
                    val res = app.get(apiUrl, headers = headers).text
                    if (!res.contains("\"errno\":0")) return
                    
                    Regex("\"dlink\":\\s*\"(.*?)\"").findAll(res).forEach { match ->
                        val dlink = match.groupValues[1].replace("\\/", "/")
                        if (dlink.isNotEmpty()) foundDlinks.add(dlink)
                    }
                    
                    val items = res.split("\"fs_id\":")
                    items.drop(1).forEach { chunk ->
                        val isDir = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: "0"
                        val path = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (isDir == "1" && !path.isNullOrEmpty() && path != dirPath) {
                            scanDir(path, depth + 1)
                        }
                    }
                } catch (e: Exception) {
                    println("Extractor scan error ($domain $dirPath): ${e.message}")
                }
            }
            
            scanDir("/")
            if (foundDlinks.isNotEmpty()) break
        }
        
        foundDlinks.forEach { dlink ->
            callback.invoke(
                newExtractorLink(
                    this.name,
                    this.name,
                    dlink,
                    INFER_TYPE,
                ) {
                    this.referer = url
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to url
                    )
                }
            )
        }
        
        if (foundDlinks.isEmpty()) {
            println("No dlinks found via API, trying page scrape")
            try {
                val pageRes = app.get(url, headers = mapOf("User-Agent" to userAgent)).text
                Regex("\"dlink\":\"(.*?)\"").findAll(pageRes).forEach { match ->
                    val dlink = match.groupValues[1].replace("\\/", "/")
                    if (dlink.isNotEmpty()) {
                        callback.invoke(
                            newExtractorLink(
                                this.name,
                                this.name,
                                dlink,
                                INFER_TYPE,
                            ) {
                                this.referer = url
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                println("Page scrape error: ${e.message}")
            }
        }
    }
}

