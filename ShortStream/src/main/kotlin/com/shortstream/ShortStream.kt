package com.shortstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import android.util.Base64

import com.fasterxml.jackson.databind.DeserializationFeature

class ShortStream : MainAPI() {
    override var mainUrl = "https://cdn.tukucoin.my.id"
    override var name = "ShortStream"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "dramabox" to "DramaBox",
        "shortmax" to "ShortMax",
        "netshort" to "NetShort",
        "reelshort" to "ReelShort",
        "dramawave" to "DramaWave",
        "melolo" to "Melolo",
        "radreel" to "RadReel",
        "flick" to "FlickReels",
        "dotdrama" to "DotDrama",
        "starshort" to "StarShort",
        "meloshort" to "MeloShort",
        "goodshort" to "GoodShort",
        "viglo" to "Viglo",
        "stardusttv" to "StardustTV",
        "dramabite" to "DramaBite",
        "hishort" to "HiShort",
        "dramadash" to "DramaDash",
    )

    data class TukucoinItem(
        val source: String,
        val unique_id: String,
        val title: String,
        val cover: String?,
        val intro: String?,
        val episodes_count: Int?
    )

    data class TukucoinResponse(
        val data: Map<String, List<TukucoinItem>>?
    )
    
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        
        // Always fetch the main catalog
        val url = "$mainUrl/api/movies"
        val responseText = app.get(url).text
        val response = mapper.readValue<TukucoinResponse>(responseText)
        
        // Filter by the requested provider key (request.data)
        val providerKey = request.data
        val items = response.data?.get(providerKey) ?: emptyList()
        
        val home = items.map { item ->
            val cleanTitle = item.title
            val poster = item.cover
            val json = mapper.writeValueAsString(item)
            val href = "shortstream://${Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)}"
            
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = SearchQuality.HD
            }
        }
        
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/api/movies"
        val responseText = app.get(url).text
        val response = mapper.readValue<TukucoinResponse>(responseText)
        val allItems = response.data?.flatMap { it.value } ?: emptyList()
        
        return allItems.filter { it.title.contains(query, ignoreCase = true) }
            .map { item ->
                val cleanTitle = item.title
                val poster = item.cover
                val json = mapper.writeValueAsString(item)
                val href = "shortstream://${Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)}"
                
                newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = if (url.contains("shortstream://")) {
             "shortstream://" + url.substringAfter("shortstream://")
        } else {
             url
        }
        
        if (!cleanUrl.startsWith("shortstream://")) return null
        
        val base64Data = cleanUrl.removePrefix("shortstream://")
        val json = String(Base64.decode(base64Data, Base64.DEFAULT))
        val item = mapper.readValue<TukucoinItem>(json)
        
        val episodes = mutableListOf<Episode>()
        
        val episodeData = "${item.source}|${item.unique_id}"
        
        episodes.add(newEpisode(episodeData) {
            this.name = "Play Series"
            this.episode = 1
        })

        return newTvSeriesLoadResponse(item.title, url, TvType.TvSeries, episodes) {
            this.posterUrl = item.cover
            this.plot = item.intro
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false
        
        val source = parts[0]
        val id = parts[1]
        
        val rishortUrl = "https://rishort.com/api/proxy/$source/$id"
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://rishort.com/"
        )

        callback.invoke(
            newExtractorLink(
                "ShortStream",
                "ShortStream",
                rishortUrl,
                ExtractorLinkType.M3U8,
            ) {
                this.headers = headers
            }
        )
        return true
    }
}
