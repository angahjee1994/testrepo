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
        val titleElement = element.selectFirst(".entry-title a") ?: return null
        val title = titleElement.text()
        val href = titleElement.attr("href")
        val poster = element.selectFirst(".post-filter-image img")?.attr("src")

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
        val document = app.get(url).document
        val title = document.selectFirst(".entry-title")?.text()?.trim() ?: "Video"
        
        // Find the Terabox link immediately to fetch metadata
        val allDlButtons = document.select(".dlbutton a")
        var tbLink = allDlButtons.firstOrNull { 
            val href = it.attr("href")
            href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true)
        }?.attr("href") ?: allDlButtons.firstOrNull()?.attr("href")

        if (tbLink == null) {
            tbLink = document.select("a").firstOrNull { 
                val href = it.attr("href")
                href.contains("terabox", ignoreCase = true) || href.contains("1024tera", ignoreCase = true) 
            }?.attr("href")
        }
        
        // Handle landing page redirect if needed
        if (tbLink?.contains("downloadkatsini.com") == true) {
             try {
                 tbLink = app.get(tbLink).document.selectFirst("a[href*=terabox], a[href*=1024tera]")?.attr("href")
             } catch (e: Exception) {}
        }

        var poster = document.selectFirst(".post-body img")?.attr("src")
            ?: document.selectFirst(".post-filter-image img")?.attr("src")
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".post-body").text().trim()
        val tags = document.select(".post-tag a").map { it.text() }

        // If we found a Terabox link, try to use it for better metadata (images/files)
        val isTerabox = !tbLink.isNullOrEmpty() && (tbLink.contains("terabox", ignoreCase = true) || tbLink.contains("1024tera", ignoreCase = true))
    
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

             // Initial tokens for streaming API
             var sharedSign: String? = null
             var sharedTimestamp: String? = null
             var initialUk: String? = null
             var initialShareid: String? = null

             // Fetch initial metadata and tokens via shorturlinfo API
             val apiDomain = if (tbLink?.contains("1024tera") == true) "www.1024tera.com" else "www.terabox.com"
             val shortUrlInfoApi = "https://$apiDomain/api/shorturlinfo?app_id=250528&web=1&channel=dubox&clienttype=0&shorturl=1$surl&root=1"
             
             try {
                 val infoRes = app.get(shortUrlInfoApi, headers = mapOf("Referer" to "https://$apiDomain/")).text
                 sharedSign = Regex("\"sign\":\"(.*?)\"").find(infoRes)?.groupValues?.get(1)
                 sharedTimestamp = Regex("\"timestamp\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                 initialUk = Regex("\"uk\":(\\d+)").find(infoRes)?.groupValues?.get(1)
                 initialShareid = Regex("\"shareid\":(\\d+)").find(infoRes)?.groupValues?.get(1)
             } catch (e: Exception) {}

             // Recursive function to scan folders
             suspend fun scanFolder(currentPath: String, uk: String? = null, shareid: String? = null) {
                 val referer = "https://$apiDomain/"
                 val effectiveUk = uk ?: initialUk
                 val effectiveShareid = shareid ?: initialShareid

                 val folderApiUrl = if (effectiveUk == null || effectiveShareid == null) {
                     "https://$apiDomain/share/list?shorturl=$surl&root=1&web=1&channel=dubox&clienttype=0"
                 } else {
                     val encodedPath = java.net.URLEncoder.encode(currentPath, "UTF-8")
                     "https://$apiDomain/share/list?uk=$effectiveUk&shareid=$effectiveShareid&dir=$encodedPath&root=0&web=1&channel=dubox&clienttype=0"
                 }
                 
                 val headersList = listOf(
                     mapOf("Referer" to referer, "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"),
                     mapOf("User-Agent" to "LogStatistic", "Referer" to referer)
                 )
                 
                 var jsonResponse: String? = null
                 for (headers in headersList) {
                     try {
                         val res = app.get(folderApiUrl, headers = headers).text
                         // Verify success
                         if (res.contains("\"errno\":0") || res.contains("\"list\":[")) {
                            jsonResponse = res
                            break
                         }
                     } catch (e: Exception) {}
                 }

// ... (rest of scanFolder logic) ...

                 if (jsonResponse != null) {
                      // Extract uk and shareid if this is root/initial response
                      var nextUk = uk
                      var nextShareid = shareid
                      if (nextUk == null) {
                          val ukMatch = Regex("\"uk\":\\s*\"?(\\d+)\"?").find(jsonResponse)
                          val shareidMatch = Regex("\"shareid\":\\s*\"?(\\d+)\"?").find(jsonResponse)
                          nextUk = ukMatch?.groupValues?.get(1)
                          nextShareid = shareidMatch?.groupValues?.get(1)
                      }
                      
                      val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m4v", "wmv")
                      
                      // Using a chunk-based extraction to handle items correctly
                      val items = jsonResponse.split("\"fs_id\":")
                      
                      items.drop(1).forEach { chunk ->
                          val item = chunk // Chunk starts right after fs_id:
                          
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
                              
                              if (isDir == "1") { // It's a folder
                                  val rawPath = pathMatch?.groupValues?.get(1)?.replace("\\/", "/")
                                  val folderPath = if (!rawPath.isNullOrEmpty()) {
                                      rawPath
                                  } else {
                                      if (currentPath == "/") "/$filename" else "$currentPath/$filename"
                                  }
                                  
                                  if (folderPath != currentPath && folderPath.count { it == '/' } < 10 && nextUk != null) { 
                                      scanFolder(folderPath, nextUk, nextShareid)
                                  }
                              } else { // It's a file
                                  val ext = filename.substringAfterLast(".", "").lowercase()
                                  if (videoExtensions.contains(ext)) {
                                       val dlink = dlinkMatch?.groupValues?.get(1)?.replace("\\/", "/") 
                                           ?: "TERABOX_STREAMING_FALLBACK|$fsid|$nextUk|$nextShareid|$sharedSign|$sharedTimestamp"
                                           
                                       // Try to get high-res url3, else url2/url1
                                       val thumbUrl = thumbsMatch.lastOrNull()?.groupValues?.get(1)?.replace("\\/", "/")
                                       
                                       if (thumbUrl != null && (poster == null || poster?.contains("terabox") == false)) {
                                           poster = thumbUrl
                                       }
                                       
                                       val finalPoster = thumbUrl ?: poster
                                       
                                       episodes.add(
                                           newEpisode(dlink) {
                                               this.name = filename
                                               this.episode = episodes.size + 1
                                               this.posterUrl = finalPoster
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
                 scanFolder("/")
             } catch (e: Exception) {}
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
