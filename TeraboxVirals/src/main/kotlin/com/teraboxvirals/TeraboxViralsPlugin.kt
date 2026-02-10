package com.teraboxvirals

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class TeraboxViralsPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        // Please use the same name as the class
        registerMainAPI(TeraboxVirals())
        registerExtractorAPI(Terabox())
    }
}
