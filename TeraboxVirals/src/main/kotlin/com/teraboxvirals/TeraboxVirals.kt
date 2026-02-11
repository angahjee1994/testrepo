package com.teraboxvirals

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class TeraboxVirals : MainAPI() {
    override var mainUrl = "https://www.teraboxvirals.com"
    override var name = "TeraboxVirals"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "" to "Latest Posts",
        "search/label/Viral" to "Viral",
        "search/label/Indo" to "Indo",
        "search/label/Melayu" to "Melayu",
        "search/label/awek%20tudung" to "Awek Tudung",
        "search/label/Hijaber" to "Hijaber",
        "search/label/Couple" to "Couple"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = if (request.data.isEmpty()) {
            if (page > 1) {
                // Blogger pagination for index
                // Usually it's ?updated-max=... or similar. 
                // For simplicity, we stick to page 1 for now or try common Blogger index
                return null
            }
            mainUrl
        } else {
            // For labels
            if (page > 1) return null
            "$mainUrl/${request.data}"
        }

        val document = app.get(url).document
        val home = document.select("article.blog-post").mapNotNull {
            toSearchResult(it)
        }
        return newHomePageResponse(request.name, home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val linkElement = element.selectFirst(".post-filter-link") ?: element.selectFirst(".entry-title a") ?: return null
        val title = element.selectFirst(".entry-title a")?.text() ?: element.selectFirst("img")?.attr("alt") ?: "Video"
        val href = linkElement.attr("href")

        var poster: String? = null
        element.select(".post-filter-link img, .snip-thumbnail, img").forEach { img ->
            if (poster != null) return@forEach
            val imgUrl = listOf("data-src", "src", "srcset").firstNotNullOfOrNull { attr ->
                val value = img.attr(attr).takeIf { it.isNotEmpty() && !it.contains("blank.gif") && !it.contains("pixel.gif") }
                if (attr == "srcset" && value != null) value.split(",").last().trim().split(" ").first() else value
            }
            if (imgUrl != null) poster = imgUrl
        }

        if (poster != null) {
            if (!poster!!.startsWith("http")) poster = mainUrl + (if (poster!!.startsWith("/")) "" else "/") + poster
            poster = poster!!.replace(Regex("/s\\d+(-[bc])?/"), "/s1600/")
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document
        return document.select("article.blog-post").mapNotNull {
            toSearchResult(it)
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("Load URL Triggered: $url")
        val response = app.get(url)
        println("Detail Page Fetch Success: ${response.url}")
        val document = response.document
        val title = document.selectFirst(".entry-title")?.text()?.trim() ?: "Video"
        
        // Find the Terabox link immediately to fetch metadata
        val allDlButtons = document.select(".dlbutton a")
        println("Found ${allDlButtons.size} dlbuttons")
        var tbLink = allDlButtons.firstOrNull { 
            val href = it.attr("href")
            println("Checking button href: $href")
            href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)
        }?.attr("href") ?: allDlButtons.firstOrNull()?.attr("href")

        if (tbLink == null) {
            println("No link in .dlbutton, checking all page anchors...")
            tbLink = document.select("a").firstOrNull { 
                val href = it.attr("href")
                val isMatch = href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true) 
                if (isMatch) println("Found match in general <a>: $href")
                isMatch
            }?.attr("href")
        }
        println("Selected tbLink: $tbLink")
        
        // Handle landing page redirect if needed
        if (tbLink?.contains("downloadkatsini.com") == true) {
             try {
                 println("Handling landing page redirect: $tbLink")
                 tbLink = app.get(tbLink!!).document.selectFirst("a[href*=terabox], a[href*=1024tera]")?.attr("href")
                 println("Redirected tbLink: $tbLink")
             } catch (e: Exception) {
                 println("Redirect Error: ${e.message}")
             }
        }

        var poster: String? = null
        document.select(".post-body img").forEach { img ->
            if (poster != null) return@forEach
            val imgUrl = listOf("data-src", "src", "srcset").firstNotNullOfOrNull { attr ->
                val value = img.attr(attr).takeIf { it.isNotEmpty() && !it.contains("blank.gif") && !it.contains("pixel.gif") }
                if (attr == "srcset" && value != null) value.split(",").last().trim().split(" ").first() else value
            }
            if (imgUrl != null) poster = imgUrl
        }
        if (poster == null) {
            poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        }
        if (poster != null) {
            if (!poster!!.startsWith("http")) poster = mainUrl + (if (poster!!.startsWith("/")) "" else "/") + poster
            poster = poster!!.replace(Regex("/s\\d+(-[bc])?/"), "/s1600/")
        }
        println("Detail Page Poster: $poster")
        val plot = document.select(".post-body").text().trim()
        val tags = document.select(".post-tag a").map { it.text() }

        // If we found a Terabox link, try to use it for better metadata (images/files)
        val isTerabox = !tbLink.isNullOrEmpty() && (tbLink.contains("terabox", ignoreCase = true) || tbLink.contains("1024tera", ignoreCase = true))
        println("Terabox Link Found: $tbLink (isTerabox: $isTerabox)")
    
        // If we found a Terabox link, attempt to fetch the file list to populate episodes
        val episodes = mutableListOf<Episode>()
        if (isTerabox) {
            val cleanLink = tbLink.replace("teraboxapp.com", "terabox.app")
                .replace("1024terabox.com", "terabox.app")
                .replace("terabox.com", "terabox.app")

            var tempSurl = if (cleanLink.contains("surl=")) {
                cleanLink.substringAfter("surl=").substringBefore("&")
            } else {
                cleanLink.substringAfter("/s/").substringBefore("?")
            }
            if (tempSurl.startsWith("1")) tempSurl = tempSurl.substring(1)
            val surl = tempSurl
            println("Extracted surl: $surl (from temp: $tempSurl)")

            var initialUk: String? = null
            var initialShareid: String? = null
            var fotoPoster: String? = null
            val apiDomain = if (tbLink?.contains("1024tera") == true) "www.1024tera.com" else "www.terabox.com"
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

            suspend fun scanFolder(dirPath: String) {
                val referer = "https://$apiDomain/"
                val folderApiUrl = "https://$apiDomain/share/list?shorturl=$surl&dir=${java.net.URLEncoder.encode(dirPath, "UTF-8")}&root=${if (dirPath == "/") "1" else "0"}&web=1&channel=dubox&clienttype=0"
                val headersList = listOf(
                    mapOf("Referer" to referer, "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"),
                    mapOf("User-Agent" to "LogStatistic", "Referer" to referer)
                )

                var jsonResponse: String? = null
                for (headers in headersList) {
                    try {
                        println("Calling List API (dir=$dirPath): $folderApiUrl")
                        val res = app.get(folderApiUrl, headers = headers).text
                        if (res.contains("\"errno\":0") || res.contains("\"list\":[")) {
                            println("List Success: ${res.take(100)}...")
                            jsonResponse = res
                            break
                        } else {
                            println("List Failed: ${res.take(100)}...")
                        }
                    } catch (e: Exception) {
                        println("List Error: ${e.message}")
                    }
                }

                if (jsonResponse == null) return

                if (initialUk == null) {
                    initialUk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(jsonResponse)?.groupValues?.get(1)?.takeIf { it != "0" }
                }
                val responseShareId = Regex("\"share_id\":\\s*\"?(\\d+)\"?").find(jsonResponse)?.groupValues?.get(1)
                    ?: Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(jsonResponse)?.groupValues?.get(1)
                if (responseShareId != null) initialShareid = responseShareId
                println("List Tokens: uk=$initialUk, shareid=$initialShareid")

                val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m4v", "wmv")
                val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
                val isFotoFolder = dirPath.contains("Foto", ignoreCase = true)

                val items = jsonResponse.split("\"fs_id\":")
                items.drop(1).forEach { chunk ->
                    val fsidMatch = Regex("^\\s*\"?(\\d+)\"?").find(chunk)
                    val fsid = fsidMatch?.groupValues?.get(1)
                    val isDirMatch = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(chunk)
                    val isDir = isDirMatch?.groupValues?.get(1) ?: "0"
                    val filenameMatch = Regex("\"server_filename\":\\s*\"(.*?)\"").find(chunk)
                    val pathMatch = Regex("\"path\":\\s*\"(.*?)\"").find(chunk)
                    val thumbUrl3 = Regex("\"url3\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                    val thumbUrl2 = Regex("\"url2\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                    val thumbUrl1 = Regex("\"url1\":\\s*\"(.*?)\"").find(chunk)?.groupValues?.get(1)?.replace("\\/", "/")
                    val bestThumb = thumbUrl3 ?: thumbUrl2 ?: thumbUrl1

                    if (filenameMatch != null) {
                        val filename = filenameMatch.groupValues[1]
                        val itemPath = pathMatch?.groupValues?.get(1)?.replace("\\/", "/")

                        if (isFotoFolder) {
                            val debugExt = filename.substringAfterLast(".", "").lowercase()
                            println("Foto Item: $filename (ext=$debugExt, isDir=$isDir, thumb=${bestThumb != null})")
                        }

                        if (isDir == "1") {
                            if (!itemPath.isNullOrEmpty() && itemPath != dirPath && itemPath.count { it == '/' } < 10) {
                                println("Found Folder: $itemPath, scanning...")
                                scanFolder(itemPath)
                            }
                        } else {
                            val ext = filename.substringAfterLast(".", "").lowercase()
                            if (isFotoFolder && imageExtensions.contains(ext) && fotoPoster == null) {
                                if (bestThumb != null) {
                                    fotoPoster = bestThumb
                                    println("Found Foto Poster: $bestThumb")
                                }
                            }
                            if (videoExtensions.contains(ext)) {
                                val episodeData = "TERABOX_STREAM|$apiDomain|$surl|${initialUk ?: ""}|${initialShareid ?: ""}|${fsid ?: ""}"
                                val episodeThumb = bestThumb ?: poster
                                if (poster == null && bestThumb != null) poster = bestThumb
                                println("Found Video: $filename (fsid=$fsid, thumb=${bestThumb != null})")
                                episodes.add(
                                    newEpisode(episodeData) {
                                        this.name = filename
                                        this.episode = episodes.size + 1
                                        this.posterUrl = episodeThumb
                                    }
                                )
                            }
                        }
                    }
                }
            }

            try {
                println("Starting Folder Scan from root...")
                scanFolder("/")
                println("Scan Complete. Total episodes found: ${episodes.size}, fotoPoster: $fotoPoster")
                if (fotoPoster != null) poster = fotoPoster
            } catch (e: Exception) {
                println("Scan Runtime Error: ${e.message}")
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

            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://$domain/",
                "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"
            )

            try {
                val sharePageUrl = "https://$domain/sharing/link?surl=$surl"
                val pageRes = app.get(sharePageUrl, headers = headers).text

                val sign = Regex("\"sign\":\"(.*?)\"").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("sign=([a-zA-Z0-9]+)").find(pageRes)?.groupValues?.get(1)
                val timestamp = Regex("\"timestamp\":(\\d+)").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("timestamp=(\\d+)").find(pageRes)?.groupValues?.get(1)
                val jsToken = Regex("fn%28%22(.*?)%22%29").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("\"jsToken\":\\s*\"(.*?)\"").find(pageRes)?.groupValues?.get(1)
                    ?: Regex("jsToken.*?=.*?\"(.*?)\"").find(pageRes)?.groupValues?.get(1)

                println("Page tokens: sign=$sign timestamp=$timestamp jsToken=${jsToken?.take(10)}")

                if (sign != null && timestamp != null) {
                    val streamTypes = listOf("M3U8_AUTO_480", "M3U8_AUTO_720", "M3U8_FLV_264_480")
                    for (streamType in streamTypes) {
                        val streamUrl = "https://$domain/share/streaming?shorturl=$surl&shareid=$shareid&uk=$uk&fid=$fsid&type=$streamType&sign=$sign&timestamp=$timestamp&channel=dubox&clienttype=0&web=1&app_id=250528"
                        try {
                            val streamRes = app.get(streamUrl, headers = headers).text
                            println("Stream response ($streamType): ${streamRes.take(200)}")
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
                            println("Stream attempt $streamType failed: ${e.message}")
                        }
                    }
                }

                val dlinkFromPage = Regex("\"dlink\":\"(.*?)\"").find(pageRes)?.groupValues?.get(1)?.replace("\\/", "/")
                if (dlinkFromPage != null) {
                    println("Found dlink from page: $dlinkFromPage")
                    callback.invoke(
                        newExtractorLink(
                            "Terabox",
                            "Terabox",
                            dlinkFromPage,
                            INFER_TYPE
                        ) {
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
