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
        val storedLang = com.lagradost.cloudstream3.AcraApplication.getKey<String>("dramabox_language") ?: "en"
        return when (storedLang) {
            "id", "in", "id-ID" -> "/in"
            "es", "es-ES" -> "/es"
            "fr", "fr-FR" -> "/fr"
            "de", "de-DE" -> "/de"
            "th", "th-TH" -> "/th"
            "ko", "ko-KR" -> "/kr"
            "vi", "vi-VN" -> "/vi"
            "zh", "zh-CN", "zh-TW" -> "/zh"
            "ar" -> "/ar"
            "pt" -> "/pt"
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
        val nextData = doc.selectFirst("#__NEXT_DATA__")?.data()
        if (!nextData.isNullOrBlank()) {
            try {
                val json = parseJson<NextData>(nextData)
                
                // Try multiple paths to find the item list
                // Search pages use "bookList", Channel pages use "moreData.items" or "channel.list"
                val channelList = json.props?.pageProps?.bookList
                    ?: json.props?.pageProps?.moreData?.items
                    ?: json.props?.pageProps?.initialState?.channel?.list 
                    ?: json.props?.pageProps?.initialState?.channel?.homeFuncList?.flatMap { it.list ?: emptyList() }
                
                if (!channelList.isNullOrEmpty()) {
                    return channelList.mapNotNull { item ->
                        val title = item.bookName ?: item.name
                        val bookId = item.bookId
                        val cover = item.cover ?: item.image ?: item.coverWap
                         
                        if (title.isNullOrBlank() || bookId.isNullOrBlank()) return@mapNotNull null
                        
                        val prefix = getLangPrefix()
                        val fullUrl = "$mainUrl$prefix/movie/$bookId/${title.toSlug()}"
                        
                        newAnimeSearchResponse(title, fullUrl, TvType.AsianDrama) {
                            this.posterUrl = cover
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }

        // Fallback to HTML: Search results use /ep/, others use /movie/
        val links = doc.select("a[href*='/movie/'], a[href*='/ep/']")
        val grouped = links.groupBy { 
            val href = it.attr("href")
            // Normalize: If it's an episode link /ep/ID_Slug/..., extract just the movie part?
            // Actually, for search results, the link IS the movie link in a way.
            // But we prefer /movie/ link format for consistency with load().
            // If the link is /ep/41000..._title/..., we can reconstruct /movie/41000.../title
            
            // For now, let's just group by the raw href to merge duplicate links for the same item.
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
            
            // If the URL is an /ep/ link, try to convert it to /movie/ link for consistency
            // Pattern: /ep/{bookId}_{slug}/{chapterId}... -> /movie/{bookId}/{slug}
            // Or just allow it. But load() expects /movie/ regex.
            val finalUrl = if (fullUrl.contains("/ep/")) {
                val match = Regex("""/ep/(\d+)_([^/]+)""").find(fullUrl)
                if (match != null) {
                    val (id, slug) = match.destructured
                    val prefix = getLangPrefix()
                    "$mainUrl$prefix/movie/$id/$slug"
                } else fullUrl
            } else fullUrl

            newAnimeSearchResponse(validTitle, finalUrl, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        }
    }
    
    private fun String.toSlug(): String {
        return this.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    override suspend fun load(url: String): LoadResponse {
        val prefix = getLangPrefix()
        val finalUrl = if (prefix.isNotEmpty() && !url.contains("$mainUrl$prefix")) {
            url.replace(mainUrl, "$mainUrl$prefix")
        } else url

        val doc = app.get(finalUrl, headers = baseHeaders).document
        
        var title = "Unknown"
        var poster: String? = null
        var description: String? = null
        var tags = emptyList<String>()
        var bookNameEn: String? = null
        
        val nextData = doc.selectFirst("#__NEXT_DATA__")?.data()
        if (!nextData.isNullOrBlank()) {
            try {
                val json = parseJson<NextData>(nextData)
                val bookInfo = json.props?.pageProps?.bookInfo
                
                if (bookInfo != null) {
                    title = bookInfo.bookName ?: bookInfo.name ?: title
                    bookNameEn = bookInfo.bookNameEn
                    poster = bookInfo.cover ?: bookInfo.image ?: poster
                    description = bookInfo.introduction ?: description
                    
                    val rawTags = mutableListOf<String>()
                    bookInfo.tags?.let { rawTags.addAll(it) }
                    bookInfo.labels?.let { rawTags.addAll(it) }
                    bookInfo.typeTwoNames?.let { rawTags.addAll(it) }
                    tags = rawTags.distinct()
                }
            } catch (e: Exception) {
            }
        }
        
        if (title == "Unknown") {
            title = doc.selectFirst("h1, .drama-title, div[class*='bookName']")?.text() ?: "Unknown"
        }
        if (poster == null) {
            poster = doc.selectFirst("img.poster, .drama-cover img, div[class*='bookImage'] img")?.attr("src") 
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        }
        if (description == null) {
            description = doc.selectFirst(".description, .plot, meta[name='description'], div[class*='introduction']")?.text() 
                ?: doc.selectFirst("meta[property='og:description']")?.attr("content")
        }
        if (tags.isEmpty()) {
            tags = doc.select(".tags a, .genre a, div[class*='tags'] span").map { it.text() }
        }
        
        val bookId = Regex("""/movie/(\d+)""").find(url)?.groupValues?.get(1) 
            ?: url.trimEnd('/').substringAfterLast("/")
        
        if (bookId == null) throw ErrorLoadingException("No Book ID found")

        val episodesUrl = "$playerApiUrl/api/dramabox/allepisode/$bookId"
        var episodes: Array<DramaboxEpisode>? = null
        
        for (i in 0..2) {
            try {
                episodes = app.get(episodesUrl, timeout = 45L).parsedSafe<Array<DramaboxEpisode>>()
                if (!episodes.isNullOrEmpty()) break
            } catch (e: Exception) {
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

    // --- JSON Data Classes ---

    data class NextData(
        @JsonProperty("props") val props: Props? = null
    )

    data class Props(
        @JsonProperty("pageProps") val pageProps: PageProps? = null
    )

    data class PageProps(
        @JsonProperty("initialState") val initialState: InitialState? = null,
        @JsonProperty("moreData") val moreData: MoreData? = null,
        @JsonProperty("bookInfo") val bookInfo: BookInfo? = null,
        @JsonProperty("bookList") val bookList: List<MovieItem>? = null
    )

    data class InitialState(
        @JsonProperty("channel") val channel: ChannelData? = null
    )
    
    data class MoreData(
        @JsonProperty("items") val items: List<MovieItem>? = null
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
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("coverWap") val coverWap: String? = null
    )
    
    data class BookInfo(
        @JsonProperty("bookId") val bookId: String? = null,
        @JsonProperty("bookName") val bookName: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("introduction") val introduction: String? = null,
        @JsonProperty("cover") val cover: String? = null,
        @JsonProperty("image") val image: String? = null,
        @JsonProperty("tags") val tags: List<String>? = null,
        @JsonProperty("labels") val labels: List<String>? = null,
        @JsonProperty("typeTwoNames") val typeTwoNames: List<String>? = null,
        @JsonProperty("bookNameEn") val bookNameEn: String? = null
    )

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