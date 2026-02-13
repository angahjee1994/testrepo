package com.shortstream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

class ShortStream : MainAPI() {
    override var mainUrl = "https://rishort.com"
    override var name = "ShortStream"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.AsianDrama)

    private val mapper = jacksonObjectMapper()

    private val providerLabels = mapOf(
        "dramabox" to "DramaBox",
        "shortmax" to "ShortMax",
        "reelshort" to "ReelShort",
        "netshort" to "NetShort",
        "dramawave" to "DramaWave",
        "melolo" to "Melolo",
        "radreel" to "RadReel",
        "flick" to "Flick",
        "dotdrama" to "DotDrama",
        "starshort" to "StarShort",
        "meloshort" to "MeloShort",
        "goodshort" to "GoodShort",
        "viglo" to "Viglo",
        "stardusttv" to "StardustTV",
        "dramabite" to "DramaBite",
        "hishort" to "HiShort",
        "dramadash" to "DramaDash"
    )

    override val mainPage = mainPageOf(
        *providerLabels.map { (k, v) -> k to v }.toTypedArray()
    )

    private fun searchUrl(source: String, query: String): String {
        if (source == "netshort") return "$mainUrl/api/proxy/netshort/find?q=$query"
        return "$mainUrl/api/proxy/$source/search?q=$query&lang=id"
    }

    private fun parseItems(source: String, json: String): List<JsonNode> {
        val root = mapper.readTree(json)
        val data = root.path("data")
        return when {
            source == "netshort" -> data.path("data").path("searchCodeSearchResult").toList()
            source == "dramawave" -> data.path("items").toList()
            data.isArray -> data.toList()
            data.has("list") -> data.path("list").toList()
            else -> emptyList()
        }
    }

    private fun getId(source: String, item: JsonNode): String = when (source) {
        "netshort" -> item.path("shortPlayId").asText("")
        "shortmax" -> item.path("code").asText("")
        "reelshort" -> item.path("id").asText("")
        "dramawave" -> item.path("id").asText("")
        else -> item.path("bookId").asText("").ifEmpty { item.path("id").asText("") }
    }

    private fun getTitle(source: String, item: JsonNode): String {
        val raw = when (source) {
            "netshort" -> item.path("shortPlayName").asText("")
            "reelshort" -> item.path("title").asText("")
            else -> item.path("bookName").asText("").ifEmpty { item.path("name").asText("") }
                .ifEmpty { item.path("title").asText("") }
        }
        return raw.replace(Regex("<[^>]+>"), "")
    }

    private fun getCover(source: String, item: JsonNode): String? = when (source) {
        "netshort" -> item.path("shortPlayCover").asText(null)
        else -> item.path("cover").asText(null)
    }

    private fun toResult(source: String, item: JsonNode): SearchResponse? {
        val id = getId(source, item).ifEmpty { return null }
        val title = getTitle(source, item).ifEmpty { return null }
        return newAnimeSearchResponse(title, "$mainUrl/watch/$source/$id/1", TvType.AsianDrama, false) {
            this.posterUrl = getCover(source, item)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList())
        val source = request.data
        for (query in listOf("", "a")) {
            try {
                val res = app.get(searchUrl(source, query)).text
                val items = parseItems(source, res)
                val home = items.mapNotNull { toResult(source, it) }
                if (home.isNotEmpty()) return newHomePageResponse(request.name, home)
            } catch (_: Exception) {}
        }
        return newHomePageResponse(request.name, emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (source in providerLabels.keys) {
            try {
                val res = app.get(searchUrl(source, query)).text
                results.addAll(parseItems(source, res).mapNotNull { toResult(source, it) })
            } catch (_: Exception) {}
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val parts = url.removePrefix("$mainUrl/watch/").split("/")
        val source = parts.getOrNull(0) ?: throw ErrorLoadingException("Invalid URL")
        val id = parts.getOrNull(1) ?: throw ErrorLoadingException("Invalid URL")

        var title = ""
        var plot: String? = null
        var cover: String? = null
        var epCount = 0
        var tags: List<String>? = null
        var videoId = id

        val detailUrls = when (source) {
            "netshort" -> listOf("$mainUrl/api/proxy/netshort/info/$id")
            "shortmax" -> listOf("$mainUrl/api/proxy/shortmax/detail/$id")
            else -> listOf(
                "$mainUrl/api/proxy/$source/drama/$id",
                "$mainUrl/api/proxy/$source/detail/$id"
            )
        }

        for (dUrl in detailUrls) {
            if (title.isNotEmpty()) break
            try {
                val res = app.get(dUrl).text
                val root = mapper.readTree(res)
                val data = root.path("data").let { if (it.isMissingNode) root else it }
                val node = if (source == "netshort" && data.has("data")) data.path("data") else data

                title = getTitle(source, node)
                cover = getCover(source, node)

                plot = node.path("introduction").asText(null)
                    ?: node.path("summary").asText(null)
                    ?: node.path("shotIntroduce").asText(null)
                    ?: node.path("desc").asText(null)
                    ?: node.path("description").asText(null)

                epCount = maxOf(
                    node.path("chapterCount").asInt(0),
                    node.path("episodes").asInt(0),
                    node.path("episode_count").asInt(0),
                    node.path("totalEpisodeCount").asInt(0)
                )

                val tagNode = node.path("tags").let {
                    if (it.isMissingNode || it.isNull) node.path("tagNames").let { tn ->
                        if (tn.isMissingNode || tn.isNull) node.path("series_tag").let { st ->
                            if (st.isMissingNode || st.isNull) node.path("labelNameList") else st
                        } else tn
                    } else it
                }
                if (tagNode.isArray && tagNode.size() > 0) {
                    tags = tagNode.map { it.asText().replace(Regex("<[^>]+>"), "") }
                }

                if (source == "shortmax") videoId = node.path("id").asText(id)
            } catch (_: Exception) {}
        }

        if (epCount <= 0) epCount = fetchEpisodeCount(source, id) ?: 100
        if (title.isEmpty()) title = "Drama"

        val episodes = (1..epCount).map { ep ->
            newEpisode(LinkData(source, id, videoId, ep).toJson()) {
                this.name = "Episode $ep"
                this.episode = ep
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = cover
            this.plot = plot
            this.tags = tags
        }
    }

    private suspend fun fetchEpisodeCount(source: String, id: String): Int? {
        val urls = when (source) {
            "dramawave" -> listOf("$mainUrl/api/proxy/dramawave3/dramas/$id/episodes")
            else -> listOf("$mainUrl/api/proxy/$source/chapters/$id")
        }
        for (u in urls) {
            try {
                val root = mapper.readTree(app.get(u).text)
                val data = root.path("data")
                if (data.isArray && data.size() > 0) return data.size()
                val cl = data.path("chapterList")
                if (cl.isArray && cl.size() > 0) return cl.size()
            } catch (_: Exception) {}
        }
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LinkData>(data)
        val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)

        val urls = when (ld.source) {
            "shortmax" -> listOf(
                "$mainUrl/api/proxy/shortmax-video/episode/${ld.videoId}/${ld.episode}?lang=in",
                "$mainUrl/api/proxy/shortmax3/watch/player?bookId=${ld.id}&index=${ld.episode}&lang=in"
            )
            else -> listOf(
                "$mainUrl/api/proxy/${ld.source}3/watch/player?bookId=${ld.id}&index=${ld.episode}&lang=in"
            )
        }

        for (playerUrl in urls) {
            try {
                val res = app.get(playerUrl, headers = headers).text
                val root = mapper.readTree(res)
                val node = root.path("data").let { if (it.isMissingNode) root else it }

                val qualities = node.path("qualities")
                if (qualities.isArray && qualities.size() > 0) {
                    qualities.forEach { q ->
                        val vUrl = q.path("videoUrl").asText("").ifEmpty { q.path("url").asText("") }
                        val qual = q.path("quality").asInt(720)
                        if (vUrl.isNotEmpty()) {
                            callback.invoke(newExtractorLink(
                                name, "${ld.source.uppercase()} ${qual}p", vUrl, INFER_TYPE
                            ) { this.quality = qual })
                        }
                    }
                    return true
                }

                for (field in listOf("videoUrl", "video_url", "m3u8_url", "url", "playUrl", "external_audio_h264_m3u8")) {
                    val vUrl = node.path(field).asText("")
                    if (vUrl.isNotEmpty() && vUrl.startsWith("http")) {
                        callback.invoke(newExtractorLink(
                            name, ld.source.uppercase(), vUrl, INFER_TYPE
                        ) { this.quality = Qualities.P1080.value })
                        return true
                    }
                }
            } catch (_: Exception) {}
        }
        return false
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    data class LinkData(
        val source: String,
        val id: String,
        val videoId: String,
        val episode: Int
    )
}
