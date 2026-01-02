@file:Suppress("DEPRECATION")
package com.AstroGo

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.util.UUID
import okhttp3.FormBody
import org.json.JSONObject

// --- Data Classes (Top Level) ---
data class AstroMedia(
    val poster: String? = null,
    val landscape: String? = null,
    val portrait: String? = null
)

data class AstroAsset(
    val id: Any? = null,
    val title: String? = null,
    val media: AstroMedia? = null,
    val duration: String? = null,
    val synopsis: String? = null,
    val productionYear: String? = null,
    val genres: List<String>? = null
)

data class AstroSwimlane(
    val title: String? = null,
    val swimlaneId: String? = null,
    val scrollableAssets: List<AstroAsset>? = null
)

data class AstroHomeResponse(
    val response: List<AstroSwimlane>? = null,
    val results: List<AstroSwimlane>? = null
)

class AstroGo : MainAPI() {
    override var mainUrl = "https://astrogo.astro.com.my"
    override var name = "Astro Go"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    private val USER_EMAIL = "usherralf@gmail.com"
    private val USER_PASS = "asy280410"

    private val CLIENT_TOKEN = "v:1!r:80800!ur:GUEST_REGION!community:Malaysia%20Live!t:k!dt:PC!f:Astro_unmanaged!pd:CHROME-FF!pt:Adults"
    private val CTAP_URL = "https://sg-sg-sg.astro.com.my:9443/ctap/r1.6.0"

    private var isLoggedIn = false

    private suspend fun login() {
        if (isLoggedIn) return

        try {
            Log.d("AstroGo", "Starting login process...")
            val home = app.get("$mainUrl/hubHome").document
            
            var loginUrl = home.selectFirst("a:contains(Login), button:contains(Login)")?.attr("href")
            
            if (loginUrl == null || !loginUrl.startsWith("http")) {
                loginUrl = "https://auth.astro.com.my/login" 
            }

            Log.d("AstroGo", "Fetching login page: $loginUrl")
            val loginPage = app.get(loginUrl).document
            val form = loginPage.selectFirst("form")
            val action = form?.attr("action") ?: return
            
            val csrfToken = form.selectFirst("input[name=csrf_token]")?.attr("value") ?: ""
            
            val submitUrl = if (action.startsWith("http")) action else {
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
                Log.d("AstroGo", "Login successful!")
            } else {
                Log.d("AstroGo", "Login failed: ${response.text}")
            }
        } catch (e: Exception) {
            Log.e("AstroGo", "Login error: ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        login()

        val homeApiUrl = "$CTAP_URL/shared/bulkContent/node:IVP:Home?clientToken=$CLIENT_TOKEN"
        
        val text = app.get(homeApiUrl, headers = mapOf("Accept" to "application/json")).text
        
        val swimlanes: List<AstroSwimlane> = try {
            val response = parseJson<AstroHomeResponse>(text)
            response.response ?: response.results ?: emptyList()
        } catch (e: Exception) {
            try {
                parseJson<List<AstroSwimlane>>(text)
            } catch (e2: Exception) {
                Log.e("AstroGo", "Failed to parse Home API: ${e2.message}")
                emptyList()
            }
        }

        if (swimlanes.isEmpty()) {
            return newHomePageResponse(emptyList(), false)
        }

        val homePageList = swimlanes.mapNotNull { swimlane: AstroSwimlane ->
            val sectionTitle = swimlane.title ?: "Featured"
            val assets = swimlane.scrollableAssets
            
            if (assets.isNullOrEmpty()) return@mapNotNull null

            val films = assets.mapNotNull { asset: AstroAsset ->
                val title = asset.title ?: return@mapNotNull null
                val id = asset.id?.toString() ?: return@mapNotNull null
                
                val posterUrl = asset.media?.poster ?: asset.media?.portrait ?: asset.media?.landscape

                newMovieSearchResponse(title, id, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
            
            if (films.isEmpty()) return@mapNotNull null
            
            HomePageList(sectionTitle, films)
        }

        return newHomePageResponse(homePageList, false)
    }

    override suspend fun load(url: String): LoadResponse {
        login()
        val docUrl = if (url.contains("ctap")) "$url?clientToken=$CLIENT_TOKEN" else url
        
        if (docUrl.contains("ctap")) {
            val responseText = app.get(docUrl).text
            val json = JSONObject(responseText)
            val data = json.optJSONObject("response") ?: json
            
            val title = data.optString("title", "Unknown")
            val plot = data.optString("longDescription") ?: data.optString("shortDescription")
            val images = data.optJSONObject("images")
            val poster = images?.optString("poster")
            
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.plot = plot
                this.posterUrl = poster
            }
        } else {
            return newMovieLoadResponse("Unknown", url, TvType.Movie, url)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        login()
        val doc = app.get(data).document
        val html = doc.html()
        
        val manifestRegex = Regex("""src:\s*"([^"]+\.mpd)"""")
        val mpdUrl = manifestRegex.find(html)?.groupValues?.get(1)
        
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
        }
        return true
    }
}
