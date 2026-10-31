package io.github.odomu.honorduo.hook

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.Prefs
import io.github.odomu.honorduo.model.TrioAppearance
import io.github.odomu.honorduo.model.TrioConfig
import io.github.odomu.honorduo.model.TrioState
import io.github.odomu.honorduo.render.TrioRenderer
import java.lang.ref.WeakReference
import java.util.ArrayList

/**
 * Manages low-level hooks on MagicOS SystemUI status-bar host views and containers.
 *
 * Responsibilities:
 * - Intercepts `HwSystemIcons` lifecycle (inflation, layout passes, capsule avoidance, detaching).
 * - Intercepts `HwStatusBarBatteryView` updates and lifecycle to coordinate glyph mounting.
 * - Suppresses native icons via `View.setFlags` visibility enforcement.
 * - Hooks `HwStatusbarContents` and `HwStatusBarClock` to extract system typeface and clock views.
 * - Listens for tint changes across status-bar containers to refresh glyph and clock colors.
 */
internal object SystemBarHook {

    /** Only visibility-related setFlags writes need the suppression pass. */
    private const val VISIBILITY_FLAGS = 0x0000000C

    private class PrivacyChipState(view: View) {
        val view = WeakReference(view)
        var hiddenVisibility: Int? = null
    }
    private val privacyChips = ArrayList<PrivacyChipState>()

    /**
     * Installs all status bar view and container hooks into the target ClassLoader.
     *
     * @param module Active Xposed module instance used to register hooks.
     * @param cl SystemUI ClassLoader containing target view classes.
     * @return Total count of successfully installed hooks.
     */
    fun install(module: XposedModule, cl: ClassLoader): Int {
        var hooked = 0
        hooked += hookRow(module, cl)
        hooked += hookBattery(module, cl)
        hooked += hookVisibility(module)
        hooked += hookContents(module, cl)
        hooked += hookClockFace(module, cl)
        hooked += hookTint(module, cl)
        hooked += hookPrivacyChip(module, cl)
        return hooked
    }

    /**
     * Hooks `HwSystemIcons` container methods to manage module view lifecycle and capsule collision.
     */
    private fun hookRow(module: XposedModule, cl: ClassLoader): Int {
        val rowClass = Refl.cls("com.android.systemui.statusbar.HwSystemIcons", cl) ?: return 0
        var n = 0

        // Intercept container inflation to register the row with StatusBarRowManager.
        n += TrioHooks.hook(module, Refl.method(rowClass, "onFinishInflate"), "honorduo-row-inflate") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                StatusBarRowManager.register(self)
            }
            result
        }

        // Intercept layout passes to synchronize custom components and check capsule encroachment.
        n += TrioHooks.hook(
            module, Refl.method(
                rowClass, "onLayout",
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            ),
            "honorduo-row-layout",
        ) { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                val row = StatusBarRowManager.find(self)
                if (row != null) {
                    StatusBarRowManager.requestSync(row)
                }
                StatusBarRowManager.checkCapsuleAvoidance(self)
                // Capsule-no-scale fallback for animation-less scale paths; the main
                // restore path is CapsuleScaleHook riding the system's own spring.
                if (TrioConfig.appearance().capsuleNoScale) {
                    runCatching { CapsuleScaleHook.resetSubtreeScale(self) }
                }
            }
            result
        }

        // Intercept icon alpha updates triggered by SystemUI animation controllers.
        n += TrioHooks.hook(module, TrioHooks.methodNamed(rowClass, "updateIconsAlpha", 0), "honorduo-update-icons-alpha") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                StatusBarRowManager.checkCapsuleAvoidance(self)
                StatusBarRowManager.restoreIconAlpha(self)
            }
            result
        }

        // Intercept the fixed icon width to drop the fixed horizontal padding,
        // letting the row pack its icons closer ("compact row").
        n += TrioHooks.hook(module, TrioHooks.methodNamed(rowClass, "initIconWidth", 0), "honorduo-compact-row") { chain ->
            val result = chain.proceed()
            if (!TrioConfig.appearance().trioCompactRow) {
                result
            } else {
                val orig = (result as? Number)?.toInt() ?: 0
                if (orig <= 0) {
                    result
                } else {
                    val host = chain.thisObject as? View
                    val pad = host?.let { h ->
                        Refl.field(h.javaClass, "mIconHorizontalPadding")
                            ?.let { f -> (Refl.get(f, h) as? Number)?.toInt()?.times(2) }
                    } ?: 0
                    // Floor at 60% of the original width so the host layout survives.
                    (orig - pad).coerceAtLeast(orig * 3 / 5)
                }
            }
        }

        // Intercept dynamic capsule area notification boundaries (5-arg overload).
        n += TrioHooks.hook(module, TrioHooks.methodNamed(rowClass, "setCapsuleArea", 5), "honorduo-capsule-area") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                val isShown = chain.getArg(1) as? Boolean ?: false
                val right = (chain.getArg(2) as? Number)?.toInt() ?: 0
                StatusBarRowManager.handleCapsuleAvoidance(self, isShown, right)
            }
            result
        }

        // Intercept dynamic capsule area notification boundaries (6-arg overload).
        n += TrioHooks.hook(module, TrioHooks.methodNamed(rowClass, "setCapsuleArea", 6), "honorduo-capsule-area-6") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                val isShown = chain.getArg(1) as? Boolean ?: false
                val right = (chain.getArg(2) as? Number)?.toInt() ?: 0
                StatusBarRowManager.handleCapsuleAvoidance(self, isShown, right)
            }
            result
        }

        // Intercept window attachment to track live container instances.
        n += TrioHooks.hook(module, Refl.method(rowClass, "onAttachedToWindow"), "honorduo-row-attach") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                StatusBarRowManager.register(self)
                // 生效范围需要一个 Context 才能监听锁屏；行挂上窗口就是最早的稳定时机。
                ScopeTracker.start(self.context)
            }
            result
        }

        // Intercept window detachment to clean up registered rows and prevent leaks.
        n += TrioHooks.hook(module, Refl.method(rowClass, "onDetachedFromWindow"), "honorduo-row-detach") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                StatusBarRowManager.drop(self)
            }
            result
        }

        return n
    }

    /**
     * Hooks native battery view updates to sync battery state and hide the stock icon when requested.
     */
    private fun hookBattery(module: XposedModule, cl: ClassLoader): Int {
        val battery = Refl.cls("com.android.systemui.battery.HwStatusBarBatteryView", cl) ?: return 0
        var n = 0

        // Intercept stock battery update events to sync glyph state and hide stock icon.
        n += TrioHooks.hook(module, Refl.method(battery, "onBatteryUpdate"), "honorduo-battery-update") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is View) {
                // 熄屏（含 AOD）期间宿主自己会把电池重挂到息屏容器：这里既不隐藏、
                // 也不刷新行、更不重绘状态栏，否则 AOD 电量图标会再次被藏掉，
                // 且每次电量更新都白白触发一轮全量重绘（后台功耗）。
                if (ScreenStateTracker.isScreenOn) {
                    if (StatusBarIconManager.isOwned(self.id, TrioConfig.appearance())) {
                        StatusBarIconManager.hide(self)
                    }
                    val row = StatusBarRowManager.rowOfBattery(self)
                    if (row != null) {
                        StatusBarRowManager.syncRow(row, false)
                    }
                    TrioHooks.invalidateHosts()
                }
            }
            result
        }

        // Intercept battery view inflation to obtain a valid Context and start screen state tracking.
        n += TrioHooks.hook(module, Refl.method(battery, "onFinishInflate"), "honorduo-battery-inflate") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is View) {
                TrioState.attachContext(self.context)
                TrioHooks.ensureScreenStateTracker(self.context)
            }
            result
        }

        return n
    }

    /**
     * Enforces visibility suppression on stock icons via View.setFlags interception.
     */
    private fun hookVisibility(module: XposedModule): Int {
        val method = Refl.method(
            View::class.java, "setFlags",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
        ) ?: return 0
        return TrioHooks.hook(module, method, "honorduo-visibility") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            val mask = (chain.getArg(1) as? Number)?.toInt() ?: 0
            if (self is View && (mask and VISIBILITY_FLAGS) != 0) {
                StatusBarIconManager.enforceSuppression(self)
            }
            result
        }
    }

    /**
     * Hooks `HwStatusbarContents` window attachment to monitor parent status-bar container.
     */
    private fun hookContents(module: XposedModule, cl: ClassLoader): Int {
        val contents = Refl.cls("com.android.systemui.statusbar.HwStatusbarContents", cl) ?: return 0
        return TrioHooks.hook(module, Refl.method(contents, "onAttachedToWindow"), "honorduo-contents-attach") { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is ViewGroup) {
                StatusBarIconManager.contents = WeakReference(self)
                StatusBarIconManager.applyIconVisibility(TrioConfig.appearance())
            }
            result
        }
    }

    /**
     * Hooks `HwStatusBarClock` window attachment to extract system typeface and hand over clock view.
     */
    private fun hookClockFace(module: XposedModule, cl: ClassLoader): Int {
        val clock = Refl.cls("com.android.systemui.time.HwStatusBarClock", cl) ?: return 0
        return TrioHooks.hook(
            module, Refl.method(clock, "onAttachedToWindow"), "honorduo-clock-face",
        ) { chain ->
            val result = chain.proceed()
            val self = chain.thisObject
            if (self is TextView) {
                if (self.typeface != null) {
                    TrioRenderer.adoptSystemTypeface(self.typeface)
                    TrioHooks.invalidateHosts()
                }
                BarClockManager.noteNativeClock(self)
                BarClockManager.dropNow()
                BarClockManager.sync(TrioConfig.appearance())
            }
            result
        }
    }

    /**
     * Hooks tint refresh calls to invalidate custom components when system switches dark/light mode.
     */
    private fun hookTint(module: XposedModule, cl: ClassLoader): Int {
        var hooked = 0
        val contents = Refl.cls("com.android.systemui.statusbar.HwStatusbarContents", cl)
        if (contents != null) {
            val refreshMethod = Refl.method(contents, "refreshTint")
            if (refreshMethod != null) {
                hooked += TrioHooks.hook(module, refreshMethod, "honorduo-contents-tint") { chain ->
                    val result = chain.proceed()
                    TrioHooks.invalidateHosts()
                    result
                }
            }
        }
        val cluster = Refl.cls("com.android.systemui.statusbar.SignalClusterView", cl)
        if (cluster != null) {
            val method = Refl.method(
                cluster, "onDarkChanged",
                ArrayList::class.java, Float::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
            )
            if (method != null) {
                hooked += TrioHooks.hook(module, method, "honorduo-tint") { chain ->
                    val result = chain.proceed()
                    TrioHooks.invalidateHosts()
                    result
                }
            }
        }
        return hooked
    }

    /**
     * 按当前面板 / 锁屏状态重算「模块生效范围」，决定整行是否让位给宿主。
     *
     * 只在状态跳变时调用（面板切换、锁屏开合、启动兜底），不是每帧 —— 里面会做一次
     * 逐行 sync，跑在每次下拉动画上就会把下拉拖卡。
     */
    fun updateEffectiveScopeState() {
        ScopeTracker.refreshKeyguard()
        val a = TrioConfig.appearance()
        val scope = a.effectiveScope
        val excluded = a.enabled && scope != Prefs.SCOPE_ALL && (
            ((scope and Prefs.SCOPE_BIT_CC) != 0 && ScopeTracker.controlCenterActive) ||
                ((scope and Prefs.SCOPE_BIT_SHADE) != 0 && ScopeTracker.shadeActive) ||
                ((scope and Prefs.SCOPE_BIT_KEYGUARD) != 0 && ScopeTracker.lockscreenActive)
            )
        StatusBarRowManager.setSuppressed(excluded)
    }

    /**
     * Hooks `HwNotchStatusBar.togglePrivacyChip` to hide the green camera/mic
     * indicator when the user asked for it.
     */
    private fun hookPrivacyChip(module: XposedModule, cl: ClassLoader): Int {
        val bar = Refl.cls("com.android.systemui.statusbar.phone.HwNotchStatusBar", cl) ?: return 0
        val toggle = TrioHooks.methodNamed(bar, "togglePrivacyChip", 2) ?: return 0
        return TrioHooks.hook(module, toggle, "honorduo-privacy-chip") { chain ->
            chain.proceed()
            val chip = chain.getArg(0) as? View
            val show = chain.getArg(1) as? Boolean ?: false
            if (chip != null) updatePrivacyChip(chip, show)
            null
        }
    }

    /** Records only live show events; release our visibility before the host reuses a chip. */
    private fun updatePrivacyChip(chip: View, show: Boolean) {
        for (i in privacyChips.indices.reversed()) {
            val state = privacyChips[i]
            val view = state.view.get()
            if (view == null) {
                privacyChips.removeAt(i)
            } else if (view === chip) {
                if (show) {
                    setPrivacyChipHidden(state, chip, TrioConfig.appearance().hidePrivacyChip)
                } else {
                    setPrivacyChipHidden(state, chip, false)
                    privacyChips.removeAt(i)
                }
                return
            }
        }
        if (show) {
            val state = PrivacyChipState(chip)
            privacyChips.add(state)
            setPrivacyChipHidden(state, chip, TrioConfig.appearance().hidePrivacyChip)
        }
    }

    /** Applies settings to all shown chip instances, including master-switch hand-back. */
    fun applyPrivacyChipVisibility(a: TrioAppearance) {
        for (i in privacyChips.indices.reversed()) {
            val state = privacyChips[i]
            val chip = state.view.get()
            if (chip == null) privacyChips.removeAt(i)
            else setPrivacyChipHidden(state, chip, a.hidePrivacyChip)
        }
    }

    private fun setPrivacyChipHidden(state: PrivacyChipState, chip: View, hide: Boolean) {
        if (hide) {
            if (chip.visibility != View.GONE) {
                if (state.hiddenVisibility == null) state.hiddenVisibility = chip.visibility
                chip.visibility = View.GONE
            }
        } else {
            val previous = state.hiddenVisibility ?: return
            state.hiddenVisibility = null
            if (chip.visibility == View.GONE) chip.visibility = previous
        }
    }
}
