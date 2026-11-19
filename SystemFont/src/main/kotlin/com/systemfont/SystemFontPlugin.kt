package com.systemfont

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class SystemFontPlugin : Plugin() {

    override fun load(context: Context) {
        try {
            SystemFontManager.init(context)
        } catch (t: Throwable) {
            android.util.Log.e("SystemFont", "Error initializing SystemFontManager", t)
        }

        openSettings = { ctx ->
            try {
                showSettingsDialog(ctx)
            } catch (t: Throwable) {
                android.util.Log.e("SystemFont", "Error showing settings dialog", t)
            }
        }
    }

    override fun beforeUnload() {
        try {
            SystemFontManager.cleanup()
        } catch (t: Throwable) {
            android.util.Log.e("SystemFont", "Error cleaning up SystemFontManager", t)
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

    private fun showSettingsDialog(ctx: Context) {
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 14))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }

        // --- TITLE ---
        val titleView = TextView(ctx).apply {
            text = "System Font"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 12))
        }
        rootLayout.addView(titleView)

        fun createToggleRow(
            title: String,
            subtitle: String?,
            initialState: Boolean,
            onChanged: (Boolean) -> Unit
        ): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(ctx, 8))
                }
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
            textLayout.addView(titleTv)

            if (!subtitle.isNullOrEmpty()) {
                val subTv = TextView(ctx).apply {
                    text = subtitle
                    textSize = 11f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, dp(ctx, 2), 0, 0)
                }
                textLayout.addView(subTv)
            }

            val toggle = Switch(ctx).apply {
                isChecked = initialState
                setOnCheckedChangeListener { _, isChecked ->
                    onChanged(isChecked)
                }
            }

            row.addView(textLayout)
            row.addView(toggle)
            return row
        }

        // Toggle 1: Follow System Font
        rootLayout.addView(createToggleRow(
            title = "Follow System Font",
            subtitle = "Adopt device system font across CloudStream",
            initialState = SystemFontManager.isEnabled(ctx)
        ) { enabled ->
            SystemFontManager.setEnabled(ctx, enabled)
        })

        // Toggle 2: Weight Boost
        rootLayout.addView(createToggleRow(
            title = "Weight & Contrast Boost",
            subtitle = "Thickens font stroke for enhanced readability",
            initialState = SystemFontManager.isWeightBoostEnabled(ctx)
        ) { enabled ->
            SystemFontManager.setWeightBoostEnabled(ctx, enabled)
        })

        // Style Selector Header
        val styleHeader = TextView(ctx).apply {
            text = "STYLE"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(ctx, 2), dp(ctx, 4), 0, dp(ctx, 6))
        }
        rootLayout.addView(styleHeader)

        // Compact Horizontal Pill Selector
        val styles = listOf(
            SystemFontManager.STYLE_DEFAULT to "Default",
            SystemFontManager.STYLE_SANS_SERIF to "Sans",
            SystemFontManager.STYLE_SERIF to "Serif",
            SystemFontManager.STYLE_MONOSPACE to "Mono"
        )
        val styleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(ctx, 12))
            }
        }

        val currentStyle = SystemFontManager.getFontStyle(ctx)
        val buttons = mutableListOf<TextView>()

        styles.forEach { (styleCode, label) ->
            val isSelected = (styleCode == currentStyle)
            val btn = TextView(ctx).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) Color.parseColor("#0F172A") else Color.parseColor("#94A3B8"))
                background = if (isSelected) roundedDrawable(ctx, Color.parseColor("#38BDF8"), 8) else null
                setPadding(dp(ctx, 6), dp(ctx, 6), dp(ctx, 6), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    SystemFontManager.setFontStyle(ctx, styleCode)
                    buttons.forEachIndexed { idx, tv ->
                        val sel = (styles[idx].first == styleCode)
                        tv.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
                        tv.setTextColor(if (sel) Color.parseColor("#0F172A") else Color.parseColor("#94A3B8"))
                        tv.background = if (sel) roundedDrawable(ctx, Color.parseColor("#38BDF8"), 8) else null
                    }
                }
            }
            buttons.add(btn)
            styleRow.addView(btn)
        }
        rootLayout.addView(styleRow)

        // Done Button
        var alertDialog: AlertDialog? = null
        val closeBtn = TextView(ctx).apply {
            text = "Done"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 6), 0, dp(ctx, 2))
            setOnClickListener { alertDialog?.dismiss() }
        }
        rootLayout.addView(closeBtn)

        alertDialog = AlertDialog.Builder(ctx)
            .setView(rootLayout)
            .create()
        alertDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        alertDialog.show()
    }
}
