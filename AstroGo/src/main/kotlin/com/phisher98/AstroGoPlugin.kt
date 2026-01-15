package com.phisher98

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.phisher98.settings.SettingsFragment

@CloudstreamPlugin
class AstroGoPlugin: Plugin() {
    override fun load(context: Context) {
        val sharedPref = context.getSharedPreferences("AstroGo", Context.MODE_PRIVATE)
        val api = AstroGo(sharedPref)
        registerMainAPI(api)
        val activity = context as AppCompatActivity
        openSettings = {
            val frag = SettingsFragment(this, sharedPref)
            frag.show(activity.supportFragmentManager, "Frag")
        }
    }
}
