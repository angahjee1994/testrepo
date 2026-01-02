package com.asianctv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class AsiancTV : MainAPI() {
    override var mainUrl = "https://asianctv.cc"
    override var name = "AsiancTV"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/popular-movies?page=" to "Popular Movies",
        "$mainUrl/popular-dramas?page=" to "Popular Dramas",
        "$mainUrl/recently-added?page=" to "Recently Added",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("ul.switch-block.list-episode-item li").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.title")?.text() ?: return null
        val href = this.selectFirst("a")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("img")?.attr("src")
        return newAnimeSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.replace(" ", "+")
        val link = "$mainUrl/search.html?keyword=$cleanQuery"
        val document = app.get(link).document

        return document.select("ul.switch-block.list-episode-item li").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        val poster = document.selectFirst(".info-left img")?.attr("src")
        
        // Description is often in a p tag with a span "Description:"
        val description = document.select(".info-right p").find { 
            it.text().contains("Description:", ignoreCase = true) 
        }?.ownText() ?: document.selectFirst(".info-right p")?.text()?.trim()

        val episodes = document.select("ul.list-episode-item li").mapNotNull {
             val a = it.selectFirst("a") ?: return@mapNotNull null
             val epUrl = a.attr("href")
             val epName = it.selectFirst("h3.title")?.text() ?: "Episode"
             val epNum = epName.filter { it.isDigit() }.toIntOrNull()
             
             newEpisode(epUrl) {
                 this.name = epName
                 this.episode = epNum
             }
        }.reversed()

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(data).document
        val iframeSrc = document.select("iframe").attr("src")
        
        if (iframeSrc.isNotEmpty()) {
             // Basic handling - often these sites use XStream, Gogo, or similar
             // For now, load it directly if it's a known extractor or try to find one
             if (iframeSrc.contains("streaming.php")) {
                 val streamingPage = app.get(fixUrl(iframeSrc)).document
                 val serverList = streamingPage.select("li.linkserver") // Adjust selector based on actual site
                 
                 // Fallback to simpler extraction for now, will need refining
                 // Usually these sites hide the real video link inside the streaming.php page
             }
             
             loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }

        return true
    }
}
