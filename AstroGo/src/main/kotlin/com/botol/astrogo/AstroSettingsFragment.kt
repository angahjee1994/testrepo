package com.botol.astrogo

import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withContext
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey

class AstroSettingsFragment(
    private val plugin: AstroGoPlugin
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()

        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#121212")) // Dark background
        }

        // Title
        val titleView = TextView(context).apply {
            text = "AstroGo Settings"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = padding
            }
        }
        rootLayout.addView(titleView)

        // Login/Logout Card
        if (plugin.provider?.isLoggedIn() == true) {
            val logoutCard = createSettingsCard("Logout", "Logged in as ${getKey<String>("astro_username") ?: "User"}")
            logoutCard.setOnClickListener {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    plugin.provider?.logout()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        dismiss()
                    }
                }
            }
            rootLayout.addView(logoutCard)
        } else {
            val loginCard = createSettingsCard("Login", "Login with your Astro ID")
            loginCard.setOnClickListener {
                showLoginDialog()
            }
            rootLayout.addView(loginCard)
        }

        return rootLayout
    }

    private fun createSettingsCard(title: String, subtitle: String): View {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()

        val cardLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = padding
            }
            setPadding(padding, padding, padding, padding)
            
            // Rounded dark grey background
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                cornerRadius = 12 * density
            }
        }

        val titleView = TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        val subtitleView = TextView(context).apply {
            text = subtitle
            textSize = 12f
            setTextColor(Color.GRAY)
        }

        cardLayout.addView(titleView)
        cardLayout.addView(subtitleView)

        return cardLayout
    }

    private fun showLoginDialog() {
        val context = requireContext()
        val density = context.resources.displayMetrics.density
        val padding = (16 * density).toInt()

        // Create WebView Container
        val frameLayout = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (500 * density).toInt() // Height constraint for dialog
            )
        }

        val webView = android.webkit.WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }
        frameLayout.addView(webView)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Login to AstroGo")
            .setView(frameLayout)
            .setNegativeButton("Close", null)
            .create()

        // WebView Client to intercept Login
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                return handleUrl(url) || super.shouldOverrideUrlLoading(view, request)
            }
            
            // Fallback for older API or different triggers
            override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                super.onPageFinished(view, url)
                url?.let { handleUrl(it) }
            }

            private fun handleUrl(url: String): Boolean {
                 // Check for access token in URL fragment or query
                 // The pattern is typically ...#access_token=XY... or ...&access_token=XY...
                 if (url.contains("access_token=")) {
                     val token = url.substringAfter("access_token=").substringBefore("&")
                     if (token.isNotEmpty()) {
                         // Run on IO for network ops (fetching profile)
                         kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                             try {
                                 val provider = plugin.provider as? AstroGo
                                 provider?.saveToken(token)
                                 provider?.fetchAndSaveProfile()
                                 
                                 kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                     setKey("astro_trigger_login", false)
                                     dialog.dismiss()
                                 }
                             } catch (e: Exception) {
                                 kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                     // Silent fail or log? For now just silent as requested to remove toasts
                                 }
                             }
                         }
                         return true // We handled it
                     }
                 }
                 return false
            }
        }

        // Load the Authorization URL
        val clientId = "browser"
        val authState = "bootup"
        val redirectUri = "https://astrogo.astro.com.my"
        val encodedRedirectUri = java.net.URLEncoder.encode(redirectUri, "UTF-8")
        val authUrl = "https://sg-sg-sg.astro.com.my:9443/oauth2/authorize?client_id=$clientId&state=$authState&redirect_uri=$encodedRedirectUri&response_type=token"

        webView.loadUrl(authUrl)
        
        dialog.show()
        
        // Fix for keyboard not showing in WebView inside Dialog
        webView.requestFocus()
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        
        dialog.window?.let { window ->
             window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
             window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
    }

    private fun close() {
        dismiss()
    }
}
