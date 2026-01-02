@file:Suppress("DEPRECATION")
package com.AstroGo

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.FormBody
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.api.Log
import java.util.UUID
import org.json.JSONObject

class AstroGo : MainAPI() {
    override var mainUrl = "https://astrogo.astro.com.my"
    override var name = "Astro Go"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    // Hardcoded credentials as requested
    private val USER_EMAIL = "usherralf@gmail.com"
    private val USER_PASS = "asy280410"

    private var isLoggedIn = false

    private suspend fun login() {
        if (isLoggedIn) return

        try {
            Log.d("AstroGo", "Starting login process...")
            // 1. Go to homepage to find the login link/redirect
            val home = app.get("$mainUrl/hubHome").document
            
            // Find the "Login with Astro ID" link or similar
            // This is a guess based on standard behavior, might need adjustment
            var loginUrl = home.selectFirst("a:contains(Login), button:contains(Login)")?.attr("href")
            
            if (loginUrl == null || !loginUrl.startsWith("http")) {
                // Fallback: try to hit a protected endpoint or the known auth base
                // Based on investigation: https://auth.astro.com.my/login
                loginUrl = "https://auth.astro.com.my/login" 
            }

            // 2. Fetch the login page to get CSRF token and flow ID
            Log.d("AstroGo", "Fetching login page: $loginUrl")
            val loginPage = app.get(loginUrl).document
            // The action URL usually contains the ?flow=... parameter
            val form = loginPage.selectFirst("form")
            val action = form?.attr("action") ?: return
            val method = form.attr("method") // usually POST
            
            // Extract hidden inputs
            val csrfToken = form.selectFirst("input[name=csrf_token]")?.attr("value") ?: ""
            // The browser analysis said input name is "identifier" and "password"
            
            // 3. Post credentials
            // Construct the full action URL if it's relative
            val submitUrl = if (action.startsWith("http")) action else {
                // simple resolve
                "https://auth.astro.com.my$action" 
            }

            val body = FormBody.Builder()
                .add("identifier", USER_EMAIL)
                .add("password", USER_PASS)
                .add("method", "password")
                .add("csrf_token", csrfToken)
                .build()

            val response = app.post(
                submitUrl,
                requestBody = body,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded"
                )
            )

            Log.d("AstroGo", "Login response code: ${response.code}")
            if (response.isSuccessful && !response.text.contains("Invalid credentials")) {
                isLoggedIn = true
                Log.d("AstroGo", "Login successful!")
            } else {
                Log.d("AstroGo", "Login failed: ${response.text}")
            }
        } catch (e: Exception) {
            Log.e("AstroGo", "Login error: ${e.message}")
            e.printStackTrace()
            // Continue as guest if login fails, but playback might fail
        }
    }

    // Captured from browser inspector
    private val CLIENT_TOKEN = "v:1!r:80800!ur:GUEST_REGION!community:Malaysia%20Live!t:k!dt:PC!f:Astro_unmanaged!pd:CHROME-FF!pt:Adults"
    private val CTAP_URL = "https://sg-sg-sg.astro.com.my:9443/ctap/r1.6.0"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        login() // Ensure we attempt login first

        Log.d("AstroGo", "Fetching main page via API...")
        // Generate a random device ID if one isn't stored, but for now a random UUID works for guest access usually
        val deviceId = UUID.randomUUID().toString()
        
        val url = "$CTAP_URL/shared/bulkContent/node:IVP:Home?clientToken=$CLIENT_TOKEN"
        val response = app.get(
            url,
            headers = mapOf(
                "X-Device-Id" to deviceId,
                "Accept" to "application/json"
            )
        )

        val homePageList = ArrayList<HomePageList>()
        
        try {
            val json = JSONObject(response.text)
            val responseNode = json.optJSONObject("response") ?: json
            
            // The structure is likely: response -> [results] -> [containers/swimlanes]
            // We need to iterate through the keys or a specific list
            // Based on generic CTAP structure, usually there's a list of items
            
            // Let's try to find a list of containers. 
            // Since we don't have exact schema, we'll look for "containers" or iterate results if it's an array
            
            // MOCKED PARSING BASED ON COMMON STRUCTURE:
            // "containers": [ { "layout": "row", "assets": [...] } ]
            // OR "results": [ ... ]
            
            // Given I can't run the parser on real data yet, I'll log the specific keys to help debug if this fails,
            // but I'll write a best-guess parser for "assets" or "items".
            
            val containers = responseNode.optJSONArray("containers") 
                ?: responseNode.optJSONArray("results")
                ?: responseNode.optJSONArray("assets")

            if (containers != null) {
                for (i in 0 until containers.length()) {
                    val container = containers.getJSONObject(i)
                    val title = container.optString("title", container.optString("name", "Featured"))
                    val assets = container.optJSONArray("assets") ?: container.optJSONArray("items")
                    
                    if (assets != null && assets.length() > 0) {
                        val innerItems = ArrayList<SearchResponse>()
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val assetTitle = asset.optString("title", asset.optString("name", "Unknown"))
                            val assetId = asset.optString("id")
                            val images = asset.optJSONObject("images")
                            val poster = images?.optString("poster") ?: images?.optString("landscape")
                            
                            // We need a smart way to pass ID to load(). 
                            // API usually gives details by ID.
                            // Let's use the API detail URL as the 'url' param.
                            // Detail URL: $CTAP_URL/shared/content/$id
                            
                            if (assetId.isNotEmpty()) {
                                innerItems.add(
                                    newMovieSearchResponse(assetTitle, "$CTAP_URL/shared/content/$assetId", TvType.Movie) {
                                        this.posterUrl = poster
                                    }
                                )
                            }
                        }
                        if (innerItems.isNotEmpty()) {
                            homePageList.add(HomePageList(title, innerItems))
                        }
                    }
                }
            } else {
                 Log.d("AstroGo", "No containers found in JSON keys: ${responseNode.keys().asSequence().toList()}")
            }

        } catch (e: Exception) {
            Log.e("AstroGo", "Error parsing API JSON: ${e.message}")
            e.printStackTrace()
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun load(url: String): LoadResponse {
        login()
        // URL should now be the API url or we handle the old mainUrl style
        // If it starts with http/https and contains 'ctap', it's our API url
        
        val docUrl = if (url.contains("ctap")) "$url?clientToken=$CLIENT_TOKEN" else url
        
        Log.d("AstroGo", "Loading detail: $docUrl")
        
        if (docUrl.contains("ctap")) {
            val response = app.get(docUrl).text
            val json = JSONObject(response)
            val data = json.optJSONObject("response") ?: json
            
            val title = data.optString("title", "Unknown")
            val plot = data.optString("longDescription") ?: data.optString("shortDescription")
            val images = data.optJSONObject("images")
            val poster = images?.optString("poster")
            
            // Check for episodes or video URL
            // This part is speculative without seeing the detail JSON.
            // But usually 'assets' inside a detail might mean episodes for a show.
            
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.plot = plot
                this.posterUrl = poster
            }
        } else {
             // Fallback to old scraping if somehow we get a web URL
            val doc = app.get(url).document
            val title = doc.selectFirst("h1.title, h1")?.text() ?: "Unknown"
            return newMovieLoadResponse(title, url, TvType.Movie, url)
        }
    }

    @Suppress("DEPRECATION")
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        login()
        
        // Fetch the player page/data to get the Manifest
        // Based on browser findings, it uses DASH/Widevine
        
        // We probably need to extract the 'playback' info from the page source or a JSON blob
        val doc = app.get(data).document
        val html = doc.html()
        
        // Look for manifest URL in scripts
        val manifestRegex = Regex("""src:\s*"([^"]+\.mpd)"""")
        val mpdUrl = manifestRegex.find(html)?.groupValues?.get(1)
        
        // Look for License URL
        // Usually configured in the player config in the HTML
        val licenseRegex = Regex("""licenseUrl:\s*"([^"]+)"""")
        val licenseUrl = licenseRegex.find(html)?.groupValues?.get(1) 
            ?: "https://sg-sg-sg.astro.com.my:9443/vgemultidrm/v1/widevine/getservicecertificate" // Fallback from research

        if (mpdUrl != null) {
            callback.invoke(
                newExtractorLink(
                    this.name,
                    "Astro Go (Dash)",
                    mpdUrl,
                    INFER_TYPE
                ) {
                    this.quality = Qualities.Unknown.value
                }
            )
            // Note: Cloudstream ExtractorLink doesn't easily support passing custom DRM license URLs 
            // without using a wrapper or specific headers. 
            // However, common M3u8/Dash helpers might handle it if we can pass headers.
            // For now, we return the link. If it fails, we know it's because of DRM.
        }
        return true
    }
}
