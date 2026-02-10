package com.teraboxvirals

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class TeraboxVirals : MainAPI() {
    override var mainUrl = "https://www.teraboxvirals.com"
    override var name = "TeraboxVirals"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest Posts",
        "search/label/Viral" to "Viral",
        "search/label/Indo" to "Indo",
        "search/label/Melayu" to "Melayu",
        "search/label/awek%20tudung" to "Awek Tudung",
        "search/label/Hijaber" to "Hijaber",
        "search/label/Couple" to "Couple"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (request.data.isEmpty()) {
            if (page > 1) {
                // Blogger pagination for index
                // Usually it's ?updated-max=... or similar. 
                // For simplicity, we stick to page 1 for now or try common Blogger index
                return null
            }
            mainUrl
        } else {
            // For labels
            if (page > 1) return null
            "$mainUrl/${request.data}"
        }

        val document = app.get(url).document
        val home = document.select("article.blog-post").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val titleElement = element.selectFirst(".entry-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val poster = element.selectFirst(".post-filter-image img")?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document
        return document.select("article.blog-post").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".entry-title")?.text() ?: "Video"
        val poster = document.selectFirst(".post-filter-image img")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.select(".post-body").text()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = plot
            // Extract tags
            val tags = document.select(".post-tag a").map { it.text() }
            this.tags = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        
        // Find links in .dlbutton a
        val buttons = document.select(".dlbutton a")
        val allLinks = document.select("a")
        
        val foundLinks = mutableSetOf<String>()

        // Prioritize buttons
        buttons.forEach { link ->
             val href = link.attr("href")
             if (href.isNotEmpty()) foundLinks.add(href)
        }

        // Add other a tags that look like terabox
        allLinks.forEach { link ->
            val href = link.attr("href")
            if (href.contains("terabox", ignoreCase = true)) {
                foundLinks.add(href)
            }
        }
        
        foundLinks.forEach { href ->
             if (href.contains("terabox", ignoreCase = true)) {
                 loadExtractor(href, subtitleCallback, callback)
             } else if (href.contains("downloadkatsini.com", ignoreCase = true)) {
                 // Try to follow redirect/landing page
                 try {
                     val landingDoc = app.get(href).document
                     landingDoc.select("a").forEach { innerLink ->
                         val innerHref = innerLink.attr("href")
                         if (innerHref.contains("terabox", ignoreCase = true)) {
                             loadExtractor(innerHref, subtitleCallback, callback)
                         }
                     }
                 } catch (e: Exception) {
                     // ignore
                 }
             }
        }
        return true
    }
}
