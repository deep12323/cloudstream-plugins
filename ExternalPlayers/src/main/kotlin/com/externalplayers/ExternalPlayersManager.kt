package com.externalplayers

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.VideoClickActionHolder
import com.lagradost.cloudstream3.plugins.Plugin

object ExternalPlayersManager {
    private const val TAG = "ExternalPlayersManager"
    private const val PREFS_NAME = "external_players_prefs"
    private const val KEY_PLAYERS_JSON = "configured_players_json"

    const val ID_MPV_INFINITY = "mpv_infinity"
    const val ID_MPVEX = "mpvex"
    const val ID_ONEDM = "onedm"

    val DEFAULT_PLAYERS = listOf(
        ExternalPlayerItem(
            id = ID_MPV_INFINITY,
            name = "Mpv Infinity",
            packageName = MpvInfinityAction.PKG_MAIN,
            activityName = MpvInfinityAction.ACTIVITY_NAME,
            isBuiltIn = true,
            isEnabled = true,
            slotIndex = 0
        ),
        ExternalPlayerItem(
            id = ID_MPVEX,
            name = "mpvEx",
            packageName = MpvExAction.PKG_PLAYSTORE,
            activityName = MpvExAction.DEFAULT_ACTIVITY_NAME,
            isBuiltIn = true,
            isEnabled = true,
            slotIndex = 1
        ),
        ExternalPlayerItem(
            id = ID_ONEDM,
            name = "1DM",
            packageName = OneDmAction.PKG_1DM_PLUS,
            activityName = OneDmAction.ACTIVITY_NAME,
            isBuiltIn = true,
            isEnabled = true,
            slotIndex = 2
        )
    )

    fun getPlayers(context: Context): List<ExternalPlayerItem> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PLAYERS_JSON, null)
        if (json == null) {
            // First time initialization: store and return default players
            savePlayers(context, DEFAULT_PLAYERS)
            return DEFAULT_PLAYERS
        }
        return try {
            ExternalPlayerItem.fromJson(json)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to parse players JSON, falling back to defaults", e)
            DEFAULT_PLAYERS
        }
    }

    fun savePlayers(context: Context, players: List<ExternalPlayerItem>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = ExternalPlayerItem.toJson(players)
        prefs.edit().putString(KEY_PLAYERS_JSON, json).apply()
    }

    fun addPlayer(context: Context, name: String, packageName: String, activityName: String? = null): ExternalPlayerItem {
        val current = getPlayers(context).toMutableList()
        val usedSlots = current.map { it.slotIndex }.toSet()
        val nextSlot = (0..19).firstOrNull { it !in usedSlots } ?: 0
        val newPlayer = ExternalPlayerItem(
            id = "custom_${System.currentTimeMillis()}_${(100..999).random()}",
            name = name.trim(),
            packageName = packageName.trim(),
            activityName = activityName?.trim()?.takeIf { it.isNotEmpty() },
            isBuiltIn = false,
            isEnabled = true,
            slotIndex = nextSlot
        )
        current.add(newPlayer)
        savePlayers(context, current)
        return newPlayer
    }

    fun removePlayer(context: Context, id: String) {
        val current = getPlayers(context).filterNot { it.id == id }
        savePlayers(context, current)
    }

    fun setPlayerEnabled(context: Context, id: String, enabled: Boolean) {
        val current = getPlayers(context).map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
        savePlayers(context, current)
    }

    fun restoreDefaults(context: Context) {
        savePlayers(context, DEFAULT_PLAYERS)
    }

    fun isAppInstalled(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val pm = context.packageManager
        when (packageName) {
            MpvInfinityAction.PKG_MAIN -> {
                for (pkg in listOf(MpvInfinityAction.PKG_MAIN, MpvInfinityAction.PKG_DEBUG)) {
                    try {
                        pm.getPackageInfo(pkg, 0)
                        return true
                    } catch (_: Throwable) {}
                }
            }
            MpvExAction.PKG_PLAYSTORE -> {
                for (pkg in listOf(
                    MpvExAction.PKG_PLAYSTORE,
                    MpvExAction.PKG_PLAYSTORE_DEBUG,
                    MpvExAction.PKG_GITHUB,
                    MpvExAction.PKG_GITHUB_PREVIEW,
                    MpvExAction.PKG_GITHUB_DEBUG
                )) {
                    try {
                        pm.getPackageInfo(pkg, 0)
                        return true
                    } catch (_: Throwable) {}
                }
            }
            OneDmAction.PKG_1DM_PLUS -> {
                for (pkg in listOf(OneDmAction.PKG_1DM_PLUS, OneDmAction.PKG_1DM_NORMAL, OneDmAction.PKG_1DM_LITE)) {
                    try {
                        pm.getPackageInfo(pkg, 0)
                        return true
                    } catch (_: Throwable) {}
                }
            }
            else -> {
                try {
                    pm.getPackageInfo(packageName, 0)
                    return true
                } catch (_: Throwable) {}
                try {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setPackage(packageName)
                        setDataAndType(Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"), "video/*")
                    }
                    return pm.queryIntentActivities(intent, 0).isNotEmpty()
                } catch (_: Throwable) {}
            }
        }
        return false
    }

    data class DetectedApp(
        val label: String,
        val packageName: String,
        val activityName: String?
    )

    fun scanInstalledVideoApps(context: Context): List<DetectedApp> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val results = mutableMapOf<String, DetectedApp>()

        // 1. Scan intent filter for video/*
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse("https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4"), "video/*")
            }
            val resolves = pm.queryIntentActivities(intent, 0)
            for (res in resolves) {
                val pkg = res.activityInfo?.packageName ?: continue
                if (pkg == selfPkg || pkg == "android") continue
                val label = res.loadLabel(pm)?.toString() ?: pkg
                val act = res.activityInfo?.name
                results[pkg] = DetectedApp(label, pkg, act)
            }
        } catch (_: Throwable) {}

        // 2. Scan known popular players
        val knownPackages = listOf(
            "org.videolan.vlc" to "VLC",
            "com.mxtech.videoplayer.ad" to "MX Player",
            "com.mxtech.videoplayer.pro" to "MX Player Pro",
            "com.brouken.player" to "Just Player",
            "dev.anilbeesetti.nextplayer" to "Next Player",
            "org.courville.nova" to "Nova Video Player",
            "is.xyz.mpv" to "mpv-android",
            "app.infinity.mpvz" to "Mpv Infinity",
            "app.marlboroadvance.mpvex" to "mpvEx",
            "idm.internet.download.manager.plus" to "1DM+",
            "idm.internet.download.manager" to "1DM",
            "com.kmplayer" to "KMPlayer",
            "com.bubblesoft.android.bubbleupnp" to "BubbleUPnP"
        )
        for ((pkg, defaultLabel) in knownPackages) {
            if (results.containsKey(pkg)) continue
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val appInfo = info.applicationInfo
                val label = if (appInfo != null) pm.getApplicationLabel(appInfo).toString() else defaultLabel
                results[pkg] = DetectedApp(label, pkg, null)
            } catch (_: Throwable) {}
        }

        return results.values.sortedBy { it.label }
    }

    fun createActionForPlayer(player: ExternalPlayerItem): VideoClickAction {
        return when (player.id) {
            ID_MPV_INFINITY -> MpvInfinityAction()
            ID_MPVEX -> MpvExAction()
            ID_ONEDM -> OneDmAction()
            else -> createCustomSlotAction(player.slotIndex, player)
        }
    }

    fun reloadActions(plugin: Plugin, context: Context) {
        try {
            val allActions = VideoClickActionHolder.allVideoClickActions
            // Identify and remove all previous actions belonging to this plugin
            val toRemove = mutableListOf<VideoClickAction>()
            for (i in 0 until allActions.size) {
                val act = allActions[i]
                if (act.sourcePlugin == plugin.filename ||
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

            // Register all active & enabled players from current preferences
            val players = getPlayers(context).filter { it.isEnabled }
            for (p in players) {
                val action = createActionForPlayer(p)
                plugin.registerVideoClickAction(action)
            }
            Log.i(TAG, "Successfully synchronized ${players.size} external player(s) in VideoClickActionHolder")
        } catch (e: Throwable) {
            Log.e(TAG, "Error reloading actions", e)
        }
    }
}
