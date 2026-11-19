package com.externalplayers

import android.content.Context
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ExternalPlayersPlugin : Plugin() {

    override fun load(context: Context) {
        // Register all enabled external players (defaults: MPV Infinity, mpvEx, 1DM)
        ExternalPlayersManager.reloadActions(this, context)

        // Settings dialog to manage, add manually, pick installed, or remove external players
        openSettings = { ctx ->
            ExternalPlayersSettingsDialog.show(this, ctx)
        }
    }

    override fun beforeUnload() {
        try {
            val allActions = VideoClickActionHolder.allVideoClickActions
            val toRemove = mutableListOf<VideoClickAction>()
            for (i in 0 until allActions.size) {
                val act = allActions[i]
                if (act.sourcePlugin == filename ||
                    act is DynamicPlayerAction ||
                    act is MpvInfinityAction ||
                    act is MpvExAction ||
                    act is OneDmAction
                ) {
                    toRemove.add(act)
                }
            }
            for (act in toRemove) {
                allActions.remove(act as Any)
            }
        } catch (_: Throwable) {}
    }
}
