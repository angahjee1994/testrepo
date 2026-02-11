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
        
        // Robust poster selection: check src, data-src, and srcset for real images
        var poster = element.selectFirst(".post-filter-link img, .snip-thumbnail, .post-filter-image img, img")?.let { img ->
            listOf("data-src", "src", "srcset", "data-original").firstNotNullOfOrNull { attr ->
                val value = img.attr(attr).takeIf { it.isNotEmpty() && !it.contains("blank.gif") && !it.contains("pixel.gif") }
                if (attr == "srcset" && value != null) value.split(",").last().trim().split(" ").first() else value
            }
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

        // More robust poster selection for detail page: prioritize high-res images
        var poster = document.selectFirst(".post-body img, .post-filter-image img, meta[property='og:image']")?.let { img ->
            if (img.tagName() == "meta") img.attr("content")
            else {
                listOf("data-src", "src", "srcset").firstNotNullOfOrNull { attr ->
                    val value = img.attr(attr).takeIf { it.isNotEmpty() && !it.contains("blank.gif") }
                    if (attr == "srcset" && value != null) value.split(",").last().trim().split(" ").first() else value
                }
            }
        }
        val plot = document.select(".post-body").text().trim()
        val tags = document.select(".post-tag a").map { it.text() }

        // If we found a Terabox link, try to use it for better metadata (images/files)
        val isTerabox = !tbLink.isNullOrEmpty() && (tbLink.contains("terabox", ignoreCase = true) || tbLink.contains("1024tera", ignoreCase = true))
        println("Terabox Link Found: $tbLink (isTerabox: $isTerabox)")
    
        // If we found a Terabox link, attempt to fetch the file list to populate episodes
        val episodes = mutableListOf<Episode>()
        if (isTerabox) {
             // Clean the link
             val cleanLink = tbLink.replace("teraboxapp.com", "terabox.app")
                 .replace("1024terabox.com", "terabox.app") 
                 .replace("terabox.com", "terabox.app")
             
             // Extract surl: handle both /s/ path and surl= query param
             var tempSurl = if (cleanLink.contains("surl=")) {
                 cleanLink.substringAfter("surl=").substringBefore("&")
             } else {
                 cleanLink.substringAfter("/s/").substringBefore("?")
             }
             if (tempSurl.startsWith("1")) tempSurl = tempSurl.substring(1)
             val surl = tempSurl
             println("Extracted surl: $surl (from temp: $tempSurl)")

             // Initial tokens for streaming API
             var sharedSign: String? = null
             var sharedTimestamp: String? = null
             var initialUk: String? = null
             var initialShareid: String? = null

             // Extract tokens from the actual Terabox sharing page to bypass shorturlinfo challenges
             println("Fetching Terabox page for tokens: $cleanLink")
             // Use a desktop UA to ensure we get the full JS payload
             val sharingPageHtml = try { app.get(cleanLink, headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")).text } catch(e: Exception) { "" }
             
             // Search for tokens in JSON strings or JS objects
             sharedSign = Regex("[\"']sign[\"']\\s*[:=]\\s*[\"']([^\"']+)[\"']").find(sharingPageHtml)?.groupValues?.get(1)
             sharedTimestamp = Regex("[\"']timestamp[\"']\\s*[:=]\\s*(?:[\"'](\\d+)[\"']|(\\d+))").find(sharingPageHtml)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
             initialUk = Regex("[\"']uk[\"']\\s*[:=]\\s*(?:[\"'](\\d+)[\"']|(\\d+))").find(sharingPageHtml)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
             initialShareid = Regex("[\"']shareid[\"']\\s*[:=]\\s*(?:[\"'](\\d+)[\"']|(\\d+))").find(sharingPageHtml)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
             
             println("Terabox Page Tokens: sign=$sharedSign, timestamp=$sharedTimestamp, uk=$initialUk, shareid=$initialShareid")

             // Fetch initial metadata and tokens via shorturlinfo API ONLY as a fallback
             val apiDomain = if (tbLink?.contains("1024tera") == true) "www.1024tera.com" else "www.terabox.com"
             
             if (initialUk == null || sharedSign == null) {
                 val shortUrlInfoApi = "https://$apiDomain/api/shorturlinfo?app_id=250528&web=1&channel=dubox&clienttype=0&shorturl=1$surl&root=1"
                 println("Calling Handshake API: $shortUrlInfoApi")
                 try {
                     val infoRes = app.get(shortUrlInfoApi, headers = mapOf("Referer" to "https://$apiDomain/")).text
                     println("Handshake Response: ${infoRes.take(100)}...")
                     if (sharedSign == null) sharedSign = Regex("\"sign\":\"(.*?)\"").find(infoRes)?.groupValues?.get(1)
                     if (sharedTimestamp == null) sharedTimestamp = Regex("\"timestamp\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                     initialUk = Regex("\"uk\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                     initialShareid = Regex("\"shareid\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                     println("Handshake Tokens: sign=$sharedSign, uk=$initialUk, shareid=$initialShareid")
                 } catch (e: Exception) {
                     println("Handshake Error: ${e.message}")
                 }
             }

             // Recursive function to scan folders
             // Recursive function to scan folders
             suspend fun scanFolder(dirPath: String) {
                 val referer = "https://$apiDomain/"
                 
                 // Use shorturl + dir for consistent guest traversal
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

                 if (jsonResponse != null) {
                      // Extract uk and shareid if we don't have them yet (share/list root provides these)
                      if (initialUk == null) {
                          initialUk = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(jsonResponse)?.groupValues?.get(1)
                          initialShareid = Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(jsonResponse)?.groupValues?.get(1)
                          println("Extracted Uk/ShareId from List: $initialUk / $initialShareid")
                      }

                      val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m4v", "wmv")
                      
                      // Using a chunk-based extraction to handle items correctly
                      val items = jsonResponse.split("\"fs_id\":")
                      items.drop(1).forEach { chunk ->
                          val item = chunk 
                          
                          val isDirMatch = Regex("\"isdir\":\\s*\"?(\\d+)\"?").find(item)
                          val isDir = isDirMatch?.groupValues?.get(1) ?: "0"
                          
                          val filenameMatch = Regex("\"server_filename\":\\s*\"(.*?)\"").find(item)
                          val dlinkMatch = Regex("\"dlink\":\\s*\"(.*?)\"").find(item)
                          val pathMatch = Regex("\"path\":\\s*\"(.*?)\"").find(item) 
                          val thumbsMatch = Regex("\"url[1-3]\":\\s*\"(.*?)\"").findAll(item).toList()
                          val fsidMatch = Regex("^\\s*\"?(\\d+)\"?").find(item)
                          
                          if (filenameMatch != null) {
                              val filename = filenameMatch.groupValues[1]
                              val fsid = fsidMatch?.groupValues?.get(1)
                              val itemPath = pathMatch?.groupValues?.get(1)?.replace("\\/", "/")?.replace("\\\\/", "/")
                              
                              if (isDir == "1") { // It's a folder
                                  if (!itemPath.isNullOrEmpty() && itemPath != dirPath && itemPath.count { it == '/' } < 10) { 
                                      println("Found Folder: $itemPath, scanning...")
                                      scanFolder(itemPath)
                                  }
                              } else { // It's a file
                                  val ext = filename.substringAfterLast(".", "").lowercase()
                                  if (videoExtensions.contains(ext)) {
                                       val dlink = dlinkMatch?.groupValues?.get(1)?.replace("\\\\/", "/") 
                                           ?: "TERABOX_STREAMING_FALLBACK|$fsid|$initialUk|$initialShareid|$sharedSign|$sharedTimestamp"
                                           
                                       val thumbUrl = thumbsMatch.lastOrNull()?.groupValues?.get(1)?.replace("\\\\/", "/")
                                       if (thumbUrl != null && (poster == null || poster?.contains("terabox") == false)) {
                                           poster = thumbUrl
                                       }
                                       
                                       println("Found Video: $filename")
                                       episodes.add(
                                           newEpisode(dlink) {
                                               this.name = filename
                                               this.episode = episodes.size + 1
                                               this.posterUrl = thumbUrl ?: poster
                                           }
                                       )
                                  }
                              }
                          }
                      }
                 }
             }

             // Start scan from root
             try {
                 println("Starting Folder Scan from root...")
                 scanFolder("/")
                 println("Scan Complete. Total episodes found: ${episodes.size}")
             } catch (e: Exception) {
                 println("Scan Runtime Error: ${e.message}")
             }
        }


        
        // If no episodes found from API (or API failed), add the main link as a single episode
        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = title
                    this.episode = 1
                    this.posterUrl = poster
                    // If we have a plain Terabox link, pass it as data
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
        // If data is a direct Terabox file link (from our load() episode logic), play it directly
        // Also handle 1024tera links if they are passed as data
        // Distinguish between direct file links and sharing links
        val isDirectLink = (data.contains("terabox.com") || data.contains("1024tera.com") || data.contains("terabox.app") || data.contains("d.terabox.com")) &&
                           (data.contains("file/") || data.contains("d.terabox.com") || data.contains(".mp4") || data.contains(".m3u8"))
        
        // If it's a sharing link (HTML page), we shouldn't treat it as a video directly
        val isSharingLink = data.contains("sharing/link") || data.contains("/s/")

        if (isDirectLink && !isSharingLink) {
             val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
             
             // If domain is 1024tera, change referer
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

        // Handle streaming fallback from scanFolder
        if (data.startsWith("TERABOX_STREAMING_FALLBACK")) {
            val parts = data.split("|")
            if (parts.size >= 6) {
                val fsid = parts[1]
                val uk = parts[2]
                val shareid = parts[3]
                var sign = parts[4]
                var timestamp = parts[5]
                
                val apiDomain = if (data.contains("1024tera")) "www.1024tera.com" else "www.terabox.com"
                
                // If sign/timestamp are missing, try to fetch them from the sharing info if we can, 
                // but for now we'll assume they were passed or try a common fallback if available.
                // share/streaming?uk=...&shareid=...&type=M3U8_FLV_264_480&fid=...&sign=...&timestamp=...
                
                val streamingUrl = "https://$apiDomain/share/streaming?uk=$uk&shareid=$shareid&type=M3U8_FLV_264_480&fid=$fsid&sign=$sign&timestamp=$timestamp&web=1&channel=dubox&clienttype=0"
                
                callback.invoke(
                    newExtractorLink(
                        "Terabox Streaming",
                        "Terabox",
                        streamingUrl,
                        INFER_TYPE
                    ) {
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                            "Referer" to "https://$apiDomain/",
                            "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"
                        )
                    }
                )
                return true
            }
        }

        // Otherwise, scrape the page for links (Legacy/Movie behavior)
        val document = app.get(data).document
        
        // Find links in .dlbutton a
        val buttons = document.select(".dlbutton a")
        val allLinks = document.select("a")
        
        val foundLinks = mutableSetOf<String>()

        // Prioritize buttons
        buttons.forEach { link ->
             val href = link.attr("href")
             if (href.isNotEmpty()) foundLinks.add(href)
        }

        // Add other a tags that look like terabox
        allLinks.forEach { link ->
            val href = link.attr("href")
            if (href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)) {
                foundLinks.add(href)
            }
        }
        
        // Prioritize direct Terabox links over redirectors
        val sortedLinks = foundLinks.sortedByDescending { 
            it.contains("terabox", ignoreCase = true) || it.contains("1024tera", ignoreCase = true) 
        }

        sortedLinks.forEach { href ->
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
