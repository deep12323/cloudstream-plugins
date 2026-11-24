package com.netflixplayerui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

object NetflixPlayerUIManager {

    private const val PREFS_NAME = "netflix_player_ui_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_FULL_APP_THEME = "full_app_theme"
    private const val KEY_RED_SEEKBAR = "red_seekbar"
    private const val KEY_GRADIENT_SCRIM = "gradient_scrim"
    private const val KEY_MINIMAL_CONTROLS = "minimal_controls"
    private const val KEY_NETFLIX_SKIP = "netflix_skip_button"
    private const val KEY_HIDE_META_CLUTTER = "hide_meta_clutter"

    private const val NETFLIX_RED = 0xFFE50914.toInt()
    private const val NETFLIX_BLACK = 0xFF000000.toInt()
    private const val NETFLIX_DARK_BG = 0xFF141414.toInt()
    private const val NETFLIX_SURFACE = 0xFF1F1F1F.toInt()
    private const val NETFLIX_SECONDARY_BTN = 0xFF2B2B2B.toInt()
    private const val NETFLIX_NAV_BG = 0xFF121212.toInt()
    private const val NETFLIX_GREEN = 0xFF46D369.toInt()
    private const val NETFLIX_BUFFERED = 0x66FFFFFF
    private const val NETFLIX_UNPLAYED = 0x40FFFFFF

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var isLifecycleRegistered = false
    private var guardianJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val hookedActivities = Collections.newSetFromMap(WeakHashMap<Activity, Boolean>())
    private val styledViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val hookedRecyclers = Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        clearCachesAndRefresh()
    }

    fun isFullAppThemeEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FULL_APP_THEME, true)
    fun setFullAppThemeEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_FULL_APP_THEME, value).apply()
        clearCachesAndRefresh()
    }

    fun isRedSeekbarEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_RED_SEEKBAR, true)
    fun setRedSeekbarEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RED_SEEKBAR, value).apply()
        clearCachesAndRefresh()
    }

    fun isGradientScrimEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_GRADIENT_SCRIM, true)
    fun setGradientScrimEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_GRADIENT_SCRIM, value).apply()
        clearCachesAndRefresh()
    }

    fun isMinimalControlsEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_MINIMAL_CONTROLS, true)
    fun setMinimalControlsEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_MINIMAL_CONTROLS, value).apply()
        clearCachesAndRefresh()
    }

    fun isNetflixSkipButtonEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_NETFLIX_SKIP, true)
    fun setNetflixSkipButtonEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NETFLIX_SKIP, value).apply()
        clearCachesAndRefresh()
    }

    fun isHideMetaClutterEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_HIDE_META_CLUTTER, false)
    fun setHideMetaClutterEnabled(context: Context, value: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HIDE_META_CLUTTER, value).apply()
        clearCachesAndRefresh()
    }

    private fun clearCachesAndRefresh() {
        styledViews.clear()
        hookedRecyclers.clear()
        refreshCurrentActivity()
    }

    fun init(context: Context) {
        val app = (context.applicationContext as? Application) ?: (context as? Application)
        appContext = app ?: context.applicationContext ?: context

        if (app != null && !isLifecycleRegistered) {
            isLifecycleRegistered = true
            registerLifecycle(app)
        }

        mainHandler.post {
            runCatching {
                findActivityFromContext(context)?.let {
                    currentActivityRef = WeakReference(it)
                    hookActivity(it)
                    applyNetflixUI(it)
                }
            }
            startGuardianLoop()
        }
    }

    fun cleanup() {
        guardianJob?.cancel()
        guardianJob = null
        styledViews.clear()
        hookedRecyclers.clear()
        hookedActivities.clear()
    }

    private fun findActivityFromContext(context: Context?): Activity? {
        var ctx = context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    private fun registerLifecycle(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                currentActivityRef = WeakReference(activity)
                hookActivity(activity)
            }

            override fun onActivityStarted(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                hookActivity(activity)
            }

            override fun onActivityResumed(activity: Activity) {
                currentActivityRef = WeakReference(activity)
                hookActivity(activity)
                applyNetflixUI(activity)
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivityRef?.get() === activity) {
                    currentActivityRef = null
                }
                hookedActivities.remove(activity)
            }
        })
    }

    private fun hookActivity(activity: Activity) {
        if (!hookedActivities.add(activity)) return
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewCreated(
                        fm: FragmentManager,
                        f: Fragment,
                        v: View,
                        savedInstanceState: Bundle?
                    ) {
                        v.post { applyNetflixUI(activity) }
                    }

                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        applyNetflixUI(activity)
                    }
                },
                true
            )
        }
    }

    private fun getForegroundActivity(): Activity? {
        runCatching {
            val act = CommonActivity.activity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                currentActivityRef = WeakReference(act)
                return act
            }
        }
        return currentActivityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    private fun refreshCurrentActivity() {
        getForegroundActivity()?.let { act ->
            act.runOnUiThread { applyNetflixUI(act) }
        }
    }

    private fun startGuardianLoop() {
        if (guardianJob?.isActive == true) return
        guardianJob = scope.launch {
            while (isActive) {
                getForegroundActivity()?.let { act ->
                    applyNetflixUI(act)
                }
                delay(450L)
            }
        }
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }

    private fun findViewByName(root: View, name: String): View? {
        val res = root.resources ?: return null
        val pkg = root.context.packageName
        val id = res.getIdentifier(name, "id", pkg)
        return if (id != 0) root.findViewById(id) else null
    }

    fun applyNetflixUI(activity: Activity) {
        if (!isEnabled(activity)) return
        val decor = activity.window?.decorView ?: return

        runCatching {
            if (isFullAppThemeEnabled(activity)) {
                applyAppShellTheme(activity, decor)
                applyHomeTheme(activity, decor)
                applyResultTheme(activity, decor)
                applySearchTheme(activity, decor)
            }

            applyNetflixPlayerUI(activity, decor)
        }
    }

    // =========================================================
    // 1. APP SHELL (Status Bar, Nav Bar, BottomNavigationView)
    // =========================================================
    private fun applyAppShellTheme(activity: Activity, decor: View) {
        runCatching {
            activity.window.statusBarColor = NETFLIX_BLACK
            activity.window.navigationBarColor = NETFLIX_NAV_BG
        }

        findViewByName(decor, "homeRoot")?.let { homeRoot ->
            if (styledViews.add(homeRoot)) {
                homeRoot.setBackgroundColor(NETFLIX_BLACK)
            }
        }

        (findViewByName(decor, "nav_view") as? BottomNavigationView)?.let { nav ->
            if (styledViews.add(nav)) {
                nav.setBackgroundColor(NETFLIX_NAV_BG)
                val states = arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                )
                val colors = intArrayOf(Color.WHITE, 0xFF737373.toInt())
                val csl = ColorStateList(states, colors)
                nav.itemIconTintList = csl
                nav.itemTextColor = csl
                runCatching {
                    nav.itemActiveIndicatorColor = ColorStateList.valueOf(Color.TRANSPARENT)
                }
            }
        }
    }

    // =========================================================
    // 2. HOME SCREEN (Hero Banner, Play/List Buttons, Cards)
    // =========================================================
    private fun applyHomeTheme(activity: Activity, decor: View) {
        val homeRoot = findViewByName(decor, "home_root") ?: return
        if (styledViews.add(homeRoot)) {
            homeRoot.setBackgroundColor(NETFLIX_BLACK)
        }

        findViewByName(homeRoot, "home_loading")?.setBackgroundColor(NETFLIX_BLACK)

        // Hero "Play" button -> Iconic Netflix white pill/rect with bold black text
        (findViewByName(homeRoot, "home_preview_play") as? MaterialButton)?.let { btn ->
            if (styledViews.add(btn)) {
                styleNetflixPrimaryButton(activity, btn)
            }
        }

        // Hero bottom-to-top fade-to-black scrim so poster melts into #000000
        findViewByName(homeRoot, "home_preview_title_holder")?.let { holder ->
            if (styledViews.add(holder)) {
                holder.background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(NETFLIX_BLACK, 0xCC000000.toInt(), 0x00000000)
                )
            }
        }

        // Top-left Netflix "N" badge on Home header
        (findViewByName(homeRoot, "home_header") as? ViewGroup)?.let { header ->
            if (styledViews.add(header)) {
                val existingBadge = header.findViewWithTag<View>("netflix_n_badge")
                if (existingBadge == null && header is FrameLayout) {
                    val badge = TextView(activity).apply {
                        tag = "netflix_n_badge"
                        text = "N"
                        textSize = 22f
                        setTypeface(Typeface.SERIF, Typeface.BOLD)
                        setTextColor(NETFLIX_RED)
                        setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
                        setPadding(dp(activity, 16), dp(activity, 12), dp(activity, 8), dp(activity, 8))
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.START
                        )
                    }
                    header.addView(badge)
                }
            }
        }

        // Hero "My List" / "Info" buttons -> Clean Netflix transparent/dark icons
        listOf("home_preview_bookmark", "home_preview_info").forEach { id ->
            (findViewByName(homeRoot, id) as? MaterialButton)?.let { btn ->
                if (styledViews.add(btn)) {
                    btn.backgroundTintList = ColorStateList.valueOf(0x66141414)
                    btn.setTextColor(Color.WHITE)
                    btn.iconTint = ColorStateList.valueOf(Color.WHITE)
                    btn.cornerRadius = dp(activity, 4)
                    btn.strokeWidth = 0
                }
            }
        }

        // Hero title
        (findViewByName(homeRoot, "home_preview_viewpager_text") as? TextView)?.let { tv ->
            if (styledViews.add(tv)) {
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                tv.setShadowLayer(8f, 0f, 2f, 0xCC000000.toInt())
            }
        }

        // Section headers ("Continue Watching", "Bookmarks")
        listOf("home_watch_parent_item_title", "home_bookmark_parent_item_title").forEach { id ->
            (findViewByName(homeRoot, id) as? TextView)?.let { tv ->
                if (styledViews.add(tv)) {
                    tv.setTextColor(Color.WHITE)
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                }
            }
        }

        // Provider switcher / FABs
        listOf("home_change_api", "home_api_fab", "home_random").forEach { id ->
            (findViewByName(homeRoot, id) as? MaterialButton)?.let { btn ->
                if (styledViews.add(btn)) {
                    btn.backgroundTintList = ColorStateList.valueOf(NETFLIX_SURFACE)
                    btn.setTextColor(Color.WHITE)
                    btn.iconTint = ColorStateList.valueOf(NETFLIX_RED)
                    btn.strokeColor = ColorStateList.valueOf(0xFF333333.toInt())
                    btn.strokeWidth = dp(activity, 1)
                }
            }
        }

        // Bookmark filter chips
        listOf(
            "home_type_watching_btt",
            "home_plan_to_watch_btt",
            "home_type_on_hold_btt",
            "home_type_dropped_btt",
            "home_type_completed_btt"
        ).forEach { id ->
            (findViewByName(homeRoot, id) as? Chip)?.let { chip ->
                if (styledViews.add(chip)) {
                    styleNetflixChip(chip)
                }
            }
        }

        // Hook RecyclerViews so newly scrolled poster cards get Netflix 4dp corners + red progress
        listOf("home_master_recycler", "home_watch_child_recyclerview", "home_bookmarked_child_recyclerview").forEach { id ->
            (findViewByName(homeRoot, id) as? RecyclerView)?.let { rv ->
                hookRecyclerForCards(activity, rv)
            }
        }
    }

    // =========================================================
    // 3. RESULT / DETAILS PAGE (Netflix Hero + Buttons + Episodes)
    // =========================================================
    private fun applyResultTheme(activity: Activity, decor: View) {
        val resultRoot = findViewByName(decor, "result_root") ?: return
        if (styledViews.add(resultRoot)) {
            resultRoot.setBackgroundColor(NETFLIX_BLACK)
        }

        findViewByName(resultRoot, "result_scroll")?.setBackgroundColor(NETFLIX_BLACK)

        (findViewByName(resultRoot, "result_title") as? TextView)?.let { tv ->
            if (styledViews.add(tv)) {
                tv.setTextColor(Color.WHITE)
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        }

        // Primary Netflix White Play buttons
        listOf("result_play_movie", "result_resume_series_button").forEach { id ->
            (findViewByName(resultRoot, id) as? MaterialButton)?.let { btn ->
                if (styledViews.add(btn)) {
                    styleNetflixPrimaryButton(activity, btn)
                }
            }
        }

        // Secondary Netflix Dark Gray buttons
        listOf(
            "result_download_movie",
            "result_next_series_button",
            "result_bookmark_button",
            "result_add_sync",
            "result_season_button",
            "result_episode_select",
            "result_dub_select",
            "result_sort_button",
            "result_batch_download_button"
        ).forEach { id ->
            (findViewByName(resultRoot, id) as? MaterialButton)?.let { btn ->
                if (styledViews.add(btn)) {
                    styleNetflixSecondaryButton(activity, btn)
                }
            }
        }

        // Netflix Green rating badge
        (findViewByName(resultRoot, "result_meta_rating") as? TextView)?.let { tv ->
            if (styledViews.add(tv)) {
                tv.setTextColor(NETFLIX_GREEN)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        }

        // Backdrop fade-to-black overlay & clean action buttons
        findViewByName(resultRoot, "result_poster_background_holder")?.let { bgHolder ->
            if (styledViews.add(bgHolder)) {
                bgHolder.foreground = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(NETFLIX_BLACK, 0xB3000000.toInt(), 0x22000000, 0x80000000.toInt())
                )
            }
        }

        listOf("result_back", "result_share", "result_openinbrower", "result_search").forEach { id ->
            (findViewByName(resultRoot, id) as? ImageView)?.let { iv ->
                if (styledViews.add(iv)) {
                    iv.setColorFilter(Color.WHITE)
                    iv.background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(0x66000000)
                    }
                }
            }
        }

        (findViewByName(resultRoot, "result_description") as? TextView)?.let { tv ->
            if (styledViews.add(tv)) {
                tv.setTextColor(0xFFD4D4D4.toInt())
                tv.setLineSpacing(dp(activity, 3).toFloat(), 1.0f)
            }
        }

        (findViewByName(resultRoot, "result_cast_text") as? TextView)?.let { tv ->
            if (styledViews.add(tv)) {
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        }

        // Netflix Red progress bars
        listOf("result_resume_series_progress", "result_movie_progress_downloaded").forEach { id ->
            (findViewByName(resultRoot, id) as? ProgressBar)?.let { pb ->
                if (styledViews.add(pb)) {
                    pb.progressTintList = ColorStateList.valueOf(NETFLIX_RED)
                    pb.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                }
            }
        }

        (findViewByName(resultRoot, "result_episodes_text") as? TextView)?.let { tv ->
            if (styledViews.add(tv)) {
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        }

        (findViewByName(resultRoot, "result_episodes") as? RecyclerView)?.let { rv ->
            hookRecyclerForEpisodes(activity, rv)
        }
    }

    // =========================================================
    // 4. SEARCH SCREEN
    // =========================================================
    private fun applySearchTheme(activity: Activity, decor: View) {
        val searchRoot = findViewByName(decor, "searchRoot") ?: return
        if (styledViews.add(searchRoot)) {
            searchRoot.setBackgroundColor(NETFLIX_BLACK)
        }

        (findViewByName(searchRoot, "search_loading_bar") as? ProgressBar)?.let { pb ->
            if (styledViews.add(pb)) {
                pb.indeterminateTintList = ColorStateList.valueOf(NETFLIX_RED)
                pb.progressTintList = ColorStateList.valueOf(NETFLIX_RED)
            }
        }

        findViewByName(searchRoot, "main_search")?.let { searchBar ->
            if (styledViews.add(searchBar)) {
                searchBar.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(NETFLIX_SURFACE)
                    cornerRadius = dp(activity, 6).toFloat()
                }
            }
        }

        listOf("search_autofit_results", "search_master_recycler").forEach { id ->
            (findViewByName(searchRoot, id) as? RecyclerView)?.let { rv ->
                hookRecyclerForCards(activity, rv)
            }
        }
    }

    private fun hookRecyclerForCards(activity: Activity, rv: RecyclerView) {
        styleVisibleCards(activity, rv)
        if (!hookedRecyclers.add(rv)) return
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                styleCardHierarchy(activity, view)
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun styleVisibleCards(activity: Activity, root: ViewGroup) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i) ?: continue
            styleCardHierarchy(activity, child)
        }
    }

    private fun styleCardHierarchy(activity: Activity, view: View) {
        (findViewByName(view, "background_card") as? CardView)?.let { card ->
            if (styledViews.add(card)) {
                card.radius = dp(activity, 4).toFloat()
                card.cardElevation = 0f
                card.setCardBackgroundColor(NETFLIX_DARK_BG)
            }
        }
        (findViewByName(view, "watchProgress") as? ProgressBar)?.let { pb ->
            if (styledViews.add(pb)) {
                pb.progressTintList = ColorStateList.valueOf(NETFLIX_RED)
                pb.progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            }
        }
        (findViewByName(view, "imageText") as? TextView)?.let { tv ->
            if (styledViews.add(tv)) {
                tv.setTextColor(Color.WHITE)
                tv.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                tv.setShadowLayer(4f, 0f, 1f, 0xCC000000.toInt())
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i) ?: continue
                if (child is RecyclerView) {
                    hookRecyclerForCards(activity, child)
                }
            }
        }
    }

    private fun hookRecyclerForEpisodes(activity: Activity, rv: RecyclerView) {
        if (!hookedRecyclers.add(rv)) return
        rv.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                (findViewByName(view, "episode_progress") as? ProgressBar)?.let { pb ->
                    if (styledViews.add(pb)) {
                        pb.progressTintList = ColorStateList.valueOf(NETFLIX_RED)
                        pb.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
                    }
                }
                (findViewByName(view, "episode_holder") as? CardView)?.let { card ->
                    if (styledViews.add(card)) {
                        card.radius = dp(activity, 4).toFloat()
                        card.cardElevation = 0f
                        card.setCardBackgroundColor(NETFLIX_DARK_BG)
                    }
                }
            }
            override fun onChildViewDetachedFromWindow(view: View) {}
        })
    }

    private fun styleNetflixPrimaryButton(context: Context, btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(Color.BLACK)
        btn.iconTint = ColorStateList.valueOf(Color.BLACK)
        btn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        btn.isAllCaps = false
        btn.cornerRadius = dp(context, 4)
        btn.strokeWidth = 0
        btn.elevation = 0f
        btn.rippleColor = ColorStateList.valueOf(0x22000000)
    }

    private fun styleNetflixSecondaryButton(context: Context, btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(NETFLIX_SECONDARY_BTN)
        btn.setTextColor(Color.WHITE)
        btn.iconTint = ColorStateList.valueOf(Color.WHITE)
        btn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        btn.isAllCaps = false
        btn.cornerRadius = dp(context, 4)
        btn.strokeWidth = 0
        btn.elevation = 0f
        btn.rippleColor = ColorStateList.valueOf(0x33FFFFFF)
    }

    private fun styleNetflixChip(chip: Chip) {
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        chip.chipBackgroundColor = ColorStateList(states, intArrayOf(NETFLIX_RED, NETFLIX_SURFACE))
        chip.setTextColor(Color.WHITE)
        chip.chipStrokeWidth = 0f
    }

    // =========================================================
    // 5. VIDEO PLAYER UI
    // =========================================================
    private fun applyNetflixPlayerUI(activity: Activity, decor: View) {
        val playerHolder = findViewByName(decor, "player_holder") ?: return

        val firstTimeForRoot = styledViews.add(playerHolder)

        // 1. Netflix Red Seekbar (always ensure colors stick even after Media3 theme resets)
        if (isRedSeekbarEnabled(activity)) {
            findViewByName(playerHolder, "exo_progress")?.let { timeBar ->
                applyTimeBarColors(timeBar)
            }
        }

        // 2. Skip Intro / Chapter button (may become visible dynamically mid-playback)
        if (isNetflixSkipButtonEnabled(activity)) {
            (findViewByName(playerHolder, "skip_chapter_button") as? MaterialButton)?.let { btn ->
                styleSkipIntroButton(activity, btn)
            }
        }

        // 3. Hide resolution/codec clutter if requested
        if (isHideMetaClutterEnabled(activity)) {
            findViewByName(playerHolder, "player_video_title_rez")?.visibility = View.GONE
            findViewByName(playerHolder, "player_video_info")?.visibility = View.GONE
        }

        if (!firstTimeForRoot) return

        // 4. Gradient Scrims (top & bottom cinematic vignette)
        if (isGradientScrimEnabled(activity)) {
            findViewByName(playerHolder, "shadow_overlay")?.apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xAA000000.toInt(), 0x1A000000, 0x1A000000, 0xCC000000.toInt())
                )
            }

            findViewByName(playerHolder, "player_top_holder")?.apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xCC000000.toInt(), 0x4D000000, 0x00000000)
                )
            }

            findViewByName(playerHolder, "bottom_player_bar")?.apply {
                background = GradientDrawable(
                    GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(0xE6000000.toInt(), 0x80000000.toInt(), 0x00000000)
                )
                setPadding(paddingLeft, dp(activity, 24), paddingRight, dp(activity, 8))
            }
        }

        // 5. Top Title & Metadata Typography
        (findViewByName(playerHolder, "player_video_title") as? TextView)?.apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
            setShadowLayer(6f, 0f, 2f, 0xAA000000.toInt())
        }

        (findViewByName(playerHolder, "player_video_title_rez") as? TextView)?.apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        (findViewByName(playerHolder, "player_video_info") as? TextView)?.apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        }

        // 6. Center Play / Pause / Skip Controls
        (findViewByName(playerHolder, "player_pause_play") as? ImageView)?.apply {
            setColorFilter(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x38000000)
            }
            val p = dp(activity, 12)
            setPadding(p, p, p, p)
        }

        listOf("player_rew", "player_ffwd").forEach { name ->
            (findViewByName(playerHolder, name) as? ImageView)?.apply {
                setColorFilter(Color.WHITE)
            }
        }

        listOf("exo_rew_text", "exo_ffwd_text").forEach { name ->
            (findViewByName(playerHolder, name) as? TextView)?.apply {
                setTextColor(Color.WHITE)
                setShadowLayer(6f, 0f, 2f, 0xCC000000.toInt())
            }
        }

        // 7. Time Labels
        listOf("exo_position", "exo_duration", "time_left", "time_live").forEach { name ->
            (findViewByName(playerHolder, name) as? TextView)?.apply {
                setTextColor(0xF2FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
                setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
            }
        }

        // 8. Minimalist Netflix Bottom Action Buttons
        if (isMinimalControlsEnabled(activity)) {
            val bottomButtonIds = listOf(
                "player_lock",
                "player_rotate_btt",
                "player_resize_btt",
                "player_speed_btt",
                "player_subtitle_offset_btt",
                "player_sources_btt",
                "player_tracks_btt",
                "player_compressor_btt",
                "player_skip_op",
                "player_skip_episode"
            )
            for (btnId in bottomButtonIds) {
                (findViewByName(playerHolder, btnId) as? MaterialButton)?.let { btn ->
                    styleMinimalBottomButton(activity, btn)
                }
            }

            (findViewByName(playerHolder, "player_episodes_button") as? ImageView)?.apply {
                setColorFilter(Color.WHITE)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x66000000)
                    setStroke(dp(activity, 1), 0x44FFFFFF)
                }
            }
        }

        // 9. Brightness / Volume Vertical Progress Bars & Download Card
        listOf("player_progressbar_left_level1", "player_progressbar_right_level1", "downloaded_progress").forEach { name ->
            (findViewByName(playerHolder, name) as? ProgressBar)?.apply {
                progressTintList = ColorStateList.valueOf(NETFLIX_RED)
                progressBackgroundTintList = ColorStateList.valueOf(0x44FFFFFF)
            }
        }

        // 10. Episode Drawer & Torrent Download Card
        findViewByName(playerHolder, "player_episode_overlay")?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xF2141414.toInt())
                cornerRadius = dp(activity, 8).toFloat()
                setStroke(dp(activity, 1), 0x33FFFFFF)
            }
        }
        (findViewByName(playerHolder, "player_episode_overlay_title") as? TextView)?.apply {
            setTextColor(Color.WHITE)
            setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        findViewByName(playerHolder, "download_header")?.apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0xCC141414.toInt())
                cornerRadius = dp(activity, 8).toFloat()
            }
        }
    }

    private fun applyTimeBarColors(timeBar: View) {
        val cls = timeBar.javaClass
        runCatching { cls.getMethod("setPlayedColor", Int::class.javaPrimitiveType).invoke(timeBar, NETFLIX_RED) }
        runCatching { cls.getMethod("setScrubberColor", Int::class.javaPrimitiveType).invoke(timeBar, NETFLIX_RED) }
        runCatching { cls.getMethod("setBufferedColor", Int::class.javaPrimitiveType).invoke(timeBar, NETFLIX_BUFFERED) }
        runCatching { cls.getMethod("setUnplayedColor", Int::class.javaPrimitiveType).invoke(timeBar, NETFLIX_UNPLAYED) }
    }

    private fun styleMinimalBottomButton(context: Context, btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
        btn.strokeWidth = 0
        btn.elevation = 0f
        btn.setTextColor(0xF2FFFFFF.toInt())
        btn.iconTint = ColorStateList.valueOf(Color.WHITE)
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        btn.isAllCaps = false
        btn.setTypeface(Typeface.SANS_SERIF, Typeface.NORMAL)
        btn.iconSize = dp(context, 20)
        btn.iconPadding = dp(context, 6)
        btn.rippleColor = ColorStateList.valueOf(0x33FFFFFF)
    }

    private fun styleSkipIntroButton(context: Context, btn: MaterialButton) {
        btn.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
        btn.setTextColor(Color.parseColor("#141414"))
        btn.setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        btn.isAllCaps = false
        btn.cornerRadius = dp(context, 4)
        btn.strokeWidth = 0
        btn.elevation = dp(context, 6).toFloat()
        btn.rippleColor = ColorStateList.valueOf(0x22000000)
    }
}
