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
        var tbLink = document.selectFirst(".dlbutton a")?.attr("href")
        if (tbLink == null) {
            tbLink = document.select("a").firstOrNull { it.attr("href").contains("terabox", ignoreCase = true) }?.attr("href")
        }
        
        // Handle landing page redirect if needed
        if (tbLink?.contains("downloadkatsini.com") == true) {
             try {
                 tbLink = app.get(tbLink).document.selectFirst("a[href*=terabox]")?.attr("href")
             } catch (e: Exception) {}
        }

        var poster = document.selectFirst(".post-filter-image img")?.attr("src") 
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.select(".post-body").text().trim()
        val tags = document.select(".post-tag a").map { it.text() }

        // If we found a Terabox link, try to use it for better metadata (images/files)
        if (!tbLink.isNullOrEmpty() && tbLink.contains("terabox")) {
             // Clean the link
             val cleanLink = tbLink.replace("teraboxapp.com", "terabox.app")
                 .replace("1024terabox.com", "terabox.app") 
                 .replace("terabox.com", "terabox.app")
             
             // Extract surl
             val surl = cleanLink.substringAfter("/s/").substringBefore("?")
             
             // Construct the API-like URLs the user mentioned, although these are usually web-viewable paths
             // Realistically, to get the file list, we should hit the API
             // For now, let's construct a "smart" poster if the original one is missing or if we want to follow the user's logic
             
             // User requested specific format: 
             // https://www.terabox.app/sharing/link?surl=...&path=.../Foto
             
             // Since we don't know the exact "path" without querying the API, we will just use the blog's poster 
             // BUT we will verify if we can fetch the file list to get a better poster if possible.
        }

        // If we found a Terabox link, attempt to fetch the file list to populate episodes
        val episodes = mutableListOf<Episode>()
        if (!tbLink.isNullOrEmpty() && tbLink.contains("terabox")) {
             // Clean the link
             val cleanLink = tbLink.replace("teraboxapp.com", "terabox.app")
                 .replace("1024terabox.com", "terabox.app") 
                 .replace("terabox.com", "terabox.app")
             
             // Extract surl: remove '1' prefix
             var tempSurl = cleanLink.substringAfter("/s/").substringBefore("?")
             if (tempSurl.startsWith("1")) tempSurl = tempSurl.substring(1)
             val surl = tempSurl

             // Recursive function to scan folders
             // We need to persist uk and shareid from the first root call
             suspend fun scanFolder(currentPath: String, uk: String? = null, shareid: String? = null) {
                 val folderApiUrl = if (uk == null || shareid == null) {
                     // First call: Root
                     "https://www.terabox.com/share/list?shorturl=$surl&root=1"
                 } else {
                     // Subfolder call
                     val encodedPath = java.net.URLEncoder.encode(currentPath, "UTF-8")
                     "https://www.terabox.com/share/list?uk=$uk&shareid=$shareid&dir=$encodedPath&root=0"
                 }
                 
                 val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                 val headersList = listOf(
                     mapOf("User-Agent" to pcUserAgent, "Referer" to cleanLink, "Cookie" to "browserid=1; lang=en; ndus=YAAAAAA"),
                     mapOf("User-Agent" to "LogStatistic", "Referer" to cleanLink)
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

                 if (jsonResponse != null) {
                      // Extract uk and shareid if this is root
                      var nextUk = uk
                      var nextShareid = shareid
                      if (nextUk == null) {
                          val ukMatch = Regex("\"uk\":(\\d+)").find(jsonResponse)
                          val shareidMatch = Regex("\"shareid\":(\\d+)").find(jsonResponse)
                          nextUk = ukMatch?.groupValues?.get(1)
                          nextShareid = shareidMatch?.groupValues?.get(1)
                      }
                      
                      val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "flv", "m4v", "wmv")
                      
                      // Split items carefully
                      val items = jsonResponse.split("{\"category\"")
                      items.drop(1).forEach { item -> 
                          val isDirMatch = Regex("\"isdir\":\"(.*?)\"").find(item)
                          val isDir = isDirMatch?.groupValues?.get(1) ?: "0"
                          
                          val filenameMatch = Regex("\"server_filename\":\"(.*?)\"").find(item)
                          val dlinkMatch = Regex("\"dlink\":\"(.*?)\"").find(item)
                          val pathMatch = Regex("\"path\":\"(.*?)\"").find(item) 
                          val thumbsMatch = Regex("\"url3\":\"(.*?)\"").find(item) // High res thumbnail
                          
                          if (filenameMatch != null) {
                              val filename = filenameMatch.groupValues[1]
                              
                              if (isDir == "1") { // It's a folder
                                  val folderPath = pathMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: "$currentPath/$filename"
                                  // Recursive call with uk/shareid
                                  // CRITICAL: Ensure we pass nextUk and nextShareid
                                  if (folderPath != currentPath && folderPath.count { it == '/' } < 10 && nextUk != null) { 
                                      scanFolder(folderPath, nextUk, nextShareid)
                                  }
                              } else if (dlinkMatch != null) { // It's a file
                                  val dlink = dlinkMatch.groupValues[2].replace("\\/", "/")
                                  val ext = filename.substringAfterLast(".", "").lowercase()
                                  
                                  if (videoExtensions.contains(ext)) {
                                       val thumbUrl = thumbsMatch?.groupValues?.get(1)?.replace("\\/", "/") ?: poster
                                       episodes.add(
                                           newEpisode(dlink) {
                                               this.name = filename
                                               this.episode = episodes.size + 1
                                               this.posterUrl = thumbUrl
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
        if (data.contains("terabox.com/file/") || data.contains("d.terabox.com")) {
             val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
             callback.invoke(
                 newExtractorLink(
                     "Terabox",
                     "Terabox",
                     data,
                     INFER_TYPE
                 ) {
                     this.headers = mapOf(
                         "User-Agent" to userAgent,
                         "Referer" to "https://www.terabox.com" 
                     )
                 }
             )
             return true
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
            if (href.contains("terabox", ignoreCase = true)) {
                foundLinks.add(href)
            }
        }
        
        foundLinks.forEach { href ->
             if (href.contains("terabox", ignoreCase = true)) {
                 loadExtractor(href, subtitleCallback, callback)
             } else if (href.contains("downloadkatsini.com", ignoreCase = true)) {
                 try {
                     val landingDoc = app.get(href).document
                     landingDoc.select("a").forEach { innerLink ->
                         val innerHref = innerLink.attr("href")
                         if (innerHref.contains("terabox", ignoreCase = true)) {
                             loadExtractor(innerHref, subtitleCallback, callback)
                         }
                     }
                 } catch (e: Exception) {}
             }
        }
        return true
    }
}
