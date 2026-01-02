package com.asianctv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AsiancTV : MainAPI() {
    override var mainUrl = "https://asianctv.cc"
    override var name = "AsiancTV"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    // Verify mobile UA to ensure we get the structure consistent with our mobile browser debug
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

    // CloudStream doesn't have a direct override for every request easily in MainAPI without interceptor, 
    // but we can pass it in our get requests or set it globally if supported.
    // However, simplest is to just rely on default or if strictly needed, headers.
    // For now, let's just make sure our requests use it if we can, or rely on standard behaviour.
    // Actually, CloudStream's 'app' client is ok, but we can verify headers.
    
    // NOTE: CloudStream by default sends a mobile-like UA. 
    // The critical part was the SELECTORS mismatch, specifically "switch-block" removal.


    override val mainPage = mainPageOf(
        "$mainUrl/popular-movies?page=" to "Popular Movies",
        "$mainUrl/popular-dramas?page=" to "Popular Dramas",
        "$mainUrl/recently-added?page=" to "Recently Added",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("ul.list-episode-item li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-original")?.takeIf { it.isNotEmpty() } 
            ?: img?.attr("src")
            
        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.replace(" ", "+")
        val link = "$mainUrl/search?type=movies&keyword=$cleanQuery" // Updated search URL pattern from observed mobile behavior
        val document = app.get(link).document

        return document.select("ul.list-episode-item li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val img = document.selectFirst(".img img")
        val poster = img?.attr("data-original")?.takeIf { it.isNotEmpty() } 
            ?: img?.attr("src")
        
        // Description
        val description = document.select(".info").text().substringAfter("Description:").substringBefore("Country:").trim()

        val episodes = document.select("ul.list-episode-item-2 li").mapNotNull {
             val a = it.selectFirst("a") ?: return@mapNotNull null
             val epUrl = a.attr("href")
             val epName = it.selectFirst("h3.title")?.text() ?: "Episode"
             val epNum = epName.filter { it.isDigit() }.toIntOrNull()
             
             newEpisode(epUrl) {
                 this.name = epName
                 this.episode = epNum
             }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Scrape server lists from the EPISODE page
        // Added .muti_link logic (verified on mobile) and .anime_muti_link
        // Also check "li" directly if it has data-video
        val serverList = document.select(".muti_link li, .anime_muti_link li, ul.list-server-items li")
        
        serverList.forEach { server ->
            val videoUrl = server.attr("data-video")
            if (videoUrl.isNotEmpty()) {
                resolveServerLink(videoUrl, data, subtitleCallback, callback)
            }
        }
        
        // Handle iframe directly if no servers found or as backup
        val defaultIframe = document.select("iframe").attr("src")
        if (defaultIframe.isNotEmpty()) {
             resolveServerLink(defaultIframe, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun resolveServerLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = fixUrl(url)
        
        if (fixedUrl.contains("streaming.php")) {
            try {
                // Fetch with Referer is CRITICAL for AsiancTV
                val response = app.get(fixedUrl, headers = mapOf("Referer" to referer)).document
                val innerIframeSrc = response.select("iframe").attr("src")
                
                if (innerIframeSrc.isNotEmpty()) {
                     loadExtractor(fixUrl(innerIframeSrc), referer, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            loadExtractor(fixedUrl, referer, subtitleCallback, callback)
        }
    }
}
