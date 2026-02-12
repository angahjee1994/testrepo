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

    private val defaultUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
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
        val folderApiUrl = "https://$apiDomain/share/list?shorturl=$surl&dir=${java.net.URLEncoder.encode(dirPath, "UTF-8")}&root=${if (dirPath == "/") "1" else "0"}&web=1&channel=dubox&clienttype=0"
        val headers = mapOf(
            "Referer" to "https://$apiDomain/",
            "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"
        )
        for (attempt in 1..3) {
            try {
                val res = app.get(folderApiUrl, headers = headers).text
                if (res.contains("\"errno\":0") || res.contains("\"list\":[")) return res
                if (res.contains("need verify")) {
                    println("List API 'need verify', retry $attempt/3...")
                    continue
                }
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
                            val episodeData = "TERABOX_STREAM|$apiDomain|$surl|${initialUk ?: ""}|${initialShareid ?: ""}|${fsid ?: ""}"
                            if (poster == null && bestThumb != null) poster = bestThumb
                            episodes.add(
                                newEpisode(episodeData) {
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
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        if (data.startsWith("TERABOX_STREAM|")) {
            val parts = data.split("|")
            val domain = parts.getOrElse(1) { "www.1024tera.com" }
            val surl = parts.getOrElse(2) { "" }
            val uk = parts.getOrElse(3) { "" }
            val shareid = parts.getOrElse(4) { "" }
            val fsid = parts.getOrElse(5) { "" }
            println("TERABOX_STREAM: domain=$domain surl=$surl uk=$uk shareid=$shareid fsid=$fsid")

            try {
                val initHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://$domain/",
                    "Cookie" to "browserid=1; lang=en"
                )

                var sharePageResponse: com.lagradost.nicehttp.NiceResponse? = null
                val sharePageUrl = "https://$domain/sharing/link?surl=$surl"
                for (attempt in 1..3) {
                    val res = app.get(sharePageUrl, headers = initHeaders)
                    if (res.text.contains("need verify")) {
                        println("Share page 'need verify', retry $attempt/3...")
                        continue
                    }
                    sharePageResponse = res
                    break
                }
                if (sharePageResponse == null) sharePageResponse = app.get(sharePageUrl, headers = initHeaders)

                val pageRes = sharePageResponse.text
                val responseCookies = sharePageResponse.headers.filter { it.first.equals("set-cookie", ignoreCase = true) }
                    .map { it.second.substringBefore(";") }
                val cookieMap = mutableMapOf("lang" to "en")
                responseCookies.forEach { cookie ->
                    val kv = cookie.split("=", limit = 2)
                    if (kv.size == 2) cookieMap[kv[0].trim()] = kv[1].trim()
                }

                val jsToken = Regex("fn%28%22(.*?)%22%29").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("fn\\(\"(.*?)\"\\)").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("\"jsToken\":\\s*\"(.*?)\"").find(pageRes)?.groupValues?.get(1)

                if (jsToken != null) cookieMap["ndus"] = jsToken

                val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                val headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://$domain/",
                    "Cookie" to cookieString
                )

                println("Cookies: $cookieString")
                println("jsToken: ${jsToken?.take(20)}")

                var sign: String? = null
                var timestamp: String? = null

                val infoUrl = "https://$domain/api/shorturlinfo?shorturl=1$surl&root=1"
                for (attempt in 1..3) {
                    try {
                        val infoRes = app.get(infoUrl, headers = headers).text
                        println("Info API ($attempt): ${infoRes.take(200)}")
                        if (infoRes.contains("need verify")) {
                            println("Info API 'need verify', retry $attempt/3...")
                            continue
                        }
                        sign = Regex("\"sign\":\"(.*?)\"").find(infoRes)?.groupValues?.get(1)
                        timestamp = Regex("\"timestamp\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                        if (sign != null && timestamp != null) break
                    } catch (e: Exception) {
                        println("Info API error ($attempt): ${e.message}")
                    }
                }

                println("Final tokens: sign=$sign timestamp=$timestamp")

                if (sign != null && timestamp != null) {
                    val streamTypes = listOf("M3U8_AUTO_480", "M3U8_AUTO_720")
                    for (streamType in streamTypes) {
                        val streamUrl = "https://$domain/share/streaming?shorturl=$surl&shareid=$shareid&uk=$uk&fid=$fsid&type=$streamType&sign=$sign&timestamp=$timestamp&channel=dubox&clienttype=0&web=1&app_id=250528"
                        try {
                            val streamRes = app.get(streamUrl, headers = headers).text
                            println("Stream ($streamType): ${streamRes.take(200)}")
                            if (streamRes.contains("need verify") || streamRes.contains("\"errno\":400141")) continue
                            val m3u8Link = Regex("\"lurl\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                                ?: Regex("\"mlink\":\\s*\"(.*?)\"").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                                ?: Regex("(https?://[^\"]+\\.m3u8[^\"]*)").find(streamRes)?.groupValues?.get(1)?.replace("\\/", "/")
                            if (m3u8Link != null) {
                                println("Found M3U8: $m3u8Link")
                                callback.invoke(
                                    newExtractorLink(
                                        "Terabox",
                                        "Terabox $streamType",
                                        m3u8Link,
                                        INFER_TYPE
                                    ) {
                                        this.headers = headers
                                    }
                                )
                                return true
                            }
                        } catch (e: Exception) {
                            println("Stream $streamType error: ${e.message}")
                        }
                    }
                }

                val dlinkFromPage = Regex("\"dlink\":\"(.*?)\"").find(pageRes)?.groupValues?.get(1)?.replace("\\/", "/")
                if (dlinkFromPage != null) {
                    callback.invoke(
                        newExtractorLink("Terabox", "Terabox", dlinkFromPage, INFER_TYPE) {
                            this.headers = headers
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                println("TERABOX_STREAM error: ${e.message}")
            }

            println("Stream failed, falling back to extractor")
            try {
                Terabox().getUrl("https://$domain/s/1$surl", "https://$domain/", subtitleCallback, callback)
            } catch (e: Exception) {
                println("Extractor fallback error: ${e.message}")
            }
            return true
        }

        if (data.startsWith("TERABOX_DLINK|")) {
            val parts = data.split("|")
            val dlink = parts[1]
            val domain = parts.getOrElse(2) { "www.terabox.com" }
            println("Playing dlink: $dlink")
            callback.invoke(
                newExtractorLink(
                    "Terabox",
                    "Terabox",
                    dlink,
                    INFER_TYPE
                ) {
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "https://$domain/",
                        "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"
                    )
                }
            )
            return true
        }

        if (data.startsWith("TERABOX_LINK|")) {
            val parts = data.split("|")
            val teraboxUrl = parts[1]
            val domain = parts.getOrElse(2) { "www.terabox.com" }
            println("Extracting via Terabox extractor: $teraboxUrl")
            try {
                Terabox().getUrl(teraboxUrl, "https://$domain/", subtitleCallback, callback)
            } catch (e: Exception) {
                println("Terabox extractor error: ${e.message}")
            }
            return true
        }

        val isTeraboxUrl = data.contains("terabox", ignoreCase = true) || data.contains("1024tera", ignoreCase = true)
        val isDirectFile = data.contains("file/") || data.contains(".mp4") || data.contains(".m3u8") || data.contains("d.terabox")
        val isSharingPage = data.contains("/s/") || data.contains("sharing/link")

        if (isTeraboxUrl && isDirectFile && !isSharingPage) {
            val referer = if (data.contains("1024tera")) "https://www.1024tera.com/" else "https://www.terabox.com/"
            callback.invoke(
                newExtractorLink(
                    "Terabox",
                    "Terabox",
                    data,
                    INFER_TYPE
                ) {
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"
                    )
                }
            )
            return true
        }

        if (isTeraboxUrl && isSharingPage) {
            loadExtractor(data, subtitleCallback, callback)
            return true
        }

        val document = app.get(data).document
        val foundLinks = mutableSetOf<String>()

        document.select(".dlbutton a").forEach { link ->
            val href = link.attr("href")
            if (href.isNotEmpty()) foundLinks.add(href)
        }

        document.select("a").forEach { link ->
            val href = link.attr("href")
            if (href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)) {
                foundLinks.add(href)
            }
        }

        foundLinks.sortedByDescending {
            it.contains("terabox", ignoreCase = true) || it.contains("1024tera", ignoreCase = true)
        }.forEach { href ->
            if (href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)) {
                loadExtractor(href, subtitleCallback, callback)
            } else if (href.contains("downloadkatsini.com", ignoreCase = true)) {
                try {
                    val finalLink = app.get(href).document.selectFirst("a[href*=terabox], a[href*=1024tera]")?.attr("href")
                    if (!finalLink.isNullOrEmpty()) {
                        loadExtractor(finalLink, subtitleCallback, callback)
                    }
                } catch (e: Exception) {}
            }
        }

        return true
    }
}
