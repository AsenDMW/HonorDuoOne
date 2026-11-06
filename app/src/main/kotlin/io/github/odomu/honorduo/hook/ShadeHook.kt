/*
 * Copyright (C) 2026 odomu and HonorDuo contributors
 *
 * This file is part of HonorDuo, a fork of HyperDuo, released under the GNU
 * General Public License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.odomu.honorduo.hook

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewParent
import android.view.ViewTreeObserver
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.Prefs
import io.github.odomu.honorduo.model.TrioAppearance
import io.github.odomu.honorduo.model.TrioConfig
import io.github.odomu.honorduo.render.WeatherIconDrawer
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * The notification shade (left pull) and the control center (right pull): the two
 * header buttons the module can take away, and the extra readings it can print on
 * the shade's date line.
 *
 * Both panels are Compose hosts on MagicOS, so nothing here touches a text view:
 * the buttons are ordinary views embedded through `AndroidView`, and the date line
 * is produced by one instance method the panel re-reads on every recomposition.
 */
internal object ShadeHook {

    private const val CLS_CONTROL_HEADER = "com.android.systemui.controllercenter.HnControlCenterHeader"
    private const val CLS_NOTIF_BELL = "com.android.systemui.statusbar.notification.panelheader.NotifMgeBtn"
    private const val CLS_NOTIF_INTERACTOR =
        "com.android.systemui.statusbar.notification.panelheader.NotifCenterHeaderInteractorImpl"

    /**
     * 日期行的点击处理在 `panelheader.viewmodel` 子包里。
     *
     * 这里曾经漏掉 `.viewmodel`，`onClickDate` 根本没被挂上，点日期走的是宿主原生
     * 逻辑（打开日历），所以「点击打开天气」的开关看起来完全没生效。
     */
    private val HEADER_VM_CANDIDATES = arrayOf(
        "com.android.systemui.statusbar.notification.panelheader.viewmodel.NotifCenterHeaderViewModelImpl",
        "com.android.systemui.statusbar.notification.panelheader.NotifCenterHeaderViewModelImpl",
    )

    private const val FIELD_POWER_BUTTON = "mPowerButton"
    private const val METHOD_CLOCK_DATE = "getCurrentClockDate"

    /** Views already carrying a re-assert listener, so one view is wired once. */
    private val watched = ConcurrentHashMap<Int, WeakReference<View>>()

    fun install(module: XposedModule, cl: ClassLoader): Int {
        var n = 0
        n += hookPowerButton(module, cl)
        n += hookBell(module, cl)
        n += hookDateLine(module, cl)
        n += hookDateClick(module, cl)
        return n
    }

    /**
     * The control center's power button.
     *
     * The host reads `hw_hide_statusbar_power` into a field and never uses it, so
     * the button has to be collapsed here. It sits in a `LinearLayout`, which closes
     * up around a `GONE` child on its own.
     */
    private fun hookPowerButton(module: XposedModule, cl: ClassLoader): Int {
        val header = Refl.cls(CLS_CONTROL_HEADER, cl) ?: return 0
        return TrioHooks.hook(module, Refl.method(header, "onFinishInflate"), "honorduo-cc-power") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject as? View
            if (self != null) {
                val button = Refl.get(Refl.field(self.javaClass, FIELD_POWER_BUTTON), self) as? View
                if (button != null) watch(button) { a -> a.shadeHidePower }
            }
            result
        }
    }

    /**
     * The bell beside the shade's clock, a plain view inside the Compose header.
     *
     * `onAttachedToWindow` is declared on `View`, so the hook sees every view in the
     * process - the class-name guard in front of the work is what keeps that from
     * costing anything: attaches are rare, and everything else returns at once.
     */
    private fun hookBell(module: XposedModule, cl: ClassLoader): Int {
        if (Refl.cls(CLS_NOTIF_BELL, cl) == null) return 0
        val attach = Refl.method(View::class.java, "onAttachedToWindow") ?: return 0
        return TrioHooks.hook(module, attach, "honorduo-shade-attach") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject as? View
            if (self != null && self.javaClass.name == CLS_NOTIF_BELL) {
                watch(self) { a -> a.shadeHideBell }
            }
            result
        }
    }

    /**
     * Collapses [view] while [hidden] answers true, and keeps it collapsed.
     *
     * The answer is re-read every frame rather than captured, so flipping the switch
     * brings the button back without a restart, and a host that re-shows the view in
     * its own refresh is overruled on the next frame. The listener unhooks itself once
     * the view leaves the hierarchy, so a rebuilt panel does not stack them.
     */
    private fun watch(view: View, hidden: (TrioAppearance) -> Boolean) {
        applyVisibility(view, hidden(TrioConfig.appearance()))
        val key = System.identityHashCode(view)
        if (watched.putIfAbsent(key, WeakReference(view)) != null) return
        view.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val parent: ViewParent? = view.parent
                if (parent == null) {
                    runCatching { view.viewTreeObserver.removeOnPreDrawListener(this) }
                    watched.remove(key)
                    return true
                }
                applyVisibility(view, hidden(TrioConfig.appearance()))
                return true
            }
        })
    }

    private fun applyVisibility(view: View, hidden: Boolean) {
        val wanted = if (hidden) View.GONE else View.VISIBLE
        if (view.visibility != wanted) {
            view.visibility = wanted
        }
    }

    /**
     * The shade's date line.
     *
     * The host builds the string once per minute (and on language or time-zone
     * changes), so appending here costs one read and one format per minute rather
     * than anything per frame.
     */
    private fun hookDateLine(module: XposedModule, cl: ClassLoader): Int {
        val interactor = Refl.cls(CLS_NOTIF_INTERACTOR, cl) ?: return 0
        val method = TrioHooks.methodNamed(interactor, METHOD_CLOCK_DATE, 1)
            ?: TrioHooks.methodNamed(interactor, METHOD_CLOCK_DATE, 0)
            ?: return 0
        return TrioHooks.hook(module, method, "honorduo-shade-date") { chain ->
            val original = chain.proceed() as? String
            val a = TrioConfig.appearance()
            if (original.isNullOrEmpty() || (!a.shadeLunar && !a.shadeWeather)) {
                original
            } else {
                decorateDateLine(original, a.shadeLunar, a.shadeWeather)
            }
        }
    }

    /**
     * [line] with the lunar date and weather appended.
     *
     * Appended, not prepended as a second line: the host renders this string in a
     * one-line box, so a `\n` there pushed the real date out of view instead of
     * adding a row above it. The separators keep the three pieces readable and the
     * date itself is never touched.
     *
     * 上一次拼接结果缓存在下面：宿主到底每分钟重建一次、还是每次重组都重建，两个
     * 说法都出现过；按「输入没变就直接返回」兜底，同一分钟、同一份天气、同一组
     * 开关下不重算农历与天气，也不产生新分配。
     */
    private var cachedLine: String? = null
    private var cachedMinute = -1L
    private var cachedLunar = false
    private var cachedWeatherOn = false
    private var cachedStyle = -1
    private var cachedData: WeatherManager.WeatherData? = null
    private var cachedValue = ""

    private fun decorateDateLine(line: String, lunar: Boolean, weather: Boolean): String {
        val minute = System.currentTimeMillis() / 60_000L
        val data = if (weather) WeatherManager.data else null
        val style = if (weather) TrioConfig.appearance().shadeWeatherStyle else -1
        if (line == cachedLine && minute == cachedMinute && lunar == cachedLunar &&
            weather == cachedWeatherOn && style == cachedStyle && data == cachedData
        ) {
            return cachedValue
        }
        val extra = StringBuilder()
        if (lunar) {
            val l = ClockTicker.formatLunar(java.util.Date())
            if (l.isNotEmpty()) extra.append(l)
        }
        if (weather) {
            val w = weatherText()
            if (w.isNotEmpty()) {
                if (extra.isNotEmpty()) extra.append(' ')
                extra.append(w)
            }
        }
        val result = if (extra.isEmpty()) line else "$line $extra"
        cachedLine = line
        cachedMinute = minute
        cachedLunar = lunar
        cachedWeatherOn = weather
        cachedStyle = style
        cachedData = data
        cachedValue = result
        return result
    }

    /**
     * Hooks the click on the shade's date line.
     *
     * When [TrioAppearance.shadeWeatherOpensApp] is on, the whole line opens the
     * system weather app instead of the clock: the host has one clickable for the
     * entire string, so there is no per-word target to tell apart. The setting's
     * own confirmation dialog states that the native calendar jump is lost while
     * this is on. With the switch off, the hook calls through and the line keeps
     * the host's calendar behaviour.
     */
    private fun hookDateClick(module: XposedModule, cl: ClassLoader): Int {
        var vm: Class<*>? = null
        for (name in HEADER_VM_CANDIDATES) {
            vm = Refl.cls(name, cl)
            if (vm != null) break
        }
        if (vm == null) {
            TrioHooks.log(TrioHooks.LOG_WARN, "shade date click: header view model not found")
            return 0
        }
        val onClickDate = TrioHooks.methodNamed(vm, "onClickDate", 0) ?: return 0
        return TrioHooks.hook(module, onClickDate, "honorduo-shade-date-click") { chain ->
            val a = TrioConfig.appearance()
            if (a.shadeWeather && a.shadeWeatherOpensApp) {
                launchWeatherApp()
                return@hook null
            }
            chain.proceed()
        }
    }

    private const val HONOR_WEATHER_PKG = "com.hihonor.android.totemweather"

    /**
     * Opens the system weather app, dismissing the keyguard and shade.
     *
     * `getLaunchIntentForPackage` returns an intent whose component is already set to
     * the weather app's launcher activity, so it cannot be handed to another app the
     * way the host's implicit date intent was. If the app is not installed there is
     * nothing sensible to open: the click is swallowed and logged instead of falling
     * through to the clock/calendar.
     */
    private fun launchWeatherApp() {
        val ctx = appContext() ?: return
        val intent = resolveWeatherIntent(ctx)
        if (intent == null) {
            TrioHooks.log(TrioHooks.LOG_WARN, "shade weather: $HONOR_WEATHER_PKG not installed")
            return
        }
        if (!launchFromStatusBar(ctx, intent)) {
            runCatching { ctx.startActivity(intent) }
        }
    }

    /** Explicit launcher intent for the weather app, or null when it is not installed. */
    private fun resolveWeatherIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        val launch = runCatching { pm.getLaunchIntentForPackage(HONOR_WEATHER_PKG) }.getOrNull()
        val component = launch?.component
            ?: runCatching {
                val probe = Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setPackage(HONOR_WEATHER_PKG)
                pm.resolveActivity(probe, 0)?.activityInfo?.let {
                    ComponentName(it.packageName, it.name)
                }
            }.getOrNull()
            ?: return null
        return Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * The host's own dismiss-then-launch, so the shade closes behind the app.
     *
     * Resolves `StatusBar` through `Dependency.get` (the host's service locator) and
     * uses the widest `startActivityDismissingKeyguard` overload, which is the call
     * the panel itself uses; `StatusBar.getInstance` does not exist on current ROMs.
     */
    private fun launchFromStatusBar(ctx: Context, intent: Intent): Boolean = runCatching {
        val loader = ctx.classLoader
        val statusBarClass = Refl.cls("com.android.systemui.statusbar.phone.StatusBar", loader)
            ?: return@runCatching false
        val dependency = Refl.cls("com.android.systemui.plugins.Dependency", loader)
            ?: return@runCatching false
        val statusBar = Refl.method(dependency, "get", Class::class.java)
            ?.invoke(null, statusBarClass) ?: return@runCatching false
        val launch = statusBarClass.methods.firstOrNull {
            it.name.contains("startActivityDismissingKeyguard")
        } ?: return@runCatching false
        launch.isAccessible = true
        when (launch.parameterCount) {
            4 -> launch.invoke(statusBar, intent, true, true, 536870912)
            3 -> launch.invoke(statusBar, intent, true, true)
            1 -> launch.invoke(statusBar, intent)
            else -> return@runCatching false
        }
        true
    }.getOrDefault(false)

    /**
     * The weather in the chosen style. This line is plain text, so every icon style
     * ends up as the system's symbol: there is nothing here that can take a canvas.
     *
     * When no reading has landed yet the date line is the only thing that can ask for
     * one: the manager backs a failed attempt off with its own stamp, so this costs at
     * most one provider round-trip per cache window however often the line rebuilds.
     */
    private fun weatherText(): String {
        val data = WeatherManager.data
        if (data == null) {
            appContext()?.let { WeatherManager.fetchAsync(it) }
            return ""
        }
        val style = TrioConfig.appearance().shadeWeatherStyle
        if (style == Prefs.WEATHER_STYLE_TEXT) return data.text()
        val glyph = WeatherIconDrawer.glyphFor(data.condition)
        return when (style) {
            Prefs.WEATHER_STYLE_ICON, Prefs.WEATHER_STYLE_SYMBOL -> "$glyph ${data.tempText()}"
            else -> "$glyph ${data.text()}"
        }
    }

    /** 主线程合并/延迟用；见 [onWeatherChanged]。 */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var refreshScheduled = false

    /** 合并后的一次主机重算，等展开动画过去再插进去。 */
    private const val REFRESH_DELAY_MS = 400L

    /**
     * Weather changed: ask the host to rebuild the date line once, but not right now.
     *
     * Called from the main thread after a weather reading lands. Nudging TimeManager
     * synchronously here means the shade recomposes and re-measures in the middle of
     * the panel's open animation — the exact frame the user sees stutter. The nudge
     * is merged and delayed so it lands after the animation.
     */
    fun onWeatherChanged() {
        if (refreshScheduled) return
        refreshScheduled = true
        mainHandler.postDelayed({
            refreshScheduled = false
            refreshDateLine()
        }, REFRESH_DELAY_MS)
    }

    /** Recomputes the date line by nudging the host's TimeManager. */
    private fun refreshDateLine() {
        runCatching {
            val manager = timeManager() ?: return@runCatching
            TrioHooks.methodNamed(manager.javaClass, "updateNextTimeMinute", 1)?.invoke(manager, 0L)
            TrioHooks.methodNamed(manager.javaClass, "updateNextTimeSecond", 1)?.invoke(manager, 0L)
        }
    }

    @Volatile
    private var sTimeManager: WeakReference<Any>? = null

    private fun timeManager(): Any? {
        sTimeManager?.get()?.let { return it }
        val loader = appContext()?.classLoader ?: return null
        val dependency = Refl.cls("com.android.systemui.plugins.Dependency", loader) ?: return null
        val managerClass = Refl.cls("com.android.systemui.time.TimeManager", loader) ?: return null
        val manager = runCatching {
            Refl.method(dependency, "get", Class::class.java)?.invoke(null, managerClass)
        }.getOrNull() ?: return null
        sTimeManager = WeakReference(manager)
        return manager
    }

    @Volatile
    private var sAppContext: WeakReference<android.content.Context>? = null

    /**
     * Application context, resolved once.
     *
     * 以前每次调用都做一次 `Class.forName` + `getMethod` + `invoke`。这段代码在
     * 日期行的热路径上（天气读不到时每个重建周期都会走到），缓存之后这条路径上
     * 不再有反射。
     */
    private fun appContext(): android.content.Context? {
        sAppContext?.get()?.let { return it }
        val ctx = runCatching {
            val at = Class.forName("android.app.ActivityThread")
            val app = at.getMethod("currentApplication").invoke(null) as? android.app.Application
            app?.applicationContext
        }.getOrNull() ?: return null
        sAppContext = WeakReference(ctx)
        return ctx
    }
}
