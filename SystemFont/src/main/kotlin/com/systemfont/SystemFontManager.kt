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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
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
    private val hookedDecorViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val hookedComposeViews = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    @Volatile private var cachedNormal: Typeface? = null
    @Volatile private var cachedBold: Typeface? = null
    @Volatile private var cachedItalic: Typeface? = null
    @Volatile private var cachedBoldItalic: Typeface? = null
    @Volatile private var currentConfigToken: Int = 1

    // Jetpack Compose reflection caches
    private var composeResolverClass: Class<*>? = null
    private var composeFontFamilyClass: Class<*>? = null
    private var composeDefaultFontFamily: Any? = null
    private var composeSansSerifFontFamily: Any? = null
    private var composeSerifFontFamily: Any? = null
    private var composeMonospaceFontFamily: Any? = null

    fun init(context: Context) {
        val app = resolveApplication(context)
        appContext = app ?: context.applicationContext

        Log.d(TAG, "Initializing SystemFontManager.")

        initComposeReflection()
        rebuildTypefaceCache()
        patchSystemFontMap()
        hookWindowManagerGlobal()
        registerLifecycleCallbacks(app)

        mainHandler.post {
            sweepAllCurrentWindows()
        }

        startGentleGuardian()
    }

    fun cleanup() {
        guardianJob?.cancel()
        guardianJob = null
        hookedRecyclerViews.clear()
        hookedDecorViews.clear()
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

    private fun rebuildTypefaceCache() {
        val base = when (getFontStyle()) {
            STYLE_SANS_SERIF -> Typeface.SANS_SERIF
            STYLE_SERIF -> Typeface.SERIF
            STYLE_MONOSPACE -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }

        val boost = isWeightBoostEnabled()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val normalWeight = if (boost) 700 else 400
            val boldWeight = if (boost) 900 else 700

            cachedNormal = Typeface.create(base, normalWeight, false)
            cachedBold = Typeface.create(base, boldWeight, false)
            cachedItalic = Typeface.create(base, normalWeight, true)
            cachedBoldItalic = Typeface.create(base, boldWeight, true)
        } else {
            val normalStyle = if (boost) Typeface.BOLD else Typeface.NORMAL
            val boldStyle = Typeface.BOLD

            cachedNormal = Typeface.create(base, normalStyle)
            cachedBold = Typeface.create(base, boldStyle)
            cachedItalic = Typeface.create(base, if (boost) Typeface.BOLD_ITALIC else Typeface.ITALIC)
            cachedBoldItalic = Typeface.create(base, Typeface.BOLD_ITALIC)
        }

        currentConfigToken++
    }

    private fun patchSystemFontMap() {
        try {
            val sSystemFontMapField = Typeface::class.java.getDeclaredField("sSystemFontMap")
            sSystemFontMapField.isAccessible = true

            @Suppress("UNCHECKED_CAST")
            val currentMap = sSystemFontMapField.get(null) as? Map<String, Typeface> ?: return
            val newMap = HashMap(currentMap)

            val normal = cachedNormal ?: Typeface.DEFAULT
            val bold = cachedBold ?: Typeface.DEFAULT_BOLD

            newMap["google_sans"] = normal
            newMap["google-sans"] = normal
            newMap["google_sans_medium"] = bold
            newMap["google_sans_bold"] = bold
            newMap["google-sans-medium"] = bold
            newMap["google-sans-bold"] = bold

            try {
                val modifiersField = Field::class.java.getDeclaredField("accessFlags")
                modifiersField.isAccessible = true
                modifiersField.setInt(sSystemFontMapField, sSystemFontMapField.modifiers and Modifier.FINAL.inv())
            } catch (_: Throwable) {}

            sSystemFontMapField.set(null, Collections.unmodifiableMap(newMap))
            Log.d(TAG, "Typeface.sSystemFontMap successfully patched.")
        } catch (e: Throwable) {
            Log.d(TAG, "Typeface.sSystemFontMap patch fallback: ${e.message}")
        }
    }

    /**
     * Inspects Jetpack Compose classes via reflection to enable replacing Compose's
     * FontFamily with the Android device's System Font.
     */
    private fun initComposeReflection() {
        try {
            composeResolverClass = Class.forName("androidx.compose.ui.text.font.FontFamily\$Resolver")
            composeFontFamilyClass = Class.forName("androidx.compose.ui.text.font.FontFamily")

            val companionClass = Class.forName("androidx.compose.ui.text.font.FontFamily\$Companion")
            val companionField = composeFontFamilyClass?.getDeclaredField("Companion")
            companionField?.isAccessible = true
            val companion = companionField?.get(null)

            if (companion != null) {
                composeDefaultFontFamily = companionClass.getMethod("getDefault").invoke(companion)
                composeSansSerifFontFamily = companionClass.getMethod("getSansSerif").invoke(companion)
                composeSerifFontFamily = companionClass.getMethod("getSerif").invoke(companion)
                composeMonospaceFontFamily = companionClass.getMethod("getMonospace").invoke(companion)
                Log.d(TAG, "Jetpack Compose FontFamily reflection initialized successfully.")
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Compose reflection optional fallback: ${e.message}")
        }
    }

    private fun getComposeTargetFontFamily(): Any? {
        return when (getFontStyle()) {
            STYLE_SANS_SERIF -> composeSansSerifFontFamily ?: composeDefaultFontFamily
            STYLE_SERIF -> composeSerifFontFamily ?: composeDefaultFontFamily
            STYLE_MONOSPACE -> composeMonospaceFontFamily ?: composeDefaultFontFamily
            else -> composeDefaultFontFamily
        }
    }

    /**
     * Intercepts Jetpack Compose's AndroidComposeView.fontFamilyResolver.
     * Replaces any request for hardcoded Compose fonts with the device's System Font!
     */
    private fun hookComposeView(view: View) {
        val resolverClass = composeResolverClass ?: return
        val defaultFont = getComposeTargetFontFamily() ?: return

        try {
            var clazz: Class<*>? = view.javaClass
            while (clazz != null && clazz != Any::class.java) {
                for (field in clazz.declaredFields) {
                    field.isAccessible = true
                    val value = field.get(view) ?: continue

                    if (resolverClass.isInstance(value)) {
                        if (!Proxy.isProxyClass(value.javaClass)) {
                            val proxy = createComposeResolverProxy(resolverClass, value, defaultFont)
                            field.set(view, proxy)
                            Log.d(TAG, "Hooked Compose fontFamilyResolver on field ${field.name}")
                            view.invalidate()
                        }
                    } else if (field.name == "composeViewContext" || field.name.contains("context", ignoreCase = true)) {
                        for (innerField in value.javaClass.declaredFields) {
                            innerField.isAccessible = true
                            val innerVal = innerField.get(value) ?: continue
                            if (resolverClass.isInstance(innerVal) && !Proxy.isProxyClass(innerVal.javaClass)) {
                                val proxy = createComposeResolverProxy(resolverClass, innerVal, defaultFont)
                                innerField.set(value, proxy)
                                Log.d(TAG, "Hooked Compose fontFamilyResolver in context ${innerField.name}")
                                view.invalidate()
                            }
                        }
                    }
                }
                clazz = clazz.superclass
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Compose hook error: ${e.message}")
        }
    }

    private fun createComposeResolverProxy(
        resolverClass: Class<*>,
        originalResolver: Any,
        targetFontFamily: Any
    ): Any {
        return Proxy.newProxyInstance(
            originalResolver.javaClass.classLoader,
            arrayOf(resolverClass),
            object : InvocationHandler {
                override fun invoke(proxy: Any?, method: Method, args: Array<out Any>?): Any? {
                    if (method.name == "resolve" && args != null && args.isNotEmpty() && isEnabled()) {
                        @Suppress("UNCHECKED_CAST")
                        val mutableArgs = args as Array<Any?>
                        mutableArgs[0] = targetFontFamily
                    }
                    return method.invoke(originalResolver, *(args ?: emptyArray()))
                }
            }
        )
    }

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
                        applyToViewTree(element)
                    }
                    return result
                }

                override fun add(index: Int, element: View) {
                    super.add(index, element)
                    mainHandler.post {
                        applyToViewTree(element)
                    }
                }
            }

            mViewsField.set(wmg, customViewsList)
            isWmgHooked = true
        } catch (_: Throwable) {}
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

        currentActivityRef?.get()?.let {
            applyToViewTree(it.window.decorView)
        }
    }

    fun applyToViewTree(root: View?) {
        if (root == null) return
        traverseAndApply(root, isEnabled())
    }

    private fun traverseAndApply(view: View, enabled: Boolean) {
        // 1. Android View System (TextView, Button, EditText, Chip, etc.)
        if (view is TextView) {
            val currentTag = view.getTag(TAG_APPLIED_CONFIG) as? Int

            if (enabled) {
                if (currentTag == currentConfigToken) {
                    return
                }

                if (!originalTypefaces.containsKey(view)) {
                    originalTypefaces[view] = view.typeface
                }

                val original = originalTypefaces[view]
                val target = resolveTargetTypeface(original, true)

                if (target != null && view.typeface !== target) {
                    view.typeface = target
                }

                val boost = isWeightBoostEnabled()
                if (view.paint.isFakeBoldText != boost) {
                    view.paint.isFakeBoldText = boost
                    view.invalidate()
                }

                view.setTag(TAG_APPLIED_CONFIG, currentConfigToken)
            } else {
                if (currentTag != 0) {
                    if (originalTypefaces.containsKey(view)) {
                        view.typeface = originalTypefaces[view]
                    }
                    if (view.paint.isFakeBoldText) {
                        view.paint.isFakeBoldText = false
                        view.invalidate()
                    }
                    view.setTag(TAG_APPLIED_CONFIG, 0)
                }
            }
        }

        // 2. Jetpack Compose Screen Hook (Settings, TestScreen, etc.)
        if (view.javaClass.name.contains("AndroidComposeView") || view.javaClass.name.contains("ComposeView")) {
            if (hookedComposeViews.add(view)) {
                hookComposeView(view)
            }
        }

        // 3. View Groups & Lists
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
        hookedComposeViews.clear()
        mainHandler.post {
            sweepAllCurrentWindows()
        }
    }

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
