package com.botol.astrogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
// import com.fasterxml.jackson.databind.JsonNode // Removed to avoid errors
// import com.fasterxml.jackson.databind.ObjectMapper // Removed to avoid errors
// import com.fasterxml.jackson.module.kotlin.readValue // Removed to avoid errors

import java.util.UUID
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import android.util.Base64
import okio.Buffer

class AstroGo : MainAPI() {
    override var mainUrl = "https://astrogo.astro.com.my"
    private val apiUrl = "https://sg-sg-sg.astro.com.my:9443/ctap/r1.6.0"
    override var name = "AstroGo"
    override val hasMainPage = true
    override var lang = "ms"
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    // configuration
    private var clientToken = "v:1!r:80200!ur:SARAWAK!community:Malaysia%20Live!t:k!dt:PC!f:Astro_unmanaged!pd:CHROME-FF!pt:Adults"
    private var bearerToken = getKey<String>("astro_bearer_token") ?: ""

    override val mainPage = mainPageOf(
        "node:IVP:Home:OnDemandRecentlyAdded" to "Home",
        "IVP:TVShow:All,-date" to "TV Shows",
        "node:IVP:Movies,-date" to "Movies",
        "IVP:Live:All" to "Live TV"
    )

    override fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val url = request.url.toString()
            
            if (url.contains("vgemultidrm/v1/widevine/license")) {
               System.out.println("DEBUG AstroGo Interceptor: Intercepting License Request: $url")
                // Get stored headers from extractorLink
                val contentId = extractorLink.headers["X-Astro-Content-ID"] ?: ""
                val authKey = extractorLink.headers["X-Astro-Auth"] ?: ""
                
                // Parse AssetId and AuthToken from AuthKey (blob)
                val assetId = authKey.split("&").find { it.startsWith("AssetId=") }?.substringAfter("AssetId=")
                val trueAuthToken = authKey.split("&").find { it.startsWith("AuthToken=") }?.substringAfter("AuthToken=")
                val finalContentId = assetId ?: contentId
                val finalAuthToken = trueAuthToken ?: authKey

                // Read original binary body (the raw challenge)
                val originalBodyBytes = request.body?.let { body ->
                    val buffer = Buffer()
                    body.writeTo(buffer)
                    buffer.readByteArray()
                } ?: ByteArray(0)
                
                val challengeBase64 = Base64.encodeToString(originalBodyBytes, Base64.NO_WRAP)

                val jsonBody = """
                    {
                        "contentID": "$finalContentId",
                        "contentType": 1,
                        "authorizationToken": "$finalAuthToken",
                        "authorizationTokenType": "1",
                        "licenseChallenge": "$challengeBase64",
                        "playbackSessionCookie": null
                    }
                """.trimIndent()
                System.out.println("DEBUG AstroGo Interceptor Payload: $jsonBody")
                
                val newRequest = request.newBuilder()
                    .post(jsonBody.toRequestBody("application/json".toMediaTypeOrNull()))
                    .build()

                val response = chain.proceed(newRequest)
                if (!response.isSuccessful) {
                     val errorBody = response.peekBody(Long.MAX_VALUE).string()
                     System.out.println("DEBUG AstroGo Interceptor Error: Code=${response.code} Body=$errorBody")
                }
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    try {
                        // Regex parse licenseData which is an array of strings
                        // "licenseData": [ "BASE64..." ]
                        val licenseRegex = "\"licenseData\"\\s*:\\s*\\[\\s*\"([^\"]+)\"".toRegex()
                        val match = licenseRegex.find(responseBody)
                        val license = match?.groupValues?.get(1)
                        
                        if (!license.isNullOrEmpty()) {
                            val licenseBytes = Base64.decode(license, Base64.DEFAULT)
                            return@Interceptor response.newBuilder()
                                .body(licenseBytes.toResponseBody("application/octet-stream".toMediaTypeOrNull()))
                                .build()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                return@Interceptor response
            }
            
            chain.proceed(request)
        }
    }

    private fun getSavedToken(): String? {
        return getKey("astro_bearer_token")
    }

    fun saveToken(token: String) {
        setKey("astro_bearer_token", token)
        bearerToken = token
        
        // Try to extract Device ID from JWT
        try {
            val parts = token.split(".")
            if (parts.size >= 2) {
                val payload = String(Base64.decode(parts[1], Base64.URL_SAFE))
                // Simple string find to avoid huge JSON dependency overhead if not needed, 
                // or use regex for robustness. Claims are usually "deviceId":"..."
                val deviceIdRegex = "\"(?:deviceId|device_id|uuid)\"\\s*:\\s*\"?([^,\"}]+)\"?".toRegex()
                val match = deviceIdRegex.find(payload)
                val deviceId = match?.groups?.get(1)?.value
                
                if (deviceId != null) {
                    setKey("astro_device_id", deviceId)
                    System.out.println("DEBUG AstroGo: Extracted Device ID from Token: $deviceId")
                }
            }
        } catch (e: Exception) {
             System.out.println("DEBUG AstroGo: Failed to parse JWT for Device ID: ${e.message}")
        }
    }

    fun isLoggedIn(): Boolean {
        return bearerToken.isNotEmpty()
    }

    suspend fun logout() {
        if (bearerToken.isNotEmpty()) {
            val storedDeviceId = getKey<String>("astro_device_id")
            
            // Common headers
            val headers = mapOf(
                "Authorization" to "Bearer $bearerToken",
                "Accept" to "application/json",
                "Content-Type" to "application/json"
            )

            var targetId: String? = null
            
            try {
                // 1. Fetch devices first to get the authoritative list IDs
                val devicesUrl = "$apiUrl/household/me/devices"
                System.out.println("DEBUG AstroGo Logout: Fetching from $devicesUrl")
                val response = app.get(devicesUrl, headers = headers).text
                System.out.println("DEBUG AstroGo Logout: JSON=$response")
                
                // 2. Parse all IDs
                val idRegex = "\"(?:id|deviceId|uuid)\"\\s*:\\s*(?:\"([^\"]+)\"|([^,}\\s]+))".toRegex()
                val allIds = idRegex.findAll(response).map { 
                    it.groups[1]?.value ?: it.groups[2]?.value 
                }.filterNotNull().toMutableList()
                
                // Filter for removable IDs (skip Set-top boxes with short IDs)
                val removableIds = allIds.filter { it.length > 15 }
                System.out.println("DEBUG AstroGo Logout: Removable Candidates: $removableIds")

                // 3. Strategy: Find "This Device"
                if (!storedDeviceId.isNullOrEmpty() && removableIds.isNotEmpty()) {
                    // Fuzzy match: Check if the stored ID is part of a server ID or vice versa
                    // e.g. server: "123.UUID", stored: "UUID"
                    targetId = removableIds.find { serverId -> 
                        serverId.contains(storedDeviceId, ignoreCase = true) || storedDeviceId.contains(serverId, ignoreCase = true)
                    }
                    if (targetId != null) {
                        System.out.println("DEBUG AstroGo Logout: Matched 'This Device' ($storedDeviceId) to Server ID: $targetId")
                    }
                }
                
                // 4. Fallback: If no match (or no stored ID), delete the FIRST removable device to free a slot.
                if (targetId == null && removableIds.isNotEmpty()) {
                    targetId = removableIds.first()
                    System.out.println("DEBUG AstroGo Logout: 'This Device' not matched. Removing candidate: $targetId")
                }

                // 5. Execute Delete
                if (targetId != null) {
                     System.out.println("DEBUG AstroGo Logout: Sending DELETE for $targetId")
                     val deleteUrl = "$apiUrl/household/me/devices/$targetId"
                     val delResp = app.delete(deleteUrl, headers = headers)
                     System.out.println("DEBUG AstroGo Logout: DELETE Code=${delResp.code} Body=${delResp.text}")
                } else {
                     System.out.println("DEBUG AstroGo Logout: No removable devices found.")
                }

            } catch (e: Exception) {
                System.out.println("DEBUG AstroGo Logout Error: ${e.message}")
            }
        }

        // Cleanup
        setKey("astro_bearer_token", null)
        setKey("astro_profile_id", null)
        setKey("astro_device_id", null)
        setKey("astro_trigger_login", false)
        bearerToken = ""
    }


    private suspend fun ensureGuestToken() {
        // If we have a valid logged-in token, don't override it with guest token
        // We simple check if bearerToken is present. 
        // NOTE: We might need a way to distinguish Guest vs User token. 
        // For now, if it's empty, get guest.
        if (bearerToken.isNotEmpty()) return 

        // Ensure clientToken is always populated
        if (clientToken.isEmpty()) {
            clientToken = generateClientToken()
        }

        try {
            // URL discovered via user feedback (port 9443 removed)
            val authUrl = "https://sg-sg-sg.astro.com.my/oauth2/authorize?client_id=browser&state=guestUserLogin&redirect_uri=https://astrogo.astro.com.my&response_type=token&prompt=none&scope=urn:synamedia:vcs:ovp:guest-user"
            
            var currentUrl = authUrl
            var attempts = 0
            while (attempts < 5) {
                val headers = mapOf(
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
                )
                
                val response = app.get(currentUrl, headers = headers, allowRedirects = false)
                
                // Check if we have the token in the URL fragment (e.g. from Location header or current response URL)
                val location = response.headers["Location"]
                val targetUrl = location ?: response.url
                
                if (targetUrl.contains("access_token=")) {
                    // Extract from hash (#) or query (?)
                    val token = targetUrl.substringAfter("access_token=").substringBefore("&").substringBefore("#")
                    if (token.isNotEmpty()) {
                        // Don't save guest token to persistent storage to avoid confusing it with user token
                        bearerToken = token
                        break
                    }
                }
                
                if (response.code in 300..308 && location != null) {
                    currentUrl = if (location.startsWith("/")) {
                        val uri = java.net.URI(currentUrl)
                        "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}$location"
                    } else {
                        location
                    }
                    attempts++
                } else {
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun login(username: String, pass: String): Boolean {
        System.out.println("DEBUG AstroGo Login: Starting for user $username")
        return try {
            val clientId = "browser"
            val authState = "bootup"
            // Ensure strict URL encoding for redirect_uri as requested
            val redirectUri = "https://astrogo.astro.com.my"
            val encodedRedirectUri = java.net.URLEncoder.encode(redirectUri, "UTF-8")

            // User Agent mimicking a real browser is often required to avoid 403 or CAPTCHA triggers on the initial request
            val baseHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Upgrade-Insecure-Requests" to "1"
            )

            // 1. Start OAuth Flow
            // URL strictly matched to user's request
            val authUrl = "https://sg-sg-sg.astro.com.my:9443/oauth2/authorize?client_id=$clientId&state=$authState&redirect_uri=$encodedRedirectUri&response_type=token"
            System.out.println("DEBUG AstroGo Login: Auth URL: $authUrl")

            var currentUrl = authUrl
            var attempts = 0
            val maxAttempts = 20
            
            // Cookie jar for this session
            val currentCookies = mutableMapOf<String, String>()

            while (attempts < maxAttempts) {
                System.out.println("DEBUG AstroGo Login: Requesting ($attempts): $currentUrl")
                val response = app.get(currentUrl, headers = baseHeaders, allowRedirects = false, cookies = currentCookies)
                
                // Update cookies from response
                response.cookies.forEach { (k, v) -> currentCookies[k] = v }
                // Also check raw Set-Cookie header just in case NiceHttp misses something for redirects
                response.headers["Set-Cookie"]?.let { cookieHeader ->
                    // excessively simple parser, but might catch what we need
                    cookieHeader.split(";").firstOrNull()?.let { 
                        val parts = it.split("=")
                        if (parts.size >= 2) currentCookies[parts[0].trim()] = parts[1].trim()
                    }
                }

                // Check for token
                val location = response.headers["Location"]
                val checkUrl = location ?: response.url

                if (checkUrl.contains("access_token=") || currentUrl.contains("access_token=")) {
                     val tokenUrl = if (checkUrl.contains("access_token=")) checkUrl else currentUrl
                     val token = tokenUrl.substringAfter("access_token=").substringBefore("&")
                     System.out.println("DEBUG AstroGo Login: Token found: ${token.take(10)}...")
                     saveToken(token)
                     fetchAndSaveProfile()
                     return true
                }

                if (response.code in 300..308 && location != null) {
                    // Handle Redirect
                    var nextUrl = location
                    if (nextUrl.startsWith("/")) {
                        val uri = java.net.URI(currentUrl)
                        nextUrl = "${uri.scheme}://${uri.host}${if (uri.port != -1 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""}$nextUrl"
                    }
                    currentUrl = nextUrl

                    // IF we are redirected to the login page
                    if (currentUrl.contains("auth.astro.com.my/login")) {
                         System.out.println("DEBUG AstroGo Login: Hit Login Page. Handling Form Submission...")
                         return handleLoginForm(currentUrl, username, pass, baseHeaders, currentCookies)
                    }

                } else {
                    // Stopped redirecting
                    if (response.url.contains("auth.astro.com.my/login")) {
                         System.out.println("DEBUG AstroGo Login: Landed on Login Page. Handling Form Submission...")
                         return handleLoginForm(response.url, username, pass, baseHeaders, currentCookies)
                    }

                    System.out.println("DEBUG AstroGo Login: Stopped at ${response.url} without token.")
                    // If we stopped at a page, print some content to see if it's a CAPTCHA or error
                    if (attempts == 0) System.out.println("DEBUG AstroGo Body Preview: ${response.text.take(500)}")
                    break
                }
                attempts++
            }
            false
        } catch (e: Exception) {
            System.out.println("DEBUG AstroGo Login Exception: ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private suspend fun handleLoginForm(
        loginUrl: String, 
        username: String, 
        pass: String, 
        baseHeaders: Map<String, String>, 
        existingCookies: MutableMap<String, String>
    ): Boolean {
         try {
             // 1. Fetch the page to get CSRF (using existing cookies)
             System.out.println("DEBUG AstroGo Login: Fetching HTML from $loginUrl")
             val pageResp = app.get(loginUrl, headers = baseHeaders, cookies = existingCookies)
             pageResp.cookies.forEach { (k, v) -> existingCookies[k] = v }
             
             val html = pageResp.text
             
             // Check if we hit a CAPTCHA
             if (html.contains("hcaptcha", ignoreCase = true) || html.contains("security check", ignoreCase = true)) {
                 System.out.println("DEBUG AstroGo Login: HIT CAPTCHA! Cannot proceed programmatically without solving.")
                 return false
             }

             // 2. Extract CSRF
             val csrfToken = Regex("""name=["']csrf_token["'][^>]*?value=["']([^"']+)["']""").find(html)?.groupValues?.get(1) 
                             ?: Regex("""value=["']([^"']+)["'][^>]*?name=["']csrf_token["']""").find(html)?.groupValues?.get(1)
             
             if (csrfToken == null) {
                 System.out.println("DEBUG AstroGo Login: CSRF Token not found in HTML!")
                 return false
             }
             System.out.println("DEBUG AstroGo Login: CSRF Token extracted.")
             
             // 3. Prepare Form Data
             val postUrl = loginUrl 
             
             val formData = mapOf(
                "identifier" to username,
                "password" to pass,
                "method" to "password",
                "csrf_token" to csrfToken
             )
             
             // Headers for POST: Add Referer/Origin
             val postHeaders = baseHeaders + mapOf(
                 "Referer" to loginUrl,
                 "Origin" to "https://auth.astro.com.my",
                 "Content-Type" to "application/x-www-form-urlencoded"
             )
             
             System.out.println("DEBUG AstroGo Login: Posting Form to $postUrl")
             val postResp = app.post(
                 postUrl, 
                 headers = postHeaders,
                 data = formData, 
                 cookies = existingCookies,
                 allowRedirects = false
             )
             
             // Update cookies again
             postResp.cookies.forEach { (k, v) -> existingCookies[k] = v }
             
             System.out.println("DEBUG AstroGo Login: POST Code: ${postResp.code}")
             
             // 4. Follow Redirects after POST
             if (postResp.code in 300..308 && postResp.headers["Location"] != null) {
                  var nextUrl = postResp.headers["Location"]!!
                  var attempts = 0
                  
                  while (attempts < 10) {
                     if (nextUrl.contains("access_token=")) {
                         val token = nextUrl.substringAfter("access_token=").substringBefore("&")
                         System.out.println("DEBUG AstroGo Login: Token found in POST redirect: ${token.take(10)}...")
                         saveToken(token)
                         fetchAndSaveProfile()
                         return true
                     }
                     
                      if (nextUrl.startsWith("/")) {
                          nextUrl = "https://auth.astro.com.my$nextUrl"
                      }
                      
                      System.out.println("DEBUG AstroGo Login: Redirecting to: $nextUrl")
                      val resp = app.get(nextUrl, headers = baseHeaders, cookies = existingCookies, allowRedirects = false)
                      resp.cookies.forEach { (k, v) -> existingCookies[k] = v }
                      
                      val loc = resp.headers["Location"]
                      if (resp.code in 300..308 && loc != null) {
                          nextUrl = loc
                      } else {
                           if (resp.url.contains("access_token=")) {
                               val token = resp.url.substringAfter("access_token=").substringBefore("&")
                               saveToken(token)
                               fetchAndSaveProfile()
                               return true
                           }
                           
                           if (loc != null && loc.contains("access_token=")) {
                               val token = loc.substringAfter("access_token=").substringBefore("&")
                               saveToken(token)
                               fetchAndSaveProfile()
                               return true
                           }
                           
                           System.out.println("DEBUG AstroGo Login: Redirect chain ended without token at ${resp.url}")
                           break
                      }
                      attempts++
                  }
             } else {
                  System.out.println("DEBUG AstroGo Login: POST did not redirect. Body start: ${postResp.text.take(100)}")
             }
         } catch (e: Exception) {
             e.printStackTrace()
         }
         return false
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (page > 1 && request.name == "Home") {
            return newHomePageResponse(emptyList())
        }

        // Check if we need to perform a pending user login (from Settings)
        val triggerLogin = getKey<Boolean>("astro_trigger_login") ?: false
        if (triggerLogin) {
            val user = getKey<String>("astro_username")
            val pass = getKey<String>("astro_password")
            if (!user.isNullOrEmpty() && !pass.isNullOrEmpty()) {
                login(user, pass)
                setKey("astro_trigger_login", false)
            }
        }

        // Load saved token if available and not in memory
        if (bearerToken.isEmpty()) {
            val saved = getSavedToken()
            if (!saved.isNullOrEmpty()) {
                bearerToken = saved
            }
        }

        if (bearerToken.isNotEmpty()) {
            val pid = getKey<String>("astro_profile_id")
            if (pid == null || pid == "NOT_FOUND") {
                fetchAndSaveProfile()
            }
        }

        // If still empty or if it's a guest token that expired (logic needed?), get guest token
        // For now, if empty, we get guest.
        ensureGuestToken()
        
        val limit = 20
        val offset = (page - 1) * limit
        
        val dataParts = request.data.split(",")
        val dataPath = dataParts[0]
        val sort = dataParts.getOrNull(1)

        // clientToken already contains %20 and other special chars that should be sent as-is (or carefully encoded)
        // User URL shows: clientToken=v:1!r:80200!ur:SARAWAK!community:Malaysia%20Live...
        // If we encode, %20 becomes %2520 which breaks it.
        val encodedToken = clientToken 
        val encodedPath = java.net.URLEncoder.encode(dataPath, "UTF-8")
        val url: String
        
        // Extra params required by Astro
        val extraParams = "offerKeys=212,34,446,49,64,94,95&isErotic=true&isAdult=false"

        // Logic based on user feedback:
        // Home: shared/content?categoryId=...&...&offerKeys=...
        // TVShows: shared/content?categoryId=...&...&offerKeys=...
        // Main difference is checking if we need bulkContent (usually for specific lists without params)
        // But user provided URLs use shared/content for both Home and TVShows with offerKeys.
        
        if (dataPath.contains("Home") || sort != null || dataPath.contains("TVShow") || dataPath.contains("Live")) {
             // Use shared/content for Home and sorted lists/TVShows
             // Ensure defaults for offset/limit if not present (though offset is calc above)
             url = "$apiUrl/shared/content?categoryId=$encodedPath&clientToken=$encodedToken&offset=$offset&limit=$limit&sort=${sort ?: "-date"}&$extraParams"
        } else {
             // Fallback for others that might need bulkContent (though maybe deprecated for these main categories)
             val endpoint = "shared/bulkContent/$encodedPath"
             url = "$apiUrl/$endpoint?clientToken=$encodedToken" // bulkContent apparently doesn't like offerKeys
        }
        
        System.out.println("DEBUG AstroGo Main Page URL: $url")
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache"
        )

        try {
            val responseBody = app.get(url, headers = headers).text
            System.out.println("DEBUG AstroGo Main Page Response: $responseBody")
            val response = AppUtils.parseJson<AstroResponse>(responseBody)
            val items = ArrayList<HomePageList>()
            val addedTitles = HashSet<String>()

            response?.categories?.forEach { category ->
                var title = category.title ?: request.name
                if (addedTitles.contains(title)) {
                    title = "$title (More)"
                }
                
                val contents = category.content?.mapNotNull { it.toSearchResponse() }
                
                if (!contents.isNullOrEmpty()) {
                    items.add(HomePageList(title, contents))
                    addedTitles.add(title)
                }
            }

            if (response?.content != null) {
                 val contents = response.content.mapNotNull { it.toSearchResponse() }
                 if (contents.isNotEmpty()) {
                     var title = if (items.isEmpty()) request.name else "Featured"
                     if (addedTitles.contains(title)) title = "$title List"
                     
                     items.add(HomePageList(title, contents))
                 }
            }
            
            System.out.println("DEBUG AstroGo Main Page Items: ${items.size}")
            return newHomePageResponse(items)
        } catch (e: Exception) {
            e.printStackTrace()
            System.out.println("DEBUG AstroGo Main Page Error: ${e.message}")
            return newHomePageResponse(emptyList())
        }
    }

    private fun AstroContent.toSearchResponse(): SearchResponse? {
        // Prioritize ID that looks like a UUID or Composite ID
        val candidates = listOfNotNull(this.id, this.packId, this.externalId)
        val id = candidates.find { it.contains("~") } 
              ?: candidates.find { it.length > 20 && it.contains("-") } 
              ?: candidates.firstOrNull() 
              ?: return null
        val title = this.title ?: return null
        val poster = this.media?.find { it.url?.contains("PORT_476x716") == true }?.url 
             ?: this.media?.find { it.url?.contains("PORT") == true }?.url 
             ?: this.media?.firstOrNull()?.url
        
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val encodedPoster = if (poster != null) java.net.URLEncoder.encode(poster, "UTF-8") else ""
        val synopsis = this.synopsis ?: ""
        val encodedPlot = if (synopsis.isNotEmpty()) java.net.URLEncoder.encode(synopsis.take(800), "UTF-8") else ""
        
        val data = "$id?title=$encodedTitle&poster=$encodedPoster&plot=$encodedPlot"

        val type = if (this.contentType?.contains("Live", true) == true || 
                       this.contentType?.contains("Channel", true) == true) {
             TvType.Live
        } else {
             TvType.Movie
        }

        return newMovieSearchResponse(title, data, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl/agg/content?limit=40&q=$encodedQuery&source=vod&sort=relevancy&isErotic=false&isAdult=false"
        
        System.out.println("DEBUG AstroGo Search URL: $url")
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )

        return try {
            val response = app.get(url, headers = headers).parsedSafe<AstroResponse>()
            val results = response?.content?.mapNotNull { it.toSearchResponse() } ?: emptyList()
            System.out.println("DEBUG AstroGo Search Results: ${results.size}")
            results
        } catch (e: Exception) {
            e.printStackTrace()
            System.out.println("DEBUG AstroGo Search Error: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val baseId = url.substringBefore("?")
        val titleParam = if (url.contains("title=")) java.net.URLDecoder.decode(url.substringAfter("title=").substringBefore("&"), "UTF-8") else null
        val posterParam = if (url.contains("poster=")) java.net.URLDecoder.decode(url.substringAfter("poster=").substringBefore("&"), "UTF-8") else null
        val plotParam = if (url.contains("plot=")) java.net.URLDecoder.decode(url.substringAfter("plot=").substringBefore("&"), "UTF-8") else null

        val rawId = baseId.removePrefix(mainUrl).removePrefix("/")
        val cleanId = rawId.substringBefore("~")
        
        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "Accept" to "application/json"
        )
        
        var detailUrl = "$apiUrl/contentInstances/$rawId"
        
        var response: AstroContent? = null
        try {
            val rawText = app.get(detailUrl, headers = headers).text
            val tree = mapper.readTree(rawText)
            var node = tree.get("response") ?: tree.get("content") ?: tree
            
            if (node.isArray && node.size() > 0) {
                 node = node.get(0)
            } else if (node.isArray) {
                 node = null
            }
            
            if (node != null && !node.isEmpty) {
                 response = mapper.treeToValue(node, AstroContent::class.java)
            }
        } catch (e: Exception) { 
            response = null
            e.printStackTrace()
        }

        if (response == null || response.title == null) {
             detailUrl = "$apiUrl/content/show/$cleanId"
             response = try {
                 app.get(detailUrl, headers = headers).parsedSafe<AstroContent>()
             } catch (e: Exception) { null }
        }
        
        val actorsList = ArrayList<ActorData>()
        try {
            val creds = response?.credits
            if (creds is Map<*, *>) {
                val actors = creds["actors"] as? List<*>
                actors?.forEach { 
                    if (it is String) actorsList.add(ActorData(Actor(it.trim(), image = null), roleString = "Actor"))
                }
                
                val directors = creds["directors"] as? List<*>
                directors?.forEach { 
                    if (it is String) actorsList.add(ActorData(Actor(it.trim(), image = null), roleString = "Director"))
                }
                
                val cast = creds["cast"] as? List<*>
                cast?.forEach { 
                    if (it is Map<*, *>) {
                        val name = it["name"] as? String ?: it["originalName"] as? String
                        val role = it["character"] as? String ?: "Actor"
                        val image = it["profilePath"] as? String
                        if (name != null) {
                             actorsList.add(ActorData(Actor(name.trim(), image = image), roleString = role))
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        if (response == null && titleParam != null) {
            response = AstroContent(id = rawId, title = titleParam, synopsis = plotParam, contentType = "Movie") 
        }

        if (response == null) throw ErrorLoadingException("Failed to load details")

        val title = response.title ?: response.name ?: titleParam ?: "No Title"
        val plot = response.synopsis ?: plotParam
        val poster = response.media?.find { it.url?.contains("PORT_476x716") == true }?.url 
             ?: response.media?.find { it.url?.contains("PORT") == true }?.url 
             ?: posterParam
        val year = response.releaseDate?.substringBefore("-")?.toIntOrNull()

        var durationMin: Int? = null
        if (response.duration != null) {
            val parts = response.duration.split(":")
            if (parts.size == 3) {
                 durationMin = (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            } else if (parts.size == 2) {
                 durationMin = (parts[0].toIntOrNull() ?: 0)
            } else {
                 durationMin = (response.duration.toIntOrNull() ?: 0) / 60
            }
        }

        if (actorsList.isEmpty()) {
            response.cast?.forEach { member ->
                val name = member.name ?: return@forEach
                actorsList.add(ActorData(Actor(name, image = null), role = ActorRole.Main))
            }
        }
        if (actorsList.isEmpty()) {
            response.actors?.forEach { 
                actorsList.add(ActorData(Actor(it, image = null), role = ActorRole.Main)) 
            }
        }
        
        val type = response.contentType
        val isTv = type.equals("Program", true) || 
                   type.equals("TVShow", true) ||
                   type.equals("show", true) ||
                   type.equals("Series", true)

        val tags = response.genres?.mapNotNull { it.name }

        if (isTv) {
            val episodes = ArrayList<Episode>()
            val rawToken = clientToken.replace("%20", " ")
            val encodedToken = java.net.URLEncoder.encode(rawToken, "UTF-8").replace("+", "%20")
            
            val baseIdShow = response.packId ?: response.externalId ?: response.id ?: cleanId
            val showId = baseIdShow.substringBefore("~")

            suspend fun fetchContent(url: String): List<AstroContent>? {
                return try {
                    val text = app.get(url, headers = headers).text
                    val wrapper = AppUtils.parseJson<AstroResponse>(text)
                    if (!wrapper.content.isNullOrEmpty()) return wrapper.content
                    return AppUtils.parseJson<List<AstroContent>>(text)
                } catch (e: Exception) { null }
            }

            val seasonsUrl = "$apiUrl/shared/content?showId=$showId&sort=seasonNumber&limit=255&offset=0&isErotic=false&isAdult=false&source=vod&clientToken=$encodedToken"
            
            val seasonContent = fetchContent(seasonsUrl)
            
            if (!seasonContent.isNullOrEmpty()) {
                seasonContent.forEach { season ->
                    val seasonId = season.seasonId ?: season.id ?: return@forEach
                    val seasonNum = season.title?.filter { it.isDigit() }?.toIntOrNull() ?: season.seasonNumber ?: 1
                    
                    val episodesUrl = "$apiUrl/shared/content?seasonId=$seasonId&sort=episodeNumber&limit=255&offset=0&isErotic=false&isAdult=false&source=vod&clientToken=$encodedToken"
                    val episodesContent = fetchContent(episodesUrl)
                    
                    episodesContent?.forEach { ep ->
                        episodes.add(ep.toEpisode(seasonNum))
                    }
                }
            } else {
                val flatEpisodesUrl = "$apiUrl/shared/content?showId=$showId&source=vod&limit=255&offset=0&sort=episodeNumber&isCollapsed=false&isErotic=false&isAdult=false&clientToken=$encodedToken"
                val flatContent = fetchContent(flatEpisodesUrl)
                    
                 if (!flatContent.isNullOrEmpty()) {
                     flatContent.forEach { ep ->
                         episodes.add(ep.toEpisode(1))
                     }
                 } else {
                     val childrenUrl = "$apiUrl/content/$showId/children?clientToken=$encodedToken&limit=100&offset=0"
                     try {
                         val asRepo = app.get(childrenUrl, headers = headers).parsedSafe<AstroResponse>()
                         if (asRepo?.content?.isNotEmpty() == true) {
                             asRepo.content.forEach { ep ->
                                 episodes.add(ep.toEpisode(1))
                             }
                         } else {
                             val asList = app.get(childrenUrl, headers = headers).parsedSafe<List<AstroContent>>()
                             asList?.forEach { ep ->
                                 episodes.add(ep.toEpisode(1))
                             }
                         }
                     } catch (e: Exception) { 
                         e.printStackTrace()
                     }
                 }
            }
            
            return newTvSeriesLoadResponse(title, rawId, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = response.media?.find { it.url?.contains("LAND_917x516") == true }?.url 
                                           ?: response.media?.find { it.url?.contains("LAND") == true || it.url?.contains("backdrop") == true }?.url
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = durationMin
                this.actors = actorsList
            }
        } else {
            return newMovieLoadResponse(title, rawId, TvType.Movie, response.id ?: rawId) {
                this.posterUrl = poster
                this.backgroundPosterUrl = response.media?.find { it.url?.contains("LAND_917x516") == true }?.url 
                                           ?: response.media?.find { it.url?.contains("LAND") == true || it.url?.contains("backdrop") == true }?.url
                this.plot = plot
                this.year = year
                this.tags = tags
                this.duration = durationMin
                this.actors = actorsList
            }
        }
    }
    
    private fun AstroContent.toEpisode(season: Int?): Episode {
        val epVal = this.episodeNumber ?: this.title?.substringBefore(" ")?.toIntOrNull()
        val seasonVal = this.seasonNumber ?: season
        
        return newEpisode(this.id ?: "") {
            this.name = this@toEpisode.title
            this.season = seasonVal
            this.episode = epVal
            this.posterUrl = this@toEpisode.media?.firstOrNull()?.url
            this.description = this@toEpisode.synopsis
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        ensureGuestToken()
        
        val baseId = data.substringBefore("?").substringAfterLast("/")
        System.out.println("DEBUG AstroGo LoadLinks: BaseId=$baseId Token=${bearerToken.take(10)}")

        // Check Profile ID integrity
        var profileId = getKey<String>("astro_profile_id") ?: ""
        if (profileId.isEmpty() || profileId == "NOT_FOUND") {
             System.out.println("DEBUG AstroGo LoadLinks: Profile ID invalid ($profileId), fetching...")
             fetchAndSaveProfile()
             profileId = getKey<String>("astro_profile_id") ?: ""
        }

        val headers = mapOf(
            "Authorization" to "Bearer $bearerToken",
            "X-VGE-Service-ID" to "AstroGo",
            "X-VGE-Client" to "AstroGo",
            "X-Identity-Profile-Id" to profileId,
            "Origin" to "https://astrogo.astro.com.my",
            "Referer" to "https://astrogo.astro.com.my/",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json"
        )
        System.out.println("DEBUG AstroGo LoadLinks Headers: $headers")

        try {
            // User requested strict adherence to this exact URL with port 9443
            val sessionUrl = "https://sg-sg-sg.astro.com.my:9443/ctap/r1.6.0/devices/me/playsessions?instanceId=$baseId&startingPosition=0"
            System.out.println("DEBUG AstroGo Request URL: $sessionUrl")
            
            // Using POST as this is a session creation endpoint
            val response = app.post(sessionUrl, headers = headers).text
            System.out.println("DEBUG AstroGo Response: $response")
            
            val json = mapper.readTree(response)
            
            // Extract playUrl from the strict structure
            val streamUrl = json.get("_links")?.get("playUrl")?.get("href")?.asText()
            
            // Extract tokens for DRM
            val drmToken = json.get("drmProperties")?.get("blob")?.asText() 
                           ?: json.get("drmToken")?.asText()
            
            // Try to find contentID for the payload - checking multiple locations
            val contentId = json.get("contentId")?.asText()
                            ?: json.get("id")?.asText()
                            ?: json.get("drmProperties")?.get("contentId")?.asText()
                            ?: baseId // Fallback to baseId from URL if not found in JSON

            System.out.println("DEBUG AstroGo DRM Data: ContentID=$contentId Token=${drmToken?.take(10)}...")

            if (!streamUrl.isNullOrEmpty()) {
                if (!drmToken.isNullOrEmpty() && !contentId.isNullOrEmpty()) {
                    callback.invoke(
                        newDrmExtractorLink(
                            source = "AstroGo",
                            name = "AstroGo",
                            url = streamUrl,
                            type = ExtractorLinkType.DASH,
                            uuid = UUID.fromString("edef8ba9-79d6-4ace-a3c8-27dcd51d21ed")
                        ) {
                            this.referer = mainUrl
                            this.licenseUrl = "https://sg-sg-sg.astro.com.my:9443/vgemultidrm/v1/widevine/license"
                            System.out.println("DEBUG AstroGo newDrmExtractorLink: Setting licenseUrl=${this.licenseUrl}")
                            
                            this.headers = mapOf(
                                "Authorization" to "Bearer $bearerToken",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                                "Referer" to "https://astrogo.astro.com.my/",
                                // Injecting ID and Token for the Interceptor to pick up
                                "X-Astro-Content-ID" to contentId,
                                "X-Astro-Auth" to drmToken
                            )
                        }
                    )
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = "AstroGo",
                            name = "AstroGo",
                            url = streamUrl,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
                return true
            }
        } catch (e: Exception) {
            System.out.println("DEBUG AstroGo Session Error: ${e.message}")
            e.printStackTrace()
        }
        
        return false
    }

    suspend fun fetchAndSaveProfile() {
        if (bearerToken.isEmpty()) return

        // endpoints to try
        val endpoints = listOf(
            "https://auth.astro.com.my/userinfo",
            "https://auth.astro.com.my/oauth2/userinfo",
            "$apiUrl/users/me/profiles?clientToken=$clientToken" // Legacy/CTAP try last
        )

        for (url in endpoints) {
            try {
                val headers = mapOf(
                    "Authorization" to "Bearer $bearerToken",
                    "Accept" to "application/json"
                )
                System.out.println("DEBUG AstroGo Fetching Profile from: $url")
                
                val response = app.get(url, headers = headers).text
                System.out.println("DEBUG AstroGo Profile Response ($url): $response")

                var foundId: String? = null

                // Strategy A: Check for "profiles" array (CTAP) or custom claim
                if (response.contains("profiles")) {
                    val idRegex = "\"id\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val matches = idRegex.findAll(response)
                    // First match might be the user ID, second might be profile? 
                    // Usually CTAP is [ { "id": 123, "name": "Default" } ]
                    // We prefer a numeric ID or UUID.
                    for (m in matches) {
                        val v = m.groupValues[1]
                        if (v != "NOT_FOUND" && v != "NOT_ENTITLED") {
                            foundId = v
                            break
                        }
                    }
                }

                // Strategy B: Check for "sub" (OIDC)
                if (foundId == null) {
                    val subRegex = "\"sub\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                    val subMatch = subRegex.find(response)
                    val subVal = subMatch?.groupValues?.get(1)
                    if (subVal != null && subVal.isNotEmpty()) {
                         foundId = subVal
                    }
                }

                // Strategy C: Check for "family_name" (OIDC - sometimes used as account ID)
                if (foundId == null) {
                     val famRegex = "\"family_name\"\\s*:\\s*\"([^\"]+)\"".toRegex()
                     val famMatch = famRegex.find(response)
                     val famVal = famMatch?.groupValues?.get(1)
                     if (famVal != null && famVal.isNotEmpty()) {
                        foundId = famVal
                     }
                }
                
                if (foundId != null) {
                    setKey("astro_profile_id", foundId)
                    System.out.println("DEBUG AstroGo Profile Selected: $foundId (Strategy determined)")
                    return // Success
                }

            } catch (e: Exception) {
                System.out.println("DEBUG AstroGo Profile Fetch Error ($url): ${e.message}")
            }
        }
        
        System.out.println("DEBUG AstroGo Failed to fetch any valid profile ID.")
    }

    private fun generateClientToken(): String {
        val version = "1"
        val regionId = "80200"
        val userRegion = "SARAWAK"
        val community = "Malaysia%20Live"
        val type = "k"
        val deviceType = "PC"
        val fleet = "Astro_unmanaged"
        val platformDevice = "CHROME-FF"
        val profileType = "Adults"

        return "v:$version!r:$regionId!ur:$userRegion!community:$community!t:$type!dt:$deviceType!f:$fleet!pd:$platformDevice!pt:$profileType"
    }

    data class AstroResponse(
        @JsonProperty("categories") val categories: List<AstroCategory>? = null,
        @JsonProperty("content") val content: List<AstroContent>? = null
    )

    data class AstroCategory(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("content") val content: List<AstroContent>? = null
    )

    data class AstroContent(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("packId") val packId: String? = null,
        @JsonProperty("externalId") val externalId: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("synopsis") val synopsis: String? = null,
        @JsonProperty("media") val media: List<AstroMedia>? = null,
        @JsonProperty("contentType") val contentType: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("duration") val duration: String? = null,
        @JsonProperty("genres") val genres: List<AstroGenre>? = null,
        @JsonProperty("cast") val cast: List<AstroCast>? = null,
        @JsonProperty("actors") val actors: List<String>? = null,
        @JsonProperty("episodeNumber") val episodeNumber: Int? = null,
        @JsonProperty("seasonNumber") val seasonNumber: Int? = null,
        @JsonProperty("showId") val showId: String? = null,
        @JsonProperty("seasonId") val seasonId: String? = null,
        @JsonProperty("credits") val credits: Any? = null
    )
    
    data class AstroCast(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("role") val role: String? = null
    )

    data class AstroGenre(
        @JsonProperty("name") val name: String? = null
    )

    data class AstroMedia(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("width") val width: Int? = null,
        @JsonProperty("height") val height: Int? = null,
        @JsonProperty("type") val type: String? = null
    )
}