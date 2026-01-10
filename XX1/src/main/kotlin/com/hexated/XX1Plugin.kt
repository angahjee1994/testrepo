
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

import androidx.appcompat.app.AppCompatActivity

@CloudstreamPlugin
class XX1Plugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        XX1.context = context
        registerMainAPI(XX1())
        registerExtractorAPI(Jeniusplay2())
        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = XX1Settings(this)
            frag.show(activity.supportFragmentManager, "")
        }
    }
}