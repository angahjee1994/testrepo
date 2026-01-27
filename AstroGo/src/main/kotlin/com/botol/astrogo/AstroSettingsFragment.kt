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

        AlertDialog.Builder(context)
            .setTitle("Login to AstroGo")
            .setView(layout)
            .setPositiveButton("Login") { _, _ ->
                val username = usernameInput.text.toString()
                val password = passwordInput.text.toString()
                
                if (username.isNotBlank() && password.isNotBlank()) {
                     // We will save these credentials temporarily or trigger the login flow
                     // Ideally we call the MainAPI login function here
                     // For now, let's just save and toast, the MainAPI will pick it up
                     // But wait, Cloudstream plugins usually don't have direct access to run suspend functions easily from UI
                     // We can store the credentials in DataStore and triggering a reload or let MainAPI handle it
                     
                     // Saving to DataStore for AstroGo to use
                     setKey("astro_username", username)
                     setKey("astro_password", password)
                     setKey("astro_trigger_login", true) // Signal to try login next load
                     
                     showToast("Credentials saved. Please refresh the home page to login.")
                     close()
                } else {
                    showToast("Please enter both username and password")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun close() {
        dismiss()
    }
}
