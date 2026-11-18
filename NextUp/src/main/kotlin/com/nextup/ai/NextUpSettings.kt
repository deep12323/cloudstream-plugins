package com.nextup.ai

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object NextUpSettings {

    fun show(context: Context) {
        val d = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val scrollView = ScrollView(context).apply {
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(root)

        fun createSectionTitle(title: String) = TextView(context).apply {
            text = title
            textSize = 14f
            setTypeface(typeface, 1)
            setTextColor(Color.parseColor("#4CAF50"))
            setPadding(0, dp(14), 0, dp(4))
        }

        fun createDescription(desc: String) = TextView(context).apply {
            text = desc
            textSize = 11.5f
            setTextColor(Color.parseColor("#9E9E9E"))
            setPadding(0, 0, 0, dp(6))
        }

        fun createInput(initial: String, hint: String, isPassword: Boolean = false) = EditText(context).apply {
            setText(initial)
            this.hint = hint
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#757575"))
            if (isPassword) {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                inputType = InputType.TYPE_CLASS_TEXT
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1E1E1E"))
                setCornerRadius(dp(8).toFloat())
                setStroke(dp(1), Color.parseColor("#333333"))
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = dp(8)
            layoutParams = lp
        }

        // --- 1. GEMINI API SECTION ---
        root.addView(createSectionTitle("🤖 Google Gemini AI"))
        root.addView(createDescription("Required for AI taste profiling & personalized recommendations (Free from aistudio.google.com)"))

        val currentApiKey = TasteProfileManager.getApiKey(context)
        val apiKeyInput = createInput(currentApiKey, "Enter Gemini API Key (AIzaSy...)", isPassword = true)
        root.addView(apiKeyInput)

        // Model Selector
        root.addView(createDescription("AI Model Tier:"))
        val modelOptions = listOf(
            "Auto (Recommended)",
            "gemini-1.5-flash",
            "gemini-2.0-flash",
            "gemini-1.5-pro",
            "Custom Tuned Model"
        )
        val modelSpinner = Spinner(context).apply {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, modelOptions)
            this.adapter = adapter
            val currentModel = TasteProfileManager.getSelectedModel(context)
            val idx = modelOptions.indexOf(currentModel).coerceAtLeast(0)
            setSelection(idx)
        }
        root.addView(modelSpinner)

        val customModelInput = createInput(
            if (TasteProfileManager.getSelectedModel(context).startsWith("tunedModels/")) TasteProfileManager.getSelectedModel(context) else "",
            "Tuned Model ID (e.g. tunedModels/my-taste or my-taste)"
        )
        root.addView(customModelInput)
        root.addView(createDescription("Paste your Model ID after tuning on Google AI Studio. Leave empty to use auto default."))

        // --- 2. SIMKL ACCOUNT SECTION ---
        root.addView(createSectionTitle("🎬 Simkl Integration"))
        val detectedAccount = TasteProfileManager.getSimklAccountName(context)
        val hasToken = TasteProfileManager.getSimklToken(context).isNotBlank()
        if (detectedAccount != null) {
            root.addView(createDescription("🟢 Connected as @$detectedAccount via CloudStream Accounts"))
        } else if (hasToken) {
            root.addView(createDescription("🟢 Simkl Connected"))
        } else {
            root.addView(createDescription("ℹ️ Log into Simkl in CloudStream (Settings ➜ Accounts ➜ Simkl) or paste token below:"))
        }

        val manualSimklToken = TasteProfileManager.getPrefs(context).getString("simkl_access_token", "") ?: ""
        val simklTokenInput = createInput(manualSimklToken, "Manual Simkl Access Token (Optional)")
        root.addView(simklTokenInput)

        // --- 3. OPEN WITH SECTION ---
        root.addView(createSectionTitle("🚀 'Open With' Action"))
        root.addView(createDescription("Choose how recommended titles open when tapped (matches CloudStream Library options):"))

        val openWithOptions = listOf(
            "Default (Follow CloudStream)",
            "CineSimkl",
            "Search (Multi-Provider Search)",
            "Browser",
            "NextUp (Native Simkl)",
            "None"
        )
        val openWithSpinner = Spinner(context).apply {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, openWithOptions)
            this.adapter = adapter
            val currentMode = TasteProfileManager.getOpenWithMode(context)
            val idx = openWithOptions.indexOf(currentMode).coerceAtLeast(0)
            setSelection(idx)
        }
        root.addView(openWithSpinner)

        // --- 4. TASTE VIBE ---
        root.addView(createSectionTitle("🎭 Recommendation Vibe"))
        val vibeOptions = listOf(
            "Balanced & High Quality",
            "Mind-Bending & Psychological Thrillers",
            "Dark & Gritty Crime",
            "Feel-Good & Uplifting",
            "Hidden Gems & Underrated",
            "Anime & Animation Focus"
        )
        val vibeSpinner = Spinner(context).apply {
            val adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, vibeOptions)
            this.adapter = adapter
            val currentVibe = TasteProfileManager.getTasteVibe(context)
            val idx = vibeOptions.indexOf(currentVibe).coerceAtLeast(0)
            setSelection(idx)
        }
        root.addView(vibeSpinner)

        // --- 5. ACTION BUTTONS ---
        root.addView(createSectionTitle("⚡ Actions & Model Tuning"))

        val refreshBtn = Button(context).apply {
            text = "🔄 Refresh AI Recommendations"
            textSize = 12f
            setOnClickListener {
                TasteProfileManager.clearCache(context)
                NextUpProvider.invalidateSession()
                Toast.makeText(context, "Cache cleared! Pull to refresh home page.", Toast.LENGTH_SHORT).show()
            }
        }
        root.addView(refreshBtn)

        val exportBtn = Button(context).apply {
            text = "📥 Export Dataset for AI Studio Tuning"
            textSize = 12f
            setOnClickListener {
                CoroutineScope(Dispatchers.Main).launch {
                    val file = TasteProfileManager.exportTrainingDataset(context)
                    if (file != null) {
                        Toast.makeText(context, "Exported: ${file.name} to Downloads folder!", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Export failed. Please check storage permission.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        root.addView(exportBtn)

        AlertDialog.Builder(context)
            .setTitle("NextUp AI Settings")
            .setView(scrollView)
            .setPositiveButton("Save") { _, _ ->
                val newKey = apiKeyInput.text.toString().trim()
                TasteProfileManager.setApiKey(context, newKey)

                val selectedModelPos = modelSpinner.selectedItemPosition
                val chosenModel = if (selectedModelPos == 4) {
                    val rawCustom = customModelInput.text.toString().trim()
                    if (rawCustom.isBlank()) {
                        GeminiClient.DEFAULT_MODEL
                    } else if (!rawCustom.startsWith("tunedModels/") && !rawCustom.startsWith("models/")) {
                        "tunedModels/$rawCustom"
                    } else {
                        rawCustom
                    }
                } else {
                    modelOptions[selectedModelPos]
                }
                TasteProfileManager.setSelectedModel(context, chosenModel)

                val newSimklToken = simklTokenInput.text.toString().trim()
                TasteProfileManager.setSimklToken(context, newSimklToken)

                val chosenOpenWith = openWithOptions[openWithSpinner.selectedItemPosition]
                TasteProfileManager.setOpenWithMode(context, chosenOpenWith)

                val chosenVibe = vibeOptions[vibeSpinner.selectedItemPosition]
                TasteProfileManager.setTasteVibe(context, chosenVibe)

                TasteProfileManager.clearCache(context)
                NextUpProvider.invalidateSession()
                Toast.makeText(context, "NextUp settings saved!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
