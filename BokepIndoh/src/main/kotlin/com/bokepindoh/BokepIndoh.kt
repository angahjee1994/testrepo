package com.bokepindoh

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.JsUnpacker
import org.jsoup.nodes.Element

class BokepIndoh : MainAPI() {
    override var mainUrl = "https://bokepindoh.makeup"
    override var name = "BokepIndoh"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/category/bokep-indo/" to "Bokep Indo"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/page/$page/"
        val document = app.get(url).document
        
        // Select articles from the list
        val home = document.select(".videos-list article").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(HomePageList(request.name, home, home.isNotEmpty()))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val title = aTag.attr("title").trim()
        val href = fixUrl(aTag.attr("href"))
        
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select(".videos-list article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1.post-title")?.text()?.trim() 
            ?: document.selectFirst("title")?.text()?.trim() ?: "Unknown"
            
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
        
        val tags = document.select(".post-tags a").map { it.text() }
        val recommendations = document.select(".related-posts article").mapNotNull { it.toSearchResult() }
        // Find Luluvid or Bebasnonton Iframe
        val iframeSrc = document.select("iframe").firstNotNullOfOrNull { 
            val src = it.attr("src")
            if (src.contains("luluvid", ignoreCase = true) || src.contains("bebasnonton", ignoreCase = true)) src else null 
        }

        if (iframeSrc == null) return null

        return newMovieLoadResponse(title, url, TvType.NSFW, iframeSrc) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        
        try {
            val document = app.get(data).document
            
            var videoUrl: String? = null
            
            val scripts = document.select("script")
            // Regex to match .m3u8 or .mp4 links (handle escaped slashes)
            val videoRegex = """https?:\\?\/\\?\/[^"']+\.(m3u8|mp4)(?:[^"']*)""".toRegex()
            
            for (script in scripts) {
                // Trim content to ensure Unpacker works if it expects strict start
                val content = script.html().trim()
                
                // 1. Try direct match
                var match = videoRegex.find(content)
                if (match != null) {
                    videoUrl = match.value.replace("\\/", "/") // Fix escaped slashes
                    break
                }
                
                // 2. Try unpacking if packed
                try {
                    val unpacked = getPacked(content)
                    if (unpacked != null) {
                        match = videoRegex.find(unpacked)
                        if (match != null) {
                            videoUrl = match.value.replace("\\/", "/")
                            break
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            if (videoUrl != null) {
                val name = "BokepIndoh"
                val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                
                 callback.invoke(
                    newExtractorLink(
                        this.name,
                        name,
                        videoUrl,
                        type
                    ) {
                        this.referer = data
                        this.quality = getQualityFromName("HD")
                    }
                )
                return true
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private fun getPacked(script: String): String? {
        val trimmed = script.trim()
        if (!trimmed.contains("eval(function(p,a,c,k,e,d)")) return null
        return JsUnpacker(trimmed).unpack()
    }
}
