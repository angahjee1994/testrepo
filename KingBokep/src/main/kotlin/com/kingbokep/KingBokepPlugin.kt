package com.kingbokep

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KingBokepPlugin: Plugin() {
    override fun load() {
        registerMainAPI(KingBokep())
    }
}
