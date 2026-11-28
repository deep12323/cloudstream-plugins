package com.audiobookbay

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AudioBookBayPlugin : Plugin() {

    override fun load(context: Context) {
        val provider = AudioBookBayProvider(context)
        registerMainAPI(provider)

        openSettings = { ctx ->
            showSettingsDialog(ctx, provider)
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
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(bgColor)
            cornerRadius = radiusDp * ctx.resources.displayMetrics.density
            if (strokeWidthDp > 0) {
                setStroke((strokeWidthDp * ctx.resources.displayMetrics.density).toInt(), strokeColor)
            }
        }
    }

    private fun showSettingsDialog(ctx: Context, provider: AudioBookBayProvider) {
        val rootScroll = ScrollView(ctx).apply {
            isFillViewport = true
            background = roundedDrawable(ctx, Color.parseColor("#121722"), 16, Color.parseColor("#243044"), 1)
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 18))
        }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        rootScroll.addView(layout)

        val titleView = TextView(ctx).apply {
            text = "AudioBookBay Settings"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(ctx, 4))
        }
        val subtitleView = TextView(ctx).apply {
            text = "Configure domain mirror if AudioBookBay changes URL"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 14))
        }
        layout.addView(titleView)
        layout.addView(subtitleView)

        val domainLabel = TextView(ctx).apply {
            text = "Active Domain URL"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E2E8F0"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        val domainInput = EditText(ctx).apply {
            setText(AudioBookBayProvider.getSavedDomain(ctx))
            hint = AudioBookBayProvider.DEFAULT_DOMAIN
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            textSize = 14f
            setSingleLine(true)
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10, Color.parseColor("#334155"), 1)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }
        layout.addView(domainLabel)
        layout.addView(domainInput)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 16), 0, 0)
        }

        var dialog: AlertDialog? = null

        val resetBtn = Button(ctx).apply {
            text = "Reset Default"
            textSize = 13f
            setTextColor(Color.parseColor("#CBD5E1"))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener {
                domainInput.setText(AudioBookBayProvider.DEFAULT_DOMAIN)
            }
        }

        val saveBtn = Button(ctx).apply {
            text = "Save"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = roundedDrawable(ctx, Color.parseColor("#0D9488"), 8)
            setPadding(dp(ctx, 18), dp(ctx, 8), dp(ctx, 18), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(ctx, 10)
            }
            setOnClickListener {
                val entered = domainInput.text.toString().trim().removeSuffix("/")
                val valid = if (entered.startsWith("http://") || entered.startsWith("https://")) {
                    entered
                } else if (entered.isNotBlank()) {
                    "https://$entered"
                } else {
                    AudioBookBayProvider.DEFAULT_DOMAIN
                }
                AudioBookBayProvider.saveDomain(ctx, valid)
                provider.mainUrl = valid
                Toast.makeText(ctx, "Saved domain: $valid", Toast.LENGTH_SHORT).show()
                dialog?.dismiss()
            }
        }

        btnRow.addView(resetBtn)
        btnRow.addView(saveBtn)
        layout.addView(btnRow)

        dialog = AlertDialog.Builder(ctx)
            .setView(rootScroll)
            .create()
        dialog.show()
    }
}
