package com.botol

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class XMalayPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(XMalay())
    }
}
