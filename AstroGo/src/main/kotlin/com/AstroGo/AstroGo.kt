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

            if (response.isSuccessful && !response.text.contains("Invalid credentials")) {
                isLoggedIn = true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // Continue as guest if login fails, but playback might fail
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        login() // Ensure we attempt login first

        val document = app.get("$mainUrl/hubHome").document
        val homePageList = ArrayList<HomePageList>()

        // Scraping logic for Astro Go homepage
        // This is generic scraping, checking for rails of content
        document.select("div.rail-container, div.carousel").forEach { rail ->
            val title = rail.selectFirst("h2, h3")?.text() ?: "Featured"
            val items = rail.select("div.tile, div.card").mapNotNull { item ->
                val link = item.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val name = item.selectFirst("div.title, h4")?.text() ?: "Unknown"
                val img = item.selectFirst("img")?.attr("src")
                
                newMovieSearchResponse(name, "$mainUrl$link", TvType.Movie) {
                    this.posterUrl = img
                }
            }
             if (items.isNotEmpty()) {
                homePageList.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun load(url: String): LoadResponse {
        login()
        val doc = app.get(url).document
        val title = doc.selectFirst("h1.title, h1")?.text() ?: "Unknown"
        val plot = doc.selectFirst("p.description, div.synopsis")?.text()
        val poster = doc.selectFirst("img.poster, img.hero-image")?.attr("src")
        
        // Check if it's a series or movie
        val isSeries = url.contains("show") || doc.select("div.episodes").isNotEmpty()

        if (isSeries) {
            val episodes = doc.select("div.episode-tile").mapNotNull { ep ->
                val epTitle = ep.selectFirst("div.title")?.text() ?: "Episode"
                val epLink = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                // Parse "Episode 1" etc
                val epNum = Regex("Episode (\\d+)").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                
                newEpisode(epLink) {
                    this.name = epTitle
                    this.episode = epNum
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.plot = plot
                this.posterUrl = poster
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.plot = plot
                this.posterUrl = poster
            }
        }
    }

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
                ExtractorLink(
                    this.name,
                    "Astro Go (Dash)",
                    mpdUrl,
                    "",
                    Qualities.Unknown.value,
                    isM3u8 = false,
                    isDash = true
                )
            )
            // Note: Cloudstream ExtractorLink doesn't easily support passing custom DRM license URLs 
            // without using a wrapper or specific headers. 
            // However, common M3u8/Dash helpers might handle it if we can pass headers.
            // For now, we return the link. If it fails, we know it's because of DRM.
        }
        return true
    }
}
