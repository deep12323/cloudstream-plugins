package com.desiremovies

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DesireMoviesPlugin : Plugin() {
    private var pluginContext: Context? = null

    init {
        openSettings = { ctx ->
            var activity: AppCompatActivity? = null
            var currentContext = ctx
            while (currentContext is android.content.ContextWrapper) {
                if (currentContext is AppCompatActivity) {
                    activity = currentContext
                    break
                }
                currentContext = currentContext.baseContext
            }

            val frag = DesireMoviesSettingsFragment(this)
            activity?.let {
                frag.show(it.supportFragmentManager, "DesireMoviesSettings")
            }
        }
    }

    override fun load(context: Context) {
        pluginContext = context
        registerMainAPI(DesireMoviesProvider(context))
        registerExtractorAPI(DesireMoviesHubCloud())
        registerExtractorAPI(DesireMoviesGDFlix())
    }
}
