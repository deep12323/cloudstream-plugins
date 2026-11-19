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
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
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
import java.util.LinkedHashMap
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

    // Unique tag key to ensure we NEVER re-apply or cause layout thrashing on already styled views
    private const val TAG_APPLIED_CONFIG = 0x7F0A0000 or 0x5346

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    private var isLifecycleRegistered = false
    private var isWmgHooked = false
    private var guardianJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val hookedRecyclerViews = Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>())
    private val hookedComposeViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    // Cached pre-computed Typeface references
    @Volatile private var cachedNormal: Typeface? = null
    @Volatile private var cachedBold: Typeface? = null
    @Volatile private var cachedItalic: Typeface? = null
    @Volatile private var cachedBoldItalic: Typeface? = null
    @Volatile private var currentConfigToken: Int = 1

    /**
     * Intercepting LinkedHashMap that sits directly inside AndroidX TypefaceCompat.sTypefaceCache.
     * When any layout inflates a TextView referencing @font/google_sans or any other font,
     * TypefaceCompat.findFromCache() checks this map FIRST before reading font files or XML.
     * Returning the system typeface directly ensures views are born with the system font,
     * completely eliminating font switching / visual glitches on app entry.
     */
    private class InterceptingFontMap(
        initialMap: Map<String, Typeface>
    ) : LinkedHashMap<String, Typeface>(initialMap) {

        override fun get(key: String): Typeface? {
            val direct = super.get(key)
            if (direct != null) return direct
            if (!isEnabled()) return null

            val lower = key.lowercase()
            val isBold = lower.contains("bold") || lower.contains("medium") || lower.contains("black") ||
                    lower.contains("700") || lower.contains("800") || lower.contains("-1") || lower.contains("-3")
            val isItalic = lower.contains("italic") || lower.contains("-2")

            val target = when {
                isBold && isItalic -> cachedBoldItalic
                isBold -> cachedBold
                isItalic -> cachedItalic
                else -> cachedNormal
            } ?: return null

            super.put(key, target)
            return target
        }

        override fun containsKey(key: String): Boolean {
            if (super.containsKey(key)) return true
            if (!isEnabled()) return false
            val lower = key.lowercase()
            return lower.contains("font") || lower.contains("sans") || lower.contains("product")
        }
    }

    fun init(context: Context) {
        val app = resolveApplication(context)
        appContext = app ?: context.applicationContext

        Log.d(TAG, "Initializing SystemFontManager with instant zero-glitch engine.")

        rebuildTypefaceCache()
        patchSystemFontMap()
        hookTypefaceCompatCache()
        hookWindowManagerGlobal()
        registerLifecycleCallbacks(app)

        // Apply immediately to current foreground activity if one exists
        findCurrentActivity()?.let { act ->
            currentActivityRef = WeakReference(act)
            act.window?.decorView?.let { applyToViewTree(it) }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            sweepAllCurrentWindows()
        } else {
            mainHandler.post {
                sweepAllCurrentWindows()
            }
        }

        startGentleGuardian()
    }

    fun cleanup() {
        guardianJob?.cancel()
        guardianJob = null
        hookedRecyclerViews.clear()
        hookedComposeViews.clear()
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

    private fun findCurrentActivity(): Activity? {
        try {
            val act = CommonActivity.activity
            if (act != null) return act
        } catch (_: Throwable) {}
        return currentActivityRef?.get()
    }

    private fun getPrefs(ctx: Context? = appContext): SharedPreferences? {
        return (ctx ?: appContext)?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isEnabled(ctx: Context? = appContext): Boolean {
        return getPrefs(ctx)?.getBoolean(KEY_ENABLED, true) ?: true
    }

    fun setEnabled(ctx: Context?, enabled: Boolean) {
        getPrefs(ctx)?.edit()?.putBoolean(KEY_ENABLED, enabled)?.commit()
        invalidateAndRefresh()
    }

    fun getFontStyle(ctx: Context? = appContext): Int {
        return getPrefs(ctx)?.getInt(KEY_FONT_STYLE, STYLE_DEFAULT) ?: STYLE_DEFAULT
    }

    fun setFontStyle(ctx: Context?, style: Int) {
        getPrefs(ctx)?.edit()?.putInt(KEY_FONT_STYLE, style)?.commit()
        invalidateAndRefresh()
    }

    fun isWeightBoostEnabled(ctx: Context? = appContext): Boolean {
        return getPrefs(ctx)?.getBoolean(KEY_WEIGHT_BOOST, false) ?: false
    }

    fun setWeightBoostEnabled(ctx: Context?, enabled: Boolean) {
        getPrefs(ctx)?.edit()?.putBoolean(KEY_WEIGHT_BOOST, enabled)?.commit()
        invalidateAndRefresh()
    }

    /**
     * Pre-computes exact Typeface singleton references for Normal, Bold, Italic, and Bold-Italic.
     */
    private fun rebuildTypefaceCache() {
        val base = when (getFontStyle()) {
            STYLE_SANS_SERIF -> Typeface.SANS_SERIF
            STYLE_SERIF -> Typeface.SERIF
            STYLE_MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }

        val boost = isWeightBoostEnabled()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val normalWeight = if (boost) 550 else 400
            val boldWeight = if (boost) 850 else 700

            cachedNormal = Typeface.create(base, normalWeight, false)
            cachedBold = Typeface.create(base, boldWeight, false)
            cachedItalic = Typeface.create(base, normalWeight, true)
            cachedBoldItalic = Typeface.create(base, boldWeight, true)
        } else {
            val normalStyle = if (boost) Typeface.BOLD else Typeface.NORMAL
            val boldStyle = Typeface.BOLD

            cachedNormal = Typeface.create(base, normalStyle)
            cachedBold = Typeface.create(base, boldStyle)
            cachedItalic = Typeface.create(base, Typeface.ITALIC)
            cachedBoldItalic = Typeface.create(base, Typeface.BOLD_ITALIC)
        }

        currentConfigToken++
    }

    /**
     * Engine 1: TypefaceCompat Cache Interception.
     * Intercepts AndroidX TypefaceCompat.sTypefaceCache so all layout inflations
     * receive the target System Font directly at instantiation time.
     */
    private fun hookTypefaceCompatCache() {
        try {
            val compatClass = Class.forName("androidx.core.graphics.TypefaceCompat")
            val cacheField = compatClass.getDeclaredField("sTypefaceCache")
            cacheField.isAccessible = true
            val cacheObj = cacheField.get(null) ?: return

            // 1. Pre-populate cache directly with all known CloudStream font resources
            prepopulateTypefaceCompatCache(cacheObj)

            // 2. Intercept the internal LinkedHashMap storage of LruCache
            val mapField = cacheObj.javaClass.getDeclaredField("map")
            mapField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val originalMap = mapField.get(cacheObj) as? LinkedHashMap<String, Typeface> ?: return

            if (originalMap !is InterceptingFontMap) {
                val interceptingMap = InterceptingFontMap(originalMap)
                mapField.set(cacheObj, interceptingMap)
                Log.d(TAG, "TypefaceCompat.sTypefaceCache LinkedHashMap successfully intercepted!")
            }
        } catch (e: Throwable) {
            Log.d(TAG, "TypefaceCompat cache hook fallback: ${e.message}")
        }
    }

    /**
     * Pre-populates sTypefaceCache with exact resource keys for every font defined in CloudStream.
     */
    private fun prepopulateTypefaceCompatCache(cacheObj: Any) {
        val normal = cachedNormal ?: Typeface.DEFAULT
        val bold = cachedBold ?: Typeface.DEFAULT_BOLD
        val pkg = appContext?.packageName ?: "com.lagradost.cloudstream3"

        val knownFonts = mutableListOf<Pair<String, Int>>()
        try {
            val rFontClass = Class.forName("com.lagradost.cloudstream3.R\$font")
            for (field in rFontClass.fields) {
                if (field.type == Int::class.javaPrimitiveType) {
                    val id = field.getInt(null)
                    knownFonts.add(field.name to id)
                }
            }
        } catch (_: Throwable) {
            knownFonts.addAll(listOf(
                "google_sans" to 2131296259,
                "productsans_regular" to 2131296274,
                "productsans_medium" to 2131296272,
                "productsans_bold" to 2131296267,
                "productsans_black" to 2131296265,
                "productsans_light" to 2131296270,
                "netflix_sans" to 2131296262,
                "open_sans" to 2131296263,
                "poppins_regular" to 2131296264,
                "ubuntu_regular" to 2131296281
            ))
        }

        try {
            val putMethod = cacheObj.javaClass.getMethod("put", Any::class.java, Any::class.java)

            for ((fontName, resId) in knownFonts) {
                val isBoldFont = fontName.contains("bold") || fontName.contains("medium") || fontName.contains("black")
                val isItalicFont = fontName.contains("italic")
                val tf = when {
                    isBoldFont && isItalicFont -> cachedBoldItalic ?: bold
                    isBoldFont -> bold
                    isItalicFont -> cachedItalic ?: normal
                    else -> normal
                }

                // Standard resource UID formats:
                // 1. "${pkg}-null-0-${resId}-${style}"
                // 2. "${pkg}-res/font/${fontName}.xml-0-${resId}-${style}"
                // 3. "${pkg}-res/font/${fontName}.ttf-0-${resId}-${style}"
                for (style in 0..3) {
                    putMethod.invoke(cacheObj, "$pkg-null-0-$resId-$style", tf)
                    putMethod.invoke(cacheObj, "$pkg-res/font/$fontName.xml-0-$resId-$style", tf)
                    putMethod.invoke(cacheObj, "$pkg-res/font/$fontName.ttf-0-$resId-$style", tf)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Engine 2: Comprehensive Low-Level Native System Font Map Patching.
     * Maps all hardcoded CloudStream font names to the selected system font.
     */
    private fun patchSystemFontMap() {
        try {
            val sSystemFontMapField = Typeface::class.java.getDeclaredField("sSystemFontMap")
            sSystemFontMapField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val currentMap = sSystemFontMapField.get(null) as? Map<String, Typeface> ?: return
            val newMap = HashMap(currentMap)

            val normal = cachedNormal ?: Typeface.DEFAULT
            val bold = cachedBold ?: Typeface.DEFAULT_BOLD
            val italic = cachedItalic ?: normal
            val boldItalic = cachedBoldItalic ?: bold

            val fontFamilyKeys = listOf(
                "google_sans", "google-sans", "google_sans_regular", "google-sans-regular",
                "google_sans_medium", "google_sans_bold", "google-sans-medium", "google-sans-bold",
                "productsans", "product_sans", "product-sans",
                "productsans_regular", "productsans-regular",
                "productsans_medium", "productsans-medium",
                "productsans_bold", "productsans-bold",
                "productsans_black", "productsans-black",
                "productsans_light", "productsans-light",
                "productsans_thin", "productsans-thin",
                "productsans_italic", "productsans-italic",
                "productsans_bolditalic", "productsans-bolditalic",
                "netflix_sans", "netflix-sans",
                "open_sans", "open-sans",
                "poppins", "poppins_regular", "poppins-regular",
                "ubuntu_regular", "ubuntu-regular",
                "comic_sans", "consola", "futura", "gotham", "lucida_grande",
                "stix_general", "times_new_roman", "trebuchet_ms", "verdana"
            )

            for (key in fontFamilyKeys) {
                val isBoldKey = key.contains("bold") || key.contains("medium") || key.contains("black")
                val isItalicKey = key.contains("italic")
                val targetTf = when {
                    isBoldKey && isItalicKey -> boldItalic
                    isBoldKey -> bold
                    isItalicKey -> italic
                    else -> normal
                }
                newMap[key] = targetTf
            }

            // If user selected a custom font family style (Serif / Mono), redirect sans-serif aliases
            if (getFontStyle() != STYLE_DEFAULT) {
                newMap["sans-serif"] = normal
                newMap["sans-serif-medium"] = bold
                newMap["sans-serif-bold"] = bold
                newMap["sans-serif-black"] = bold
                newMap["sans-serif-light"] = normal
                newMap["sans-serif-thin"] = normal
                newMap["sans-serif-condensed"] = normal
            }

            try {
                val modifiersField = Field::class.java.getDeclaredField("accessFlags")
                modifiersField.isAccessible = true
                modifiersField.setInt(sSystemFontMapField, sSystemFontMapField.modifiers and Modifier.FINAL.inv())
            } catch (_: Throwable) {}

            sSystemFontMapField.set(null, Collections.unmodifiableMap(newMap))

            // Patch sDefaultTypeface if accessible
            try {
                val sDefaultTypefaceField = Typeface::class.java.getDeclaredField("sDefaultTypeface")
                sDefaultTypefaceField.isAccessible = true
                sDefaultTypefaceField.set(null, normal)
            } catch (_: Throwable) {}

            Log.d(TAG, "Typeface.sSystemFontMap patched successfully with ${fontFamilyKeys.size} font keys.")
        } catch (e: Throwable) {
            Log.d(TAG, "Typeface.sSystemFontMap patch fallback: ${e.message}")
        }
    }

    /**
     * Resolves the target Typeface matching the view's current style from the pre-computed cache.
     */
    fun resolveTargetTypeface(current: Typeface?, enabled: Boolean = isEnabled()): Typeface? {
        if (!enabled) {
            return current
        }

        val isBold = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && current != null) {
            current.weight >= 600 || current.isBold
        } else {
            (current?.style ?: 0) and Typeface.BOLD != 0
        }

        val isItalic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && current != null) {
            current.isItalic
        } else {
            (current?.style ?: 0) and Typeface.ITALIC != 0
        }

        return when {
            isBold && isItalic -> cachedBoldItalic
            isBold -> cachedBold
            isItalic -> cachedItalic
            else -> cachedNormal
        }
    }

    /**
     * Engine 3: WindowManagerGlobal Synchronous Hook.
     * Hooks into window addition so newly spawned Dialogs, Menus, and BottomSheets
     * are styled synchronously BEFORE initial measure and draw.
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
                    // Synchronous styling before window renders
                    applyToViewTree(element)
                    mainHandler.post {
                        applyToViewTree(element)
                    }
                    return result
                }

                override fun add(index: Int, element: View) {
                    super.add(index, element)
                    applyToViewTree(element)
                    mainHandler.post {
                        applyToViewTree(element)
                    }
                }
            }

            mViewsField.set(wmg, customViewsList)
            isWmgHooked = true
        } catch (_: Throwable) {}
    }

    /**
     * Engine 4: Early Activity Lifecycle Enforcement.
     * Styles decor views in onActivityCreated immediately after setContentView(),
     * BEFORE onStart or onResume, guaranteeing frame 0 is already in system font.
     */
    private fun registerLifecycleCallbacks(app: Application?) {
        if (app == null || isLifecycleRegistered) return

        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(activity)
                    hookFragmentActivity(activity)
                    // Apply immediately at the end of onCreate before drawing!
                    activity.window?.decorView?.let { applyToViewTree(it) }
                }

                override fun onActivityStarted(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    activity.window?.decorView?.let { applyToViewTree(it) }
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    activity.window?.decorView?.let { applyToViewTree(it) }
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
        } catch (_: Throwable) {}
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

                    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                        f.view?.let { applyToViewTree(it) }
                    }

                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        f.view?.let { applyToViewTree(it) }
                    }
                },
                true
            )
        }
    }

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
                applyToViewTree(v)
            }
        } catch (_: Throwable) {}

        findCurrentActivity()?.window?.decorView?.let {
            applyToViewTree(it)
        }
    }

    fun applyToViewTree(root: View?) {
        if (root == null) return
        traverseAndApply(root, isEnabled())
    }

    /**
     * High-speed, non-thrashing view traversal.
     * Uses TAG_APPLIED_CONFIG so views that have already been converted to the current
     * system font are NEVER touched again, preventing layout relayouts or font glitching!
     */
    private fun traverseAndApply(view: View, enabled: Boolean) {
        if (view is TextView) {
            val currentTag = view.getTag(TAG_APPLIED_CONFIG) as? Int

            if (enabled) {
                val currentTypeface = view.typeface
                val isAlreadyTarget = currentTypeface === cachedNormal ||
                        currentTypeface === cachedBold ||
                        currentTypeface === cachedItalic ||
                        currentTypeface === cachedBoldItalic

                if (currentTag == currentConfigToken && isAlreadyTarget) {
                    return
                }

                if (!originalTypefaces.containsKey(view)) {
                    originalTypefaces[view] = currentTypeface
                }

                val original = originalTypefaces[view]
                val target = resolveTargetTypeface(original, true)

                if (target != null && currentTypeface !== target) {
                    view.typeface = target
                }

                view.setTag(TAG_APPLIED_CONFIG, currentConfigToken)
            } else {
                if (currentTag != 0) {
                    if (originalTypefaces.containsKey(view)) {
                        view.typeface = originalTypefaces[view]
                    }
                    view.setTag(TAG_APPLIED_CONFIG, 0)
                }
            }
        }

        // Support for Compose root views (Settings, etc.)
        if (view.javaClass.name.contains("AndroidComposeView") || view.javaClass.name.contains("ComposeView")) {
            if (hookedComposeViews.add(view)) {
                view.invalidate()
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

    /**
     * Clears Compose internal typeface request caches.
     */
    private fun clearComposeCaches() {
        try {
            val resolverKtClass = Class.forName("androidx.compose.ui.text.font.FontFamilyResolverKt")
            for (fieldName in listOf("GlobalTypefaceRequestCache", "GlobalAsyncTypefaceCache")) {
                try {
                    val field = resolverKtClass.getDeclaredField(fieldName)
                    field.isAccessible = true
                    val cacheObj = field.get(null) ?: continue
                    for (f in cacheObj.javaClass.declaredFields) {
                        f.isAccessible = true
                        val inner = f.get(cacheObj) ?: continue
                        if (inner is androidx.collection.LruCache<*, *>) {
                            inner.evictAll()
                        }
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    private fun invalidateAndRefresh() {
        rebuildTypefaceCache()
        patchSystemFontMap()
        hookTypefaceCompatCache()
        clearComposeCaches()
        hookedComposeViews.clear()
        mainHandler.post {
            sweepAllCurrentWindows()
        }
    }

    /**
     * Non-intrusive background guardian checks for un-styled views every 3 seconds
     * without triggering layout passes on already-styled views.
     */
    private fun startGentleGuardian() {
        guardianJob?.cancel()
        guardianJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(3000)
                if (isEnabled()) {
                    mainHandler.post {
                        sweepAllCurrentWindows()
                    }
                }
            }
        }
    }
}
