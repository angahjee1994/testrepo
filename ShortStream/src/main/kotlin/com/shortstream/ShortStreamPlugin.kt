package com.shortstream

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class ShortStreamPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(ShortStream())
    }
}
