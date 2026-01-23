package com.botol

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class DramaboxPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Dramabox())
        this.openSettings = { ctx ->
            val frag = DramaboxSettings()
            frag.show((ctx as AppCompatActivity).supportFragmentManager, "DramaboxSettings")
        }
    }
}
