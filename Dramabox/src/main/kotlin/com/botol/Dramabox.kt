package com.botol

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Document
import kotlinx.coroutines.delay

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
            "id", "in", "id-ID" -> "/in"
            "es", "es-ES" -> "/es"
            "fr", "fr-FR" -> "/fr"
            "de", "de-DE" -> "/de"
            "th", "th-TH" -> "/th"
            "ko", "ko-KR" -> "/kr"
            "vi", "vi-VN" -> "/vi"
            "zh", "zh-CN", "zh-TW" -> "/zh"
            else -> "" 
        }
    }

    override val mainPage = mainPageOf(
        "channel/must-sees" to "Must Sees",
        "channel/trending" to "Trending",
        "channel/hidden-gems" to "Hidden Gems"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val prefix = getLangPrefix()
        val sectionPath = request.data
        val pageSuffix = if (page > 1) "/$page" else ""
        
        val url = if (sectionPath.isEmpty()) {
            "$mainUrl$prefix/"
        } else {
            "$mainUrl$prefix/$sectionPath$pageSuffix"
        }

        val doc = app.get(url, headers = baseHeaders).document
        val items = parseItems(doc)
        
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val prefix = getLangPrefix()
        val url = "$mainUrl$prefix/search?searchValue=$query"
        val doc = app.get(url, headers = baseHeaders).document
        return parseItems(doc)
    }

    private fun parseItems(doc: Document): List<SearchResponse> {
        // Strategy 1: Try to parse Next.js data (Contains ALL items including those not rendered in HTML)
        val nextData = doc.selectFirst("#__NEXT_DATA__")?.data()
        if (!nextData.isNullOrBlank()) {
            try {
                val json = parseJson<NextData>(nextData)
                
                // Try multiple paths to find the item list
                val channelList = json.props?.pageProps?.moreData?.items
                    ?: json.props?.pageProps?.initialState?.channel?.list 
                    ?: json.props?.pageProps?.initialState?.channel?.homeFuncList?.flatMap { it.list ?: emptyList() }
                
                if (!channelList.isNullOrEmpty()) {
                    return channelList.mapNotNull { item ->
                        val title = item.bookName ?: item.name
                        val bookId = item.bookId
                        val cover = item.cover ?: item.image
                         
                        if (title.isNullOrBlank() || bookId.isNullOrBlank()) return@mapNotNull null
                        
                        val fullUrl = "$mainUrl/movie/$bookId/${title.toSlug()}"
                        
                        newAnimeSearchResponse(title, fullUrl, TvType.AsianDrama) {
                            this.posterUrl = cover
                        }
                    }
                }
            } catch (e: Exception) {
                // Fallback to HTML parsing if JSON structure changes
            }
        }

        // Strategy 2: Fallback to HTML Link Aggregation (Original robust logic)
        val links = doc.select("a[href*='/movie/']")
        val grouped = links.groupBy { 
            val href = it.attr("href")
            val full = if (href.startsWith("http")) href else "$mainUrl$href"
            full.trimEnd('/').substringBefore('?')
        }

        return grouped.mapNotNull { (fullUrl, elements) ->
            val titles = elements.mapNotNull { element ->
                 val t = element.selectFirst(".title, h3, h4, span[class*='bookName'], div[class*='bookName']")?.text() 
                    ?: element.selectFirst("img")?.attr("alt") 
                    ?: element.text()
                 
                 t.trim().takeIf { it.isNotEmpty() }
            }
            
            val validTitle = titles.firstOrNull { text ->
                 !text.contains("Episodes", true) && 
                 !text.equals("Coming Soon", true) &&
                 !Regex("""^\d+\s*Episodes?$""", RegexOption.IGNORE_CASE).matches(text) &&
                 !Regex("""^\d+$""").matches(text)
            }
            
            if (validTitle == null) return@mapNotNull null
            
            val poster = elements.mapNotNull { it.selectFirst("img")?.attr("src") }.firstOrNull()
            
            newAnimeSearchResponse(validTitle, fullUrl, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }
    
    // Helper to slugify title for URL construction (mimics simple slug behavior)
    private fun String.toSlug(): String {
        return this.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    // JSON Data Structures for Next.js Data
    data class NextData(
        @JsonProperty("props") val props: Props? = null
    )

    data class Props(
        @JsonProperty("pageProps") val pageProps: PageProps? = null
    )

    data class PageProps(
        @JsonProperty("initialState") val initialState: InitialState? = null,
        @JsonProperty("moreData") val moreData: MoreData? = null
    )
    
    data class MoreData(
        @JsonProperty("items") val items: List<MovieItem>? = null
    )

    data class InitialState(
        @JsonProperty("channel") val channel: ChannelData? = null
    )
    
    data class ChannelData(
        @JsonProperty("list") val list: List<MovieItem>? = null,
        @JsonProperty("homeFuncList") val homeFuncList: List<HomeSection>? = null
    )
    
    data class HomeSection(
        @JsonProperty("list") val list: List<MovieItem>? = null
    )

    data class MovieItem(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("image") val image: String? = null
    )

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = baseHeaders).document
        
        val title = doc.selectFirst("h1, .drama-title, div[class*='bookName']")?.text() ?: "Unknown"
        val poster = doc.selectFirst("img.poster, .drama-cover img, div[class*='bookImage'] img")?.attr("src") 
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        val description = doc.selectFirst(".description, .plot, meta[name='description'], div[class*='introduction']")?.text() 
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")
        val tags = doc.select(".tags a, .genre a, div[class*='tags'] span").map { it.text() }
        
        val bookId = Regex("""/movie/(\d+)""").find(url)?.groupValues?.get(1) 
            ?: url.trimEnd('/').substringAfterLast("/")
        
        if (bookId == null) throw ErrorLoadingException("No Book ID found")

        val episodesUrl = "$playerApiUrl/api/dramabox/allepisode/$bookId"
        var episodes: Array<DramaboxEpisode>? = null
        
        // Retry logic for slow API to prevent caching empty/failed responses
        for (i in 0..2) {
            try {
                // Increased timeout to 45s
                episodes = app.get(episodesUrl, timeout = 45L).parsedSafe<Array<DramaboxEpisode>>()
                if (!episodes.isNullOrEmpty()) break
            } catch (e: Exception) {
                // Ignore and retry
            }
            if (i < 2) delay(1500)
        }

        if (episodes.isNullOrEmpty()) {
            throw ErrorLoadingException("Failed to fetch episodes (Timeout)")
        }

        val epData = episodes.map { ep ->
            val data = LoadLinksData(bookId, ep.chapterId ?: "").toJson()
            newEpisode(data) {
                this.name = ep.chapterName
                this.episode = ep.chapterIndex?.plus(1)
                this.posterUrl = ep.chapterImg
            }
        }

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
