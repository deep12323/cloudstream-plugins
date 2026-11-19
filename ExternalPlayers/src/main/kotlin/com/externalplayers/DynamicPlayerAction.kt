package com.externalplayers

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.actions.updateDurationAndPosition
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.utils.DataStoreHelper.getViewPos
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.UiText
import com.lagradost.cloudstream3.utils.txt

open class DynamicPlayerAction(
    open var playerId: String = "",
    open var displayName: String = "",
    open var targetPackage: String = "",
    open var targetActivity: String? = null
) : VideoClickAction() {

    override val name: UiText
        get() = txt(if (displayName.isNotBlank()) "Play in $displayName" else "Play in External Player")

    override val isPlayer: Boolean = true
    override val oneSource: Boolean = true
    override val sourceTypes: Set<ExtractorLinkType> = ExtractorLinkType.entries.toSet()

    override fun shouldShow(context: Context?, video: ResultEpisode?): Boolean {
        if (context == null) return true
        val isConfigured = ExternalPlayersManager.getPlayers(context)
            .any { it.id == playerId && it.isEnabled }
        if (!isConfigured) return false
        // Custom players manually added by the user should show when enabled
        if (targetPackage.isBlank()) return true
        return ExternalPlayersManager.isAppInstalled(context, targetPackage)
    }

    override suspend fun runAction(
        context: Context?,
        video: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        if (context == null) return
        val streamUrl = (if (index != null) result.links.getOrNull(index)?.url else result.links.firstOrNull()?.url) ?: return
        val streamUri = Uri.parse(streamUrl)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            if (targetPackage.isNotBlank()) {
                setPackage(targetPackage)
                if (!targetActivity.isNullOrBlank()) {
                    component = ComponentName(targetPackage, targetActivity!!)
                } else {
                    // Try to resolve best activity in package
                    try {
                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setPackage(targetPackage)
                            setDataAndType(streamUri, "video/*")
                        }
                        val resolveInfo = context.packageManager.queryIntentActivities(viewIntent, 0)
                        val matched = resolveInfo.firstOrNull { it.activityInfo?.packageName == targetPackage }?.activityInfo
                        if (matched != null) {
                            component = ComponentName(matched.packageName, matched.name)
                        }
                    } catch (_: Throwable) {}
                }
            }

            setDataAndType(streamUri, "video/*")

            // Pass external subtitles (compatible with VLC, MX Player, Just Player, MPV)
            val subsArray = result.subs.map { Uri.parse(it.url) }.toTypedArray()
            if (subsArray.isNotEmpty()) {
                putExtra("subs", subsArray)
                putExtra("subs.enable", subsArray)
                putExtra("subtitles_location", subsArray)
                putExtra("subtitles", subsArray.first())
            }

            // Title & Filename
            putExtra("title", video.name)
            putExtra("filename", video.name)
            putExtra("android.intent.extra.TITLE", video.name)

            // Resume playback position
            val savedPos = getViewPos(video.id)?.position
            if (savedPos != null && savedPos > 0) {
                putExtra("position", savedPos.toInt())
            }
            putExtra("return_result", true)

            // Headers
            val linkObj = if (index != null) result.links.getOrNull(index) else result.links.firstOrNull()
            if (linkObj != null && linkObj.headers.isNotEmpty()) {
                val headerStrings = linkObj.headers.map { "${it.key}: ${it.value}" }.toTypedArray()
                putExtra("headers", headerStrings)

                val headersBundle = Bundle()
                for ((k, v) in linkObj.headers) {
                    headersBundle.putString(k, v)
                    if (k.equals("user-agent", ignoreCase = true)) {
                        putExtra("User-Agent", v)
                        putExtra("user_agent", v)
                        putExtra("extra_useragent", v)
                    }
                    if (k.equals("referer", ignoreCase = true)) {
                        putExtra("Referer", v)
                        putExtra("extra_referer", v)
                    }
                    if (k.equals("cookie", ignoreCase = true)) {
                        putExtra("extra_cookies", v)
                    }
                }
                putExtra("extra_headers", headersBundle)
            }

            putExtra("secure_uri", true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        setKey("last_opened", video)
        launch(intent)
    }

    fun onResult(activity: Activity, intent: Intent?) {
        val position = intent?.getIntExtra("position", -1)?.takeIf { it > 0 }?.toLong()
            ?: intent?.getLongExtra("position", -1L)?.takeIf { it > 0 } ?: -1L
        val duration = intent?.getIntExtra("duration", -1)?.takeIf { it > 0 }?.toLong()
            ?: intent?.getLongExtra("duration", -1L)?.takeIf { it > 0 } ?: -1L
        if (position > 0 && duration > 0) {
            updateDurationAndPosition(position, duration)
        }
    }
}

// 20 unique slot classes ensuring distinct uniqueId() in CloudStream's VideoClickAction registry
class CustomPlayerSlot0(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot1(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot2(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot3(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot4(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot5(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot6(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot7(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot8(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot9(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot10(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot11(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot12(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot13(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot14(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot15(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot16(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot17(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot18(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)
class CustomPlayerSlot19(id: String, name: String, pkg: String, act: String?) : DynamicPlayerAction(id, name, pkg, act)

fun createCustomSlotAction(slot: Int, item: ExternalPlayerItem): DynamicPlayerAction {
    return when (slot) {
        0 -> CustomPlayerSlot0(item.id, item.name, item.packageName, item.activityName)
        1 -> CustomPlayerSlot1(item.id, item.name, item.packageName, item.activityName)
        2 -> CustomPlayerSlot2(item.id, item.name, item.packageName, item.activityName)
        3 -> CustomPlayerSlot3(item.id, item.name, item.packageName, item.activityName)
        4 -> CustomPlayerSlot4(item.id, item.name, item.packageName, item.activityName)
        5 -> CustomPlayerSlot5(item.id, item.name, item.packageName, item.activityName)
        6 -> CustomPlayerSlot6(item.id, item.name, item.packageName, item.activityName)
        7 -> CustomPlayerSlot7(item.id, item.name, item.packageName, item.activityName)
        8 -> CustomPlayerSlot8(item.id, item.name, item.packageName, item.activityName)
        9 -> CustomPlayerSlot9(item.id, item.name, item.packageName, item.activityName)
        10 -> CustomPlayerSlot10(item.id, item.name, item.packageName, item.activityName)
        11 -> CustomPlayerSlot11(item.id, item.name, item.packageName, item.activityName)
        12 -> CustomPlayerSlot12(item.id, item.name, item.packageName, item.activityName)
        13 -> CustomPlayerSlot13(item.id, item.name, item.packageName, item.activityName)
        14 -> CustomPlayerSlot14(item.id, item.name, item.packageName, item.activityName)
        15 -> CustomPlayerSlot15(item.id, item.name, item.packageName, item.activityName)
        16 -> CustomPlayerSlot16(item.id, item.name, item.packageName, item.activityName)
        17 -> CustomPlayerSlot17(item.id, item.name, item.packageName, item.activityName)
        18 -> CustomPlayerSlot18(item.id, item.name, item.packageName, item.activityName)
        else -> CustomPlayerSlot19(item.id, item.name, item.packageName, item.activityName)
    }
}
