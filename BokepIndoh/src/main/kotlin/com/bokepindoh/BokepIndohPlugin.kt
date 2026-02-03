package com.bokepindoh

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BokepIndohPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(BokepIndoh())
    }
}
