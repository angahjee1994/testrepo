package com.bokepindoh

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class BokepIndoh : MainAPI() {
    override var mainUrl = "https://bokepindoh.makeup"
    override var name = "BokepIndoh"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        mainUrl to "Latest Updates",
        "$mainUrl/bokep-indo/" to "Bokep Indo",
        "$mainUrl/video-bokep-jepang-sub-indo/" to "Bokep Jepang",
        "$mainUrl/bokep-barat/" to "Bokep Barat",
        "$mainUrl/bokep-viral/" to "Bokep Viral",
        "$mainUrl/nonton-bokep-jilbab/" to "Bokep Jilbab"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        
        val home = document.select(".videos-list article").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(HomePageList(request.name, home, true))
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

        val iframeSrc = document.select("iframe").firstNotNullOfOrNull { 
            val src = it.attr("src")
            if (src.contains("luluvid", ignoreCase = true)) src else null 
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
            
            var m3u8Url: String? = null
            
            val scripts = document.select("script")
            val m3u8Regex = """https?://[^"']+\.m3u8[^"']*""".toRegex()
            
            for (script in scripts) {
                val content = script.html()
                val match = m3u8Regex.find(content)
                if (match != null) {
                    m3u8Url = match.value
                    break
                }
            }
            
            if (m3u8Url != null) {
                 callback.invoke(
                    newExtractorLink(
                        name,
                        "LuluStream",
                        m3u8Url,
                        ExtractorLinkType.M3U8
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
}
