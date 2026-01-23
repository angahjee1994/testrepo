package com.botol

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class DramaboxPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dramabox())
    }

    fun openSettings(context: Context) {
        val frag = DramaboxSettings()
        frag.show((context as androidx.fragment.app.FragmentActivity).supportFragmentManager, "DramaboxSettings")
    }
}
