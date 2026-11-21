package com.desiremovies

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DesireMoviesSettingsFragment(private val plugin: DesireMoviesPlugin) : BottomSheetDialogFragment() {

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog ?: return
        val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior = BottomSheetBehavior.from(bottomSheet)
        behavior.state = BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()

        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 20), dp(context, 20), dp(context, 20))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#121216"))
                val radius = dp(context, 20).toFloat()
                cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header Title
        val titleView = TextView(context).apply {
            text = "🎬 DesireMovies Settings"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E50914"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 4))
            }
        }
        mainContainer.addView(titleView)

        val subTitleView = TextView(context).apply {
            text = "Customize active domain and categories shown in the CloudStream Home catalogue rows."
            textSize = 13f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 16))
            }
        }
        mainContainer.addView(subTitleView)

        val formContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
            addView(formContainer)
        }
        mainContainer.addView(scrollView)

        // Domain Section
        val domainHeader = TextView(context).apply {
            text = "🌐 Active Mirror Domain"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E50914"))
            setPadding(0, dp(context, 10), 0, dp(context, 6))
        }
        formContainer.addView(domainHeader)

        val domainInput = EditText(context).apply {
            setText(DesireMoviesPreferences.getSavedDomain(context))
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "e.g. https://1desiremovies.xyz"
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1C24"))
                cornerRadius = dp(context, 8).toFloat()
                setStroke(dp(context, 1), Color.parseColor("#33333F"))
            }
            setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 10))
            }
        }
        formContainer.addView(domainInput)

        val domainBtn = Button(context).apply {
            text = "Save Domain"
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E50914"))
                cornerRadius = dp(context, 8).toFloat()
            }
            setOnClickListener {
                val newDomain = domainInput.text.toString().trim()
                if (newDomain.startsWith("http")) {
                    DesireMoviesPreferences.setSavedDomain(context, newDomain)
                    Toast.makeText(context, "Domain saved: $newDomain", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Please enter a valid URL starting with http:// or https://", Toast.LENGTH_SHORT).show()
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dp(context, 16))
            }
        }
        formContainer.addView(domainBtn)

        // Categories Header
        val catHeader = TextView(context).apply {
            text = "📑 Home Catalogue Rows"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#E50914"))
            setPadding(0, dp(context, 10), 0, dp(context, 6))
        }
        formContainer.addView(catHeader)

        fun refreshCategoriesList() {
            formContainer.removeViews(4, formContainer.childCount - 4)
            val categories = DesireMoviesPreferences.getCategories(context)

            for (cat in categories) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10))
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#1C1C24"))
                        cornerRadius = dp(context, 8).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp(context, 8))
                    }
                }

                val titleText = TextView(context).apply {
                    text = cat.name
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                row.addView(titleText)

                val switchView = Switch(context).apply {
                    isChecked = cat.isEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        DesireMoviesPreferences.toggleCategory(context, cat.slug, isChecked)
                    }
                }
                row.addView(switchView)

                if (cat.isCustom) {
                    val delBtn = ImageButton(context).apply {
                        background = null
                        setImageResource(android.R.drawable.ic_menu_delete)
                        setOnClickListener {
                            DesireMoviesPreferences.removeCategory(context, cat.slug)
                            refreshCategoriesList()
                        }
                    }
                    row.addView(delBtn)
                }

                formContainer.addView(row)
            }
        }

        refreshCategoriesList()

        // Reset to Defaults Button
        val resetBtn = Button(context).apply {
            text = "Reset All Categories to Default"
            setTextColor(Color.parseColor("#AAAAAA"))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#262633"))
                cornerRadius = dp(context, 8).toFloat()
            }
            setOnClickListener {
                DesireMoviesPreferences.resetToDefaults(context)
                refreshCategoriesList()
                Toast.makeText(context, "Categories reset to default", Toast.LENGTH_SHORT).show()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(context, 16), 0, 0)
            }
        }
        mainContainer.addView(resetBtn)

        return mainContainer
    }
}
