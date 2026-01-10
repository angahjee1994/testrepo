package com.hexated

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SearchView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.AcraApplication.Companion.getKey
import com.lagradost.cloudstream3.AcraApplication.Companion.setKey
import com.phisher98.BuildConfig

class XX1Settings(private val plugin: XX1Plugin) : BottomSheetDialogFragment() {

    private val res = plugin.resources!!
    private lateinit var container: LinearLayout
    private val PREFS_DISABLED = "xx1_disabled_providers"
    
    // List of providers in XX1
    private val providers = listOf(
        "Idlix", "Kisskh", "Vidsrccc", "Vidsrc", "RiveStream", "Watchsomuch", 
        "Vixsrc", "Vidlink", "Vidfast", "Mapple", "Wyzie", "Vidsrccx", 
        "Superembed", "Vidrock", "MovieBox", "Netflix"
    ).sorted()

    private fun <T : View> View.findView(name: String): T {
        val id = res.getIdentifier(name, "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        return this.findViewById(id)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val id = res.getIdentifier("fragment_providers", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        return inflater.inflate(res.getLayout(id), container, false)
    }

    @SuppressLint("SetTextI18n")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnSave = view.findView<ImageButton>("btn_save")
        val saveIconId = res.getIdentifier("save_icon", "drawable", BuildConfig.LIBRARY_PACKAGE_NAME)
        btnSave.setImageDrawable(res.getDrawable(saveIconId, null))
        
        container = view.findView("list_container")
        
        val btnSelectAll = view.findView<View>("btn_select_all")
        val btnDeselectAll = view.findView<View>("btn_deselect_all")

        val disabledStr = getKey<String>(PREFS_DISABLED) ?: ""
        val disabledProviders = disabledStr.split(",").filter { it.isNotEmpty() }.toMutableSet()
        
        val itemLayoutId = res.getIdentifier("item_provider_checkbox", "layout", BuildConfig.LIBRARY_PACKAGE_NAME)
        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)

        providers.forEach { name ->
            val item = layoutInflater.inflate(res.getLayout(itemLayoutId), container, false)
            val chk = item.findViewById<CheckBox>(chkId)
            chk.text = name
            chk.isChecked = name !in disabledProviders
            
            chk.setOnCheckedChangeListener { _, isChecked ->
                val currentDisabled = (getKey<String>(PREFS_DISABLED) ?: "").split(",").filter { it.isNotEmpty() }.toMutableSet()
                if (isChecked) currentDisabled.remove(name) else currentDisabled.add(name)
                setKey(PREFS_DISABLED, currentDisabled.joinToString(","))
            }
            
            container.addView(item)
        }

        btnSelectAll.setOnClickListener {
            setKey(PREFS_DISABLED, "")
            updateCheckboxes(true)
        }

        btnDeselectAll.setOnClickListener {
            setKey(PREFS_DISABLED, providers.joinToString(","))
            updateCheckboxes(false)
        }

        btnSave.setOnClickListener { dismiss() }
        
        val searchView = view.findView<SearchView>("search_provider")
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText.orEmpty().lowercase()
                for (i in 0 until container.childCount) {
                    val item = container.getChildAt(i)
                    val chk = item.findViewById<CheckBox>(chkId)
                    item.visibility = if (chk.text.toString().lowercase().contains(query)) View.VISIBLE else View.GONE
                }
                return true
            }
        })
    }

    private fun updateCheckboxes(checked: Boolean) {
        val chkId = res.getIdentifier("chk_provider", "id", BuildConfig.LIBRARY_PACKAGE_NAME)
        for (i in 0 until container.childCount) {
            container.getChildAt(i).findViewById<CheckBox>(chkId).isChecked = checked
        }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? com.google.android.material.bottomsheet.BottomSheetDialog)?.behavior?.state = BottomSheetBehavior.STATE_EXPANDED
    }
}
