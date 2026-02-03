package com.kingbokep

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KingBokep : MainAPI() {
    override var mainUrl = "https://kingbokep.tv"
    override var name = "KingBokep"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/indonesia/" to "Bokep Indo",
        "$mainUrl/category/viral/" to "Indo Viral",
        "$mainUrl/category/bispak/" to "Bispak",
        "$mainUrl/category/chindo/" to "Chindo",
        "$mainUrl/category/ruang-bokep/" to "Ruang Bokep",
        "$mainUrl/category/bokebhub/" to "BokebHub"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {






        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = app.get(url).document
        val home = document.select("li.video-card a.group").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").ifEmpty { this.selectFirst("span")?.text()?.trim() } ?: return null
        val href = fixUrl(this.attr("href"))
        val posterUrl = this.selectFirst("img")?.let { img ->
            img.attr("data-src").ifEmpty { img.attr("src") }
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val url = "$mainUrl/?s=$query"
        val document = app.get(url).document
        return document.select("li.video-card a.group").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document


        val ldJsonScripts = document.select("script[type=application/ld+json]")
        
        var title: String? = null
        var description: String? = null
        var poster: String? = null
        var duration: String? = null
        var tags = emptyList<String>()
        
        for (script in ldJsonScripts) {
            try {
                val json = AppUtils.parseJson<LdJsonVideo>(script.data())
                if (!json.contentUrl.isNullOrEmpty() || !json.thumbnailUrl.toString().isNullOrEmpty()) {
                    title = json.name
                    description = json.description
                    poster = when (val thumb = json.thumbnailUrl) {
                        is String -> thumb
                        is List<*> -> thumb.firstOrNull()?.toString()
                        else -> null
                    }
                    duration = json.duration
                    tags = json.keywords?.split(",")?.map { it.trim() } ?: emptyList()
                    break // Found the video object
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (title == null) {
            title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        }
        if (description == null) {
            description = document.select("meta[name=description]").attr("content")
        }
        if (poster == null) {
            poster = document.select("meta[property=og:image]").attr("content")
        }
        
        // Recommendations
        val recommendations = document.select("li.video-card a.group").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations


        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {

        val document = app.get(data).document
        val ldJsonScripts = document.select("script[type=application/ld+json]")
        
        // 1. Try JSON-LD
        for (script in ldJsonScripts) {
            try {
                val json = AppUtils.parseJson<LdJsonVideo>(script.data())
                val m3u8Url = json.contentUrl
                
                if (!m3u8Url.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            m3u8Url,
                            ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = getQualityFromName("HD")
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Try Regex Fallback for .m3u8
        try {
            val html = document.html()
            val m3u8Regex = """https?://[^"']+\.m3u8""".toRegex()
            val match = m3u8Regex.find(html)
            if (match != null) {
                val m3u8Url = match.value
                callback.invoke(
                     newExtractorLink(
                        name,
                        name,
                        m3u8Url,
                        ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class LdJsonVideo(
        @JsonProperty("name") val name: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("thumbnailUrl") val thumbnailUrl: Any?,
        @JsonProperty("contentUrl") val contentUrl: String?,
        @JsonProperty("duration") val duration: String?,
        @JsonProperty("keywords") val keywords: String?
    )
}
