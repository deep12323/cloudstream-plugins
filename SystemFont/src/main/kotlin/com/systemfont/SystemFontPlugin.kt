package com.systemfont

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SystemFontPlugin : Plugin() {

    override fun load(context: Context) {
        SystemFontManager.init(context)

        openSettings = { ctx ->
            showSettingsDialog(ctx)
        }
    }

    override fun beforeUnload() {
        SystemFontManager.cleanup()
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

    private fun showSettingsDialog(ctx: Context) {
        val scrollView = ScrollView(ctx).apply {
            isFillViewport = true
        }

        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 16))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }
        scrollView.addView(rootLayout)

        // --- TITLE ---
        val titleView = TextView(ctx).apply {
            text = "System Font Enforcer"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 4))
        }

        val descView = TextView(ctx).apply {
            text = "Overwrites hardcoded Google Sans with your device's native system font across all UI elements."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 16))
        }
        rootLayout.addView(titleView)
        rootLayout.addView(descView)

        // --- PREVIEW CARD ---
        val previewContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10, Color.parseColor("#334155"), 1)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(ctx, 14))
            }
            layoutParams = lp
        }

        val previewHeader = TextView(ctx).apply {
            text = "LIVE PREVIEW"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, 0, 0, dp(ctx, 4))
        }

        val previewTitle = TextView(ctx).apply {
            text = "The Quick Brown Fox Jumps Over 123"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 4))
        }

        val previewBody = TextView(ctx).apply {
            text = "This preview displays your selected system font family, weight, and style in real time."
            textSize = 12f
            setTextColor(Color.parseColor("#CBD5E1"))
        }

        previewContainer.addView(previewHeader)
        previewContainer.addView(previewTitle)
        previewContainer.addView(previewBody)
        rootLayout.addView(previewContainer)

        fun updatePreview() {
            val enabled = SystemFontManager.isEnabled(ctx)
            if (enabled) {
                val boldTf = SystemFontManager.resolveTargetTypeface(Typeface.DEFAULT_BOLD, true)
                val normalTf = SystemFontManager.resolveTargetTypeface(Typeface.DEFAULT, true)
                previewTitle.typeface = boldTf
                previewBody.typeface = normalTf
            } else {
                previewTitle.typeface = Typeface.DEFAULT_BOLD
                previewBody.typeface = Typeface.DEFAULT
            }
        }

        fun createToggleRow(
            title: String,
            subtitle: String,
            initialState: Boolean,
            onChanged: (Boolean) -> Unit
        ): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(ctx, 10))
                }
                layoutParams = lp
            }

            val textLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(ctx).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F1F5F9"))
            }

            val subTv = TextView(ctx).apply {
                text = subtitle
                textSize = 11f
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(0, dp(ctx, 2), 0, 0)
            }

            textLayout.addView(titleTv)
            textLayout.addView(subTv)

            val toggle = Switch(ctx).apply {
                isChecked = initialState
                setOnCheckedChangeListener { _, isChecked ->
                    onChanged(isChecked)
                    updatePreview()
                }
            }

            row.addView(textLayout)
            row.addView(toggle)
            return row
        }

        // Toggle 1: Follow System Font
        val followRow = createToggleRow(
            title = "Follow System Font",
            subtitle = "Enable system font override across all CloudStream screens",
            initialState = SystemFontManager.isEnabled(ctx)
        ) { enabled ->
            SystemFontManager.setEnabled(ctx, enabled)
        }
        rootLayout.addView(followRow)

        // Toggle 2: Weight Boost
        val boostRow = createToggleRow(
            title = "Weight & Contrast Boost",
            subtitle = "Slightly thickens font weight (+150) for enhanced readability on OLED",
            initialState = SystemFontManager.isWeightBoostEnabled(ctx)
        ) { enabled ->
            SystemFontManager.setWeightBoostEnabled(ctx, enabled)
        }
        rootLayout.addView(boostRow)

        // --- FONT FAMILY SELECTOR ---
        val familyHeader = TextView(ctx).apply {
            text = "SYSTEM FONT FAMILY"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 6))
        }
        rootLayout.addView(familyHeader)

        val radioGroup = RadioGroup(ctx).apply {
            orientation = RadioGroup.VERTICAL
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(ctx, 14))
            }
            layoutParams = lp
        }

        val styles = listOf(
            SystemFontManager.STYLE_DEFAULT to "System Default (matches device)",
            SystemFontManager.STYLE_SANS_SERIF to "System Sans-Serif",
            SystemFontManager.STYLE_SERIF to "System Serif",
            SystemFontManager.STYLE_MONOSPACE to "System Monospace"
        )

        val currentStyle = SystemFontManager.getFontStyle(ctx)

        styles.forEach { (styleCode, label) ->
            val rb = RadioButton(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(Color.parseColor("#E2E8F0"))
                isChecked = (styleCode == currentStyle)
                setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        SystemFontManager.setFontStyle(ctx, styleCode)
                        updatePreview()
                    }
                }
            }
            radioGroup.addView(rb)
        }
        rootLayout.addView(radioGroup)

        // Initial preview update
        updatePreview()

        // --- DONE / CLOSE BUTTON ---
        var alertDialog: AlertDialog? = null

        val closeBtn = TextView(ctx).apply {
            text = "Done"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            layoutParams = lp
            isFocusable = true
            setOnClickListener {
                alertDialog?.dismiss()
            }
        }
        rootLayout.addView(closeBtn)

        alertDialog = AlertDialog.Builder(ctx)
            .setView(scrollView)
            .create()

        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }
}
