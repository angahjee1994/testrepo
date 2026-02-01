package com.botol

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Document
import kotlinx.coroutines.*

class Dramabox : MainAPI() {
    override var mainUrl = "https://www.dramaboxdb.com"
    private val playerApiUrl = "https://dracin-web-eight.vercel.app" 
    override var name = "Dramabox"
    override val hasMainPage = true
    override val instantLinkLoading = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    )

    private var cachedLangPrefix: String? = null
    private fun getLangPrefix(): String {
        cachedLangPrefix?.let { return it }
        val storedLang = com.lagradost.cloudstream3.AcraApplication.getKey<String>("dramabox_language") ?: "en"
        val prefix = when (storedLang) {
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
        cachedLangPrefix = prefix
        return prefix
    }

    override val mainPage = mainPageOf(
        "channel/must-sees" to "Must Sees",
        "channel/trending" to "Trending",
        "channel/hidden-gems" to "Hidden Gems",
        "genres/0" to "All Short"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val prefix = getLangPrefix()
        val sectionPath = request.data
        val pageSuffix = if (page > 1) "/$page" else ""
        val url = if (sectionPath.isEmpty()) "$mainUrl$prefix/" else "$mainUrl$prefix/$sectionPath$pageSuffix"
        val doc = app.get(url, headers = baseHeaders).document
        return newHomePageResponse(request.name, parseItems(doc), true)
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
                val items = json.props?.pageProps?.bookList
                    ?: json.props?.pageProps?.moreData?.items
                    ?: json.props?.pageProps?.initialState?.channel?.list 
                    ?: json.props?.pageProps?.initialState?.channel?.homeFuncList?.flatMap { it.list ?: emptyList() }
                
                if (!items.isNullOrEmpty()) {
                    return items.mapNotNull { item ->
                        val itemTitle = item.bookName ?: item.name ?: return@mapNotNull null
                        val bookId = item.bookId ?: return@mapNotNull null
                        val prefix = getLangPrefix()
                        val itemUrl = "$mainUrl$prefix/movie/$bookId/${itemTitle.toSlug()}"
                        newAnimeSearchResponse(itemTitle, itemUrl, TvType.AsianDrama) {
                            this.posterUrl = item.cover ?: item.image ?: item.coverWap
                        }
                    }
                }
            } catch (e: Exception) { }
        }

        val links = doc.select("a[href*='/movie/'], a[href*='/ep/']")
        return links.mapNotNull { element ->
            val href = element.attr("href")
            val rawTitle = element.selectFirst(".title, h3, h4, span[class*='bookName'], div[class*='bookName']")?.text() 
                ?: element.selectFirst("img")?.attr("alt") 
                ?: element.text()
            
            val cleanTitle = rawTitle.trim()
            if (cleanTitle.isEmpty() || cleanTitle.contains(Regex("Episodes?|Coming Soon|^\\d+$", RegexOption.IGNORE_CASE))) return@mapNotNull null
            
            val bookMatch = Regex("/(?:movie|ep)/(\\d+)").find(href) ?: return@mapNotNull null
            val bookId = bookMatch.groupValues[1]
            val prefix = getLangPrefix()
            val finalUrl = "$mainUrl$prefix/movie/$bookId/${cleanTitle.toSlug()}"

            newAnimeSearchResponse(cleanTitle, finalUrl, TvType.AsianDrama) {
                this.posterUrl = element.selectFirst("img")?.attr("src")
            }
        }.distinctBy { it.url }
    }

    private fun String.toSlug() = this.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    override suspend fun load(url: String): LoadResponse {
        val prefix = getLangPrefix()
        val finalUrl = if (prefix.isNotEmpty() && !url.contains("$mainUrl$prefix")) url.replace(mainUrl, "$mainUrl$prefix") else url
        val bookId = Regex("/movie/(\\d+)").find(url)?.groupValues?.get(1) ?: url.trimEnd('/').substringAfterLast("/")

        return coroutineScope {
            val docDeferred = async { app.get(finalUrl, headers = baseHeaders).document }
            val epDeferred = async {
                val episodesUrl = "$playerApiUrl/api/dramabox/allepisode/$bookId"
                for (i in 0..2) {
                    try {
                        val resp = app.get(episodesUrl, timeout = 30L).parsedSafe<Array<DramaboxEpisode>>()
                        if (!resp.isNullOrEmpty()) return@async resp
                    } catch (e: Exception) { }
                    if (i < 2) delay(300)
                }
                null
            }

            val doc = docDeferred.await()
            val episodes = epDeferred.await() ?: throw ErrorLoadingException("Failed to fetch episodes")
            
            var movieTitle = "Unknown"
            var posterUrl: String? = null
            var movieDescription: String? = null
            var movieTags = emptyList<String>()
            
            val nextData = doc.selectFirst("#__NEXT_DATA__")?.data()
            if (!nextData.isNullOrBlank()) {
                try {
                    val json = parseJson<NextData>(nextData)
                    json.props?.pageProps?.bookInfo?.let { info ->
                        movieTitle = info.bookName ?: info.name ?: movieTitle
                        posterUrl = info.cover ?: info.image ?: posterUrl
                        movieDescription = info.introduction ?: movieDescription
                        movieTags = (info.tags.orEmpty() + info.labels.orEmpty() + info.typeTwoNames.orEmpty()).distinct()
                    }
                } catch (e: Exception) { }
            }
            
            if (movieTitle == "Unknown") movieTitle = doc.selectFirst("h1, .drama-title, div[class*='bookName']")?.text() ?: "Unknown"
            if (posterUrl == null) posterUrl = doc.selectFirst("img.poster, .drama-cover img, div[class*='bookImage'] img")?.attr("src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            if (movieDescription == null) movieDescription = doc.selectFirst(".description, .plot, meta[name='description'], div[class*='introduction']")?.text() ?: doc.selectFirst("meta[property='og:description']")?.attr("content")
            if (movieTags.isEmpty()) movieTags = doc.select(".tags a, .genre a, div[class*='tags'] span").map { it.text() }

            val episodesData = episodes.map { ep ->
                newEpisode(LoadLinksData(bookId, ep.chapterId.orEmpty(), ep.cdnList).toJson()) {
                    this.name = ep.chapterName
                    this.episode = ep.chapterIndex?.plus(1)
                    this.posterUrl = ep.chapterImg
                }
            }

            newTvSeriesLoadResponse(movieTitle, url, TvType.AsianDrama, episodesData) {
                this.posterUrl = posterUrl
                this.plot = movieDescription
                this.tags = movieTags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = parseJson<LoadLinksData>(data)
        
        val cdnList = loadData.cdnList ?: run {
            val episodesUrl = "$playerApiUrl/api/dramabox/allepisode/${loadData.bookId}"
            val episodes = app.get(episodesUrl).parsedSafe<Array<DramaboxEpisode>>() ?: return false
            episodes.find { it.chapterId == loadData.chapterId }?.cdnList
        } ?: return false

        cdnList.forEach { cdn ->
            cdn.videoPathList?.forEach { video ->
                callback.invoke(newExtractorLink(this@Dramabox.name, this@Dramabox.name, video.videoPath ?: return@forEach, INFER_TYPE) {
                    this.quality = getQualityFromName(video.quality.toString())
                })
            }
        }
        return true
    }

    data class NextData(@JsonProperty("props") val props: Props? = null)
    data class Props(@JsonProperty("pageProps") val pageProps: PageProps? = null)
    data class PageProps(
        @JsonProperty("initialState") val initialState: InitialState? = null,
        @JsonProperty("moreData") val moreData: MoreData? = null,
        @JsonProperty("bookInfo") val bookInfo: BookInfo? = null,
        @JsonProperty("bookList") val bookList: List<MovieItem>? = null
    )
    data class InitialState(@JsonProperty("channel") val channel: ChannelData? = null)
    data class MoreData(@JsonProperty("items") val items: List<MovieItem>? = null)
    data class ChannelData(
        @JsonProperty("list") val list: List<MovieItem>? = null,
        @JsonProperty("homeFuncList") val homeFuncList: List<HomeSection>? = null
    )
    data class HomeSection(@JsonProperty("list") val list: List<MovieItem>? = null)
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
        @JsonProperty("typeTwoNames") val typeTwoNames: List<String>? = null
    )
    data class LoadLinksData(
        @JsonProperty("bookId") val bookId: String, 
        @JsonProperty("chapterId") val chapterId: String,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null
    )
    data class DramaboxEpisode(
        @JsonProperty("chapterId") val chapterId: String? = null,
        @JsonProperty("chapterIndex") val chapterIndex: Int? = null,
        @JsonProperty("chapterName") val chapterName: String? = null,
        @JsonProperty("chapterImg") val chapterImg: String? = null,
        @JsonProperty("cdnList") val cdnList: List<CdnItem>? = null,
    )
    data class CdnItem(@JsonProperty("cdnDomain") val cdnDomain: String? = null, @JsonProperty("videoPathList") val videoPathList: List<VideoPath>? = null)
    data class VideoPath(@JsonProperty("quality") val quality: Int? = null, @JsonProperty("videoPath") val videoPath: String? = null)
}