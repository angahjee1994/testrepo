package com.botol

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DramaboxSettings : BottomSheetDialogFragment() {
    private val languages = mapOf(
        "English" to "en",
        "Bahasa Indonesia" to "id",
        "Español" to "es",
        "Français" to "fr",
        "Deutsch" to "de",
        "ไทย" to "th",
        "한국어" to "ko",
        "Tiếng Việt" to "vi",
        "中文" to "zh",
        "العربية" to "ar",
        "Português" to "pt"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val currentContext = requireContext()
        val currentLang = getKey<String>("dramabox_language") ?: "en"
        
        val root = LinearLayout(currentContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val titleView = TextView(currentContext).apply {
            text = "Select Language"
            textSize = 18f
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 40)
        }
        root.addView(titleView)

        val radioGroup = RadioGroup(currentContext).apply {
            languages.forEach { (langName, langCode) ->
                val radioButton = RadioButton(currentContext).apply {
                    id = View.generateViewId()
                    text = langName
                    tag = langCode
                    isChecked = langCode == currentLang
                    textSize = 16f
                    setPadding(16, 16, 16, 16)
                }
                addView(radioButton)
            }
            
            setOnCheckedChangeListener { group, checkedId ->
                val selectedButton = group.findViewById<RadioButton>(checkedId)
                val code = selectedButton?.tag as? String ?: return@setOnCheckedChangeListener
                setKey("dramabox_language", code)
                dismiss()
            }
        }

        root.addView(radioGroup)
        return ScrollView(currentContext).apply { addView(root) }
    }
}
