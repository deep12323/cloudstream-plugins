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
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
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
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

/**
 * SystemFontManager v12 - Ultimate Zero-Glitch System Font Engine
 *
 * Eliminates all visual font transitions and flickers by intercepting views at the
 * exact point of creation and pre-draw:
 * 1. Pre-Inflation Shield (LayoutInflater.Factory2): Injects target typeface immediately
 *    during XML view creation, before views are added to the hierarchy, measured, or drawn.
 * 2. Pre-Draw Gate (OnPreDrawListener): Intercepts the window right before the GPU draws
 *    the display buffer, guaranteeing that frame 0 is rendered with the system font.
 * 3. Immediate Hierarchy Attachment (OnHierarchyChangeListener): Catches dynamic child views
 *    and ComposeView / AndroidComposeView instances the microsecond they are attached.
 * 4. Deep Compose Multiplatform Resolver Proxy: Injects zero-latency resolver proxy into
 *    AndroidComposeView.fontFamilyResolver before the first composition pass.
 * 5. TypefaceCompat & Native System Font Map Hooks: Fallback anchors for any legacy requests.
 */
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
    private val hookedInflaters = Collections.newSetFromMap(WeakHashMap<LayoutInflater, Boolean>())
    private val hookedHierarchyContainers = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    // Cached pre-computed Typeface references
    @Volatile private var cachedNormal: Typeface? = null
    @Volatile private var cachedBold: Typeface? = null
    @Volatile private var cachedItalic: Typeface? = null
    @Volatile private var cachedBoldItalic: Typeface? = null

    // Reference to Compose interceptor cache to allow instant invalidation on settings change
    @Volatile private var activeComposeLruCache: LruCache<Any, Any>? = null

    // Cached reflection references for zero-overhead font resolution
    @Volatile private var immutableConstructor: Constructor<*>? = null
    @Volatile private var fontFamilyClass: Class<*>? = null
    @Volatile private var resolverInterfaceClass: Class<*>? = null
    @Volatile private var interceptorInterfaceClass: Class<*>? = null
    @Volatile private var platformResolveInterceptorProxy: Any? = null

    // Cached fields on TypefaceRequest to avoid repeated reflective scans
    @Volatile private var trWeightField: Field? = null
    @Volatile private var trStyleField: Field? = null
    @Volatile private var trReflectionInitialized = false

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
     * Evaluates TypefaceRequests in 0ms, returning pre-computed Immutable TypefaceResults.
     */
    private class ComposeFontInterceptorLruCache(maxSize: Int) : LruCache<Any, Any>(maxSize) {
        override fun create(key: Any): Any? {
            if (!isEnabled()) return null
            return createComposeTypefaceResultFromRequest(key)
        }
    }

    fun init(context: Context) {
        val app = resolveApplication(context)
        appContext = app ?: context.applicationContext

        Log.d(TAG, "Initializing SystemFontManager v12 with Pre-Inflation Shield and Zero-Glitch Engine.")

        try {
            rebuildTypefaceCache()
            patchSystemFontMap()
            hookTypefaceCompatCache()
            initComposeReflection()
            hookPlatformResolveInterceptorDefault()
            hookComposeTypefaceCache()
            registerLifecycleCallbacks(app)
        } catch (t: Throwable) {
            Log.e(TAG, "Error in background setup: ${t.message}")
        }

        mainHandler.post {
            try {
                findCurrentActivity()?.let { act ->
                    currentActivityRef = WeakReference(act)
                    hookActivityLayoutInflater(act)
                    act.window?.decorView?.let { decor ->
                        applyToViewTree(decor)
                        attachContinuousLayoutObserver(decor)
                    }
                }
                sweepAllComposeViews()
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
        hookedInflaters.clear()
        hookedHierarchyContainers.clear()
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
     * Initializes reflection handles for Compose text classes once.
     */
    private fun initComposeReflection() {
        val classLoader = appContext?.classLoader ?: ClassLoader.getSystemClassLoader()
        try {
            fontFamilyClass = try {
                classLoader.loadClass("androidx.compose.ui.text.font.FontFamily")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.FontFamily")
            }
        } catch (_: Throwable) {}

        try {
            resolverInterfaceClass = try {
                classLoader.loadClass("androidx.compose.ui.text.font.FontFamily\$Resolver")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.FontFamily\$Resolver")
            }
        } catch (_: Throwable) {}

        try {
            interceptorInterfaceClass = try {
                classLoader.loadClass("androidx.compose.ui.text.font.PlatformResolveInterceptor")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.PlatformResolveInterceptor")
            }
        } catch (_: Throwable) {}

        try {
            val immutableClass = try {
                classLoader.loadClass("androidx.compose.ui.text.font.TypefaceResult\$Immutable")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.TypefaceResult\$Immutable")
            }
            immutableConstructor = immutableClass.getDeclaredConstructor(Any::class.java, Boolean::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        } catch (_: Throwable) {
            try {
                val immutableClass = Class.forName("androidx.compose.ui.text.font.TypefaceResult\$Immutable")
                immutableConstructor = immutableClass.declaredConstructors.firstOrNull { it.parameterTypes.size == 2 }?.apply {
                    isAccessible = true
                }
            } catch (_: Throwable) {}
        }

        buildPlatformResolveInterceptorProxy()
    }

    /**
     * Builds our dynamic proxy implementing Compose's PlatformResolveInterceptor.
     */
    private fun buildPlatformResolveInterceptorProxy() {
        val iface = interceptorInterfaceClass ?: return
        try {
            platformResolveInterceptorProxy = Proxy.newProxyInstance(
                iface.classLoader,
                arrayOf(iface),
                ZeroGlitchPlatformResolveInterceptorInvocationHandler()
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Error building PlatformResolveInterceptor proxy: ${t.message}")
        }
    }

    /**
     * Resolves the target Compose FontFamily instance based on current user preference.
     */
    private fun getTargetComposeFontFamily(): Any? {
        val ffc = fontFamilyClass ?: return null
        return try {
            val companionField = ffc.getDeclaredField("Companion").apply { isAccessible = true }
            val companion = companionField.get(null) ?: return null
            val methodName = when (getFontStyle()) {
                STYLE_SANS_SERIF -> "getSansSerif"
                STYLE_SERIF -> "getSerif"
                STYLE_MONOSPACE -> "getMonospace"
                else -> "getDefault"
            }
            companion.javaClass.getMethod(methodName).invoke(companion)
        } catch (_: Throwable) {
            try {
                val fieldName = when (getFontStyle()) {
                    STYLE_SANS_SERIF -> "SansSerif"
                    STYLE_SERIF -> "Serif"
                    STYLE_MONOSPACE -> "Monospace"
                    else -> "Default"
                }
                ffc.getDeclaredField(fieldName).apply { isAccessible = true }.get(null)
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * InvocationHandler for PlatformResolveInterceptor proxy.
     */
    private class ZeroGlitchPlatformResolveInterceptorInvocationHandler : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val name = method.name
            if (name == "interceptFontFamily") {
                if (!isEnabled()) return args?.getOrNull(0)
                val target = getTargetComposeFontFamily()
                return target ?: args?.getOrNull(0)
            }

            if (name == "interceptFontWeight") {
                val weight = args?.getOrNull(0)
                if (isWeightBoostEnabled() && weight != null) {
                    return boostFontWeightObject(weight)
                }
                return weight
            }

            if (name.contains("interceptFontStyle")) {
                return args?.getOrNull(0) ?: 0
            }

            if (name.contains("interceptFontSynthesis")) {
                return args?.getOrNull(0) ?: 0
            }

            if (name == "equals") {
                return args?.getOrNull(0) === proxy
            }
            if (name == "hashCode") {
                return System.identityHashCode(proxy)
            }
            if (name == "toString") {
                return "ZeroGlitchPlatformResolveInterceptorProxy"
            }

            return null
        }
    }

    private fun boostFontWeightObject(weightObj: Any): Any {
        try {
            val field = weightObj.javaClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
            if (field != null) {
                field.isAccessible = true
                val currentW = field.getInt(weightObj)
                val targetW = if (currentW >= 600) 850 else 550
                val constructor = weightObj.javaClass.getConstructor(Int::class.javaPrimitiveType)
                return constructor.newInstance(targetW)
            }
        } catch (_: Throwable) {}
        return weightObj
    }

    /**
     * Sets PlatformResolveInterceptor.Companion.Default to our interceptor proxy.
     */
    private fun hookPlatformResolveInterceptorDefault() {
        val proxy = platformResolveInterceptorProxy ?: return
        try {
            val classLoader = appContext?.classLoader ?: ClassLoader.getSystemClassLoader()
            val companionClass = try {
                classLoader.loadClass("androidx.compose.ui.text.font.PlatformResolveInterceptor\$Companion")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.PlatformResolveInterceptor\$Companion")
            }

            val defaultField = companionClass.declaredFields.firstOrNull {
                it.name == "Default" || it.type.name.contains("PlatformResolveInterceptor")
            }
            if (defaultField != null) {
                defaultField.isAccessible = true
                defaultField.set(null, proxy)
                Log.d(TAG, "PlatformResolveInterceptor.Companion.Default hooked successfully!")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "PlatformResolveInterceptor.Companion.Default fallback: ${t.message}")
        }
    }

    /**
     * Patches the internal platformResolveInterceptor field inside a FontFamilyResolverImpl instance.
     */
    private fun patchPlatformResolveInterceptor(resolverInstance: Any) {
        val proxy = platformResolveInterceptorProxy ?: return
        try {
            val field = resolverInstance.javaClass.declaredFields.firstOrNull {
                it.name == "platformResolveInterceptor" || it.type.name.contains("PlatformResolveInterceptor")
            }
            if (field != null) {
                field.isAccessible = true
                field.set(resolverInstance, proxy)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Engine 1 (Compose): Intercepts Jetpack Compose Multiplatform font resolution.
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
                try {
                    val interceptor = ComposeFontInterceptorLruCache(1024)
                    resultCacheField.set(globalCache, interceptor)
                    activeComposeLruCache = interceptor
                    Log.d(TAG, "Compose GlobalTypefaceRequestCache successfully intercepted!")
                } catch (_: Throwable) {
                    // Internal type mismatch on some Compose Multiplatform builds, handled gracefully
                }
            } else {
                activeComposeLruCache = currentLru
            }

            evictAsyncTypefaceCache(resolverKtClass)
        } catch (t: Throwable) {
            Log.d(TAG, "Compose font cache hook fallback: ${t.message}")
        }
    }

    private fun evictAsyncTypefaceCache(resolverKtClass: Class<*>? = null) {
        try {
            val classLoader = appContext?.classLoader ?: ClassLoader.getSystemClassLoader()
            val clazz = resolverKtClass ?: try {
                classLoader.loadClass("androidx.compose.ui.text.font.FontFamilyResolverKt")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.text.font.FontFamilyResolverKt")
            }

            val asyncCacheField = clazz.getDeclaredField("GlobalAsyncTypefaceCache")
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
    }

    /**
     * Directly injects our zero-glitch FontFamily.Resolver dynamic proxy into an AndroidComposeView.
     */
    fun hookAndroidComposeView(view: View) {
        if (!view.javaClass.name.contains("AndroidComposeView")) return
        try {
            val resolverField = view.javaClass.declaredFields.firstOrNull {
                it.name == "fontFamilyResolver" || it.name.contains("fontFamilyResolver")
            } ?: return
            resolverField.isAccessible = true
            val state = resolverField.get(view) ?: return

            val getValueMethod = state.javaClass.getMethod("getValue")
            val setValueMethod = state.javaClass.getMethod("setValue", Any::class.java)
            val currentResolver = getValueMethod.invoke(state) ?: return

            patchPlatformResolveInterceptor(currentResolver)

            if (!Proxy.isProxyClass(currentResolver.javaClass)) {
                val iface = resolverInterfaceClass ?: try {
                    appContext?.classLoader?.loadClass("androidx.compose.ui.text.font.FontFamily\$Resolver")
                } catch (_: Throwable) {
                    Class.forName("androidx.compose.ui.text.font.FontFamily\$Resolver")
                }

                if (iface != null) {
                    val proxy = Proxy.newProxyInstance(
                        iface.classLoader,
                        arrayOf(iface),
                        ZeroGlitchFontResolverInvocationHandler(currentResolver)
                    )
                    setValueMethod.invoke(state, proxy)
                    Log.d(TAG, "Injected ZeroGlitchFontResolver into AndroidComposeView!")
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "hookAndroidComposeView fallback: ${t.message}")
        }
    }

    /**
     * Dynamic proxy InvocationHandler for FontFamily.Resolver.
     */
    private class ZeroGlitchFontResolverInvocationHandler(
        private val delegate: Any
    ) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            val name = method.name
            if (!isEnabled()) {
                return method.invoke(delegate, *(args ?: emptyArray()))
            }

            if (name.contains("resolve") || name.contains("mo3408")) {
                val weightArg = args?.getOrNull(1)
                val styleArg = args?.getOrNull(2)
                val targetTf = resolveTargetTypefaceFromArgs(weightArg, styleArg)
                val result = createComposeTypefaceResultDirect(targetTf)
                if (result != null) {
                    return result
                }
                return method.invoke(delegate, *(args ?: emptyArray()))
            }

            if (name == "preload") {
                return Unit
            }

            if (name == "equals") {
                return args?.getOrNull(0) === proxy
            }
            if (name == "hashCode") {
                return System.identityHashCode(proxy)
            }
            if (name == "toString") {
                return "ZeroGlitchSystemFontResolverProxy"
            }

            return method.invoke(delegate, *(args ?: emptyArray()))
        }
    }

    private fun resolveTargetTypefaceFromArgs(weightObj: Any?, styleObj: Any?): Typeface {
        var isBold = false
        var isItalic = false

        if (weightObj != null) {
            val w = when (weightObj) {
                is Int -> weightObj
                is Number -> weightObj.toInt()
                else -> {
                    try {
                        val field = weightObj.javaClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                        if (field != null) {
                            field.isAccessible = true
                            field.getInt(weightObj)
                        } else 400
                    } catch (_: Throwable) { 400 }
                }
            }
            if (w >= 600) isBold = true
        }

        if (styleObj != null) {
            val s = when (styleObj) {
                is Int -> styleObj
                is Number -> styleObj.toInt()
                else -> {
                    try {
                        val field = styleObj.javaClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                        if (field != null) {
                            field.isAccessible = true
                            field.getInt(styleObj)
                        } else 0
                    } catch (_: Throwable) { 0 }
                }
            }
            if (s == 1) isItalic = true
        }

        return when {
            isBold && isItalic -> cachedBoldItalic
            isBold -> cachedBold
            isItalic -> cachedItalic
            else -> cachedNormal
        } ?: cachedNormal ?: Typeface.DEFAULT
    }

    private fun resolveTypefaceFromComposeRequest(request: Any): Typeface {
        var isBold = false
        var isItalic = false

        try {
            if (!trReflectionInitialized) {
                for (f in request.javaClass.declaredFields) {
                    f.isAccessible = true
                    val typeName = f.type.name
                    if (typeName.contains("FontWeight") || f.name.contains("weight", ignoreCase = true)) {
                        trWeightField = f
                    } else if (f.type == Int::class.javaPrimitiveType && f.name.contains("style", ignoreCase = true)) {
                        trStyleField = f
                    }
                }
                trReflectionInitialized = true
            }

            val wVal = trWeightField?.get(request)
            if (wVal != null) {
                if (wVal is Number) {
                    if (wVal.toInt() >= 600) isBold = true
                } else {
                    val intField = wVal.javaClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType }
                    if (intField != null) {
                        intField.isAccessible = true
                        if (intField.getInt(wVal) >= 600) isBold = true
                    }
                }
            }

            val sVal = trStyleField?.getInt(request)
            if (sVal != null && sVal == 1) {
                isItalic = true
            }
        } catch (_: Throwable) {}

        if (!isBold || !isItalic) {
            val str = request.toString()
            if (!isBold && (str.contains("weight=700") || str.contains("weight=800") ||
                        str.contains("weight=900") || str.contains("W700") || str.contains("W800") ||
                        str.contains("Bold") || str.contains("Medium"))) {
                isBold = true
            }
            if (!isItalic && (str.contains("Italic") || str.contains("value=1"))) {
                isItalic = true
            }
        }

        return when {
            isBold && isItalic -> cachedBoldItalic
            isBold -> cachedBold
            isItalic -> cachedItalic
            else -> cachedNormal
        } ?: cachedNormal ?: Typeface.DEFAULT
    }

    private fun createComposeTypefaceResultFromRequest(request: Any): Any? {
        val tf = resolveTypefaceFromComposeRequest(request)
        return createComposeTypefaceResultDirect(tf)
    }

    private fun createComposeTypefaceResultDirect(tf: Typeface): Any? {
        return try {
            if (immutableConstructor == null) {
                initComposeReflection()
            }
            val constructor = immutableConstructor ?: return null
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

    fun sweepAllComposeViews() {
        try {
            val classLoader = appContext?.classLoader ?: ClassLoader.getSystemClassLoader()
            val acvClass = try {
                classLoader.loadClass("androidx.compose.ui.platform.AndroidComposeView")
            } catch (_: Throwable) {
                Class.forName("androidx.compose.ui.platform.AndroidComposeView")
            }

            val composeViewsField = acvClass.declaredFields.firstOrNull {
                it.name == "composeViews" || it.type.name.contains("ObjectList")
            }
            if (composeViewsField != null) {
                composeViewsField.isAccessible = true
                val list = composeViewsField.get(null)
                if (list != null) {
                    val contentField = list.javaClass.declaredFields.firstOrNull { it.type.isArray }
                        ?: list.javaClass.superclass?.declaredFields?.firstOrNull { it.type.isArray }
                    val sizeField = list.javaClass.declaredFields.firstOrNull { it.type == Int::class.javaPrimitiveType && it.name.contains("size") }
                        ?: list.javaClass.superclass?.declaredFields?.firstOrNull { it.type == Int::class.javaPrimitiveType && it.name.contains("size") }

                    if (contentField != null && sizeField != null) {
                        contentField.isAccessible = true
                        sizeField.isAccessible = true
                        val content = contentField.get(list) as? Array<*>
                        val size = sizeField.getInt(list)
                        if (content != null) {
                            for (i in 0 until minOf(size, content.size)) {
                                val v = content[i] as? View ?: continue
                                hookAndroidComposeView(v)
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // PILLAR 1: PRE-INFLATION SHIELD (LayoutInflater.Factory2 Interceptor)
    // =========================================================================

    /**
     * Hooks an Activity's LayoutInflater with our ZeroGlitchLayoutFactory.
     * Intercepts views at the exact microsecond they are inflated from XML,
     * setting the system font BEFORE they are added to parents, measured, or drawn!
     */
    fun hookActivityLayoutInflater(activity: Activity) {
        try {
            val inflater = activity.layoutInflater
            hookLayoutInflater(inflater)
        } catch (t: Throwable) {
            Log.e(TAG, "Error hooking activity layout inflater: ${t.message}")
        }
    }

    fun hookLayoutInflater(inflater: LayoutInflater) {
        if (!hookedInflaters.add(inflater)) return

        val originalFactory2 = inflater.factory2
        if (originalFactory2 is ZeroGlitchLayoutFactory) return

        try {
            val mFactorySetField = LayoutInflater::class.java.getDeclaredField("mFactorySet")
            mFactorySetField.isAccessible = true
            mFactorySetField.setBoolean(inflater, false)
        } catch (_: Throwable) {}

        val customFactory = ZeroGlitchLayoutFactory(originalFactory2, inflater)
        try {
            inflater.factory2 = customFactory
            Log.d(TAG, "ZeroGlitchLayoutFactory successfully hooked into LayoutInflater!")
        } catch (_: Throwable) {
            try {
                val mFactory2Field = LayoutInflater::class.java.getDeclaredField("mFactory2")
                mFactory2Field.isAccessible = true
                mFactory2Field.set(inflater, customFactory)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Intercepts view creation during XML layout inflation in 0ms.
     */
    private class ZeroGlitchLayoutFactory(
        private val delegate: LayoutInflater.Factory2?,
        private val inflater: LayoutInflater
    ) : LayoutInflater.Factory2 {

        override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
            val view = delegate?.onCreateView(parent, name, context, attrs)
                ?: tryFallbackCreateView(name, context, attrs)
            if (view != null) {
                applyFontInstantly(view)
            }
            return view
        }

        override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
            val view = delegate?.onCreateView(name, context, attrs)
                ?: tryFallbackCreateView(name, context, attrs)
            if (view != null) {
                applyFontInstantly(view)
            }
            return view
        }

        private fun tryFallbackCreateView(name: String, context: Context, attrs: AttributeSet): View? {
            return try {
                if (-1 == name.indexOf('.')) {
                    when (name) {
                        "TextView", "Button", "EditText", "AutoCompleteTextView",
                        "MultiAutoCompleteTextView", "CheckBox", "RadioButton",
                        "ToggleButton", "CheckedTextView" -> inflater.createView(name, "android.widget.", attrs)
                        else -> null
                    }
                } else {
                    inflater.createView(name, null, attrs)
                }
            } catch (_: Throwable) {
                null
            }
        }

        private fun applyFontInstantly(view: View) {
            if (!isEnabled()) return

            if (view is TextView) {
                val current = view.typeface
                val target = resolveTargetTypeface(current, true)
                if (target != null && current !== target) {
                    if (!originalTypefaces.containsKey(view)) {
                        originalTypefaces[view] = current
                    }
                    view.typeface = target
                }
            }

            if (view is ViewGroup) {
                attachHierarchyListener(view)
            }
        }
    }

    // =========================================================================
    // PILLAR 2: IMMEDIATE HIERARCHY INTERCEPTION (OnHierarchyChangeListener)
    // =========================================================================

    /**
     * Attaches an OnHierarchyChangeListener to a ViewGroup so any newly attached child
     * view (including RecyclerView items and ComposeView) is intercepted immediately.
     */
    fun attachHierarchyListener(group: ViewGroup) {
        if (!hookedHierarchyContainers.add(group)) return

        try {
            group.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) {
                    if (child == null || !isEnabled()) return

                    if (child is TextView) {
                        val current = child.typeface
                        val target = resolveTargetTypeface(current, true)
                        if (target != null && current !== target) {
                            if (!originalTypefaces.containsKey(child)) {
                                originalTypefaces[child] = current
                            }
                            child.typeface = target
                        }
                    }

                    if (child.javaClass.name.contains("AndroidComposeView")) {
                        hookAndroidComposeView(child)
                    }

                    if (child is ViewGroup) {
                        attachHierarchyListener(child)
                        traverseAndApply(child, isEnabled())
                    }
                }

                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // PILLAR 3: PRE-DRAW GATE (OnPreDrawListener)
    // =========================================================================

    /**
     * Attaches continuous PreDraw and Layout observers to the Window's DecorView.
     * OnPreDrawListener executes immediately before GPU rendering, guaranteeing that
     * no frame can ever be drawn with the old font!
     */
    private fun attachContinuousLayoutObserver(view: View) {
        if (!attachedLayoutViews.add(view)) return
        try {
            val vto = view.viewTreeObserver
            if (vto == null || !vto.isAlive) return

            val viewRef = WeakReference(view)

            // 1. PreDraw Gate: Guarantees frame 0 rendering
            vto.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val root = viewRef.get()
                    if (root != null && isEnabled()) {
                        applyToViewTree(root)
                        sweepAllComposeViews()
                    }
                    return true
                }
            })

            // 2. Global Layout Observer: Backstop for subsequent layout passes
            vto.addOnGlobalLayoutListener {
                val root = viewRef.get()
                if (root != null) {
                    applyToViewTree(root)
                    sweepAllComposeViews()
                }
            }
        } catch (_: Throwable) {}
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
                    "productsans_light" to 2131296270,
                    "productsans_thin" to 2131296275,
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
     * Lifecycle enforcer hooking activities, fragments, and layout inflaters continuously.
     */
    private fun registerLifecycleCallbacks(app: Application?) {
        if (app == null || isLifecycleRegistered) return

        try {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(activity)
                    hookActivityLayoutInflater(activity)
                    hookFragmentActivity(activity)
                    mainHandler.post {
                        activity.window?.decorView?.let { decor ->
                            if (decor is ViewGroup) attachHierarchyListener(decor)
                            applyToViewTree(decor)
                            attachContinuousLayoutObserver(decor)
                        }
                        sweepAllComposeViews()
                    }
                }

                override fun onActivityStarted(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    hookActivityLayoutInflater(activity)
                    mainHandler.post {
                        activity.window?.decorView?.let { decor ->
                            if (decor is ViewGroup) attachHierarchyListener(decor)
                            applyToViewTree(decor)
                            attachContinuousLayoutObserver(decor)
                        }
                        sweepAllComposeViews()
                    }
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    hookActivityLayoutInflater(activity)
                    mainHandler.post {
                        activity.window?.decorView?.let { decor ->
                            if (decor is ViewGroup) attachHierarchyListener(decor)
                            applyToViewTree(decor)
                            attachContinuousLayoutObserver(decor)
                        }
                        sweepAllComposeViews()
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
                        if (v is ViewGroup) attachHierarchyListener(v)
                        applyToViewTree(v)
                        attachContinuousLayoutObserver(v)
                        sweepAllComposeViews()
                    }

                    override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
                        f.view?.let {
                            if (it is ViewGroup) attachHierarchyListener(it)
                            applyToViewTree(it)
                            attachContinuousLayoutObserver(it)
                            sweepAllComposeViews()
                        }
                    }

                    override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
                        f.view?.let {
                            if (it is ViewGroup) attachHierarchyListener(it)
                            applyToViewTree(it)
                            attachContinuousLayoutObserver(it)
                            sweepAllComposeViews()
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
                if (v is ViewGroup) attachHierarchyListener(v)
                applyToViewTree(v)
                attachContinuousLayoutObserver(v)
            }
        } catch (_: Throwable) {}

        findCurrentActivity()?.let { act ->
            hookActivityLayoutInflater(act)
            act.window?.decorView?.let { decor ->
                if (decor is ViewGroup) attachHierarchyListener(decor)
                applyToViewTree(decor)
                attachContinuousLayoutObserver(decor)
            }
        }

        sweepAllComposeViews()
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
     * Uses strict instance equality (===) so views that already have the target
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

        if (view.javaClass.name.contains("AndroidComposeView") || view.javaClass.name.contains("ComposeView")) {
            if (hookedComposeViews.add(view)) {
                hookAndroidComposeView(view)
            }
        }

        if (view is ViewGroup) {
            attachHierarchyListener(view)

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
        buildPlatformResolveInterceptorProxy()
        hookPlatformResolveInterceptorDefault()
        activeComposeLruCache?.evictAll()
        hookComposeTypefaceCache()
        sweepAllComposeViews()
        mainHandler.post {
            findCurrentActivity()?.let { hookActivityLayoutInflater(it) }
            sweepAllCurrentWindows()
            hookedComposeViews.forEach { it.invalidate() }
        }
    }

    /**
     * Gentle background guardian runs as a backstop.
     */
    private fun startGentleGuardian() {
        guardianJob?.cancel()
        guardianJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(2500)
                if (isEnabled()) {
                    mainHandler.post {
                        sweepAllComposeViews()
                        sweepAllCurrentWindows()
                    }
                }
            }
        }
    }
}
