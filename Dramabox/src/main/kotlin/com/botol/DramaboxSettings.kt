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
        val context = requireContext()
        val scroll = ScrollView(context)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        val title = TextView(context).apply {
            text = "Select Language"
            textSize = 20f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 50)
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(title)

        val currentLang = getKey<String>("dramabox_language") ?: "en"
        
        val group = RadioGroup(context)
        languages.forEach { (name, code) ->
            val btn = RadioButton(context).apply {
                id = View.generateViewId()
                text = name
                tag = code
                isChecked = code == currentLang
                textSize = 16f
                setPadding(20, 20, 20, 20)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            group.addView(btn)
        }
        
        group.setOnCheckedChangeListener { _, checkedId ->
            val selected = group.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            val code = selected.tag as? String ?: return@setOnCheckedChangeListener
            setKey("dramabox_language", code)
            dismiss()
        }

        root.addView(group)
        scroll.addView(root)
        return scroll
    }
}
