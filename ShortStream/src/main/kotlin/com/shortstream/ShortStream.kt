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
        "hishort" to "HiShort"
    )

    override val mainPage = mainPageOf(
        *providerLabels.map { (k, v) -> k to v }.toTypedArray()
    )

    private val noLangSources = setOf("starshort", "dotdrama", "hishort")

    private fun searchUrl(source: String, query: String): String {
        if (source == "netshort") return "$mainUrl/api/proxy/netshort/find?q=$query"
        if (source in noLangSources) return "$mainUrl/api/proxy/$source/search?q=$query"
        return "$mainUrl/api/proxy/$source/search?q=$query&lang=id"
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

    private fun getId(source: String, item: JsonNode): String = when (source) {
        "netshort" -> item.path("shortPlayId").asText("")
        "shortmax" -> item.path("code").asText("")
        "reelshort", "dramawave", "stardusttv" -> item.path("id").asText("")
        "meloshort" -> item.path("dramaId").asText("").ifEmpty { item.path("id").asText("") }
        "goodshort" -> item.path("bookId").asText("")
        "dotdrama" -> item.path("dcup").asText("")
        "hishort" -> item.path("vidId").asText("")
        "starshort" -> item.path("compilationsId").asText("").ifEmpty { item.path("fakeId").asText("") }
        else -> item.path("bookId").asText("").ifEmpty { item.path("id").asText("") }
    }

    private fun getTitle(source: String, item: JsonNode): String {
        val raw = when (source) {
            "netshort" -> item.path("shortPlayName").asText("").ifEmpty { item.path("name").asText("") }
            "dotdrama" -> item.path("nseri").asText("").ifEmpty { item.path("name").asText("") }
            "stardusttv" -> item.path("english_name").asText("").ifEmpty { item.path("name").asText("") }
            "hishort" -> item.path("vidName").asText("").ifEmpty { item.path("name").asText("") }
            "dramabox", "goodshort", "melolo", "flick", "viglo" -> item.path("bookName").asText("").ifEmpty { item.path("name").asText("") }
            "reelshort", "meloshort", "starshort", "dramawave", "radreel", "dramabite" -> item.path("title").asText("").ifEmpty { item.path("name").asText("") }
            "shortmax" -> item.path("name").asText("").ifEmpty { item.path("title").asText("") }
            else -> item.path("bookName").asText("").ifEmpty { item.path("name").asText("") }
                .ifEmpty { item.path("title").asText("") }
        }
        return raw.replace(Regex("<[^>]+>"), "")
    }

    private fun getCover(source: String, item: JsonNode): String? = when (source) {
        "netshort" -> item.path("shortPlayCover").asText(null) ?: item.path("cover").asText(null)
        "dotdrama" -> item.path("pday").asText(null) ?: item.path("cover").asText(null)
        "stardusttv" -> item.path("alioss_cover").asText(null) ?: item.path("cover_path").asText(null) ?: item.path("cover").asText(null)
        "hishort" -> item.path("coverUrl").asText(null) ?: item.path("cover").asText(null)
        "starshort" -> item.path("coverImgUrl").asText(null) ?: item.path("cover").asText(null)
        "dramabite" -> item.path("cover_url").asText(null) ?: item.path("cover").asText(null)
        "dramabox", "shortmax", "reelshort", "dramawave", "melolo", "radreel", "flick", "meloshort", "goodshort", "viglo" -> item.path("cover").asText(null)
        else -> item.path("cover").asText(null)
    }

    private fun getPlayCount(source: String, item: JsonNode): String? = when (source) {
        "dramabox", "dramawave", "melolo", "radreel", "flick", "viglo", "dramabite", "shortmax" ->
            item.path("playCount").asText(null) ?: item.path("formatHeatScore").asText(null)
        "stardusttv" -> item.path("plays_num").asText(null)
        else -> null
    }

    private fun toResult(source: String, item: JsonNode): SearchResponse? {
        val id = getId(source, item).ifEmpty { return null }
        val rawTitle = getTitle(source, item).ifEmpty { return null }
        val views = getPlayCount(source, item)
        val displayTitle = if (views != null) "$rawTitle [$views]" else rawTitle
        return newAnimeSearchResponse(displayTitle, "$mainUrl/watch/$source/$id/1", TvType.AsianDrama, false) {
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
        var rating: String? = null
        var year: Int? = null

        val detailUrls = when (source) {
            "netshort" -> listOf("$mainUrl/api/proxy/netshort/info/$id")
            "shortmax" -> listOf("$mainUrl/api/proxy/shortmax/detail/$id")
            "goodshort" -> listOf("$mainUrl/api/proxy/goodshort/chapters/$id")
            "meloshort" -> listOf("$mainUrl/api/proxy/meloshort/drama/$id")
            else -> listOf(
                "$mainUrl/api/proxy/$source/drama/$id",
                "$mainUrl/api/proxy/$source/detail/$id"
            )
        }

        val searchMetaSources = setOf("meloshort", "goodshort", "dotdrama", "stardusttv", "hishort", "starshort")
        if (source in searchMetaSources) {
            try {
                val searchRes = app.get(searchUrl(source, ""), timeout = 10).text
                val items = parseItems(source, searchRes)
                val match = items.firstOrNull { getId(source, it) == id }
                if (match != null) {
                    title = getTitle(source, match)
                    cover = getCover(source, match)
                    plot = match.path("description").asText(null)
                        ?: match.path("introduction").asText(null)
                        ?: match.path("intro").asText(null)
                        ?: match.path("dwill").asText(null)
                        ?: match.path("introduce").asText(null)
                        ?: match.path("vidDescribe").asText(null)
                    epCount = maxOf(
                        match.path("chapterTotal").asInt(0),
                        match.path("chapterCount").asInt(0),
                        match.path("ewood").asInt(0),
                        match.path("episode_total").asInt(0),
                        match.path("totalNum").asInt(0)
                    )
                    rating = match.path("fav_count").asText(null)
                        ?: match.path("viewCountDisplay").asText(null)
                        ?: match.path("plays_num").asText(null)
                    val tagArray = match.path("subTags")
                        .let { if (it.isMissingNode || it.isNull) match.path("labels") else it }
                        .let { if (it.isMissingNode || it.isNull) match.path("tagList") else it }
                        .let { if (it.isMissingNode || it.isNull) match.path("search_label") else it }
                    if (tagArray.isArray && tagArray.size() > 0) {
                        tags = tagArray.map {
                            it.path("english_name").asText("").ifEmpty {
                                it.path("title").asText("").ifEmpty { it.asText("") }
                            }
                        }.filter { it.isNotEmpty() }
                    }
                }
            } catch (_: Exception) {}
        }

        if (source == "radreel") {
            try {
                val searchRes = app.get(searchUrl(source, ""), timeout = 10).text
                val items = parseItems(source, searchRes)
                val match = items.firstOrNull { getId(source, it) == id }
                if (match != null) {
                    title = getTitle(source, match)
                    cover = getCover(source, match)
                    plot = match.path("intro").asText(null)
                    epCount = match.path("episodes").asInt(0)
                    rating = match.path("views").let { if (it.isMissingNode || it.isNull) null else it.asText(null) }
                    val tagArray = match.path("tags")
                    if (tagArray.isArray && tagArray.size() > 0) {
                        tags = tagArray.map { it.asText("") }.filter { it.isNotEmpty() }
                    }
                }
            } catch (_: Exception) {}
        }

        for (dUrl in detailUrls) {
            if (title.isNotEmpty()) break
            try {
                val res = app.get(dUrl).text
                val root = mapper.readTree(res)
                val data = root.path("data").let { if (it.isMissingNode) root else it }
                val node = if (source == "netshort" && data.has("data")) data.path("data") else data

                title = getTitle(source, node)
                if (cover.isNullOrEmpty()) cover = getCover(source, node)

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

                rating = node.path("playCount").asText(null)
                    ?: node.path("formatHeatScore").asText(null)
                    ?: node.path("views").asText(null)
                    ?: node.path("view_count").asText(null)

                val listingTime = node.path("listing_time").asLong(0)
                if (listingTime > 0) {
                    year = java.util.Calendar.getInstance().apply {
                        timeInMillis = listingTime * 1000
                    }.get(java.util.Calendar.YEAR)
                }

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
                    val match = items.firstOrNull { getId(source, it) == id }
                    if (match != null) {
                        if (cover.isNullOrEmpty()) cover = getCover(source, match)
                        if (rating == null) rating = match.path("playCount").asText(null)
                            ?: match.path("formatHeatScore").asText(null)
                            ?: match.path("views").asText(null)
                    }
                } catch (_: Exception) {}
            }
        }

        val episodes = fetchEpisodes(source, id, videoId, epCount)

        if (title.isEmpty()) title = "Drama"

        val plotWithRating = if (rating != null && plot != null) {
            "Views: $rating\n\n$plot"
        } else if (rating != null) {
            "Views: $rating"
        } else plot

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = cover
            this.plot = plotWithRating
            this.tags = tags
            this.year = year
        }
    }

    private suspend fun fetchEpisodes(source: String, id: String, videoId: String, detailEpCount: Int): List<Episode> {
        when (source) {
            "reelshort" -> {
                try {
                    val res = app.get("$mainUrl/api/proxy/reelshort/episodes/$id").text
                    val root = mapper.readTree(res)
                    val data = root.path("data")
                    if (data.isArray && data.size() > 0) {
                        return data.mapIndexed { idx, ep ->
                            val epNum = ep.path("ep").asInt(idx)
                            val epName = ep.path("name").asText("Episode $epNum")
                            val epThumb = ep.path("pic").asText(null)
                            val linkData = LinkData(source, id, videoId, epNum).toJson()
                            newEpisode(linkData) {
                                this.name = epName
                                this.episode = epNum
                                this.posterUrl = epThumb
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            "meloshort" -> {
                try {
                    val res = app.get("$mainUrl/api/proxy/meloshort/drama/$id").text
                    val root = mapper.readTree(res)
                    val data = root.path("data")
                    if (data.isArray && data.size() > 0) {
                        return data.map { ch ->
                            val epNum = ch.path("chapter_index").asInt(1)
                            val epThumb = ch.path("first_frame").asText(null)
                            val linkData = LinkData(source, id, videoId, epNum).toJson()
                            newEpisode(linkData) {
                                this.name = "Episode $epNum"
                                this.episode = epNum
                                this.posterUrl = epThumb
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            "goodshort" -> {
                try {
                    val res = app.get("$mainUrl/api/proxy/goodshort/chapters/$id").text
                    val root = mapper.readTree(res)
                    val list = root.path("data").path("list")
                    if (list.isArray && list.size() > 0) {
                        return list.map { ch ->
                            val epNum = ch.path("index").asInt(0) + 1
                            val epThumb = ch.path("image").asText(null)
                            val epName = ch.path("chapterName").asText("Episode $epNum")
                            val linkData = LinkData(source, id, videoId, epNum).toJson()
                            newEpisode(linkData) {
                                this.name = epName
                                this.episode = epNum
                                this.posterUrl = epThumb
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            "netshort" -> {
                try {
                    val res = app.get("$mainUrl/api/proxy/netshort/info/$id").text
                    val root = mapper.readTree(res)
                    val resultArray = root.path("result")
                    if (resultArray.isArray && resultArray.size() > 0) {
                        return resultArray.map { ep ->
                            val epNum = ep.path("episodeNo").asInt(1)
                            val linkData = LinkData(source, id, videoId, epNum).toJson()
                            newEpisode(linkData) {
                                this.name = "Episode $epNum"
                                this.episode = epNum
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            "dramawave" -> {
                try {
                    val res = app.get("$mainUrl/api/proxy/dramawave3/dramas/$id/episodes").text
                    val root = mapper.readTree(res)
                    val epArray = root.path("episodes")
                    if (epArray.isArray && epArray.size() > 0) {
                        return epArray.map { ep ->
                            val epNum = ep.path("index").asInt(1)
                            val linkData = LinkData(source, id, videoId, epNum).toJson()
                            newEpisode(linkData) {
                                this.name = "Episode $epNum"
                                this.episode = epNum
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
            else -> {
                try {
                    val res = app.get("$mainUrl/api/proxy/$source/chapters/$id").text
                    val root = mapper.readTree(res)
                    val chapterList = root.path("data").path("chapterList")
                    if (chapterList.isArray && chapterList.size() > 0) {
                        return chapterList.map { ch ->
                            val chIdx = ch.path("chapterIndex").asInt(0)
                            val epNum = chIdx + 1
                            val linkData = LinkData(source, id, videoId, epNum).toJson()
                            newEpisode(linkData) {
                                this.name = "Episode $epNum"
                                this.episode = epNum
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        val count = if (detailEpCount > 0) detailEpCount else 100
        return (1..count).map { ep ->
            newEpisode(LinkData(source, id, videoId, ep).toJson()) {
                this.name = "Episode $ep"
                this.episode = ep
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val ld = parseJson<LinkData>(data)
        val headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to USER_AGENT)

        if (ld.source == "netshort") {
            try {
                val res = app.get("$mainUrl/api/proxy/netshort/info/${ld.id}", headers = headers).text
                val root = mapper.readTree(res)
                val resultArray = root.path("result")
                if (resultArray.isArray) {
                    val ep = resultArray.firstOrNull { it.path("episodeNo").asInt(0) == ld.episode }
                    if (ep != null) {
                        val videoUrl = ep.path("videoUrl").asText("")
                        if (videoUrl.isNotEmpty()) {
                            callback.invoke(newExtractorLink(
                                name, "NETSHORT", videoUrl, INFER_TYPE
                            ) { this.quality = Qualities.P1080.value })
                        }
                        val subs = ep.path("subtitles")
                        if (subs.isArray) {
                            subs.forEach { sub ->
                                val subUrl = sub.path("url").asText("")
                                val subLang = sub.path("lang").asText("id_ID")
                                if (subUrl.isNotEmpty()) {
                                    subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                                }
                            }
                        }
                        if (videoUrl.isNotEmpty()) return true
                    }
                }
            } catch (_: Exception) {}
        }

        if (ld.source == "dramawave") {
            try {
                val res = app.get("$mainUrl/api/proxy/dramawave3/dramas/${ld.id}/episodes", headers = headers).text
                val root = mapper.readTree(res)
                val epArray = root.path("episodes")
                if (epArray.isArray) {
                    val ep = epArray.firstOrNull { it.path("index").asInt(0) == ld.episode }
                    if (ep != null) {
                        val h264Url = ep.path("h264_url").asText("")
                        val h265Url = ep.path("h265_url").asText("")
                        if (h264Url.isNotEmpty()) {
                            callback.invoke(newExtractorLink(
                                name, "DRAMAWAVE H264", h264Url, INFER_TYPE
                            ) { this.quality = Qualities.P1080.value })
                        }
                        if (h265Url.isNotEmpty()) {
                            callback.invoke(newExtractorLink(
                                name, "DRAMAWAVE H265", h265Url, INFER_TYPE
                            ) { this.quality = Qualities.P1080.value })
                        }
                        val subs = ep.path("subtitles")
                        if (subs.isArray) {
                            subs.forEach { sub ->
                                val subUrl = sub.path("subtitle").asText("")
                                val subLang = sub.path("display_name").asText(sub.path("language").asText(""))
                                if (subUrl.isNotEmpty()) {
                                    subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                                }
                            }
                        }
                        if (h264Url.isNotEmpty()) return true
                    }
                }
            } catch (_: Exception) {}
        }

        if (ld.source == "goodshort") {
            try {
                val res = app.get("$mainUrl/api/proxy/goodshort/chapters/${ld.id}", headers = headers).text
                val root = mapper.readTree(res)
                val list = root.path("data").path("list")
                if (list.isArray) {
                    val epIndex = ld.episode - 1
                    val ch = list.firstOrNull { it.path("index").asInt(-1) == epIndex }
                    if (ch != null) {
                        val multiVideos = ch.path("multiVideos")
                        if (multiVideos.isArray && multiVideos.size() > 0) {
                            multiVideos.forEach { mv ->
                                val vUrl = mv.path("filePath").asText("")
                                val qualType = mv.path("type").asText("720p")
                                val qual = qualType.replace("p", "").toIntOrNull() ?: 720
                                if (vUrl.isNotEmpty()) {
                                    callback.invoke(newExtractorLink(
                                        name, "GOODSHORT $qualType", vUrl, INFER_TYPE
                                    ) { this.quality = qual })
                                }
                            }
                            return true
                        }
                        val cdn = ch.path("cdn").asText("")
                        if (cdn.isNotEmpty()) {
                            callback.invoke(newExtractorLink(
                                name, "GOODSHORT", cdn, INFER_TYPE
                            ) { this.quality = Qualities.P720.value })
                            return true
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (ld.source == "dotdrama") {
            try {
                val res = app.get(searchUrl("dotdrama", ""), headers = headers).text
                val items = parseItems("dotdrama", res)
                val match = items.firstOrNull { getId("dotdrama", it) == ld.id }
                if (match != null) {
                    val funi = match.path("funi")
                    if (funi.isArray && funi.size() > 0) {
                        funi.forEach { vid ->
                            val vUrl = vid.path("Mopp").asText("").ifEmpty { vid.path("Bcold").asText("") }
                            val qualType = vid.path("Dbag").asText("720P")
                            val qual = qualType.replace("P", "").replace("p", "").toIntOrNull() ?: 720
                            if (vUrl.isNotEmpty()) {
                                callback.invoke(newExtractorLink(
                                    name, "DOTDRAMA $qualType", vUrl, INFER_TYPE
                                ) { this.quality = qual })
                            }
                        }
                        return true
                    }
                }
            } catch (_: Exception) {}
        }

        if (ld.source == "dramabite") {
            try {
                val res = app.get("$mainUrl/api/proxy/dramabite/drama/${ld.id}/episode/${ld.episode}", headers = headers).text
                val root = mapper.readTree(res)
                val linkInfo = root.path("data").path("link_info")
                val vUrl = linkInfo.path("video_link_m3u8").asText("").ifEmpty { linkInfo.path("video_link").asText("") }
                if (vUrl.isNotEmpty()) {
                    callback.invoke(newExtractorLink(
                        name, "DRAMABITE", vUrl, INFER_TYPE
                    ) { this.quality = Qualities.P720.value })
                    return true
                }
            } catch (_: Exception) {}
        }

        val urls = when (ld.source) {
            "shortmax" -> listOf(
                "$mainUrl/api/proxy/shortmax-video/episode/${ld.videoId}/${ld.episode}?lang=in"
            )
            "dramabite" -> emptyList()
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

                if (qualities.isObject && qualities.size() > 0) {
                    qualities.fieldNames().forEach { qualLabel ->
                        if (qualLabel == "default") return@forEach
                        val vUrl = qualities.path(qualLabel).asText("")
                        val qual = qualLabel.replace("p", "").toIntOrNull() ?: 720
                        if (vUrl.isNotEmpty()) {
                            callback.invoke(newExtractorLink(
                                name, "${ld.source.uppercase()} $qualLabel", vUrl, INFER_TYPE
                            ) { this.quality = qual })
                        }
                    }
                    return true
                }

                val hlsUrl = node.path("hls_url").asText("")
                if (hlsUrl.isNotEmpty() && hlsUrl.startsWith("http")) {
                    callback.invoke(newExtractorLink(
                        name, ld.source.uppercase(), hlsUrl, INFER_TYPE
                    ) { this.quality = Qualities.P720.value })
                    return true
                }

                val linkInfo = node.path("link_info")
                val linkUrl = linkInfo.path("video_link_m3u8").asText("").ifEmpty { linkInfo.path("video_link").asText("") }
                if (linkUrl.isNotEmpty()) {
                    callback.invoke(newExtractorLink(
                        name, ld.source.uppercase(), linkUrl, INFER_TYPE
                    ) { this.quality = Qualities.P720.value })
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
