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
        val surl = if (fixedUrl.contains("surl=")) {
            fixedUrl.substringAfter("surl=").substringBefore("&")
        } else {
            val s = fixedUrl.substringAfter("/s/").substringBefore("?").substringBefore("&")
            // If it's a short URL path without '1' prefix, add it if it looks like a standard surl
            if (s.startsWith("1") || s.length < 15) s else "1$s"
        }
        
        // Method 1: Hit the share/list API
        val apiUrl = "https://www.terabox.com/share/list?surl=$surl"
        val response = app.get(apiUrl, headers = mapOf(
            "User-Agent" to "LogStatistic",
            "Referer" to fixedUrl
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
                        fixedUrl,
                        INFER_TYPE,
                    )
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
                        fixedUrl,
                        INFER_TYPE,
                    )
                )
            }
        }
    }
}
