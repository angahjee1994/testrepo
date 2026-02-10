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
        val fixedUrl = url.replace("teraboxapp.com", "terabox.com")
            .replace("1024terabox.com", "terabox.com")
            .replace("mirrobox.com", "terabox.com")
            .replace("nephobox.com", "terabox.com")
            .replace("freeterabox.com", "terabox.com")
            .replace("momot.com", "terabox.com")

        // Parse surl from url
        // Parse surl from url
        val rawSurl = if (fixedUrl.contains("surl=")) {
            fixedUrl.substringAfter("surl=").substringBefore("&")
        } else {
            fixedUrl.substringAfter("/s/").substringBefore("?").substringBefore("&")
        }
        
        // Remove '1' prefix if present for 'shorturl'
        val surl = if (rawSurl.startsWith("1")) rawSurl.substring(1) else rawSurl
        
        // Use a robust PC User Agent
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        
        // Method 1: Hit the share/list API using 'shorturl'
        val apiUrl = "https://www.terabox.com/share/list?shorturl=$surl&root=1"
        val response = app.get(apiUrl, headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to fixedUrl,
            "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"
        ))
        
        val data = response.text
        if (data.contains("\"dlink\":\"")) {
            val dlinks = Regex("\"dlink\":\"(.*?)\"").findAll(data).map { 
                it.groupValues[1].replace("\\/", "/") 
            }.filter { it.isNotEmpty() }.toList()
            
            dlinks.forEach { dlink ->
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        dlink,
                        INFER_TYPE,
                    ) {
                        this.referer = fixedUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to fixedUrl
                        )
                    }
                )
            }
        }
        
        // Method 2: Fallback to scraping the page for INITIAL_STATE if API fails or returns empty
        if (data.contains("\"dlink\":\"").not()) {
            val pageRes = app.get(fixedUrl)
            val pageData = pageRes.text
            
            // Look for any dlink in the raw page content
            val dlinkMatch = Regex("\"dlink\":\"(.*?)\"").find(pageData)?.groupValues?.get(1)?.replace("\\/", "/")
            if (dlinkMatch != null) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        this.name,
                        dlinkMatch,
                        INFER_TYPE,
                    ) {
                        this.referer = fixedUrl
                    }
                )
            }
        }
    }
}
