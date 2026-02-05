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
        "$mainUrl/latest-updates/" to "Latest Videos",
        "$mainUrl/networks/brazzers-com/" to "Brazzers",
        "$mainUrl/networks/mylf-com/" to "MYLF",
        "$mainUrl/networks/tushy-com/" to "TUSHY",
        "$mainUrl/networks/blacked/" to "BLACKED",
        "$mainUrl/networks/adult-time/" to "Adult Time",
        "$mainUrl/networks/teamskeet-com/" to "Team Skeet",
        "$mainUrl/networks/kink-com/" to "Kink",
        "$mainUrl/networks/nubiles-porn-com/" to "Nubiles Porn",
        "$mainUrl/networks/fakehub/" to "FakeHub",
        "$mainUrl/networks/oldje-com/" to "Oldje",
        "$mainUrl/networks/pornforce/" to "PornForce",
        "$mainUrl/networks/bangbros/" to "Bangbros",
        "$mainUrl/networks/dogfart-network/" to "DFXtra",
        "$mainUrl/networks/rk-com/" to "Reality Kings",
        "$mainUrl/networks/naughtyamerica-com/" to "Naughty America",
        "$mainUrl/networks/mom-lover/" to "Mom Lover",
        "$mainUrl/networks/evil-angel/" to "Evil Angel",
        "$mainUrl/networks/freeze/" to "freeze",
        "$mainUrl/networks/mature-nl/" to "Mature.nl",
        "$mainUrl/networks/it-s-pov/" to "It's POV",
        "$mainUrl/networks/ersties/" to "Ersties",
        "$mainUrl/networks/sinfulxxx/" to "SinfulXXX",
        "$mainUrl/networks/xempire/" to "XEmpire",
        "$mainUrl/networks/adultprime/" to "AdultPrime",
        "$mainUrl/networks/woodman-casting-x/" to "Woodman Casting X"
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

        // Prioritize OG:Image as it is the most reliable (LD-JSON thumbnail often broken)
        var poster: String? = document.selectFirst("meta[property=og:image]")?.attr("content")
        var title = document.selectFirst("h1")?.text()?.trim() ?: "Unknown"
        var description = document.selectFirst("meta[name=description]")?.attr("content")
        var duration: Int? = null

         // Parse LD-JSON for additional metadata (Title, Desc, Duration)
        val ldJsonScripts = document.select("script[type=application/ld+json]")
        for (script in ldJsonScripts) {
            try {
                if (script.data().contains("VideoObject")) {
                    val json = AppUtils.parseJson<LdJsonVideo>(script.data())
                    title = json.name ?: title
                    description = json.description ?: description
                    duration = getDurationFromString(json.duration)
                    // Only use JSON thumbnail if we still don't have one
                    if (poster.isNullOrEmpty()) {
                        poster = json.thumbnailUrl
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

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
