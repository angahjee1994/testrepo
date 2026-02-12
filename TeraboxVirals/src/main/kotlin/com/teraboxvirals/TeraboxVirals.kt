package com.teraboxvirals

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink

class TeraboxVirals : MainAPI() {
    override var mainUrl = "https://www.teraboxvirals.com"
    override var name = "TeraboxVirals"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest Posts",
        "search/label/Viral" to "Viral"
    )

    private val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m4v", "wmv")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    private suspend fun extractTeraboxLink(doc: org.jsoup.nodes.Document): String? {
        var tbLink = doc.select(".dlbutton a").firstOrNull {
            val href = it.attr("href")
            href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)
        }?.attr("href") ?: doc.select(".dlbutton a").firstOrNull()?.attr("href")

        if (tbLink == null) {
            tbLink = doc.select("a").firstOrNull {
                val href = it.attr("href")
                href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)
            }?.attr("href")
        }

        if (tbLink?.contains("downloadkatsini.com") == true) {
            try {
                tbLink = app.get(tbLink).document.selectFirst("a[href*=terabox], a[href*=1024tera]")?.attr("href")
            } catch (_: Exception) {}
        }
        return tbLink
    }

    private fun extractSurl(tbLink: String): String {
        val raw = if (tbLink.contains("surl=")) {
            tbLink.substringAfter("surl=").substringBefore("&")
        } else {
            tbLink.substringAfter("/s/").substringBefore("?")
        }
        return if (raw.startsWith("1")) raw.substring(1) else raw
    }

    private fun getApiDomain(tbLink: String): String {
        return if (tbLink.contains("1024tera")) "www.1024tera.com" else "www.terabox.com"
    }

    private suspend fun callListApi(surl: String, apiDomain: String, dirPath: String): String? {
        val folderApiUrl = "https://$apiDomain/share/list?app_id=250528&shorturl=$surl&dir=${java.net.URLEncoder.encode(dirPath, "UTF-8")}&root=${if (dirPath == "/") "1" else "0"}&web=1&channel=dubox&clienttype=0"
        val browserId = buildString {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            repeat(48) { append(chars.random()) }
            append("=")
        }
        val headers = mapOf(
            "accept" to "application/json, text/plain, */*",
            "accept-language" to "en-US,en;q=0.9",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "referer" to "https://$apiDomain/",
            "cookie" to "browserid=$browserId; lang=en"
        )
        for (attempt in 1..3) {
            try {
                val res = app.get(folderApiUrl, headers = headers).text
                if (res.contains("\"errno\":0") || res.contains("\"list\":[")) return res
                if (res.contains("need verify")) continue
                return null
            } catch (_: Exception) {}
        }
        return null
    }

    private fun parseThumbFromChunk(chunk: String): String? {
        return Regex("\"url3\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex("\"url2\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
            ?: Regex("\"url1\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
    }

    private suspend fun fetchFotoPoster(surl: String, apiDomain: String, depthLimit: Int = 5): String? {
        var fotoPoster: String? = null

        suspend fun scanForFoto(dirPath: String, depth: Int = 0) {
            if (depth > depthLimit || fotoPoster != null) return
            val json = callListApi(surl, apiDomain, dirPath) ?: return

            val isFotoFolder = dirPath.contains("Foto", ignoreCase = true)
            val items = json.split("\"fs_id\":")
            items.drop(1).forEach { chunk ->
                if (fotoPoster != null) return@forEach
                val isDir = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: "0"
                val filename = Regex("\"server_filename\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1) ?: return@forEach
                val itemPath = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")

                if (isDir == "1") {
                    if (!itemPath.isNullOrEmpty() && itemPath != dirPath) {
                        scanForFoto(itemPath, depth + 1)
                    }
                } else if (isFotoFolder) {
                    val ext = filename.substringAfterLast(".", "").lowercase()
                    if (imageExtensions.contains(ext)) {
                        val thumb = parseThumbFromChunk(chunk)
                        if (thumb != null) {
                            fotoPoster = thumb
                            println("Found Foto Poster: $thumb")
                        }
                    }
                }
            }
        }

        try {
            scanForFoto("/")
        } catch (e: Exception) {
            println("FotoPoster scan error: ${e.message}")
        }
        return fotoPoster
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (request.data.isEmpty()) {
            if (page > 1) return null
            mainUrl
        } else {
            if (page > 1) return null
            "$mainUrl/${request.data}"
        }

        val document = app.get(url).document
        val articles = document.select("article.blog-post")
        val home = articles.mapNotNull { element ->
            val linkElement = element.selectFirst(".post-filter-link") ?: element.selectFirst(".entry-title a") ?: return@mapNotNull null
            val title = element.selectFirst(".entry-title a")?.text() ?: element.selectFirst("img")?.attr("alt") ?: "Video"
            val href = linkElement.attr("href")
            val poster = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() && !it.contains("blank.gif") && !it.contains("pixel.gif") }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document
        return document.select("article.blog-post").mapNotNull { element ->
            val linkElement = element.selectFirst(".post-filter-link") ?: element.selectFirst(".entry-title a") ?: return@mapNotNull null
            val title = element.selectFirst(".entry-title a")?.text() ?: element.selectFirst("img")?.attr("alt") ?: "Video"
            val href = linkElement.attr("href")
            val poster = element.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() && !it.contains("blank.gif") }
            newMovieSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst(".entry-title")?.text()?.trim() ?: "Video"
        val tbLink = extractTeraboxLink(document)
        val isTerabox = !tbLink.isNullOrEmpty() && (tbLink.contains("terabox", ignoreCase = true) || tbLink.contains("1024tera", ignoreCase = true))

        var poster: String? = null
        val plot = document.select(".post-body").text().trim()
        val tags = document.select(".post-tag a").map { it.text() }

        val episodes = mutableListOf<Episode>()
        if (isTerabox) {
            val surl = extractSurl(tbLink!!)
            val apiDomain = getApiDomain(tbLink)
            var initialUk: String? = null
            var initialShareid: String? = null

            poster = fetchFotoPoster(surl, apiDomain)

            suspend fun scanForVideos(dirPath: String, depth: Int = 0) {
                if (depth > 5) return
                val json = callListApi(surl, apiDomain, dirPath) ?: return

                if (initialUk == null) {
                    initialUk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)?.takeIf { it != "0" }
                }
                val responseShareId = Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)
                    ?: Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(json)?.groupValues?.get(1)
                if (responseShareId != null) initialShareid = responseShareId

                val items = json.split("\"fs_id\":")
                items.drop(1).forEach { chunk ->
                    val fsid = Regex("^\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1)
                    val isDir = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: "0"
                    val filename = Regex("\"server_filename\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1) ?: return@forEach
                    val itemPath = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                    val bestThumb = parseThumbFromChunk(chunk)

                    if (isDir == "1") {
                        if (!itemPath.isNullOrEmpty() && itemPath != dirPath && itemPath.count { it == '/' } < 10) {
                            scanForVideos(itemPath, depth + 1)
                        }
                    } else {
                        val ext = filename.substringAfterLast(".", "").lowercase()
                        if (videoExtensions.contains(ext)) {
                            val episodeUrl = "https://$apiDomain/sharing/link?surl=$surl&uk=${initialUk ?: ""}&shareid=${initialShareid ?: ""}&fsid=${fsid ?: ""}"
                            if (poster == null && bestThumb != null) poster = bestThumb
                            episodes.add(
                                newEpisode(episodeUrl) {
                                    this.name = filename
                                    this.episode = episodes.size + 1
                                    this.posterUrl = bestThumb ?: poster
                                }
                            )
                        }
                    }
                }
            }

            try {
                scanForVideos("/")
            } catch (e: Exception) {
                println("Video scan error: ${e.message}")
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                    if (!tbLink.isNullOrEmpty()) this.data = tbLink
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val isTeraboxUrl = data.contains("terabox", ignoreCase = true) || data.contains("1024tera", ignoreCase = true)
        if (!data.startsWith("http") || !isTeraboxUrl) {
            if (data.startsWith("http")) {
                try {
                    val document = app.get(data).document
                    document.select("a").forEach { link ->
                        val href = link.attr("href")
                        if (href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)) {
                            loadExtractor(href, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
            return true
        }

        val domain = if (data.contains("1024tera")) "www.1024tera.com" else "www.terabox.com"
        val surl = extractSurl(data)
        if (surl.isEmpty()) {
            try { loadExtractor(data, subtitleCallback, callback) } catch (_: Exception) {}
            return true
        }

        val uri = try { java.net.URI(data) } catch (_: Exception) { null }
        val queryParams = uri?.query?.split("&")?.associate {
            val kv = it.split("=", limit = 2)
            kv[0] to (kv.getOrElse(1) { "" })
        } ?: emptyMap()
        var fsid = queryParams["fsid"] ?: ""
        var uk = queryParams["uk"] ?: ""
        var shareid = queryParams["shareid"] ?: ""

        try {
            val browserId = buildString {
                val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
                repeat(48) { append(chars.random()) }
                append("=")
            }

            val browserHeaders = mapOf(
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                "accept-language" to "en-US,en;q=0.9",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "sec-fetch-dest" to "document",
                "sec-fetch-mode" to "navigate",
                "sec-fetch-site" to "none",
                "sec-fetch-user" to "?1",
                "upgrade-insecure-requests" to "1",
                "cookie" to "browserid=$browserId; lang=en"
            )

            var sharePageResponse: com.lagradost.nicehttp.NiceResponse? = null
            val sharePageUrl = "https://$domain/sharing/link?surl=$surl"
            for (attempt in 1..3) {
                val res = app.get(sharePageUrl, headers = browserHeaders)
                if (res.text.contains("need verify")) continue
                sharePageResponse = res
                break
            }
            if (sharePageResponse == null) sharePageResponse = app.get(sharePageUrl, headers = browserHeaders)

            val pageRes = sharePageResponse.text
            val responseCookies = sharePageResponse.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }
                .map { it.second.substringBefore(";") }
            val cookieMap = mutableMapOf("browserid" to browserId, "lang" to "en")
            responseCookies.forEach { cookie ->
                val kv = cookie.split("=", limit = 2)
                if (kv.size == 2) cookieMap[kv[0].trim()] = kv[1].trim()
            }

            val jsToken = Regex("fn%28%22(.*?)%22%29").find(pageRes)?.groupValues?.get(1)
                ?: Regex("fn\\(\"(.*?)\"\\)").find(pageRes)?.groupValues?.get(1)
                ?: Regex("\"jsToken\":\\s*\"(.*?)\"").find(pageRes)?.groupValues?.get(1)

            if (jsToken != null) cookieMap["ndus"] = jsToken

            val randsk = cookieMap["TSID"]?.let { java.net.URLEncoder.encode(it, "UTF-8") }
                ?: cookieMap["randsk"]?.let { java.net.URLEncoder.encode(it, "UTF-8") }
                ?: Regex("\"randsk\":\\s*\"(.*?)\"").find(pageRes)?.groupValues?.get(1)
                ?: ""

            if (uk.isEmpty()) uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1) ?: ""
            if (shareid.isEmpty()) {
                shareid = Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(pageRes)?.groupValues?.get(1) ?: ""
            }

            var sign = Regex("\"sign\":\\s*\"([a-f0-9]+)\"").find(pageRes)?.groupValues?.get(1)
            var timestamp = Regex("\"timestamp\":\\s*(\\d+)").find(pageRes)?.groupValues?.get(1)

            val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val headers = mapOf(
                "accept" to "application/json, text/plain, */*",
                "accept-language" to "en-US,en;q=0.9",
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "sec-ch-ua" to "\"Google Chrome\";v=\"131\", \"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "referer" to "https://$domain/sharing/link?surl=$surl",
                "cookie" to cookieString
            )

            if (fsid.isEmpty()) {
                val listBaseParams = "app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}&shorturl=$surl&uk=$uk&shareid=$shareid"
                val rootListUrl = "https://$domain/share/list?$listBaseParams&dir=%2F&root=1"
                for (attempt in 1..3) {
                    try {
                        val listRes = app.get(rootListUrl, headers = headers).text
                        if (listRes.contains("need verify")) continue
                        if (uk.isEmpty()) uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(listRes)?.groupValues?.get(1) ?: ""
                        if (shareid.isEmpty()) {
                            shareid = Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(listRes)?.groupValues?.get(1)
                                ?: Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(listRes)?.groupValues?.get(1) ?: ""
                        }

                        suspend fun findFirstVideo(json: String, depth: Int = 0): String? {
                            if (depth > 3) return null
                            val items = json.split("\"fs_id\":")
                            for (chunk in items.drop(1)) {
                                val itemFsid = Regex("^\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: continue
                                val isDir = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)?.groupValues?.get(1) ?: "0"
                                val filename = Regex("\"server_filename\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1) ?: continue
                                if (isDir == "1") {
                                    val itemPath = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/") ?: continue
                                    val subListUrl = "https://$domain/share/list?$listBaseParams&dir=${java.net.URLEncoder.encode(itemPath, "UTF-8")}&root=0&randsk=$randsk"
                                    val subRes = app.get(subListUrl, headers = headers).text
                                    if (!subRes.contains("need verify")) {
                                        val found = findFirstVideo(subRes, depth + 1)
                                        if (found != null) return found
                                    }
                                } else {
                                    val ext = filename.substringAfterLast(".", "").lowercase()
                                    if (videoExtensions.contains(ext)) return itemFsid
                                }
                            }
                            return null
                        }

                        val foundFsid = findFirstVideo(listRes)
                        if (foundFsid != null) {
                            fsid = foundFsid
                            break
                        }
                    } catch (_: Exception) {}
                }
            }

            if (fsid.isEmpty()) {
                try { loadExtractor("https://$domain/s/1$surl", "https://$domain/", subtitleCallback, callback) } catch (_: Exception) {}
                return true
            }

            if (sign == null || timestamp == null) {
                val infoUrl = "https://$domain/api/shorturlinfo?app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}&shorturl=1$surl&root=1"
                for (attempt in 1..3) {
                    try {
                        val infoRes = app.get(infoUrl, headers = headers).text
                        if (infoRes.contains("need verify")) continue
                        if (sign == null) sign = Regex("\"sign\":\"(.*?)\"").find(infoRes)?.groupValues?.get(1)
                        if (timestamp == null) timestamp = Regex("\"timestamp\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                        if (uk.isEmpty()) uk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(infoRes)?.groupValues?.get(1) ?: ""
                        if (shareid.isEmpty()) shareid = Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(infoRes)?.groupValues?.get(1) ?: ""
                        if (sign != null && timestamp != null) break
                    } catch (_: Exception) {}
                }
            }

            if (sign != null && timestamp != null) {
                val sekey = java.net.URLDecoder.decode(randsk, "UTF-8")
                val downloadUrl = "https://$domain/share/download?app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}"
                try {
                    val downloadBody = mapOf(
                        "shareid" to shareid,
                        "uk" to uk,
                        "sign" to (sign ?: ""),
                        "timestamp" to (timestamp ?: ""),
                        "fid_list" to "[$fsid]",
                        "extra" to "{\"sekey\":\"$sekey\"}",
                        "primaryid" to shareid,
                        "product" to "share"
                    )
                    val downloadRes = app.post(downloadUrl, headers = headers, data = downloadBody).text
                    if (!downloadRes.contains("need verify") && !downloadRes.contains("\"errno\":400")) {
                        val dlink = Regex("\"dlink\":\\s*\"(.*?)\"").find(downloadRes)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (dlink != null && dlink.isNotEmpty()) {
                            callback.invoke(
                                newExtractorLink("Terabox", "Terabox Download", dlink, INFER_TYPE) {
                                    this.headers = mapOf(
                                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                                        "referer" to "https://$domain/",
                                        "cookie" to cookieString
                                    )
                                }
                            )
                            return true
                        }
                    }
                } catch (_: Exception) {}

                val streamTypes = listOf("M3U8_AUTO_480", "M3U8_AUTO_720", "M3U8_AUTO_240", "M3U8_FLV_264_480")
                for (streamType in streamTypes) {
                    val streamUrl = "https://$domain/share/streaming?uk=$uk&shareid=$shareid&type=$streamType&fid=$fsid&sign=$sign&timestamp=$timestamp&app_id=250528&web=1&channel=dubox&clienttype=0&jsToken=${jsToken ?: ""}"
                    try {
                        val streamRes = app.get(streamUrl, headers = headers).text
                        if (streamRes.contains("need verify") || streamRes.contains("\"errno\":400141")) continue
                        if (streamRes.contains("\"errno\"") && !streamRes.contains("\"errno\":0")) continue

                        if (streamRes.trimStart().startsWith("#EXTM3U")) {
                            callback.invoke(
                                newExtractorLink("Terabox", "Terabox $streamType", streamUrl, ExtractorLinkType.M3U8) {
                                    this.headers = headers
                                }
                            )
                            return true
                        }

                        val m3u8Link = Regex("\"lurl\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            ?: Regex("\"mlink\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            ?: Regex("(https?://[^\"]+\\.m3u8[^\"]*)").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (m3u8Link != null) {
                            callback.invoke(
                                newExtractorLink("Terabox", "Terabox $streamType", m3u8Link, ExtractorLinkType.M3U8) {
                                    this.headers = headers
                                }
                            )
                            return true
                        }
                    } catch (_: Exception) {}
                }
            }

            try { Terabox().getUrl("https://$domain/s/1$surl", "https://$domain/", subtitleCallback, callback) } catch (_: Exception) {}
        } catch (_: Exception) {
            try { loadExtractor(data, subtitleCallback, callback) } catch (_: Exception) {}
        }

        return true
    }
}
