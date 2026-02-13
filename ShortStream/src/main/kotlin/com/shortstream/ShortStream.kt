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
    private val apiUrl = "https://rishort.com"
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
        val episodes_count: Int?,
        val tags: List<String>? = null
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
        val hls_url: String?,
        val m3u8_url: String?,
        val qualities: Any?
    )

    data class PlayerResponse(
        val data: PlayerData?
    )





    data class SimplePlayerResponse(
        val video_url: String?,
        val pic: String?
    )

    data class BookItem(
        val bookId: String?,
        val bookName: String?,
        val intro: String?,
        val labels: List<String>?,
        val cover: String?
    )

    data class BookData(
        val book: BookItem?
    )

    data class BookResponse(
        val data: BookData?
    )

    data class ChapterItemDetail(
        val id: Long?,
        val chapterName: String?,
        val index: Int?,
        val image: String?,
        val cdn: String?
    )
    
    data class ChapterData(
        val list: List<ChapterItemDetail>?
    )

    data class ChapterResponse(
        val data: ChapterData?
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
        var tags: List<String>? = item.tags
        var plot = item.intro
        
        var detailSuccess = false
        
        fun addEp(idx: Int, name: String? = null, pic: String? = null) {
            val epName = name ?: "Episode $idx"
            val epData = "$source|$bookId|$idx|generic"
            episodes.add(newEpisode(epData) {
                this.name = epName
                this.episode = idx
                this.posterUrl = pic
            })
        }

        val detailEndpoints = listOf(
            "$apiUrl/api/proxy/$source/detail/$bookId?lang=id",
            "$apiUrl/api/proxy/$source/info/$bookId",
            "$apiUrl/api/proxy/$source/dramas/$bookId/episodes"
        )

        for (endpoint in detailEndpoints) {
            if (detailSuccess) break
            try {
                val detailRes = app.get(endpoint).text
                val tree = mapper.readTree(detailRes)
                val data = if (tree.has("data")) tree["data"] else tree
                
                if (data.isObject) {
                    data["intro"]?.asText()?.let { plot = it }
                    data["tagList"]?.map { it.asText() }?.let { tags = it }
                }
                
                // Unified Episode Parsing
                val epsNode = data["episodes"] ?: data // if data is array, treat as episodes list
                
                fun parseEpNode(ep: com.fasterxml.jackson.databind.JsonNode) {
                    val epNum = ep["ep"]?.asInt() ?: ep["chapterIndex"]?.asInt() ?: ep["index"]?.asInt() ?: 0
                    val name = ep["name"]?.asText() ?: ep["title"]?.asText() ?: "Episode $epNum"
                    val pic = ep["cover"]?.asText() ?: ep["pic"]?.asText() ?: ep["image"]?.asText()
                    if (epNum > 0) {
                        addEp(epNum, name, pic)
                    }
                }

                if (data.isArray) {
                    data.forEach { parseEpNode(it) }
                } else if (epsNode.isArray) {
                    epsNode.forEach { parseEpNode(it) }
                } else if (epsNode.isInt) {
                    val count = epsNode.asInt()
                    val idToUse = data["id"]?.asText() ?: bookId
                    for (i in 1..count) {
                         val epData = "$source|$idToUse|$i|generic"
                         episodes.add(newEpisode(epData) {
                            this.name = "Episode $i"
                            this.episode = i
                        })
                    }
                } else if (data["total_episodes"] != null) {
                    val count = data["total_episodes"].asInt()
                    for (i in 1..count) {
                        addEp(i)
                    }
                } else {
                   try {
                        val chaptersUrl = "$apiUrl/api/proxy/$source/chapters/$bookId"
                        val chapterRes = app.get(chaptersUrl).text
                        val chNode = mapper.readTree(chapterRes).get("data")?.get("chapterList")
                        
                        if (chNode != null && chNode.isArray) {
                            chNode.forEach { node ->
                                val idx = node.get("chapterIndex")?.asInt() ?: 0
                                val epNum = idx + 1 
                                val name = node.get("chapterName")?.asText() ?: "Episode $epNum"
                                val pic = node.get("cover")?.asText() ?: node.get("pic")?.asText()
                                addEp(epNum, name, pic)
                            }
                        }
                   } catch (e: Exception) {}
                }
                if (episodes.isNotEmpty()) detailSuccess = true
            } catch (e: Exception) {}
        }
        
        if (!detailSuccess || episodes.isEmpty()) {
             try {
                 val bookUrl = "$apiUrl/api/proxy/$source/book/$bookId?lang=in"
                 val bookRes = app.get(bookUrl).text
                 val bookData = mapper.readValue<BookResponse>(bookRes).data?.book
                 
                 if (bookData != null) {
                     if (!bookData.intro.isNullOrEmpty()) plot = bookData.intro
                     if (!bookData.labels.isNullOrEmpty()) tags = bookData.labels
                 }
                 
                 val chaptersUrl = "$apiUrl/api/proxy/$source/chapters/$bookId?lang=in"
                 val chaptersRes = app.get(chaptersUrl).text
                 val chapters = mapper.readValue<ChapterResponse>(chaptersRes).data?.list
                 
                 chapters?.forEach { ch ->
                     val idx = ch.index ?: 0
                     val epNum = idx + 1
                     val name = ch.chapterName ?: "Episode $epNum"
                     val pic = ch.image
                     val cdn = ch.cdn
                     
                     val epData = if (!cdn.isNullOrEmpty()) {
                         "$cdn||$epNum|direct"
                     } else {
                         "$source|$bookId|$epNum|generic"
                     }
                     
                     episodes.add(newEpisode(epData) {
                         this.name = name
                         this.episode = epNum
                         this.posterUrl = pic
                     })
                 }
             } catch (e: Exception) {}
        }
        
        if (episodes.isEmpty()) {
             val count = item.episodes_count ?: 1
             for (i in 1..count) {
                  val epData = "$source|$bookId|$i|generic"
                  episodes.add(newEpisode(epData) {
                      this.name = "Episode $i"
                      this.episode = i
                  })
             }
        }

        return newTvSeriesLoadResponse(item.title, url, TvType.TvSeries, episodes) {
            this.posterUrl = item.cover
            this.plot = plot
            this.tags = tags
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val parts = data.split("|")
        val rawSource = parts[0]
        val source = if (rawSource.startsWith("http")) {
            rawSource.trimEnd('/').substringAfterLast('/')
        } else {
            rawSource
        }
        
        if (parts.size >= 4 && parts[3] == "direct") {
             val directUrl = parts[0]
             if (directUrl.startsWith("http")) {
                 val headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Referer" to "$apiUrl/"
                )
                 callback.invoke(
                     newExtractorLink(
                         source = "ShortStream",
                         name = "ShortStream Direct",
                         url = directUrl,
                         type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                     ) {
                         this.referer = "$apiUrl/"
                         this.headers = headers
                     }
                 )
                 return true
             }
        }
        
        if (parts.size < 3) return false
        val id = parts[1]
        val index = parts[2]
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$apiUrl/"
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
                    this.referer = "$apiUrl/"
                    this.headers = headers
                }
            )
        }

        val candidateUrls = listOf(
            "$apiUrl/api/proxy/$source/play/$id?ep=$index&lang=id",
            "$apiUrl/api/proxy/$source-video/episode/$id/$index?lang=in",
            "$apiUrl/api/proxy/$source/watch/player?bookId=$id&index=$index&lang=in"
        )
        
        for (url in candidateUrls) {
            try {
                val responseText = app.get(url).text
                
                try {
                    val rsResponse = mapper.readValue<SimplePlayerResponse>(responseText)
                    if (!rsResponse.video_url.isNullOrEmpty()) {
                        addLink(source, rsResponse.video_url)
                        return true
                    }
                } catch (e: Exception) {}

                try {
                     val pResponse = mapper.readValue<PlayerResponse>(responseText)
                     if (pResponse.data != null) {
                         parsePlayerResponse(pResponse, ::addLink)
                         return true
                     }
                } catch (e: Exception) {}
                
            } catch (e: Exception) {
            }
        }
        
        return false
    }

    private suspend fun parsePlayerResponse(response: PlayerResponse, addLink: suspend (String, String) -> Unit) {
        val d = response.data
        val videoUrl = d?.videoUrl ?: d?.hls_url ?: d?.m3u8_url
        if (!videoUrl.isNullOrEmpty()) {
            addLink("ShortStream", videoUrl)
        }
        
        val qAny = d?.qualities
        if (qAny is List<*>) {
             for (q in qAny) {
                 if (q is Map<*, *>) { 
                     val url = q["videoUrl"] as? String
                     val qual = q["quality"]?.toString() ?: "SD"
                     if (!url.isNullOrEmpty() && url != videoUrl) {
                         addLink("ShortStream $qual", url)
                     }
                 }
             }
        } else if (qAny is Map<*, *>) {
            for ((k, v) in qAny) {
                val url = v as? String
                if (!url.isNullOrEmpty() && url != videoUrl) {
                    addLink("ShortStream $k", url)
                }
            }
        }
    }
}
