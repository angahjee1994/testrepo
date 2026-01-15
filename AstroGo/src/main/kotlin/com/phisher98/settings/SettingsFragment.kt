package com.phisher98.settings

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.phisher98.AstroGoPlugin

class SettingsFragment(
    plugin: AstroGoPlugin,
    private val sharedPref: SharedPreferences,
) : BottomSheetDialogFragment() {
    private val res = plugin.resources ?: throw Exception("Unable to read resources")
    private val packageName = "com.phisher98"

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", packageName)
        return this.findViewById(id)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val id = res.getIdentifier("settings_fragment", "layout", packageName)
        val layout = res.getLayout(id)
        return inflater.inflate(layout, container, false)
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
            isDraggable = false
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val loginButton = view.findView<Button>("loginButton")
        val logoutButton = view.findView<Button>("logoutButton")
        val statusText = view.findView<TextView>("statusText")
        val webView = view.findView<WebView>("authWebView")
        
        val savedCookies = sharedPref.getString("cookies", null)
        if (!savedCookies.isNullOrEmpty()) {
            statusText.text = "Status: Logged In"
            logoutButton.visibility = View.VISIBLE
        } else {
            statusText.text = "Status: Not Logged In"
            logoutButton.visibility = View.GONE
        }

        setupWebView(webView, statusText, logoutButton)

        loginButton.setOnClickListener {
            webView.visibility = View.VISIBLE
            webView.loadUrl("https://auth.astro.com.my/login")
        }

        logoutButton.setOnClickListener {
            sharedPref.edit().apply {
                remove("cookies")
                apply()
            }
            statusText.text = "Status: Not Logged In"
            logoutButton.visibility = View.GONE
            
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            
            showToast("Logged out successfully")
            dismiss()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, statusText: TextView, logoutButton: Button) {
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)

                view?.evaluateJavascript(
                    "(function() { return document.body.scrollHeight; })();"
                ) { value ->
                    val height = value.replace("\"", "").toFloatOrNull()
                    if (height != null) {
                        val density = resources.displayMetrics.density
                        val layoutParams = view.layoutParams
                        layoutParams.height = (height * density).toInt()
                        view.layoutParams = layoutParams
                    }
                }

                if (url?.contains("astrogo.astro.com.my") == true || 
                    url?.contains("profile") == true) {
                    
                    val cookieManager = CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(url)

                    if (!cookies.isNullOrEmpty()) {
                        activity?.runOnUiThread {
                            sharedPref.edit().apply {
                                putString("cookies", cookies)
                                apply()
                            }

                            statusText.text = "Status: Logged In"
                            logoutButton.visibility = View.VISIBLE
                            webView.visibility = View.GONE
                            
                            showToast("Login successful!")
                        }
                    }
                }
            }
        }
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}
