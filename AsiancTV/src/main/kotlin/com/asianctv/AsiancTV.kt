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
        val poster = document.selectFirst(".img img")?.attr("src")
        
        // Description
        val description = document.select(".info").text().substringAfter("Description:").substringBefore("Country:").trim()

        val episodes = document.select("ul.list-episode-item-2 li").mapNotNull {
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
        
        // 1. Scrape server lists from the EPISODE page (data)
        // Selectors found: .muti_link li, ul.list-server-items li, .anime_muti_link li
        val serverList = document.select(".muti_link li, ul.list-server-items li, .anime_muti_link li")
        
        serverList.forEach { server ->
            val videoUrl = server.attr("data-video")
            if (videoUrl.isNotEmpty()) {
                resolveServerLink(videoUrl, data, subtitleCallback, callback)
            }
        }
        
        // 2. Also handle the default iframe (often duplicates one of the servers)
        val defaultIframe = document.select("iframe").attr("src")
        if (defaultIframe.isNotEmpty()) {
             resolveServerLink(defaultIframe, data, subtitleCallback, callback)
        }

        return true
    }

    private suspend fun resolveServerLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixedUrl = fixUrl(url)
        
        // If it's an internal streaming.php link, we MUST fetch it with Referer to get the real source
        if (fixedUrl.contains("streaming.php")) {
            try {
                // Fetch with Referer to bypass "Direct access is not allowed"
                val response = app.get(fixedUrl, headers = mapOf("Referer" to referer)).document
                val innerIframeSrc = response.select("iframe").attr("src")
                
                if (innerIframeSrc.isNotEmpty()) {
                     loadExtractor(fixUrl(innerIframeSrc), referer, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // Direct external link (rare, but possible)
            loadExtractor(fixedUrl, referer, subtitleCallback, callback)
        }
    }
}
