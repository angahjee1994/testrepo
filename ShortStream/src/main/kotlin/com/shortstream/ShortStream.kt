package com.shortstream

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        "dotdrama" to "DotDrama",
        "starshort" to "StarShort",
        "meloshort" to "MeloShort",
        "goodshort" to "GoodShort",
        "viglo" to "Viglo",
        "stardusttv" to "StardustTV"
    )

    override val mainPage = mainPageOf(
        *providerLabels.map { (k, v) -> v to k }.toTypedArray()
    )

    private val noLangSources = setOf("starshort", "dotdrama")

    private fun searchUrl(source: String, query: String): String {
        return if (source == "netshort") "$mainUrl/api/proxy/netshort/find?q=$query"
        else if (source in noLangSources) "$mainUrl/api/proxy/$source/search?q=$query"
        else "$mainUrl/api/proxy/$source/search?q=$query&lang=id"
    }

    private fun parseItems(source: String, json: String): List<JsonNode> {
        val root = mapper.readTree(json)
        return when (source) {
            "netshort" -> root.path("data").path("data").path("searchCodeSearchResult").toList()
            "dramawave" -> root.path("data").path("items").toList()
            "goodshort" -> root.path("data").path("searchResult").path("records").toList()
                .ifEmpty { root.path("data").path("otherSearchResults").toList() }
            "meloshort" -> root.path("data").let { if (it.isArray) it.toList() else listOf(it) }
            "dotdrama" -> root.path("results").toList()
            "stardusttv" -> root.path("data").path("data").toList()
            "hishort" -> root.path("source").toList()
            "starshort" -> root.path("data").toList()
            else -> {
                val data = root.path("data")
                when {
                    data.isArray -> data.toList()
                    data.has("list") -> data.path("list").toList()
                    else -> emptyList()
                }
            }
        }
    }

    private fun JsonNode.text(vararg paths: String): String {
        for (path in paths) {
            val txt = this.path(path).asText("")
            if (txt.isNotEmpty()) return txt
        }
        return ""
    }

    private fun getId(item: JsonNode): String {
        return item.text("shortPlayId", "code", "key", "id", "drama_id", "dramaId", "dcup", "compilationsId", "fakeId", "bookId")
    }

    private fun getTitle(item: JsonNode): String {
        val raw = item.text(
            "shortPlayName", "nseri", "english_name", "bookName", 
            "drama_title", "title", "name"
        )
        return raw.replace(Regex("<[^>]+>"), "")
    }

    private fun getCover(item: JsonNode): String? {
        return item.text(
            "shortPlayCover", "pday", "alioss_cover", "cover_path", 
            "coverImgUrl", "drama_cover", "cover"
        ).let { if (it.isEmpty()) null else it }
    }

    private fun getPlayCount(item: JsonNode): String? {
        return item.text("playCount", "formatHeatScore", "fav_count", "plays_num")
            .let { if (it.isEmpty()) null else it }
    }

    private fun toResult(source: String, item: JsonNode): SearchResponse? {
        val id = getId(item).ifEmpty { return null }
        val rawTitle = getTitle(item).ifEmpty { return null }
        val views = getPlayCount(item)
        val displayTitle = if (views != null) "$rawTitle [$views]" else rawTitle
        return newAnimeSearchResponse(displayTitle, "$mainUrl/watch/$source/$id/1", TvType.AsianDrama, false) {
            this.posterUrl = getCover(item)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val source = request.data
        try {
            val url = "$mainUrl/api/vendors/explore/$source?page=$page&size=50"
            val res = app.get(url).text
            val root = mapper.readTree(res)
            
            val itemsNode = if (root.has("items")) root.path("items") else root.path("data")
            val items = if (itemsNode.isArray) itemsNode.toList() else emptyList()
            
            val home = items.mapNotNull { toResult(source, it) }
            val hasNext = root.path("hasMore").asBoolean(false)
            
            if (home.isNotEmpty()) return newHomePageResponse(request.name, home, hasNext)
        } catch (_: Exception) {}

        return newHomePageResponse(request.name, emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        providerLabels.keys.map { source ->
            async {
                try {
                    val res = app.get(searchUrl(source, query)).text
                    parseItems(source, res).mapNotNull { toResult(source, it) }
                } catch (e: Exception) {
                    emptyList<SearchResponse>()
                }
            }
        }.awaitAll().flatten()
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
        var rating: String? = null
        var year: Int? = null

        val detailUrls = when (source) {
            "netshort" -> listOf("$mainUrl/api/proxy/netshort/info/$id")
            "shortmax" -> listOf("$mainUrl/api/proxy/shortmax/detail/$id")
            "goodshort" -> listOf("$mainUrl/api/proxy/goodshort/chapters/$id")
            "meloshort" -> listOf("$mainUrl/api/proxy/meloshort/drama/$id")
            "dotdrama" -> listOf("$mainUrl/api/proxy/dotdrama/drama/$id")
            "starshort" -> listOf("$mainUrl/api/proxy/starshort/drama/$id?lang=4")
            else -> listOf(
                "$mainUrl/api/proxy/$source/drama/$id",
                "$mainUrl/api/proxy/$source/detail/$id"
            )
        }

        val searchMetaSources = setOf("meloshort", "goodshort", "dotdrama", "stardusttv", "hishort", "starshort")

        if (source in searchMetaSources || source == "radreel") {
            try {
                val q = if (source in searchMetaSources) "" else "a"
                val searchRes = app.get(searchUrl(source, q), timeout = 10).text
                val root = mapper.readTree(searchRes)
                val itemsNode = if (root.has("items")) root.path("items") else root.path("data")
                val items = if (itemsNode.isArray) itemsNode.toList() else parseItems(source, searchRes)
                val match = items.firstOrNull { getId(it) == id || it.text("fakeId", "id") == id }

                if (match != null) {
                    title = getTitle(match)
                    cover = getCover(match)
                    plot = match.text("description", "introduction", "intro", "dwill", "introduce", "vidDescribe").let { if (it.isEmpty()) null else it }
                    epCount = maxOf(
                        match.path("chapterTotal").asInt(0),
                        match.path("chapterCount").asInt(0),
                        match.path("ewood").asInt(0),
                        match.path("episode_total").asInt(0),
                        match.path("totalNum").asInt(0),
                        match.path("episodes").asInt(0)
                    )
                    rating = match.text("fav_count", "viewCountDisplay", "plays_num", "views").let { if (it.isEmpty()) null else it }
                    
                    val tagArray = match.path("subTags")
                        .let { if (it.isMissingNode || it.isNull) match.path("labels") else it }
                        .let { if (it.isMissingNode || it.isNull) match.path("tagList") else it }
                        .let { if (it.isMissingNode || it.isNull) match.path("search_label") else it }
                        .let { if (it.isMissingNode || it.isNull) match.path("tags") else it }
                        
                    if (tagArray.isArray && tagArray.size() > 0) {
                         tags = tagArray.map { 
                             if (it.isObject) it.text("english_name", "title").ifEmpty { it.asText("") } else it.asText("")
                         }.filter { it.isNotEmpty() }
                    }
                }
            } catch (_: Exception) {}
        }

        for (dUrl in detailUrls) {
            if (title.isNotEmpty()) break
            try {
                val res = app.get(dUrl).text
                val root = mapper.readTree(res)
                var data = root.path("data")
                if (data.isMissingNode) data = root
                val node = if (source == "netshort" && data.has("data")) data.path("data") else data

                title = getTitle(node)
                if (title.isEmpty()) title = getTitle(root)

                if (cover.isNullOrEmpty()) cover = getCover(node)
                if (cover.isNullOrEmpty()) cover = getCover(root)

                if (plot == null) plot = node.text("introduction", "summary", "shotIntroduce", "intro", "desc", "description").let { if (it.isEmpty()) null else it }
                if (plot == null) plot = root.text("introduction", "summary", "shotIntroduce", "intro", "desc", "description").let { if (it.isEmpty()) null else it }

                epCount = maxOf(
                    epCount,
                    node.path("chapterCount").asInt(0),
                    node.path("episodes").asInt(0),
                    node.path("episode_count").asInt(0),
                    node.path("totalEpisodeCount").asInt(0),
                    root.path("chapterCount").asInt(0),
                    root.path("episodes").asInt(0),
                    root.path("episode_count").asInt(0),
                    root.path("totalEpisodeCount").asInt(0)
                )

                if (rating == null) rating = node.text("playCount", "formatHeatScore", "views", "view_count").let { if (it.isEmpty()) null else it }

                val listingTime = node.path("listing_time").asLong(0)
                if (listingTime > 0) {
                    year = java.util.Calendar.getInstance().apply { timeInMillis = listingTime * 1000 }.get(java.util.Calendar.YEAR)
                }

                 if (tags == null) {
                    val tagNode = node.path("tags")
                        .let { if (it.isMissingNode || it.isNull) node.path("tagNames") else it }
                        .let { if (it.isMissingNode || it.isNull) node.path("series_tag") else it }
                        .let { if (it.isMissingNode || it.isNull) node.path("labelNameList") else it }
                    
                    if (tagNode.isArray && tagNode.size() > 0) {
                        tags = tagNode.map { it.asText().replace(Regex("<[^>]+>"), "") }
                    }
                }

                if (source == "shortmax") videoId = node.path("id").asText(id)

                if (source == "netshort") {
                    val resultArray = root.path("result")
                    if (resultArray.isArray && resultArray.size() > 0) {
                        epCount = maxOf(epCount, resultArray.size())
                    }
                }
            } catch (_: Exception) {}
        }

        if ((cover.isNullOrEmpty() || rating == null) && source !in searchMetaSources && source != "radreel") {
            val searchQuery = title.split(" ").firstOrNull()?.take(20) ?: ""
            for (q in listOf(searchQuery, "")) {
                if (cover?.isNotEmpty() == true && rating != null) break
                try {
                    val searchRes = app.get(searchUrl(source, q), timeout = 10).text
                    val items = parseItems(source, searchRes)
                    val match = items.firstOrNull { getId(it) == id || it.text("fakeId", "id") == id }
                    if (match != null) {
                        if (cover.isNullOrEmpty()) cover = getCover(match)
                        if (rating == null) rating = match.text("playCount", "formatHeatScore", "views").let { if (it.isEmpty()) null else it }
                    }
                } catch (_: Exception) {}
            }
        }

        val episodes = fetchEpisodes(source, id, videoId, epCount)

        if (title.isEmpty()) title = "Drama"

        val plotWithRating = listOfNotNull(
            rating?.let { "Views: $it" },
            plot
        ).joinToString("\n\n")

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = cover
            this.plot = plotWithRating
            this.tags = tags
            this.year = year
        }
    }

    private suspend fun fetchEpisodes(source: String, id: String, videoId: String, detailEpCount: Int): List<Episode> {
        val count = if (detailEpCount > 0) detailEpCount else 100
        return (1..count).map { ep ->
            newEpisode(LinkData(source, id, videoId, ep).toJson()) {
                this.name = "Episode $ep"
                this.episode = ep
            }
        }
    }

    // Helper data class for internal use
    data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private suspend fun simpleEpisodeExtract(
        url: String, 
        path: String, 
        extractor: (Int, JsonNode) -> Quadruple<Int, String, String?, String?> 
    ): List<Quadruple<Int, String, String?, String?>>? {
        return try {
            val res = app.get(url).text
            val root = mapper.readTree(res)
            var node = root
            path.split(".").forEach { node = node.path(it) }
            
            if (node.isArray && node.size() > 0) {
                node.mapIndexed { idx, item -> extractor(idx, item) }
            } else null
        } catch (e: Exception) { null }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LinkData>(data)
        val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)

        val handled = when (ld.source) {
            "netshort" -> checkNetshort(ld, headers, subtitleCallback, callback)
            "dramawave" -> checkDramawave(ld, headers, subtitleCallback, callback)
            else -> false
        }
        
        if (handled) return true

        val urls = when (ld.source) {
            "shortmax" -> listOf("$mainUrl/api/proxy/shortmax-video/episode/${ld.videoId}/${ld.episode}?lang=in")
            "reelshort" -> listOf("$mainUrl/api/proxy/reelshort/play/${ld.id}?ep=${ld.episode}&lang=id")
            "starshort" -> listOf("$mainUrl/api/proxy/starshort/play/${ld.id}?ep=${ld.episode}&lang=4")
            "meloshort" -> listOf("$mainUrl/api/proxy/meloshort/play/${ld.id}/${ld.episode}")
            "goodshort" -> listOf("$mainUrl/api/proxy/goodshort/chapters/${ld.id}")
            "dotdrama" -> listOf("$mainUrl/api/proxy/dotdrama/drama/${ld.id}")
            "radreel" -> listOf("$mainUrl/api/proxy/radreel/video/${ld.videoId}")
            "melolo" -> listOf("$mainUrl/api/proxy/melolo/video/${ld.videoId}")
            "dramawave" -> listOf("$mainUrl/api/proxy/dramawave3/dramas/${ld.id}/episodes", "$mainUrl/api/proxy/dramawave/dramas/${ld.id}/episodes")
            "dramabite" -> emptyList()
            else -> listOf("$mainUrl/api/proxy/${ld.source}3/watch/player?bookId=${ld.id}&index=${ld.episode}&lang=in")
        }

        for (playerUrl in urls) {
             try {
                val res = app.get(playerUrl, headers = headers).text
                val root = mapper.readTree(res)
                val node = root.path("data").let { if (it.isMissingNode) root else it }
                if (extractGenericLinks(node, ld, callback)) return true
             } catch (_: Exception) {}
        }
        
        return false
    }

    private suspend fun checkNetshort(ld: LinkData, headers: Map<String, String>, subCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val res = app.get("$mainUrl/api/proxy/netshort/info/${ld.id}", headers = headers).text
            val root = mapper.readTree(res)
            val resultArray = root.path("result")
            if (resultArray.isArray) {
                val ep = resultArray.firstOrNull { it.path("episodeNo").asInt(0) == ld.episode } ?: return false
                val videoUrl = ep.path("videoUrl").asText("")
                if (videoUrl.isNotEmpty()) {
                    callback.invoke(newExtractorLink(name, "NETSHORT", videoUrl, INFER_TYPE) { this.quality = Qualities.P1080.value })
                }
                ep.path("subtitles").forEach { sub ->
                    val subUrl = sub.path("url").asText("")
                    if (subUrl.isNotEmpty()) {
                        subCallback.invoke(SubtitleFile(sub.text("lang", "id_ID"), subUrl))
                    }
                }
                return videoUrl.isNotEmpty()
            }
        } catch (_: Exception) {}
        return false
    }

    private suspend fun checkDramawave(ld: LinkData, headers: Map<String, String>, subCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val res = app.get("$mainUrl/api/proxy/dramawave3/dramas/${ld.id}/episodes", headers = headers).text
            val epArray = mapper.readTree(res).path("episodes")
            val ep = epArray.firstOrNull { it.path("index").asInt(0) == ld.episode } ?: return false
            
            var found = false
            val h264 = ep.path("h264_url").asText("")
            if (h264.isNotEmpty()) {
                callback.invoke(newExtractorLink(name, "DRAMAWAVE H264", h264, INFER_TYPE) { this.quality = Qualities.P1080.value })
                found = true
            }
            val h265 = ep.path("h265_url").asText("")
            if (h265.isNotEmpty()) {
                callback.invoke(newExtractorLink(name, "DRAMAWAVE H265", h265, INFER_TYPE) { this.quality = Qualities.P1080.value })
                found = true
            }
            ep.path("subtitles").forEach { sub ->
                val subUrl = sub.path("subtitle").asText("")
                if (subUrl.isNotEmpty()) {
                    subCallback.invoke(SubtitleFile(sub.path("display_name").asText(sub.path("language").asText("")), subUrl))
                }
            }
            return found
        } catch (_: Exception) {}
        return false
    }

    private suspend fun extractGenericLinks(node: JsonNode, ld: LinkData, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        val sourceName = ld.source.uppercase()

        // Handle Array structures first (like DotDrama, GoodShort)
        if (node.isArray || node.has("list") || node.has("dgiv") || node.has("episodes")) {
            val list = when {
                node.isArray -> node
                node.has("list") -> node.path("list")
                node.has("episodes") -> node.path("episodes")
                ld.source == "dotdrama" -> {
                    val episodes = node.path("dgiv").path("ebeer")
                    if (episodes.isArray) {
                        episodes.firstOrNull { it.path("ewheel").asInt(0) == ld.episode } 
                            ?: episodes.get(ld.episode - 1) ?: node
                    } else node
                }
                else -> node
            }
            
            // GoodShort array check
            if (list.isArray) {
                val ch = list.firstOrNull { it.path("index").asInt(-1) == ld.episode }
                    ?: list.firstOrNull { it.path("index").asInt(-1) == ld.episode - 1 }
                
                if (ch != null) {
                    var innerNode = ch
                    if (ch.has("chapter")) innerNode = ch.path("chapter")
                    
                    val cdn = innerNode.path("cdn").asText("")
                    if (cdn.isNotEmpty()) {
                        callback.invoke(newExtractorLink(name, sourceName, cdn, INFER_TYPE) { this.quality = Qualities.P720.value })
                        found = true
                    }
                    val multiVideos = innerNode.path("multiVideos")
                    if (multiVideos.isArray && multiVideos.size() > 0) {
                        multiVideos.forEach { mv ->
                            val vUrl = mv.path("filePath").asText("")
                            if (vUrl.isNotEmpty()) {
                                val qualType = mv.path("type").asText("720p")
                                val qual = qualType.replace("p", "").toIntOrNull() ?: 720
                                callback.invoke(newExtractorLink(name, "$sourceName $qualType", vUrl, INFER_TYPE) { this.quality = qual })
                                found = true
                            }
                        }
                    }
                    if (found) return true
                }

                // Melolo array check
                list.forEach { item ->
                    val encoded = item.path("url").asText("")
                    if (encoded.isNotEmpty()) {
                        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT))
                        if (decoded.startsWith("http")) {
                            val def = item.path("definition").asText("720p")
                            callback.invoke(newExtractorLink(name, "$sourceName $def", decoded, INFER_TYPE) { 
                                this.quality = def.replace("p", "").toIntOrNull() ?: 720 
                            })
                            found = true
                        }
                    }
                }
            }

            // Dotdrama `pphys` array
            val pphys = list.path("pphys")
            if (pphys.isArray && pphys.size() > 0) {
                pphys.forEach { vid ->
                    val vUrl = vid.text("Mopp", "Bcold")
                    if (vUrl.isNotEmpty()) {
                         val qualType = vid.path("Dbag").asText("720P")
                         val qual = qualType.replace("P", "").replace("p", "").toIntOrNull() ?: 720
                         callback.invoke(newExtractorLink(name, "$sourceName $qualType", vUrl, INFER_TYPE) { this.quality = qual })
                         found = true
                    }
                }
            }
            if (found) return true
        }
        val qualities = node.path("qualities")
        if (qualities.isArray && qualities.size() > 0) {
            qualities.forEach { q ->
                val vUrl = q.text("videoUrl", "url")
                val qual = q.path("quality").asInt(720)
                if (vUrl.isNotEmpty()) {
                    callback.invoke(newExtractorLink(name, "$sourceName ${qual}p", vUrl, INFER_TYPE) { this.quality = qual })
                    found = true
                }
            }
            return found
        }

        if (qualities.isObject && qualities.size() > 0) {
            qualities.fieldNames().forEach { qualLabel ->
                if (qualLabel != "default") {
                    val vUrl = qualities.path(qualLabel).asText("")
                    val qual = qualLabel.replace("p", "").toIntOrNull() ?: 720
                    if (vUrl.isNotEmpty()) {
                        callback.invoke(newExtractorLink(name, "$sourceName $qualLabel", vUrl, INFER_TYPE) { this.quality = qual })
                        found = true
                    }
                }
            }
            return found
        }

        val hlsUrl = node.path("hls_url").asText("")
        if (hlsUrl.startsWith("http")) {
            callback.invoke(newExtractorLink(name, sourceName, hlsUrl, INFER_TYPE) { this.quality = Qualities.P720.value })
            return true
        }

        val linkInfo = node.path("link_info")
        val linkUrl = linkInfo.text("video_link_m3u8", "video_link")
        if (linkUrl.isNotEmpty()) {
             callback.invoke(newExtractorLink(name, sourceName, linkUrl, INFER_TYPE) { this.quality = Qualities.P720.value })
             return true
        }

        listOf("videoUrl", "video_url", "m3u8_url", "url", "playUrl", "external_audio_h264_m3u8").forEach { field ->
            val vUrl = node.path(field).asText("")
            if (vUrl.isNotEmpty() && vUrl.startsWith("http")) {
                callback.invoke(newExtractorLink(name, sourceName, vUrl, INFER_TYPE) { this.quality = Qualities.P1080.value })
                found = true
            }
        }
        return found
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
