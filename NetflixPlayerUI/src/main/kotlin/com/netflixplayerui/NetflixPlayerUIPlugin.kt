package com.netflixplayerui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetflixPlayerUIPlugin : Plugin() {

    override fun load(context: Context) {
        NetflixPlayerUIManager.init(context)

        openSettings = { ctx ->
            showSettingsDialog(ctx)
        }
    }

    override fun beforeUnload() {
        NetflixPlayerUIManager.cleanup()
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

    private fun showSettingsDialog(ctx: Context) {
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 16))
            background = roundedDrawable(
                ctx,
                Color.parseColor("#141414"),
                16,
                Color.parseColor("#2A2A2A"),
                1
            )
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(ctx, 14))
        }

        val badgeView = TextView(ctx).apply {
            text = "N"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = roundedDrawable(ctx, Color.parseColor("#E50914"), 6)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28)).apply {
                marginEnd = dp(ctx, 10)
            }
        }

        val titleView = TextView(ctx).apply {
            text = "Netflix Player UI"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }

        headerRow.addView(badgeView)
        headerRow.addView(titleView)
        rootLayout.addView(headerRow)

        val optionsContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun addToggle(
            title: String,
            subtitle: String,
            initialState: Boolean,
            onChanged: (Boolean) -> Unit
        ) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
                background = roundedDrawable(ctx, Color.parseColor("#1F1F1F"), 10)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(ctx, 10))
                }
            }

            val textLayout = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(ctx, 10)
                }
            }

            val titleTv = TextView(ctx).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#F5F5F5"))
            }

            val subTv = TextView(ctx).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(Color.parseColor("#A3A3A3"))
                setPadding(0, dp(ctx, 2), 0, 0)
            }

            textLayout.addView(titleTv)
            textLayout.addView(subTv)

            val toggle = Switch(ctx).apply {
                isChecked = initialState
                setOnCheckedChangeListener { _, isChecked ->
                    onChanged(isChecked)
                }
            }

            row.addView(textLayout)
            row.addView(toggle)
            row.setOnClickListener { toggle.isChecked = !toggle.isChecked }
            optionsContainer.addView(row)
        }

        addToggle(
            "Enable Netflix Player UI",
            "Master switch for Netflix-inspired video player styling",
            NetflixPlayerUIManager.isEnabled(ctx)
        ) { NetflixPlayerUIManager.setEnabled(ctx, it) }

        addToggle(
            "Full App Netflix Theme",
            "Themes Home, Details, Search, Bottom Nav & Play buttons like Netflix",
            NetflixPlayerUIManager.isFullAppThemeEnabled(ctx)
        ) { NetflixPlayerUIManager.setFullAppThemeEnabled(ctx, it) }

        addToggle(
            "Netflix Red Seekbar",
            "Colors progress bar and scrubber in #E50914 Netflix Red",
            NetflixPlayerUIManager.isRedSeekbarEnabled(ctx)
        ) { NetflixPlayerUIManager.setRedSeekbarEnabled(ctx, it) }

        addToggle(
            "Cinematic Gradient Scrims",
            "Smooth top & bottom dark vignette gradients behind controls",
            NetflixPlayerUIManager.isGradientScrimEnabled(ctx)
        ) { NetflixPlayerUIManager.setGradientScrimEnabled(ctx, it) }

        addToggle(
            "Minimalist Bottom Controls",
            "Clean icon + label buttons without bulky borders",
            NetflixPlayerUIManager.isMinimalControlsEnabled(ctx)
        ) { NetflixPlayerUIManager.setMinimalControlsEnabled(ctx, it) }

        addToggle(
            "Netflix \"Skip Intro\" Button",
            "Crisp white rounded button with bold black text",
            NetflixPlayerUIManager.isNetflixSkipButtonEnabled(ctx)
        ) { NetflixPlayerUIManager.setNetflixSkipButtonEnabled(ctx, it) }

        addToggle(
            "Hide Resolution & Codec Badges",
            "Hide extra resolution/codec text above the title",
            NetflixPlayerUIManager.isHideMetaClutterEnabled(ctx)
        ) { NetflixPlayerUIManager.setHideMetaClutterEnabled(ctx, it) }

        val scrollView = ScrollView(ctx).apply {
            addView(optionsContainer)
        }
        rootLayout.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx)
            .setView(rootLayout)
            .setPositiveButton("Done") { d, _ -> d.dismiss() }
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(Color.parseColor("#E50914"))
    }
}
