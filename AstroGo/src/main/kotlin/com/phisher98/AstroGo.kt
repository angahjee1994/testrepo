package com.phisher98

import android.content.SharedPreferences
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray

class AstroGo(private val sharedPref: SharedPreferences) : MainAPI() {
    override var mainUrl = "https://astrogo.astro.com.my"
    override var name = "AstroGo"
    override val hasMainPage = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    private val apiUrl = "https://linearjitp-playback.astro.com.my"
    private val authUrl = "https://auth.astro.com.my"
    
    private fun getAuthHeaders(): Map<String, String> {
        val cookies = sharedPref.getString("cookies", "") ?: ""
        return mapOf(
            "Cookie" to cookies,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "Referer" to mainUrl
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/api/v1/content/home" to "Home",
        "$mainUrl/api/v1/content/movies" to "Movies",
        "$mainUrl/api/v1/content/tvshows" to "TV Shows",
        "$mainUrl/api/v1/content/live" to "Live TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val doc = app.get(request.data, headers = getAuthHeaders()).document
        
        doc.select("div.content-item").forEach { element ->
            val title = element.selectFirst("h3")?.text() ?: return@forEach
            val href = element.selectFirst("a")?.attr("href") ?: return@forEach
            val posterUrl = element.selectFirst("img")?.attr("src")
            
            items.add(
                MovieSearchResponse(
                    title,
                    mainUrl + href,
                    this.name,
                    TvType.Movie,
                    posterUrl,
                    null
                )
            )
        }
        
        return HomePageResponse(listOf(HomePageList(request.name, items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResults = mutableListOf<SearchResponse>()
        val url = "$mainUrl/api/v1/search?q=$query"
        
        val response = app.get(url, headers = getAuthHeaders()).text
        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: return searchResults
        
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val title = item.optString("title")
            val id = item.optString("id")
            val poster = item.optString("poster")
            
            searchResults.add(
                MovieSearchResponse(
                    title,
                    "$mainUrl/content/$id",
                    this.name,
                    TvType.Movie,
                    poster,
                    null
                )
            )
        }
        
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = getAuthHeaders()).document
        
        val title = doc.selectFirst("h1.title")?.text() ?: "Unknown"
        val poster = doc.selectFirst("img.poster")?.attr("src")
        val description = doc.selectFirst("p.description")?.text()
        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()
        
        val episodes = doc.select("div.episode-item").map { ep ->
            val epTitle = ep.selectFirst("span.episode-title")?.text() ?: ""
            val epUrl = ep.selectFirst("a")?.attr("href") ?: ""
            val epNum = ep.selectFirst("span.episode-number")?.text()?.toIntOrNull()
            
            Episode(
                mainUrl + epUrl,
                epTitle,
                null,
                epNum
            )
        }
        
        return if (episodes.isNotEmpty()) {
            TvSeriesLoadResponse(
                title,
                url,
                this.name,
                TvType.TvSeries,
                episodes,
                poster,
                year,
                description
            )
        } else {
            MovieLoadResponse(
                title,
                url,
                this.name,
                TvType.Movie,
                url,
                poster,
                year,
                description
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = getAuthHeaders()).document
        val videoUrl = doc.selectFirst("video source")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
        
        if (videoUrl != null) {
            callback.invoke(
                ExtractorLink(
                    this.name,
                    this.name,
                    videoUrl,
                    mainUrl,
                    Qualities.Unknown.value,
                    isM3u8 = videoUrl.contains(".m3u8")
                )
            )
        }
        
        return true
    }
}
