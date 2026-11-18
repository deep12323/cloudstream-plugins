package com.nextup.ai

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NextUpPlugin : Plugin() {

    override fun load(context: Context) {
        // 1. Register NextUp MainAPI Provider
        registerMainAPI(NextUpProvider(context))

        // 2. Register Settings Dialog
        openSettings = { ctx ->
            NextUpSettings.show(ctx)
        }
    }
}
