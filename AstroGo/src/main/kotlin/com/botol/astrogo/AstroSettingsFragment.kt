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

        // Login Card
        val loginCard = createSettingsCard("Login", "Login with your Astro ID")
        loginCard.setOnClickListener {
            showLoginDialog()
        }
        rootLayout.addView(loginCard)

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
        val padding = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        val usernameInput = EditText(context).apply {
            hint = "Astro ID (Email/Phone)"
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }
        
        val passwordInput = EditText(context).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(Color.BLACK)
            setHintTextColor(Color.GRAY)
        }

        layout.addView(usernameInput)
        layout.addView(passwordInput)

        val dialog = AlertDialog.Builder(context)
            .setTitle("Login to AstroGo")
            .setView(layout)
            .setPositiveButton("Login", null) // Set null to override behaviour
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            button.setOnClickListener {
                val username = usernameInput.text.toString()
                val password = passwordInput.text.toString()
                
                if (username.isNotBlank() && password.isNotBlank()) {
                    showToast("Logging in...")
                    // Disable button to prevent double clicks
                    button.isEnabled = false
                    
                    // Use a simple thread if lifecycleScope is tricky to import without verified dependencies, 
                    // but Cloudstream should have it. trying generic coroutine approach.
                    // If lifecycleScope is not available, we might error. 
                    // Let's use CommanActivity scope concept or similar if possible.
                    // Or just GlobalScope for this simple action to avoid unresolved references if uncertain.
                    // Better: use the plugin's context? No.
                    
                    // Assuming coroutines are available
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            val success = plugin.provider?.login(username, password) == true
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                button.isEnabled = true
                                if (success) {
                                    setKey("astro_username", username)
                                    setKey("astro_password", password)
                                    setKey("astro_trigger_login", false) // Since we already logged in
                                    
                                    showToast("Login Successful! You can close this.")
                                    dialog.dismiss()
                                } else {
                                    showToast("Login Failed. Check credentials.")
                                    // Dialog stays open
                                }
                            }
                        } catch (e: Exception) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                button.isEnabled = true
                                showToast("Login Error: ${e.message}")
                            }
                        }
                    }
                } else {
                    showToast("Please enter both username and password")
                }
            }
        }
        
        dialog.show()
    }

    private fun close() {
        dismiss()
    }
}
