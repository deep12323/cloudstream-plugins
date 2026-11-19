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
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.collection.LruCache
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
    private var guardianJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val hookedRecyclerViews = Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>())
    private val hookedComposeViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val attachedLayoutViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    // Cached pre-computed Typeface references
    @Volatile private var cachedNormal: Typeface? = null
    @Volatile private var cachedBold: Typeface? = null
    @Volatile private var cachedItalic: Typeface? = null
    @Volatile private var cachedBoldItalic: Typeface? = null

    // Reference to Compose interceptor cache to allow instant invalidation on settings change
    @Volatile private var activeComposeLruCache: LruCache<Any, Any>? = null

    /**
     * Intercepting LruCache that replaces TypefaceCompat.sTypefaceCache.
     * Whenever AndroidX or AppCompat requests @font/google_sans or any other font,
     * this cache intercepts the query and IMMEDIATELY returns the System Font.
     */
    private class FontInterceptorLruCache(maxSize: Int) : LruCache<String, Typeface>(maxSize) {
        override fun create(key: String): Typeface? {
            if (!isEnabled()) return null
            val lower = key.lowercase()
            if (lower.contains("font") || lower.contains("sans") || lower.contains("product")) {
                val isBold = lower.contains("bold") || lower.contains("medium") || lower.contains("black") ||
                        lower.contains("700") || lower.contains("800")
                val isItalic = lower.contains("italic")

                return when {
                    isBold && isItalic -> cachedBoldItalic
                    isBold -> cachedBold
                    isItalic -> cachedItalic
                    else -> cachedNormal
                } ?: cachedNormal
            }
            return null
        }
    }

    /**
     * Deep Compose Interceptor LruCache replacing GlobalTypefaceRequestCache.resultCache.
     * In CloudStream 4 (Compose Multiplatform), all screens (Home, Search, Details, Settings)
     * query GlobalTypefaceRequestCache.runCached(TypefaceRequest) to render text.
     * By dynamically returning TypefaceResult.Immutable(systemTypeface) upon request,
     * Compose renders all text directly in the device's native System Font from frame 0!
     */
    private class ComposeFontInterceptorLruCache(maxSize: Int) : LruCache<Any, Any>(maxSize) {
        override fun create(key: Any): Any? {
            if (!isEnabled()) return null
            return createComposeTypefaceResult(key)
        }
    }

    fun init(context: Context) {
        val app = resolveApplication(context)
        appContext = app ?: context.applicationContext

        Log.d(TAG, "Initializing SystemFontManager v10 with deep Jetpack Compose & View engine.")

        try {
            rebuildTypefaceCache()
            patchSystemFontMap()
            hookTypefaceCompatCache()
            hookComposeTypefaceCache()
            registerLifecycleCallbacks(app)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in background setup: ${t.message}")
        }

        mainHandler.post {
            try {
                findCurrentActivity()?.let { act ->
                    currentActivityRef = WeakReference(act)
                    act.window?.decorView?.let { decor ->
                        applyToViewTree(decor)
                        attachContinuousLayoutObserver(decor)
                    }
                }
                sweepAllCurrentWindows()
            } catch (t: Throwable) {
                Log.e(TAG, "Error in initial Main Thread sweep: ${t.message}")
            }
        }

        startGentleGuardian()
    }

    fun cleanup() {
        guardianJob?.cancel()
        guardianJob = null
        hookedRecyclerViews.clear()
        hookedComposeViews.clear()
        attachedLayoutViews.clear()
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
    }

    /**
     * Engine 1 (Compose): Intercepts Jetpack Compose Multiplatform font resolution.
     * Replaces GlobalTypefaceRequestCache.resultCache with ComposeFontInterceptorLruCache.
     */
    private fun hookComposeTypefaceCache() {
        try {
            val classLoader = appContext?.classLoader ?: ClassLoader.getSystemClassLoader()
            val resolverKtClass = try {
                classLoader.loadClass("androidx.compose.ui.text.font.FontFamilyResolverKt")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.FontFamilyResolverKt")
            }

            val globalCacheField = resolverKtClass.getDeclaredField("GlobalTypefaceRequestCache")
            globalCacheField.isAccessible = true
            val globalCache = globalCacheField.get(null) ?: return

            val resultCacheField = globalCache.javaClass.declaredFields.firstOrNull { f ->
                LruCache::class.java.isAssignableFrom(f.type) || f.name.contains("resultCache")
            } ?: globalCache.javaClass.getDeclaredField("resultCache")
            resultCacheField.isAccessible = true

            val currentLru = resultCacheField.get(globalCache)
            if (currentLru !is ComposeFontInterceptorLruCache) {
                val interceptor = ComposeFontInterceptorLruCache(1024)
                resultCacheField.set(globalCache, interceptor)
                activeComposeLruCache = interceptor
                Log.d(TAG, "Compose GlobalTypefaceRequestCache successfully intercepted with ComposeFontInterceptorLruCache!")
            } else {
                activeComposeLruCache = currentLru
            }

            // Evict any async cache entries
            try {
                val asyncCacheField = resolverKtClass.getDeclaredField("GlobalAsyncTypefaceCache")
                asyncCacheField.isAccessible = true
                val asyncCache = asyncCacheField.get(null)
                if (asyncCache != null) {
                    for (f in asyncCache.javaClass.declaredFields) {
                        f.isAccessible = true
                        val map = f.get(asyncCache) ?: continue
                        val clearMethod = map.javaClass.methods.firstOrNull { it.name == "clear" || it.name == "evictAll" }
                        clearMethod?.invoke(map)
                    }
                }
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            Log.d(TAG, "Compose font cache hook fallback: ${t.message}")
        }
    }

    /**
     * Inspects a Compose TypefaceRequest object and resolves whether Bold or Italic is needed.
     */
    private fun resolveTypefaceFromComposeRequest(request: Any): Typeface {
        var isBold = false
        var isItalic = false

        try {
            for (field in request.javaClass.declaredFields) {
                field.isAccessible = true
                val value = field.get(request) ?: continue
                val typeName = field.type.name

                if (typeName.contains("FontWeight") || value.javaClass.name.contains("FontWeight")) {
                    val weightField = value.javaClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                    if (weightField != null) {
                        weightField.isAccessible = true
                        val weight = weightField.getInt(value)
                        if (weight >= 600) isBold = true
                    }
                } else if (typeName.contains("FontStyle") || value.javaClass.name.contains("FontStyle")) {
                    val str = value.toString().lowercase()
                    if (str.contains("italic") || str.contains("1")) {
                        isItalic = true
                    }
                }
            }
        } catch (_: Throwable) {}

        // Fallback string check
        val str = request.toString()
        if (!isBold && (str.contains("FontWeight(weight=700") || str.contains("FontWeight(weight=800") ||
                    str.contains("FontWeight(weight=900") || str.contains("W700") || str.contains("W800") ||
                    str.contains("W900") || str.contains("Bold") || str.contains("Medium"))) {
            isBold = true
        }
        if (!isItalic && (str.contains("Italic") || str.contains("FontStyle(value=1)"))) {
            isItalic = true
        }

        return when {
            isBold && isItalic -> cachedBoldItalic
            isBold -> cachedBold
            isItalic -> cachedItalic
            else -> cachedNormal
        } ?: cachedNormal ?: Typeface.DEFAULT
    }

    /**
     * Wraps the resolved System Typeface in a TypefaceResult.Immutable object for Jetpack Compose.
     */
    private fun createComposeTypefaceResult(request: Any): Any? {
        val tf = resolveTypefaceFromComposeRequest(request)
        return try {
            val classLoader = appContext?.classLoader ?: ClassLoader.getSystemClassLoader()
            val immutableClass = try {
                classLoader.loadClass("androidx.compose.ui.text.font.TypefaceResult\$Immutable")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.TypefaceResult\$Immutable")
            }

            val constructor = immutableClass.declaredConstructors.firstOrNull { c ->
                val p = c.parameterTypes
                (p.size == 2 && p[0] == Any::class.java) || (p.size == 1 && p[0] == Any::class.java)
            } ?: immutableClass.declaredConstructors.first()
            constructor.isAccessible = true

            if (constructor.parameterTypes.size == 2) {
                constructor.newInstance(tf, true)
            } else {
                constructor.newInstance(tf)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error creating TypefaceResult.Immutable: ${t.message}")
            null
        }
    }

    /**
     * Engine 2 (Android Views): Intercepts AndroidX TypefaceCompat.sTypefaceCache.
     */
    private fun hookTypefaceCompatCache() {
        try {
            val compatClass = Class.forName("androidx.core.graphics.TypefaceCompat")
            val cacheField = compatClass.getDeclaredField("sTypefaceCache")
            cacheField.isAccessible = true
            val currentCache = cacheField.get(null)

            if (currentCache !is FontInterceptorLruCache) {
                val interceptor = FontInterceptorLruCache(1024)
                prefillCache(interceptor)
                cacheField.set(null, interceptor)
                Log.d(TAG, "TypefaceCompat.sTypefaceCache replaced with FontInterceptorLruCache!")
            } else {
                prefillCache(currentCache)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "TypefaceCompat cache hook fallback: ${t.message}")
            try {
                val compatClass = Class.forName("androidx.core.graphics.TypefaceCompat")
                val cacheField = compatClass.getDeclaredField("sTypefaceCache")
                cacheField.isAccessible = true
                cacheField.get(null)?.let { prefillCache(it) }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Proactively pre-populates all known CloudStream font keys across styles and cookies.
     */
    private fun prefillCache(cacheObj: Any) {
        try {
            val putMethod = cacheObj.javaClass.getMethod("put", Any::class.java, Any::class.java)
            val normal = cachedNormal ?: Typeface.DEFAULT
            val bold = cachedBold ?: Typeface.DEFAULT_BOLD
            val pkg = appContext?.packageName ?: "com.lagradost.cloudstream3"

            val knownFonts = mutableListOf<Pair<String, Int>>()
            try {
                val rFontClass = appContext?.classLoader?.loadClass("com.lagradost.cloudstream3.R\$font")
                    ?: Class.forName("com.lagradost.cloudstream3.R\$font")
                for (field in rFontClass.fields) {
                    if (field.type == Int::class.javaPrimitiveType) {
                        knownFonts.add(field.name to field.getInt(null))
                    }
                }
            } catch (_: Throwable) {
                knownFonts.addAll(listOf(
                    "google_sans" to 2131296259,
                    "productsans_regular" to 2131296274,
                    "productsans_medium" to 2131296272,
                    "productsans_bold" to 2131296267,
                    "productsans_black" to 2131296265,
                    "netflix_sans" to 2131296262,
                    "open_sans" to 2131296263,
                    "poppins_regular" to 2131296264,
                    "ubuntu_regular" to 2131296281
                ))
            }

            for ((fontName, resId) in knownFonts) {
                val isBold = fontName.contains("bold") || fontName.contains("medium") || fontName.contains("black")
                val isItalic = fontName.contains("italic")
                val tf = when {
                    isBold && isItalic -> cachedBoldItalic ?: bold
                    isBold -> bold
                    isItalic -> cachedItalic ?: normal
                    else -> normal
                }

                for (cookie in 0..4) {
                    for (style in 0..3) {
                        putMethod.invoke(cacheObj, "$pkg-null-$cookie-$resId-$style", tf)
                        putMethod.invoke(cacheObj, "$pkg-res/font/$fontName.xml-$cookie-$resId-$style", tf)
                        putMethod.invoke(cacheObj, "$pkg-res/font/$fontName.ttf-$cookie-$resId-$style", tf)
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Maps hardcoded font family names to the target system font in Android's native font table.
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
                "ubuntu_regular", "ubuntu-regular"
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

            if (getFontStyle() != STYLE_DEFAULT) {
                newMap["sans-serif"] = normal
                newMap["sans-serif-medium"] = bold
                newMap["sans-serif-bold"] = bold
                newMap["sans-serif-black"] = bold
                newMap["sans-serif-light"] = normal
                newMap["sans-serif-thin"] = normal
                newMap["sans-serif-condensed"] = normal
            }

            sSystemFontMapField.set(null, Collections.unmodifiableMap(newMap))

            try {
                val sDefaultTypefaceField = Typeface::class.java.getDeclaredField("sDefaultTypeface")
                sDefaultTypefaceField.isAccessible = true
                sDefaultTypefaceField.set(null, normal)
            } catch (_: Throwable) {}

            Log.d(TAG, "Typeface.sSystemFontMap patched successfully.")
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
     * Attaches a continuous OnGlobalLayoutListener to the window.
     * Fires whenever the layout changes (navigating tabs, loading movie details, opening search),
     * applying the font BEFORE the frame reaches the screen.
     */
    private fun attachContinuousLayoutObserver(view: View) {
        if (!attachedLayoutViews.add(view)) return
        try {
            val vto = view.viewTreeObserver
            if (vto == null || !vto.isAlive) return

            val viewRef = WeakReference(view)
            val listener = ViewTreeObserver.OnGlobalLayoutListener {
                val root = viewRef.get()
                if (root != null) {
                    applyToViewTree(root)
                }
            }
            vto.addOnGlobalLayoutListener(listener)
        } catch (_: Throwable) {}
    }

    /**
     * Lifecycle enforcer hooking activities and fragments continuously as the user navigates.
     */
    private fun registerLifecycleCallbacks(app: Application?) {
        if (app == null || isLifecycleRegistered) return

        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(activity)
                    hookFragmentActivity(activity)
                    mainHandler.post {
                        activity.window?.decorView?.let { decor ->
                            applyToViewTree(decor)
                            attachContinuousLayoutObserver(decor)
                        }
                    }
                }

                override fun onActivityStarted(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    mainHandler.post {
                        activity.window?.decorView?.let { decor ->
                            applyToViewTree(decor)
                            attachContinuousLayoutObserver(decor)
                        }
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    mainHandler.post {
                        activity.window?.decorView?.let { decor ->
                            applyToViewTree(decor)
                            attachContinuousLayoutObserver(decor)
                        }
                    }
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
                        attachContinuousLayoutObserver(v)
                    }

                    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                        f.view?.let {
                            applyToViewTree(it)
                            attachContinuousLayoutObserver(it)
                        }
                    }

                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        f.view?.let {
                            applyToViewTree(it)
                            attachContinuousLayoutObserver(it)
                        }
                    }
                },
                true
            )
        }
    }

    fun sweepAllCurrentWindows() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { sweepAllCurrentWindows() }
            return
        }

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
                attachContinuousLayoutObserver(v)
            }
        } catch (_: Throwable) {}

        findCurrentActivity()?.window?.decorView?.let { decor ->
            applyToViewTree(decor)
            attachContinuousLayoutObserver(decor)
        }
    }

    fun applyToViewTree(root: View?) {
        if (root == null) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { applyToViewTree(root) }
            return
        }
        traverseAndApply(root, isEnabled())
    }

    /**
     * Non-thrashing recursive view hierarchy traversal.
     * Uses strict instance equality (`===`) so views that already have the target
     * system font are NEVER touched again, preventing layout invalidations or flickering.
     */
    private fun traverseAndApply(view: View, enabled: Boolean) {
        if (view is TextView) {
            val currentTypeface = view.typeface

            if (enabled) {
                val target = resolveTargetTypeface(currentTypeface, true)

                if (target != null && currentTypeface !== target) {
                    if (!originalTypefaces.containsKey(view)) {
                        originalTypefaces[view] = currentTypeface
                    }
                    view.typeface = target
                }
            } else {
                if (originalTypefaces.containsKey(view)) {
                    view.typeface = originalTypefaces[view]
                }
            }
        }

        // Support for Jetpack Compose root views (AndroidComposeView, etc.)
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

    private fun invalidateAndRefresh() {
        rebuildTypefaceCache()
        patchSystemFontMap()
        hookTypefaceCompatCache()
        activeComposeLruCache?.evictAll()
        hookComposeTypefaceCache()
        hookedComposeViews.forEach { it.invalidate() }
        mainHandler.post {
            sweepAllCurrentWindows()
        }
    }

    /**
     * Background guardian checks for un-styled views every 1 second as an extra safety backstop.
     */
    private fun startGentleGuardian() {
        guardianJob?.cancel()
        guardianJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1000)
                if (isEnabled()) {
                    mainHandler.post {
                        sweepAllCurrentWindows()
                    }
                }
            }
        }
    }
}
