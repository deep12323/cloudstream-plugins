package com.externalplayers

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.Plugin

object ExternalPlayersSettingsDialog {

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

    fun show(plugin: Plugin, ctx: Context) {
        val rootLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 14))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }

        // Header Title
        val titleView = TextView(ctx).apply {
            text = "📺 External Video Players"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 2))
        }
        rootLayout.addView(titleView)

        val subTitleView = TextView(ctx).apply {
            text = "Add, remove, or customize external players in CloudStream"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 12))
        }
        rootLayout.addView(subTitleView)

        // Action Buttons Bar
        val actionsBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(ctx, 12))
        }

        val addManualBtn = TextView(ctx).apply {
            text = "+ Add Manually"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#38BDF8"), 1)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(ctx, 6), 0)
            }
        }

        val pickInstalledBtn = TextView(ctx).apply {
            text = "📱 Pick Installed"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981"))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#10B981"), 1)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(ctx, 6), 0)
            }
        }

        val restoreDefaultsBtn = TextView(ctx).apply {
            text = "↺ Reset"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#94A3B8"))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#475569"), 1)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        actionsBar.addView(addManualBtn)
        actionsBar.addView(pickInstalledBtn)
        actionsBar.addView(restoreDefaultsBtn)
        rootLayout.addView(actionsBar)

        // Section Label
        val sectionHeader = TextView(ctx).apply {
            text = "CONFIGURED PLAYERS"
            textSize = 11f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            setPadding(dp(ctx, 2), 0, 0, dp(ctx, 6))
        }
        rootLayout.addView(sectionHeader)

        // Player List Container inside ScrollView
        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 280)
            )
            isFillViewport = true
            addView(listContainer)
        }
        rootLayout.addView(scrollView)

        // Function to refresh player list
        fun renderList() {
            listContainer.removeAllViews()
            val players = ExternalPlayersManager.getPlayers(ctx)

            if (players.isEmpty()) {
                val emptyTv = TextView(ctx).apply {
                    text = "No external players configured.\nTap '+ Add Manually' or '📱 Pick Installed' to add one, or '↺ Reset' to restore defaults."
                    textSize = 12f
                    setTextColor(Color.parseColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 16), dp(ctx, 30), dp(ctx, 16), dp(ctx, 30))
                }
                listContainer.addView(emptyTv)
                return
            }

            for (player in players) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10)
                    setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(ctx, 8))
                    }
                }

                // Left info
                val textLayout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                val nameTv = TextView(ctx).apply {
                    text = player.name
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F1F5F9"))
                }
                titleRow.addView(nameTv)

                if (player.isBuiltIn) {
                    val builtInBadge = TextView(ctx).apply {
                        text = " DEFAULT "
                        textSize = 9f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#38BDF8"))
                        background = roundedDrawable(ctx, Color.parseColor("#0F2942"), 4)
                        setPadding(dp(ctx, 4), dp(ctx, 1), dp(ctx, 4), dp(ctx, 1))
                        val lp = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(dp(ctx, 6), 0, 0, 0)
                        }
                        layoutParams = lp
                    }
                    titleRow.addView(builtInBadge)
                }
                textLayout.addView(titleRow)

                val pkgTv = TextView(ctx).apply {
                    text = player.packageName
                    textSize = 11f
                    setTextColor(Color.parseColor("#94A3B8"))
                    setPadding(0, dp(ctx, 2), 0, 0)
                }
                textLayout.addView(pkgTv)

                val isInstalled = ExternalPlayersManager.isAppInstalled(ctx, player.packageName)
                val statusTv = TextView(ctx).apply {
                    if (isInstalled) {
                        text = "● Installed"
                        setTextColor(Color.parseColor("#10B981"))
                    } else {
                        text = "○ Not Installed"
                        setTextColor(Color.parseColor("#64748B"))
                    }
                    textSize = 10f
                    setPadding(0, dp(ctx, 2), 0, 0)
                }
                textLayout.addView(statusTv)
                card.addView(textLayout)

                // Right controls: Toggle & Delete
                val toggle = Switch(ctx).apply {
                    isChecked = player.isEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        ExternalPlayersManager.setPlayerEnabled(ctx, player.id, isChecked)
                        ExternalPlayersManager.reloadActions(plugin, ctx)
                    }
                }
                card.addView(toggle)

                val deleteBtn = TextView(ctx).apply {
                    text = "✕"
                    textSize = 15f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#EF4444"))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
                    setOnClickListener {
                        showConfirmDeleteDialog(ctx, player.name) {
                            ExternalPlayersManager.removePlayer(ctx, player.id)
                            ExternalPlayersManager.reloadActions(plugin, ctx)
                            renderList()
                            Toast.makeText(ctx, "${player.name} removed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                card.addView(deleteBtn)

                listContainer.addView(card)
            }
        }

        renderList()

        // Done button
        var mainDialog: AlertDialog? = null
        val doneBtn = TextView(ctx).apply {
            text = "Done"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 4))
            setOnClickListener {
                mainDialog?.dismiss()
            }
        }
        rootLayout.addView(doneBtn)

        // Button Click Listeners
        addManualBtn.setOnClickListener {
            showAddManualDialog(plugin, ctx) {
                renderList()
            }
        }

        pickInstalledBtn.setOnClickListener {
            showPickInstalledDialog(plugin, ctx) {
                renderList()
            }
        }

        restoreDefaultsBtn.setOnClickListener {
            showConfirmRestoreDefaults(ctx) {
                ExternalPlayersManager.restoreDefaults(ctx)
                ExternalPlayersManager.reloadActions(plugin, ctx)
                renderList()
                Toast.makeText(ctx, "Default players restored", Toast.LENGTH_SHORT).show()
            }
        }

        mainDialog = AlertDialog.Builder(ctx)
            .setView(rootLayout)
            .create()
        mainDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        mainDialog.show()
    }

    private fun showAddManualDialog(plugin: Plugin, ctx: Context, onUpdated: () -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 16))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }

        val titleTv = TextView(ctx).apply {
            text = "Add Player Manually"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 12))
        }
        layout.addView(titleTv)

        fun createInput(hint: String, label: String): Pair<TextView, EditText> {
            val lbl = TextView(ctx).apply {
                text = label
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#94A3B8"))
                setPadding(dp(ctx, 2), dp(ctx, 4), 0, dp(ctx, 4))
            }
            val et = EditText(ctx).apply {
                setHint(hint)
                setHintTextColor(Color.parseColor("#64748B"))
                setTextColor(Color.parseColor("#F1F5F9"))
                textSize = 13f
                background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8)
                setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, dp(ctx, 10))
                }
            }
            return lbl to et
        }

        val (nameLabel, nameInput) = createInput("e.g. VLC, MX Player, Just Player", "DISPLAY NAME")
        layout.addView(nameLabel)
        layout.addView(nameInput)

        val (pkgLabel, pkgInput) = createInput("e.g. org.videolan.vlc", "PACKAGE NAME")
        layout.addView(pkgLabel)
        layout.addView(pkgInput)

        val (actLabel, actInput) = createInput("Optional (leave empty for default)", "ACTIVITY NAME (OPTIONAL)")
        layout.addView(actLabel)
        layout.addView(actInput)

        val buttonsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(ctx, 6), 0, 0)
        }

        var dialog: AlertDialog? = null

        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener { dialog?.dismiss() }
        }

        val addBtn = TextView(ctx).apply {
            text = "Add Player"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#38BDF8"), 1)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener {
                val name = nameInput.text.toString().trim()
                val pkg = pkgInput.text.toString().trim()
                val act = actInput.text.toString().trim().takeIf { it.isNotEmpty() }

                if (name.isEmpty()) {
                    Toast.makeText(ctx, "Please enter a player name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (pkg.isEmpty()) {
                    Toast.makeText(ctx, "Please enter a package name", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                ExternalPlayersManager.addPlayer(ctx, name, pkg, act)
                ExternalPlayersManager.reloadActions(plugin, ctx)
                onUpdated()
                dialog?.dismiss()
                Toast.makeText(ctx, "$name added successfully!", Toast.LENGTH_SHORT).show()
            }
        }

        buttonsRow.addView(cancelBtn)
        buttonsRow.addView(addBtn)
        layout.addView(buttonsRow)

        dialog = AlertDialog.Builder(ctx)
            .setView(layout)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showPickInstalledDialog(plugin: Plugin, ctx: Context, onUpdated: () -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 18), dp(ctx, 16), dp(ctx, 18), dp(ctx, 14))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }

        val titleTv = TextView(ctx).apply {
            text = "📱 Detected Video Players"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 2))
        }
        layout.addView(titleTv)

        val subTitleTv = TextView(ctx).apply {
            text = "Tap any detected app to add it as an external player:"
            textSize = 11f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 10))
        }
        layout.addView(subTitleTv)

        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val scrollView = ScrollView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(ctx, 260)
            )
            isFillViewport = true
            addView(listContainer)
        }
        layout.addView(scrollView)

        var dialog: AlertDialog? = null
        val detected = ExternalPlayersManager.scanInstalledVideoApps(ctx)
        val currentPlayers = ExternalPlayersManager.getPlayers(ctx)

        if (detected.isEmpty()) {
            val emptyTv = TextView(ctx).apply {
                text = "No compatible video player apps found.\nYou can still add any player manually with '+ Add Manually'."
                textSize = 12f
                setTextColor(Color.parseColor("#64748B"))
                gravity = Gravity.CENTER
                setPadding(dp(ctx, 12), dp(ctx, 30), dp(ctx, 12), dp(ctx, 30))
            }
            listContainer.addView(emptyTv)
        } else {
            for (app in detected) {
                val alreadyAdded = currentPlayers.any { it.packageName == app.packageName }

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 10)
                    setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(ctx, 8))
                    }
                }

                val textCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val nameTv = TextView(ctx).apply {
                    text = app.label
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(Color.parseColor("#F1F5F9"))
                }
                textCol.addView(nameTv)

                val pkgTv = TextView(ctx).apply {
                    text = app.packageName
                    textSize = 10f
                    setTextColor(Color.parseColor("#94A3B8"))
                }
                textCol.addView(pkgTv)
                row.addView(textCol)

                val actionBadge = TextView(ctx).apply {
                    if (alreadyAdded) {
                        text = "Added"
                        textSize = 11f
                        setTextColor(Color.parseColor("#64748B"))
                        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
                    } else {
                        text = "+ Add"
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        setTextColor(Color.parseColor("#10B981"))
                        background = roundedDrawable(ctx, Color.parseColor("#0F291E"), 6, Color.parseColor("#10B981"), 1)
                        setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                    }
                }
                row.addView(actionBadge)

                if (!alreadyAdded) {
                    row.setOnClickListener {
                        ExternalPlayersManager.addPlayer(ctx, app.label, app.packageName, app.activityName)
                        ExternalPlayersManager.reloadActions(plugin, ctx)
                        onUpdated()
                        dialog?.dismiss()
                        Toast.makeText(ctx, "${app.label} added!", Toast.LENGTH_SHORT).show()
                    }
                }

                listContainer.addView(row)
            }
        }

        val closeBtn = TextView(ctx).apply {
            text = "Cancel"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, dp(ctx, 10), 0, dp(ctx, 2))
            setOnClickListener { dialog?.dismiss() }
        }
        layout.addView(closeBtn)

        dialog = AlertDialog.Builder(ctx)
            .setView(layout)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showConfirmDeleteDialog(ctx: Context, playerName: String, onConfirmed: () -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 16))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }

        val titleTv = TextView(ctx).apply {
            text = "Remove Player?"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        layout.addView(titleTv)

        val msgTv = TextView(ctx).apply {
            text = "Are you sure you want to remove \"$playerName\" from external players?"
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 14))
        }
        layout.addView(msgTv)

        val buttonsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        var dialog: AlertDialog? = null

        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener { dialog?.dismiss() }
        }

        val removeBtn = TextView(ctx).apply {
            text = "Remove"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#EF4444"))
            background = roundedDrawable(ctx, Color.parseColor("#2A1215"), 8, Color.parseColor("#EF4444"), 1)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener {
                onConfirmed()
                dialog?.dismiss()
            }
        }

        buttonsRow.addView(cancelBtn)
        buttonsRow.addView(removeBtn)
        layout.addView(buttonsRow)

        dialog = AlertDialog.Builder(ctx)
            .setView(layout)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }

    private fun showConfirmRestoreDefaults(ctx: Context, onConfirmed: () -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 16))
            background = roundedDrawable(ctx, Color.parseColor("#0F172A"), 16, Color.parseColor("#1E293B"), 1)
        }

        val titleTv = TextView(ctx).apply {
            text = "Restore Defaults?"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#F8FAFC"))
            setPadding(0, 0, 0, dp(ctx, 6))
        }
        layout.addView(titleTv)

        val msgTv = TextView(ctx).apply {
            text = "This will reset your external players list back to default (Mpv Infinity, mpvEx, and 1DM)."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 0, 0, dp(ctx, 14))
        }
        layout.addView(msgTv)

        val buttonsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        var dialog: AlertDialog? = null

        val cancelBtn = TextView(ctx).apply {
            text = "Cancel"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener { dialog?.dismiss() }
        }

        val confirmBtn = TextView(ctx).apply {
            text = "Restore Defaults"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#38BDF8"))
            background = roundedDrawable(ctx, Color.parseColor("#1E293B"), 8, Color.parseColor("#38BDF8"), 1)
            setPadding(dp(ctx, 14), dp(ctx, 8), dp(ctx, 14), dp(ctx, 8))
            setOnClickListener {
                onConfirmed()
                dialog?.dismiss()
            }
        }

        buttonsRow.addView(cancelBtn)
        buttonsRow.addView(confirmBtn)
        layout.addView(buttonsRow)

        dialog = AlertDialog.Builder(ctx)
            .setView(layout)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
    }
}
