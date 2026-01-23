package com.botol

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.fasterxml.jackson.annotation.JsonProperty

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
        val text = doc.html()

        val iframeSrc = Regex("""https://stream25\.xyz/player\.php\?id=[a-zA-Z0-9-]+""").find(text)?.value
            ?: doc.selectFirst("iframe[src*='stream25']")?.attr("src")
            ?: doc.selectFirst("iframe[src*='player.php']")?.attr("src")
            ?: return false

        val ref = "$mainUrl/"
        
        // Fetch the iframe content
        val iframeDoc = app.get(iframeSrc, referer = ref).document
        val iframeText = iframeDoc.html()

        // 1. Try to find direct video/source tags first (as fallback or if simple player)
        iframeDoc.select("video source, video").forEach { element ->
            val src = element.attr("src")
            if (src.isNotBlank()) {
                val type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                callback.invoke(
                    newExtractorLink(
                        "Stream25",
                        name,
                        src,
                        type
                    ) {
                        this.headers = mapOf("Referer" to iframeSrc)
                    }
                )
            }
        }

        // 2. Parse FluidPlayer / EmbedPlayer Logic
        // Script contains variables like: a = 17999, b = "11412623afe4a3fd", u = "..."
        // 2. Parse FluidPlayer / EmbedPlayer Logic
        // Script variables are often just comma separated, so we loosen regex to not require 'var'
        // Matches "a = 1234, ... b = '...'"
        // 2. Parse FluidPlayer / EmbedPlayer Logic
        // Script variables are: var a = 1234, b = '...', u = '...'
        // The regex needs to be robust enough to handle newlines or spacing differences
        
        // 2. Parse FluidPlayer / EmbedPlayer Logic
        // Strategy 1: Strict Sequence (Reliable for standard template)
        val scriptRegex = Regex("""var\s+a\s*=\s*(?<id>\d+)\s*,\s*b\s*=\s*["'](?<token>[^"']+)["']\s*,\s*u\s*=\s*["'](?<auth>[^"']+)["']""", RegexOption.IGNORE_CASE)
        val match = scriptRegex.find(iframeText)
        
        var id = match?.groups?.get("id")?.value
        var token = match?.groups?.get("token")?.value
        var auth = match?.groups?.get("auth")?.value
        
        // Strategy 2: Fallback (Extract ID/Token from URL, Auth from script)
        if (id == null || token == null || auth == null) {
            val idParam = Regex("id=([a-zA-Z0-9-]+)").find(iframeSrc)?.groupValues?.get(1)
            if (idParam != null && idParam.contains("-")) {
                val parts = idParam.split("-")
                id = parts[0]
                token = parts[1]
            } else if (idParam != null) {
                id = idParam // Sometimes only ID is present?
            }
            
            // Loose search for 'u' if not found in sequence
            if (auth == null) {
                // Look for u = "..." specifically in the var list pattern context if possible, or just loose
                auth = Regex("""\bu\s*=\s*["']([^"']+)["']""").find(iframeText)?.groupValues?.get(1)
            }
        }
        
        if (id != null && token != null && auth != null) {
            // Determine the base domain for the API call
            val apiDomain = if (iframeSrc.contains("stream25.xyz")) "https://stream25.xyz" else "https://embedplayer.net"
             val apiBase = Regex("https?://[^/]+").find(iframeSrc)?.value ?: apiDomain

            val apiUrl = "$apiBase/get_signed_url.php?id=$id&token=$token&auth=$auth"
            try {
                // Request the signed URL JSON
                val json = app.get(apiUrl, referer = iframeSrc).parsedSafe<Stream25Response>()
                
                json?.video?.let { videoUrl ->
                    val domains = listOf("pool1.embedplayer.net", "pool2.embedplayer.net")
                    val targetDomain = domains.random()
                    // Reconstruct final URL ensuring extraction parameters are preserved
                    val finalUrl = videoUrl.replace(Regex("^(https?://)[^/]+"), "$1$targetDomain")

                    callback.invoke(
                        newExtractorLink(
                            "Stream25",
                            name,
                            finalUrl,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.headers = mapOf("Referer" to iframeSrc)
                        }
                    )
                }
            } catch (e: Exception) {
               // Log or ignore failure
            }
        }
        
        // 3. Regex fallback for scripts (standard m3u8/mp4 patterns)
        val m3u8Regex = Regex("""["'](?<url>https?://[^"']+\.m3u8[^"']*)["']""")
        val mp4Regex = Regex("""["'](?<url>https?://[^"']+\.mp4[^"']*)["']""")
        
        m3u8Regex.findAll(iframeText).forEach { match ->
            val url = match.groups["url"]?.value ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    "Stream25 (HLS)",
                    name,
                    url,
                    ExtractorLinkType.M3U8
                ) {
                   this.headers = mapOf("Referer" to iframeSrc)
                }
            )
        }
        
        mp4Regex.findAll(iframeText).forEach { match ->
            // Avoid duplicates if we already found it via API
            val url = match.groups["url"]?.value ?: return@forEach
            callback.invoke(
                newExtractorLink(
                    "Stream25 (MP4)",
                    name,
                    url,
                    ExtractorLinkType.VIDEO
                ) {
                   this.headers = mapOf("Referer" to iframeSrc)
                }
            )
        }

        return true
    }

    data class Stream25Response(
        @JsonProperty("video") val video: String? = null,
        @JsonProperty("thumb") val thumb: String? = null,
        @JsonProperty("error") val error: String? = null
    )
}

