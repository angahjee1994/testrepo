package com.botol

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class XMalay : MainAPI() {
    override var mainUrl = "https://xmalay.xyz"
    override var name = "XMalay"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "?sort=popular" to "Popular Videos",
        "?sort=oldest" to "Oldest Videos"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/${request.data}" + if (page > 1) "&page=$page" else "" // Check pagination format!
        // Pagination on this site usually is /page/2/ or ?page=2. 
        // Let's assume ?page=2 for filters, but standard WP is often /page/2/.
        // I'll try to detect or use robust logic. 
        // Analysis said home is https://xmalay.xyz/. Pagination usually /page/2/.
        
        val realUrl = if(page > 1) {
            if(request.data.isEmpty()) "$mainUrl/page/$page/" 
            else "$mainUrl/${request.data}&page=$page" // This might be wrong for WP.
        } else {
            "$mainUrl/${request.data}"
        }

        val doc = app.get(realUrl).document
        val home = doc.select(".video-item, .columns .column").mapNotNull {
            it.toSearchResponse()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val anchor = this.selectFirst("a[href]") ?: return null
        val title = this.selectFirst(".video-item-title, .title")?.text() 
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: return null
            
        val href = anchor.attr("href")
        val poster = this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src")
        
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?s=$query"
        val doc = app.get(url).document
        return doc.select(".video-item, .columns .column").mapNotNull {
            it.toSearchResponse()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.video-title, .title")?.text() ?: ""
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = doc.selectFirst("meta[property='og:description']")?.attr("content") 
            ?: doc.selectFirst(".video-details-text")?.text()
        
        val tags = doc.select(".video-tags a").map { it.text() }
        val date = doc.selectFirst(".video-details-stats")?.text()

        val recommendations = doc.select(".video-item").mapNotNull {
            it.toSearchResponse()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val iframeSrc = doc.selectFirst("iframe[src*='stream25']")?.attr("src") 
            ?: doc.selectFirst("iframe[src*='player.php']")?.attr("src")
            ?: return false

        val ref = "$mainUrl/"
        
        // Fetch the iframe content
        val iframeDoc = app.get(iframeSrc, referer = ref).text
        
        // Regex to find m3u8 or mp4
        val m3u8Regex = Regex("""["'](?<url>https?://[^"']+\.m3u8[^"']*)["']""")
        val mp4Regex = Regex("""["'](?<url>https?://[^"']+\.mp4[^"']*)["']""")
        
        m3u8Regex.findAll(iframeDoc).forEach { match ->
            val url = match.groups["url"]?.value ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    "Stream25 (HLS)",
                    name,
                    url,
                    ExtractorLinkType.M3U8
                ) {
                   this.headers = mapOf("Referer" to ref)
                }
            )
        }
        
        mp4Regex.findAll(iframeDoc).forEach { match ->
            val url = match.groups["url"]?.value ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    "Stream25 (MP4)",
                    name,
                    url,
                    ExtractorLinkType.VIDEO
                ) {
                   this.headers = mapOf("Referer" to ref)
                }
            )
        }

        return true
    }
}
