package com.botol.astrogo

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class AstroGoPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        registerMainAPI(AstroGo())

        openSettings = { ctx ->
            val activity = ctx as? androidx.fragment.app.FragmentActivity
            if (activity != null) {
                AstroSettingsFragment(this).show(activity.supportFragmentManager, "AstroSettings")
            }
        }
    }
}
