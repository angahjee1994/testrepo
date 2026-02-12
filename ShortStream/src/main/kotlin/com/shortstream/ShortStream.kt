package com.shortstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
    
    data class ChapterItem(
        val chapterId: String?,
        val chapterIndex: Int?,
        val chapterName: String?,
        val cover: String? 
    )

    data class ChapterListResponse(
        val data: Map<String, Any>?
    )

    data class PlayerQuality(
        val quality: Int?,
        val videoUrl: String?
    )

    data class PlayerData(
        val videoUrl: String?,
        val hls_url: String?, // for ShortMax/Dramawave
        val m3u8_url: String?, // for NetShort
        val qualities: Any? // Can be List<PlayerQuality> or Map<String, String>
    )

    data class PlayerResponse(
        val data: PlayerData?
    )

    data class DetailResponse(
        val data: DetailData?
    )

    data class DetailData(
        val code: String?,
        val id: String?, // playlet_id for ShortMax style
        val episodes: Any?, // Int for ShortMax, List for ReelShort
        val total_episodes: Int?, // For ReelShort style (fallback)
        val chapterList: List<ChapterItem>?, // for DramaBox style fallback
        val videoUrl: String?, // direct video
        val m3u8_url: String? // for NetShort
    )

    data class ReelShortPlayerResponse(
        val video_url: String?,
        val pic: String?
    )
    
    private val mapper = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        if (page > 1) return null
        val url = "$mainUrl/api/movies"
        val response = mapper.readValue<TukucoinResponse>(app.get(url).text)
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
        val response = mapper.readValue<TukucoinResponse>(app.get(url).text)
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
        val cleanUrl = if (url.contains("shortstream://")) "shortstream://" + url.substringAfter("shortstream://") else url
        if (!cleanUrl.startsWith("shortstream://")) return null
        
        val base64Data = cleanUrl.removePrefix("shortstream://")
        val json = String(Base64.decode(base64Data, Base64.DEFAULT))
        val item = mapper.readValue<TukucoinItem>(json)
        
        val episodes = mutableListOf<Episode>()
        val rawSource = item.source
        val source = if (rawSource.startsWith("http")) {
             rawSource.trimEnd('/').substringAfterLast('/')
        } else {
             rawSource
        }
        val bookId = item.unique_id
        
        // Try Detail Endpoint first
        var playletId: String? = null
        
        try {
            val detailUrl = "https://rishort.com/api/proxy/$source/detail/$bookId?lang=id"
            val detailRes = app.get(detailUrl).text
            val jsonNode = mapper.readTree(detailRes)
            val dataNode = if (jsonNode.has("data")) jsonNode.get("data") else jsonNode
            val detail = mapper.treeToValue(dataNode, DetailData::class.java)
            
            val eps = detail?.episodes
            
            if (eps is List<*>) {
                 // ReelShort style with episode list
                eps.forEach { ep ->
                    if (ep is Map<*, *>) {
                        val epNum = (ep["ep"] as? Number)?.toInt() ?: (ep["chapterIndex"] as? Number)?.toInt() ?: 0
                        if (epNum > 0) {
                             val name = ep["name"] as? String ?: "Episode $epNum"
                             val pic = ep["pic"] as? String
                             val epData = "$source|$bookId|$epNum|reelshort"
                             episodes.add(newEpisode(epData) {
                                this.name = name
                                this.episode = epNum
                                this.posterUrl = pic
                            })
                        }
                    }
                }
            } else if (eps is Int) {
                // ShortMax / Dramawave style
                if (detail.id != null) {
                    playletId = detail.id
                    for (i in 1..eps) {
                        // Data: source|playletId|index|style
                        val epData = "$source|$playletId|$i|shortmax"
                        episodes.add(newEpisode(epData) {
                            this.name = "Episode $i"
                            this.episode = i
                        })
                    }
                } else if (detail.total_episodes != null) {
                     // Fallback 
                     for (i in 1..detail.total_episodes) {
                         val epData = "$source|$bookId|$i|reelshort"
                         episodes.add(newEpisode(epData) {
                            this.name = "Episode $i"
                            this.episode = i
                        })
                    }
                }
            } else if (detail?.total_episodes != null) {
                // ReelShort style
                val count = detail.total_episodes
                for (i in 1..count) {
                     val epData = "$source|$bookId|$i|reelshort"
                     episodes.add(newEpisode(epData) {
                        this.name = "Episode $i"
                        this.episode = i
                    })
                }
            } else {
                // Try Chapters endpoint (DramaBox style)
                val chaptersUrl = "https://rishort.com/api/proxy/$source/chapters/$bookId"
                val chapterRes = app.get(chaptersUrl).text
                val chNode = mapper.readTree(chapterRes).get("data")?.get("chapterList")
                
                if (chNode != null && chNode.isArray) {
                    chNode.forEach { node ->
                        val idx = node.get("chapterIndex")?.asInt() ?: 0
                        val epNum = idx + 1 
                        val name = node.get("chapterName")?.asText() ?: "Episode $epNum"
                        val pic = node.get("cover")?.asText() ?: node.get("pic")?.asText()
                        
                        val epData = "$source|$bookId|$epNum|dramabox"
                        episodes.add(newEpisode(epData) {
                            this.name = name
                            this.episode = epNum
                            this.posterUrl = pic
                        })
                    }
                } else {
                     // Fallback
                     val count = item.episodes_count ?: 1
                     for (i in 1..count) {
                         val epData = "$source|$bookId|$i|dramabox" 
                         episodes.add(newEpisode(epData) {
                            this.name = "Episode $i"
                            this.episode = i
                        })
                     }
                }
            }
        } catch (e: Exception) {
             e.printStackTrace()
             // Fallback
             val count = item.episodes_count ?: 1
             for (i in 1..count) {
                 val epData = "$source|$bookId|$i|dramabox"
                 episodes.add(newEpisode(epData) {
                    this.name = "Episode $i"
                    this.episode = i
                })
             }
        }

        return newTvSeriesLoadResponse(item.title, url, TvType.TvSeries, episodes) {
            this.posterUrl = item.cover
            this.plot = item.intro
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parts = data.split("|")
        if (parts.size < 4) return false
        
        val rawSource = parts[0]
        val source = if (rawSource.startsWith("http")) {
            rawSource.trimEnd('/').substringAfterLast('/')
        } else {
            rawSource
        }
        val id = parts[1] // playletId or bookId
        val index = parts[2]
        val style = parts[3]
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "https://rishort.com/"
        )
        
        suspend fun addLink(name: String, url: String) {
            val type = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
            callback.invoke(
                newExtractorLink(
                    source = "ShortStream",
                    name = name,
                    url = url,
                    type = type
                ) {
                    this.referer = "https://rishort.com/"
                    this.headers = headers
                }
            )
        }

        if (style == "reelshort") {
             val playerUrl = "https://rishort.com/api/proxy/reelshort/play/$id?ep=$index&lang=id"
              try {
                val responseText = app.get(playerUrl).text
                val response = mapper.readValue<ReelShortPlayerResponse>(responseText)
                val url = response.video_url
                if (!url.isNullOrEmpty()) {
                     addLink("ReelShort", url)
                }
            } catch (e: Exception) { e.printStackTrace() }
            return true
        }
        
        val playerUrl = if (style == "shortmax") {
            "https://rishort.com/api/proxy/$source-video/episode/$id/$index?lang=in"
        } else {
            "https://rishort.com/api/proxy/$source/watch/player?bookId=$id&index=$index&lang=in"
        }
        
        try {
            val responseText = app.get(playerUrl).text
            val response = mapper.readValue<PlayerResponse>(responseText)
            val d = response.data
            
            // Collect main video
            val mainUrl = d?.videoUrl ?: d?.hls_url ?: d?.m3u8_url
            if (!mainUrl.isNullOrEmpty()) {
                addLink("ShortStream", mainUrl)
            }
            
            // Collect qualities if available
            val qAny = d?.qualities
            if (qAny is List<*>) {
                 for (q in qAny) {
                     if (q is Map<*, *>) { 
                         val url = q["videoUrl"] as? String
                         val qual = q["quality"]?.toString() ?: "SD"
                         if (!url.isNullOrEmpty() && url != mainUrl) {
                             addLink("ShortStream $qual", url)
                         }
                     }
                 }
            } else if (qAny is Map<*, *>) {
                for ((k, v) in qAny) {
                    val url = v as? String
                    if (!url.isNullOrEmpty() && url != mainUrl) {
                        addLink("ShortStream $k", url)
                    }
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }
}
