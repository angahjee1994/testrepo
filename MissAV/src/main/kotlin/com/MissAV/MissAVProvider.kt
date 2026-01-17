package com.MissAv

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class MissAVProvider : MainAPI() {
    override var mainUrl              = "https://missav.ws"
    override var name                 = "MissAV"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded
    val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
            "/dm515/en/new" to "Recent update",
            "/dm588/en/release" to "New Release",
            "/dm1/en/english-subtitle" to "English subtitle",
            "/dm291/en/today-hot" to "Most Viewed Today",
            "/dm169/en/weekly-hot" to "Most Viewed by Week",
            "/dm263/en/monthly-hot" to "Most Viewed by Month",
            "/dm628/en/uncensored-leak" to "Uncensored Leak",
            "/dm150/en/fc2" to "Uncensored FC2",
            "/dm1037028/en/heyzo" to "Uncensored HEYZO",
            "/dm29/en/tokyohot" to "Uncensored Tokyo Hot",
            "/dm24/en/marriedslash" to "Uncensored Married Slash",
            "/dm66/en/makers/Prestige" to "Prestige AV",
            "/dm863/en/maan" to "Amateur PRESTIGE PREMIUM",
            "/dm820/en/makers/Moody%27s" to "Moody's AV",
            "/dm265/en/makers/Madonna" to "Madonna AV",
            "/dm737/en/makers/Premium" to "Premium AV",
            "/dm21/en/luxu" to "Amateur LUXU AV",
            "/dm35/en/madou" to "Madou"
        )
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
            val document = app.get("$mainUrl${request.data}?page=$page").document
            val responseList  = document.select(".thumbnail").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true),hasNext = true)

    }

    private fun Element.toSearchResult(): SearchResponse {
        val status = this.select(".bg-blue-800").text()
        val titleElement = this.selectFirst(".text-secondary") ?: this.selectFirst("a.text-secondary")
        val titleText = titleElement?.text()?.trim() ?: this.select("a").text().trim()
        val title = if(status.isNotBlank()) "[$status] $titleText" else titleText
        val href = titleElement?.attr("href") ?: this.selectFirst("a")?.attr("href") ?: ""
        val posterUrl = this.selectFirst(".w-full")?.attr("data-src") 
            ?: this.selectFirst("img")?.attr("data-src")
            ?: this.selectFirst("img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        val encoded = URLEncoder.encode(query, "UTF-8")
        
        var domainPrefix = ""
        try {
            val homePage = app.get(mainUrl).document
            val firstLink = homePage.selectFirst("a[href*='/dm']")?.attr("href")
            if (firstLink != null) {
                val prefixMatch = Regex("/(dm\\d+)/").find(firstLink)
                domainPrefix = prefixMatch?.groupValues?.get(1)?.let { "/$it" } ?: ""
            }
        } catch (e: Exception) { }

        val searchUrls = if (domainPrefix.isNotBlank()) {
            listOf("$mainUrl$domainPrefix/en/search/$encoded", "$mainUrl/en/search/$encoded")
        } else {
            listOf("$mainUrl/en/search/$encoded")
        }

        for (searchUrl in searchUrls) {
            try {
                for (i in 1..7) {
                    val document = app.get("$searchUrl?page=$i").document
                    
                    var elementList = document.select(".thumbnail")
                    if (elementList.isEmpty()) {
                        elementList = document.select("div.grid > div")
                    }
                    val results = elementList.mapNotNull { it.toSearchResult() }

                    if(results.isNotEmpty()) {
                        for (result in results) {
                            if(!searchResponse.contains(result)) {
                                searchResponse.add(result)
                            }
                        }
                    } else {
                        break
                    }
                }
                if (searchResponse.isNotEmpty()) break
            } catch (e: Exception) {
                continue
            }
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        
        val actors = document.select("a[href*='/actresses/']").mapNotNull { actorLink ->
            val actorName = actorLink.text().trim()
            if (actorName.isBlank()) return@mapNotNull null
            
            val actorImageUrl = actorLink.selectFirst("img")?.attr("src")
                ?: actorLink.selectFirst("img")?.attr("data-src")
                ?: actorLink.parent()?.selectFirst("img")?.attr("src")
                ?: actorLink.parent()?.selectFirst("img")?.attr("data-src")
            
            ActorData(
                actor = Actor(actorName, actorImageUrl),
                roleString = "Actress"
            )
        }.distinctBy { it.actor.name }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.actors = actors.takeIf { it.isNotEmpty() }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {


            val data = app.get(data)
            val doc = data.document
            getAndUnpack(data.text).let { unpackedText ->
                val linkList = unpackedText.split(";")
                val finalLink = "source='(.*)'".toRegex().find(linkList.first())?.groups?.get(1)?.value
                callback.invoke(
                    newExtractorLink(
                    source = name,
                    name = name,
                    url = finalLink.toString(),
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = ""
                    this.quality = Qualities.Unknown.value
                }
                )
            }

        try {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty())
            {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList)
                {
                    if(item.text().contains(javCode,ignoreCase = true))
                    {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(item in sList)
                        {
                            try {
                                val language = item.select(".sub-single span:nth-child(2)").text()
                                val text = item.select(".sub-single span:nth-child(3) a")
                                if(text != null && text.size > 0 && text[0].text() == "Download")
                                {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    subtitleCallback.invoke(
                                        SubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E",""),  // Use label for the name
                                            url     // Use extracted URL
                                        )
                                    )
                                }
                            } catch (e: Exception) { }
                        }

                    }
                }

            }
        } catch (e: Exception) { }



        return true
    }
}

