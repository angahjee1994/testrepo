package com.botol

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class Dramabox : MainAPI() {
    override var mainUrl = "https://www.dramaboxdb.com"
    private val playerApiUrl = "https://dracin-web-eight.vercel.app" 
    override var name = "Dramabox"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    )

    private fun getLangPrefix(): String {
        return when (lang) {
            "id", "in" -> "/in"
            "es" -> "/es"
            "fr" -> "/fr"
            "de" -> "/de"
            "th" -> "/th"
            "ko" -> "/kr"
            "vi" -> "/vi"
            "zh" -> "/zh"
            "en" -> "" // English is default
            else -> ""
        }
    }

    override val mainPage = mainPageOf(
        "channel/must-sees" to "Must Sees",
        "channel/trending" to "Trending",
        "channel/hidden-gems" to "Hidden Gems",
        "channel/new" to "New Arrivals"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val prefix = getLangPrefix()
        val sectionPath = request.data
        
        // Structure: https://www.dramaboxdb.com/es/channel/must-sees/2
        // English: https://www.dramaboxdb.com/channel/must-sees/2
        
        val pageSuffix = if (page > 1) "/$page" else ""
        
        // Construct URL. Ensure we don't end up with double slashes if prefix is empty.
        // If prefix is "", url is mainUrl + / + sectionPath + pageSuffix
        // If prefix is "/es", url is mainUrl + /es + / + sectionPath + pageSuffix
        
        val url = "$mainUrl$prefix/$sectionPath$pageSuffix"

        val doc = app.get(url, headers = baseHeaders).document
        
        val home = doc.select("a[href*='/movie/']").mapNotNull {
            it.toSearchResponse()
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, home, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val prefix = getLangPrefix()
        val url = "$mainUrl$prefix/search?searchValue=$query"
        val doc = app.get(url, headers = baseHeaders).document
        
        return doc.select("a[href*='/movie/']").mapNotNull {
            it.toSearchResponse()
        }
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val href = this.attr("href")
        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
        
        // Basic scraping for title, fallback to text if necessary
        // Structure is often: <a> <img class="cover"> <div class="info"> <div class="title">Title</div> </div> </a>
        val title = this.selectFirst(".title, h3, h4, span[class*='bookName'], div[class*='bookName']")?.text() 
            ?: this.selectFirst("img")?.attr("alt")
            ?: this.text()
            
        // If title is empty/null, skip
        if (title.isNullOrBlank()) return null

        val poster = this.selectFirst("img")?.attr("src")
        
        return newAnimeSearchResponse(title, fullUrl, TvType.AsianDrama) {
            this.posterUrl = poster
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = baseHeaders).document
        
        val title = doc.selectFirst("h1, .drama-title, div[class*='bookName']")?.text() ?: "Unknown"
        val poster = doc.selectFirst("img.poster, .drama-cover img, div[class*='bookImage'] img")?.attr("src") 
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = doc.selectFirst(".description, .plot, meta[name='description'], div[class*='introduction']")?.text() 
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")
        val tags = doc.select(".tags a, .genre a, div[class*='tags'] span").map { it.text() }
        
        // Recommendation handling could be added here if needed

        // ID extraction
        val bookId = Regex("""/movie/(\d+)""").find(url)?.groupValues?.get(1) 
            ?: url.trimEnd('/').substringAfterLast("/")
        
        if (bookId == null) throw ErrorLoadingException("No Book ID found")

        val episodesUrl = "$playerApiUrl/api/dramabox/allepisode/$bookId"
        val episodes = try {
            app.get(episodesUrl).parsedSafe<Array<DramaboxEpisode>>()
        } catch (e: Exception) {
            null
        }

        val epData = episodes?.map { ep ->
            val data = LoadLinksData(bookId, ep.chapterId ?: "").toJson()
            newEpisode(data) {
                this.name = ep.chapterName
                this.episode = ep.chapterIndex?.plus(1)
                this.posterUrl = ep.chapterImg
            }
        } ?: emptyList()

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, epData) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = parseJson<LoadLinksData>(data)
        val episodesUrl = "$playerApiUrl/api/dramabox/allepisode/${loadData.bookId}"
        val episodes = app.get(episodesUrl).parsedSafe<Array<DramaboxEpisode>>() ?: return false
        val chapter = episodes.find { it.chapterId == loadData.chapterId } ?: return false

        chapter.cdnList?.forEach { cdn ->
            cdn.videoPathList?.forEach { video ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        video.videoPath ?: return@forEach,
                        INFER_TYPE,
                    ) {
                        this.quality = getQualityFromName(video.quality.toString())
                    }
                )
            }
        }
        return true
    }

    data class LoadLinksData(
        @JsonProperty("bookId") val bookId: String,
        @JsonProperty("chapterId") val chapterId: String
    )

    data class DramaboxEpisode(
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("chapterImg") val chapterImg: String? = null,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null,
    )

    data class CdnItem(
        @JsonProperty("cdnDomain") val cdnDomain: String? = null,
        @JsonProperty("videoPathList") val videoPathList: List<VideoPath>? = null,
    )

    data class VideoPath(
        @JsonProperty("quality") val quality: Int? = null,
        @JsonProperty("videoPath") val videoPath: String? = null,
    )
}
