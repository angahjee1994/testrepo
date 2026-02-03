package com.wowxxx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.ActorRole

class WowXXX : MainAPI() {
    override var mainUrl = "https://www.wow.xxx"
    override var name = "WowXXX"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Videos",
        "$mainUrl/best/" to "Best Videos",
        "$mainUrl/search/asian/" to "Asian"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data.removeSuffix("/")}/$page/"
        val document = app.get(url).document
        val home = document.select("div.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(HomePageList(request.name, home, home.isNotEmpty()))
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a.img, a") ?: return null
        val title = this.selectFirst(".title, strong")?.text() ?: aTag.attr("title").ifEmpty { "Unknown" }
        val href = fixUrl(aTag.attr("href"))
        val img = this.selectFirst("img")
        // Prefer data-src for high-res/lazy loaded, fallback to src
        val posterUrl = img?.attr("data-src")?.ifEmpty { null } ?: img?.attr("src")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/?q=$query"
        val document = app.get(url).document
        return document.select("div.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Poster Logic: 1. LD-JSON 2. og:image 3. Fallback
        var poster: String? = null
        var description: String? = null
        var title = "Unknown"
        var duration: Int? = null

         // Try LD-JSON first
        val ldJsonScripts = document.select("script[type=application/ld+json]")
        for (script in ldJsonScripts) {
            try {
                if (script.data().contains("VideoObject")) {
                    val json = AppUtils.parseJson<LdJsonVideo>(script.data())
                    title = json.name ?: title
                    poster = json.thumbnailUrl
                    description = json.description
                    duration = getDurationFromString(json.duration)
                    // No score mapping due to semantic mismatch
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Fallback to CSS if LD-JSON failed or missed data
        if (title == "Unknown") title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        if (poster.isNullOrEmpty()) poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (description.isNullOrEmpty()) description = document.selectFirst("meta[name=description]")?.attr("content")

        val tags = document.select("a.btn_tag").map { it.text().trim() }
        val actors = document.select("a.btn_model").map { 
            ActorData(Actor(it.text().trim(), ""), role = ActorRole.Main) 
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = description
            this.duration = duration
            this.tags = tags
            this.actors = actors
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        
        // 1. Check video tag
        document.select("video source").forEach { source ->
            val src = source.attr("src")
            val type = source.attr("type")
            if (src.isNotEmpty() && (type.contains("mp4") || src.contains(".mp4"))) {
                callback.invoke(
                    newExtractorLink(name, "WowXXX MP4", src, ExtractorLinkType.VIDEO)
                )
                return true
            }
        }

        // 2. Regex search in scripts
        val scriptContent = document.select("script").html()
        val mp4Regex = """https?://[^"']+\.mp4""".toRegex()
        val matches = mp4Regex.findAll(scriptContent)
        var found = false
        matches.forEach { match ->
            val url = match.value
            callback.invoke(
                newExtractorLink(name, "WowXXX Stream", url, ExtractorLinkType.VIDEO)
            )
            found = true
        }

        return found
    }

    // JSON-LD classes
    data class LdJsonVideo(
        val name: String?,
        val description: String?,
        val thumbnailUrl: String?,
        val duration: String?,
        val interactionStatistic: List<InteractionStatistic>?
    )

    data class InteractionStatistic(
        val interactionType: String?,
        val userInteractionCount: String?
    )
    
    private fun getDurationFromString(duration: String?): Int? {
        if (duration == null) return null
        // ISO 8601 duration handling could be added here or just basic parsing if needed
        return null 
    }
}
