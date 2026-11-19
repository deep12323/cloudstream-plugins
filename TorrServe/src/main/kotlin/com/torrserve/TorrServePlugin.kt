package com.torrserve

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class TorrServePlugin : Plugin() {

    override fun load(context: Context) {
        TorrServeManager.initContext(context)
        val provider = TorrServeProvider(context)
        registerMainAPI(provider)

        // Automatically pre-warm and monitor TorrServer to auto-apply user settings on cold launch!
        TorrServeManager.startAutoConfigDaemon(context)

        openSettings = { ctx ->
            showModernSettingsDialog(ctx)
        }
    }

    private fun dp(ctx: Context, value: Int): Int {
        return (value * ctx.resources.displayMetrics.density).toInt()
    }

    private fun roundedDrawable(
        ctx: Context,
        bgColor: Int,
        radiusDp: Int = 12,
        strokeColor: Int = 0,
        strokeWidthDp: Int = 0
    ): GradientDrawable {
        val rPx = radiusDp * ctx.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = rPx
            if (strokeWidthDp > 0) {
                setStroke((strokeWidthDp * ctx.resources.displayMetrics.density).toInt(), strokeColor)
            }
        }
    }

    private fun showModernSettingsDialog(ctx: Context) {
        var currentCacheMb = TorrServeManager.getCacheSizeMb(ctx)
        var currentPreload = TorrServeManager.getPreloadPercent(ctx)
        var currentReadAhead = TorrServeManager.getReadAheadPercent(ctx)
        var currentTimeout = TorrServeManager.getDisconnectTimeout(ctx)
        var currentUseDisk = TorrServeManager.getUseDisk(ctx)
        var currentDisableUpload = TorrServeManager.getDisableUpload(ctx)

        val rootScroll = ScrollView(ctx).apply {
            isFillViewport = true
            background = roundedDrawable(ctx, Color.parseColor("#11151E"), radiusDp = 16)
            setPadding(dp(ctx, 16), dp(ctx, 16), dp(ctx, 16), dp(ctx, 20))
        }

        val mainLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScroll.addView(mainLayout)

        // --- 1. HEADER & STATUS ---
        val headerLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(ctx, 12))
        }

        val titleView = TextView(ctx).apply {
            text = "TorrServe"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F8FAFC"))
        }

        val subtitleView = TextView(ctx).apply {
            text = "BitTorrent Streaming Configuration"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        }

        val statusBadge = TextView(ctx).apply {
            text = "● Checking status..."
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F59E0B"))
            background = roundedDrawable(ctx, Color.parseColor("#262010"), radiusDp = 6)
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        }

        CoroutineScope(Dispatchers.IO).launch {
            val serverUrl = runCatching { TorrServeManager.getOrStartServer(ctx) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (serverUrl != null) {
                    statusBadge.text = "● Online: $serverUrl"
                    statusBadge.setTextColor(Color.parseColor("#10B981"))
                    statusBadge.background = roundedDrawable(ctx, Color.parseColor("#064E3B"), radiusDp = 6)
                } else {
                    statusBadge.text = "○ Standby"
                    statusBadge.setTextColor(Color.parseColor("#94A3B8"))
                    statusBadge.background = roundedDrawable(ctx, Color.parseColor("#1E2433"), radiusDp = 6)
                }
            }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(subtitleView)
        headerLayout.addView(statusBadge)
        mainLayout.addView(headerLayout)

        // --- 2. QUICK PROFILES SECTION ---
        val profilesHeader = TextView(ctx).apply {
            text = "PROFILES"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#818CF8"))
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 6))
        }
        mainLayout.addView(profilesHeader)

        val profileScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(ctx, 10))
        }
        val profileRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        profileScroll.addView(profileRow)
        mainLayout.addView(profileScroll)

        var updateUiCallback: (() -> Unit)? = null

        fun addProfileBtn(label: String, cache: Long, preload: Int, readAhead: Int, timeoutSec: Int, disk: Boolean, noSeed: Boolean) {
            val btn = TextView(ctx).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#E2E8F0"))
                background = roundedDrawable(ctx, Color.parseColor("#1E2433"), radiusDp = 8, strokeColor = Color.parseColor("#2E384D"), strokeWidthDp = 1)
                setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 8), 0)
                layoutParams = params

                setOnClickListener {
                    currentCacheMb = cache
                    currentPreload = preload
                    currentReadAhead = readAhead
                    currentTimeout = timeoutSec
                    currentUseDisk = disk
                    currentDisableUpload = noSeed
                    updateUiCallback?.invoke()
                    Toast.makeText(ctx, "Profile: $label", Toast.LENGTH_SHORT).show()
                }
            }
            profileRow.addView(btn)
        }

        addProfileBtn("Keep Alive", 128L, 15, 95, 300, false, false)
        addProfileBtn("Fast Stream", 128L, 10, 95, 180, false, false)
        addProfileBtn("TV / Low RAM", 48L, 15, 85, 180, true, false)
        addProfileBtn("4K Cinema", 384L, 15, 95, 300, false, false)
        addProfileBtn("Default", 64L, 50, 95, 60, false, false)

        fun createCard(): LinearLayout {
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = roundedDrawable(ctx, Color.parseColor("#1A202C"), radiusDp = 12)
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(0, 0, 0, dp(ctx, 10))
                layoutParams = params
            }
        }

        // --- 3. CARD: BUFFER SIZE ---
        val cacheCard = createCard()
        val cacheTitle = TextView(ctx).apply {
            text = "BUFFER SIZE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val cacheValueLabel = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#60A5FA"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        val cacheDescLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 8))
        }

        val cacheChipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
        }
        val cacheChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        cacheChipsScroll.addView(cacheChipsRow)

        val presetSizes = listOf(32L, 48L, 64L, 128L, 200L, 256L, 384L, 512L, 1024L)
        val chipViews = mutableListOf<TextView>()

        fun refreshBufferChips() {
            cacheValueLabel.text = "$currentCacheMb MB"
            cacheDescLabel.text = when {
                currentCacheMb < 32 -> "Low (< 32 MB) • Risk of buffering"
                currentCacheMb < 64 -> "Minimal (32–48 MB) • For low-RAM devices"
                currentCacheMb <= 256 -> "Optimal (64–256 MB) • Recommended"
                else -> "Large (256 MB+) • For 4K streams"
            }
            cacheDescLabel.setTextColor(if (currentCacheMb < 32) Color.parseColor("#EF4444") else Color.parseColor("#64748B"))
            chipViews.forEach { chip ->
                val sizeTag = chip.tag as? Long
                if (sizeTag == currentCacheMb) {
                    chip.background = roundedDrawable(ctx, Color.parseColor("#3B82F6"), radiusDp = 8)
                    chip.setTextColor(Color.WHITE)
                } else {
                    chip.background = roundedDrawable(ctx, Color.parseColor("#262F40"), radiusDp = 8)
                    chip.setTextColor(Color.parseColor("#CBD5E1"))
                }
            }
        }

        presetSizes.forEach { size ->
            val chip = TextView(ctx).apply {
                text = if (size >= 1024L) "1 GB" else "$size MB"
                tag = size
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 6), 0)
                layoutParams = params
                setOnClickListener {
                    currentCacheMb = size
                    refreshBufferChips()
                }
            }
            chipViews.add(chip)
            cacheChipsRow.addView(chip)
        }

        val customChip = TextView(ctx).apply {
            text = "Custom"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#93C5FD"))
            background = roundedDrawable(ctx, Color.parseColor("#1E2A42"), radiusDp = 8, strokeColor = Color.parseColor("#3B82F6"), strokeWidthDp = 1)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            isFocusable = true
            setOnClickListener {
                val input = EditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(currentCacheMb.toString())
                    setHint("Size in MB (min 16)")
                    setSelection(text.length)
                }
                val container = FrameLayout(ctx).apply {
                    setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
                    addView(input)
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Custom Buffer Size (MB)")
                    .setView(container)
                    .setPositiveButton("Set") { _, _ ->
                        val parsed = input.text.toString().trim().toLongOrNull()
                        if (parsed != null && parsed >= 16) {
                            currentCacheMb = parsed
                            refreshBufferChips()
                        } else {
                            Toast.makeText(ctx, "Minimum 16 MB required", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        cacheChipsRow.addView(customChip)

        cacheCard.addView(cacheTitle)
        cacheCard.addView(cacheValueLabel)
        cacheCard.addView(cacheDescLabel)
        cacheCard.addView(cacheChipsScroll)
        mainLayout.addView(cacheCard)

        // --- 4. CARD: PRELOAD CACHE % ---
        val preloadCard = createCard()
        val preloadTitle = TextView(ctx).apply {
            text = "PRELOAD BUFFER"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val preloadValueLabel = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#34D399"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        val preloadDescLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }

        val preloadSeekBar = SeekBar(ctx).apply {
            max = 70
            isFocusable = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        fun updatePreloadLabels(percent: Int) {
            val clamped = percent.coerceIn(0, 70)
            preloadValueLabel.text = "$clamped%"
            preloadDescLabel.text = when {
                clamped == 0 -> "0% • Instant start without pre-buffering"
                clamped <= 10 -> "$clamped% • Fast start (1–2s)"
                clamped <= 20 -> "$clamped% • Recommended (3–5s)"
                clamped <= 35 -> "$clamped% • Moderate buffer"
                else -> "$clamped% • High buffer"
            }
        }

        preloadSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceIn(0, 70)
                currentPreload = value
                updatePreloadLabels(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val preloadChipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        val preloadChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        preloadChipsScroll.addView(preloadChipsRow)

        listOf(5, 10, 15, 25, 50).forEach { p ->
            val chip = TextView(ctx).apply {
                text = "$p%"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedDrawable(ctx, Color.parseColor("#262F40"), radiusDp = 8)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 6), 0)
                layoutParams = params
                setOnClickListener {
                    currentPreload = p
                    preloadSeekBar.progress = p
                    updatePreloadLabels(p)
                }
            }
            preloadChipsRow.addView(chip)
        }

        preloadCard.addView(preloadTitle)
        preloadCard.addView(preloadValueLabel)
        preloadCard.addView(preloadDescLabel)
        preloadCard.addView(preloadChipsScroll)
        preloadCard.addView(preloadSeekBar)
        mainLayout.addView(preloadCard)

        // --- 5. CARD: READ-AHEAD WINDOW % ---
        val readAheadCard = createCard()
        val readAheadTitle = TextView(ctx).apply {
            text = "READ-AHEAD WINDOW"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val readAheadValueLabel = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A78BFA"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        val readAheadDescLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }

        val readAheadSeekBar = SeekBar(ctx).apply {
            max = 100
            isFocusable = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        fun updateReadAheadLabels(percent: Int) {
            val clamped = percent.coerceIn(5, 100)
            readAheadValueLabel.text = "$clamped%"
            readAheadDescLabel.text = when {
                clamped < 50 -> "$clamped% • Low forward buffer"
                clamped < 80 -> "$clamped% • Moderate forward buffer"
                clamped < 95 -> "$clamped% • High forward buffer"
                else -> "$clamped% • Continuous buffer (Recommended)"
            }
            readAheadDescLabel.setTextColor(if (clamped < 50) Color.parseColor("#EF4444") else Color.parseColor("#64748B"))
        }

        readAheadSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceIn(5, 100)
                currentReadAhead = value
                updateReadAheadLabels(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val readAheadChipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        val readAheadChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        readAheadChipsScroll.addView(readAheadChipsRow)

        listOf(50, 75, 90, 95, 100).forEach { p ->
            val chip = TextView(ctx).apply {
                text = "$p%"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedDrawable(ctx, Color.parseColor("#262F40"), radiusDp = 8)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 6), 0)
                layoutParams = params
                setOnClickListener {
                    currentReadAhead = p
                    readAheadSeekBar.progress = p
                    updateReadAheadLabels(p)
                }
            }
            readAheadChipsRow.addView(chip)
        }

        readAheadCard.addView(readAheadTitle)
        readAheadCard.addView(readAheadValueLabel)
        readAheadCard.addView(readAheadDescLabel)
        readAheadCard.addView(readAheadChipsScroll)
        readAheadCard.addView(readAheadSeekBar)
        mainLayout.addView(readAheadCard)

        // --- 6. CARD: STREAM / DISCONNECT TIMEOUT ---
        val timeoutCard = createCard()
        val timeoutTitle = TextView(ctx).apply {
            text = "DISCONNECT TIMEOUT"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
        }
        val timeoutValueLabel = TextView(ctx).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FBBF24"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 2))
        }
        val timeoutDescLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }

        val timeoutSeekBar = SeekBar(ctx).apply {
            max = 600
            isFocusable = true
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
        }

        fun updateTimeoutLabels(seconds: Int) {
            val clamped = seconds.coerceAtLeast(10)
            timeoutValueLabel.text = when {
                clamped >= 60 && clamped % 60 == 0 -> "${clamped / 60}m (${clamped}s)"
                else -> "${clamped}s"
            }
            timeoutDescLabel.text = when {
                clamped < 30 -> "${clamped}s • Short timeout"
                clamped == 30 -> "30s • Default"
                clamped <= 60 -> "${clamped}s • Standard (Recommended)"
                clamped <= 180 -> "${clamped / 60}m • Extended cushion"
                else -> "${clamped / 60}m • Maximum keep-alive"
            }
        }

        timeoutSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(10)
                currentTimeout = value
                updateTimeoutLabels(value)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        val timeoutChipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        val timeoutChipsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        timeoutChipsScroll.addView(timeoutChipsRow)

        listOf(30, 60, 120, 300, 600).forEach { t ->
            val chip = TextView(ctx).apply {
                text = if (t >= 60) "${t / 60}m" else "${t}s"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#CBD5E1"))
                background = roundedDrawable(ctx, Color.parseColor("#262F40"), radiusDp = 8)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                isFocusable = true
                val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                params.setMargins(0, 0, dp(ctx, 6), 0)
                layoutParams = params
                setOnClickListener {
                    currentTimeout = t
                    timeoutSeekBar.progress = t
                    updateTimeoutLabels(t)
                }
            }
            timeoutChipsRow.addView(chip)
        }

        val customTimeoutChip = TextView(ctx).apply {
            text = "Custom"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FDE68A"))
            background = roundedDrawable(ctx, Color.parseColor("#2A2415"), radiusDp = 8, strokeColor = Color.parseColor("#F59E0B"), strokeWidthDp = 1)
            setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
            isFocusable = true
            setOnClickListener {
                val input = EditText(ctx).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(currentTimeout.toString())
                    setHint("Seconds (min 10)")
                    setSelection(text.length)
                }
                val container = FrameLayout(ctx).apply {
                    setPadding(dp(ctx, 20), dp(ctx, 10), dp(ctx, 20), dp(ctx, 10))
                    addView(input)
                }
                AlertDialog.Builder(ctx)
                    .setTitle("Custom Disconnect Timeout (Seconds)")
                    .setView(container)
                    .setPositiveButton("Set") { _, _ ->
                        val parsed = input.text.toString().trim().toIntOrNull()
                        if (parsed != null && parsed >= 10) {
                            currentTimeout = parsed
                            timeoutSeekBar.progress = parsed.coerceAtMost(300)
                            updateTimeoutLabels(parsed)
                        } else {
                            Toast.makeText(ctx, "Minimum 10 seconds required", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        timeoutChipsRow.addView(customTimeoutChip)

        timeoutCard.addView(timeoutTitle)
        timeoutCard.addView(timeoutValueLabel)
        timeoutCard.addView(timeoutDescLabel)
        timeoutCard.addView(timeoutChipsScroll)
        timeoutCard.addView(timeoutSeekBar)
        mainLayout.addView(timeoutCard)

        // --- 7. CARD: STORAGE & NETWORK TOGGLES ---
        val togglesCard = createCard()
        val togglesTitle = TextView(ctx).apply {
            text = "STORAGE & SEEDING"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 8))
        }
        togglesCard.addView(togglesTitle)

        // Seeding toggle row
        val seedRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
        }
        val seedTextLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val seedLabel = TextView(ctx).apply {
            text = "Disable Seeding"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
        }
        val seedSubLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        }
        seedTextLayout.addView(seedLabel)
        seedTextLayout.addView(seedSubLabel)

        val seedSwitch = Switch(ctx).apply {
            isFocusable = true
        }

        fun updateSeedUi(disabled: Boolean) {
            seedSwitch.isChecked = disabled
            seedSubLabel.text = if (disabled) {
                "Leech only (Upload disabled)"
            } else {
                "Upload enabled (Optimal download speeds)"
            }
            seedSubLabel.setTextColor(if (disabled) Color.parseColor("#F59E0B") else Color.parseColor("#64748B"))
        }
        seedSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentDisableUpload = isChecked
            updateSeedUi(isChecked)
        }

        seedRow.addView(seedTextLayout)
        seedRow.addView(seedSwitch)
        togglesCard.addView(seedRow)

        // Divider
        val divider = View(ctx).apply {
            background = ColorDrawable(Color.parseColor("#263043"))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
                setMargins(0, dp(ctx, 4), 0, dp(ctx, 8))
            }
        }
        togglesCard.addView(divider)

        // Disk Storage toggle row
        val diskRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 4))
        }
        val diskTextLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val diskLabel = TextView(ctx).apply {
            text = "Disk Cache"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F1F5F9"))
        }
        val diskSubLabel = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
        }
        diskTextLayout.addView(diskLabel)
        diskTextLayout.addView(diskSubLabel)

        val diskSwitch = Switch(ctx).apply {
            isFocusable = true
        }

        fun updateDiskUi(useDisk: Boolean) {
            diskSwitch.isChecked = useDisk
            diskSubLabel.text = if (useDisk) {
                "Storage drive (Preserves device RAM)"
            } else {
                "RAM (Fast seek & zero storage wear)"
            }
        }
        diskSwitch.setOnCheckedChangeListener { _, isChecked ->
            currentUseDisk = isChecked
            updateDiskUi(isChecked)
        }

        diskRow.addView(diskTextLayout)
        diskRow.addView(diskSwitch)
        togglesCard.addView(diskRow)

        mainLayout.addView(togglesCard)

        // --- 8. CARD: TRACKERS ---
        val trackersCard = createCard()
        val trackersTitle = TextView(ctx).apply {
            text = "TRACKERS"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 4))
        }
        val trackersBadge = TextView(ctx).apply {
            text = "${TorrServeManager.FAST_TRACKERS.size} Active Trackers"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 4))
        }
        val trackersDesc = TextView(ctx).apply {
            text = "Tier-1 public trackers injected into streams to accelerate peer discovery and download speed."
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, 0, 0, dp(ctx, 8))
        }

        val trackerRefreshBtn = TextView(ctx).apply {
            text = "Re-inject Trackers"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#E0F2FE"))
            background = roundedDrawable(ctx, Color.parseColor("#0C4A6E"), radiusDp = 8, strokeColor = Color.parseColor("#0284C7"), strokeWidthDp = 1)
            setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 12), dp(ctx, 7))
            isFocusable = true
            val btnParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            layoutParams = btnParams
            setOnClickListener {
                TorrServeManager.injectTrackersToEngine(ctx)
                TorrServeManager.syncSettingsFileToDisk(ctx)
                Toast.makeText(ctx, "Trackers re-injected", Toast.LENGTH_SHORT).show()
            }
        }

        trackersCard.addView(trackersTitle)
        trackersCard.addView(trackersBadge)
        trackersCard.addView(trackersDesc)
        trackersCard.addView(trackerRefreshBtn)
        mainLayout.addView(trackersCard)

        // Full UI refresh lambda (for profiles)
        updateUiCallback = {
            refreshBufferChips()
            preloadSeekBar.progress = currentPreload
            updatePreloadLabels(currentPreload)
            readAheadSeekBar.progress = currentReadAhead
            updateReadAheadLabels(currentReadAhead)
            timeoutSeekBar.progress = currentTimeout.coerceAtMost(300)
            updateTimeoutLabels(currentTimeout)
            updateSeedUi(currentDisableUpload)
            updateDiskUi(currentUseDisk)
        }
        updateUiCallback?.invoke()

        // --- 8. ACTION BUTTONS (BOTTOM) ---
        val actionsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 12), 0, 0)
        }

        val alertDialog = AlertDialog.Builder(ctx)
            .setView(rootScroll)
            .create()

        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            background = roundedDrawable(ctx, Color.parseColor("#1E2433"), radiusDp = 10)
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10))
            isFocusable = true
            val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, dp(ctx, 10), 0)
            layoutParams = params
            setOnClickListener {
                alertDialog.dismiss()
            }
        }

        val applyBtn = TextView(ctx).apply {
            text = "Save"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = roundedDrawable(ctx, Color.parseColor("#2563EB"), radiusDp = 10)
            setPadding(dp(ctx, 24), dp(ctx, 10), dp(ctx, 24), dp(ctx, 10))
            isFocusable = true
            setOnClickListener {
                TorrServeManager.setCacheSizeMb(ctx, currentCacheMb)
                TorrServeManager.setPreloadPercent(ctx, currentPreload)
                TorrServeManager.setReadAheadPercent(ctx, currentReadAhead)
                TorrServeManager.setDisconnectTimeout(ctx, currentTimeout)
                TorrServeManager.setUseDisk(ctx, currentUseDisk)
                TorrServeManager.setDisableUpload(ctx, currentDisableUpload)

                TorrServeManager.syncSettingsFileToDisk(ctx)

                TorrServeManager.applySettingsAsync(ctx) { success, _ ->
                    Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
                }
                alertDialog.dismiss()
            }
        }

        actionsLayout.addView(cancelBtn)
        actionsLayout.addView(applyBtn)
        mainLayout.addView(actionsLayout)

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()

        actionsLayout.addView(cancelBtn)
        actionsLayout.addView(applyBtn)
        mainLayout.addView(actionsLayout)

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }
}
