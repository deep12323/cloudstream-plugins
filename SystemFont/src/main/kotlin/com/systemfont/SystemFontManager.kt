package com.systemfont

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.WeakHashMap

object SystemFontManager {

    private const val TAG = "SystemFont"
    private const val PREFS_NAME = "system_font_preferences"
    const val KEY_ENABLED = "system_font_enabled"
    const val KEY_FONT_STYLE = "system_font_style"
    const val KEY_WEIGHT_BOOST = "system_font_weight_boost"

    const val STYLE_DEFAULT = 0
    const val STYLE_SANS_SERIF = 1
    const val STYLE_SERIF = 2
    const val STYLE_MONOSPACE = 3

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var isLifecycleRegistered = false
    private var isWmgHooked = false
    private var guardianJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val hookedRecyclerViews = Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>())
    private val hookedDecorViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun init(context: Context) {
        val app = resolveApplication(context)
        appContext = app ?: context.applicationContext

        Log.d(TAG, "Initializing SystemFontManager. Persistence active across app restarts.")

        // 1. Hook TypefaceCompat cache early so any future font inflation returns system font
        hookTypefaceCompatCache()

        // 2. Hook WindowManagerGlobal so any new window (Dialog, Activity, Popup) is intercepted instantly
        hookWindowManagerGlobal()

        // 3. Register lifecycle callbacks for all activity and fragment events
        registerLifecycleCallbacks(app)

        // 4. Immediately sweep all active windows and decor views on the main thread
        mainHandler.post {
            sweepAllCurrentWindows()
        }

        // 5. Start persistent guardian loop to catch dynamic recycler binding & async fragments
        startGuardianDaemon()
    }

    fun cleanup() {
        guardianJob?.cancel()
        guardianJob = null
        hookedRecyclerViews.clear()
        hookedDecorViews.clear()
        originalTypefaces.clear()
    }

    private fun resolveApplication(context: Context): Application? {
        if (context is Application) return context
        var c = context
        while (c is ContextWrapper) {
            if (c is Application) return c
            c = c.baseContext
        }
        return context.applicationContext as? Application
    }

    private fun getPrefs(ctx: Context? = appContext): SharedPreferences? {
        return (ctx ?: appContext)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(ctx: Context? = appContext): Boolean {
        // ALWAYS default to true so it stays active permanently across app restarts
        return getPrefs(ctx)?.getBoolean(KEY_ENABLED, true) ?: true
    }

    fun setEnabled(ctx: Context?, enabled: Boolean) {
        getPrefs(ctx)?.edit()?.putBoolean(KEY_ENABLED, enabled)?.commit()
        refreshAllViews()
    }

    fun getFontStyle(ctx: Context? = appContext): Int {
        return getPrefs(ctx)?.getInt(KEY_FONT_STYLE, STYLE_DEFAULT) ?: STYLE_DEFAULT
    }

    fun setFontStyle(ctx: Context?, style: Int) {
        getPrefs(ctx)?.edit()?.putInt(KEY_FONT_STYLE, style)?.commit()
        refreshAllViews()
    }

    fun isWeightBoostEnabled(ctx: Context? = appContext): Boolean {
        return getPrefs(ctx)?.getBoolean(KEY_WEIGHT_BOOST, false) ?: false
    }

    fun setWeightBoostEnabled(ctx: Context?, enabled: Boolean) {
        getPrefs(ctx)?.edit()?.putBoolean(KEY_WEIGHT_BOOST, enabled)?.commit()
        refreshAllViews()
    }

    fun resolveTargetTypeface(current: Typeface?, enabled: Boolean = isEnabled()): Typeface? {
        if (!enabled) {
            return current
        }

        val base = when (getFontStyle()) {
            STYLE_SANS_SERIF -> Typeface.SANS_SERIF
            STYLE_SERIF -> Typeface.SERIF
            STYLE_MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }

        val boost = isWeightBoostEnabled()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && current != null) {
            val weight = current.weight
            val isItalic = current.isItalic
            val finalWeight = if (boost) (weight + 150).coerceAtMost(1000) else weight
            Typeface.create(base, finalWeight, isItalic)
        } else {
            val style = current?.style ?: Typeface.NORMAL
            val finalStyle = if (boost) (style or Typeface.BOLD) else style
            Typeface.create(base, finalStyle)
        }
    }

    /**
     * Intercepts AndroidX TypefaceCompat cache.
     * When CloudStream requests @font/google_sans, this cache intercepts it and returns
     * the system default typeface directly.
     */
    private fun hookTypefaceCompatCache() {
        try {
            val compatClass = Class.forName("androidx.core.graphics.TypefaceCompat")
            val cacheField = compatClass.getDeclaredField("sTypefaceCache")
            cacheField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val originalCache = cacheField.get(null) as? LruCache<String, Typeface>

            if (originalCache != null) {
                val interceptingCache = object : LruCache<String, Typeface>(128) {
                    override fun create(key: String?): Typeface? {
                        if (isEnabled()) {
                            val target = resolveTargetTypeface(null, true)
                            if (target != null) return target
                        }
                        return null
                    }
                }

                // In ART, remove final modifier if present and install interceptor
                try {
                    val modifiersField = Field::class.java.getDeclaredField("accessFlags")
                    modifiersField.isAccessible = true
                    modifiersField.setInt(cacheField, cacheField.modifiers and Modifier.FINAL.inv())
                } catch (_: Throwable) {}

                cacheField.set(null, interceptingCache)
                Log.d(TAG, "TypefaceCompat sTypefaceCache successfully intercepted.")
            }
        } catch (e: Throwable) {
            Log.d(TAG, "TypefaceCompat hook non-critical fallback: ${e.message}")
        }
    }

    /**
     * Replaces WindowManagerGlobal.mViews with an observable list so any new window
     * (Dialog, Sheet, Activity, Popup) is styled with the system font immediately upon creation.
     */
    private fun hookWindowManagerGlobal() {
        if (isWmgHooked) return
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstanceMethod = wmgClass.getMethod("getInstance")
            val wmg = getInstanceMethod.invoke(null) ?: return

            val mViewsField = wmgClass.getDeclaredField("mViews")
            mViewsField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val currentViews = mViewsField.get(wmg) as? ArrayList<View> ?: return

            val customViewsList = object : ArrayList<View>(currentViews) {
                override fun add(element: View): Boolean {
                    val result = super.add(element)
                    mainHandler.post {
                        hookDecorView(element)
                        applyToViewTree(element)
                    }
                    return result
                }

                override fun add(index: Int, element: View) {
                    super.add(index, element)
                    mainHandler.post {
                        hookDecorView(element)
                        applyToViewTree(element)
                    }
                }
            }

            mViewsField.set(wmg, customViewsList)
            isWmgHooked = true
            Log.d(TAG, "WindowManagerGlobal.mViews successfully hooked.")
        } catch (e: Throwable) {
            Log.d(TAG, "WindowManagerGlobal hook non-critical: ${e.message}")
        }
    }

    private fun registerLifecycleCallbacks(app: Application?) {
        if (app == null || isLifecycleRegistered) return

        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(activity)
                    hookFragmentActivity(activity)
                }

                override fun onActivityStarted(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    hookDecorView(activity.window.decorView)
                    applyToViewTree(activity.window.decorView)
                }

                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {
                    if (currentActivityRef?.get() == activity) {
                        currentActivityRef = null
                    }
                }
            })
            isLifecycleRegistered = true
            Log.d(TAG, "ActivityLifecycleCallbacks registered successfully.")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to register ActivityLifecycleCallbacks", e)
        }
    }

    private fun hookFragmentActivity(activity: Activity) {
        if (activity is FragmentActivity) {
            activity.supportFragmentManager.registerFragmentLifecycleCallbacks(
                object : FragmentManager.FragmentLifecycleCallbacks() {
                    override fun onFragmentViewCreated(
                        fm: FragmentManager,
                        f: Fragment,
                        v: View,
                        savedInstanceState: Bundle?
                    ) {
                        applyToViewTree(v)
                    }

                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        f.view?.let { applyToViewTree(it) }
                    }
                },
                true
            )
        }
    }

    private fun hookDecorView(decorView: View) {
        if (!hookedDecorViews.add(decorView)) return

        decorView.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var lastApplyTime = 0L

            override fun onGlobalLayout() {
                val now = System.currentTimeMillis()
                if (now - lastApplyTime > 300) {
                    lastApplyTime = now
                    applyToViewTree(decorView)
                }
            }
        })
    }

    /**
     * Fetches all active root views across the entire app process via WindowManagerGlobal
     * and applies the system font immediately.
     */
    fun sweepAllCurrentWindows() {
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val getInstanceMethod = wmgClass.getMethod("getInstance")
            val wmg = getInstanceMethod.invoke(null) ?: return
            val mViewsField = wmgClass.getDeclaredField("mViews")
            mViewsField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val views = (mViewsField.get(wmg) as? List<View>)?.toList() ?: emptyList()
            for (v in views) {
                hookDecorView(v)
                applyToViewTree(v)
            }
        } catch (_: Throwable) {}

        currentActivityRef?.get()?.let {
            hookDecorView(it.window.decorView)
            applyToViewTree(it.window.decorView)
        }
    }

    fun applyToViewTree(root: View?) {
        if (root == null) return
        val enabled = isEnabled()

        traverseAndApply(root, enabled)
    }

    private fun traverseAndApply(view: View, enabled: Boolean) {
        if (view is TextView) {
            if (enabled) {
                if (!originalTypefaces.containsKey(view)) {
                    originalTypefaces[view] = view.typeface
                }
                val original = originalTypefaces[view]
                val target = resolveTargetTypeface(original, true)
                if (view.typeface != target && target != null) {
                    view.typeface = target
                }
            } else {
                if (originalTypefaces.containsKey(view)) {
                    val original = originalTypefaces[view]
                    if (view.typeface != original) {
                        view.typeface = original
                    }
                }
            }
        }

        if (view is ViewGroup) {
            if (view is RecyclerView && hookedRecyclerViews.add(view)) {
                view.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(child: View) {
                        traverseAndApply(child, isEnabled())
                    }

                    override fun onChildViewDetachedFromWindow(child: View) {}
                })
            }

            for (i in 0 until view.childCount) {
                traverseAndApply(view.getChildAt(i), enabled)
            }
        }
    }

    fun refreshAllViews() {
        mainHandler.post {
            sweepAllCurrentWindows()
        }
    }

    /**
     * Background guardian daemon runs every 1 second while CloudStream is active.
     * Ensures any new views, cards, or lists loaded dynamically from network or cache
     * always adopt the system font without resetting.
     */
    private fun startGuardianDaemon() {
        guardianJob?.cancel()
        guardianJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1200)
                if (isEnabled()) {
                    mainHandler.post {
                        sweepAllCurrentWindows()
                    }
                }
            }
        }
    }
}
