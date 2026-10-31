/*
 * Copyright (C) 2026 yixing233 and HyperDuo contributors
 * Copyright (C) 2026 odomu and HonorDuo contributors
 *
 * This file is part of HonorDuo, a fork of HyperDuo, released under the GNU
 * General Public License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.odomu.honorduo.hook

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.TrioConfig
import io.github.odomu.honorduo.render.TrioRenderer
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dual-row cellular signal, drawn by the module itself (rendering delegated to
 * [TrioRenderer.drawOutSignal] - the same code path as the out-of-ring SIM reading).
 *
 * Behaviour:
 * - After `onAttachedToWindow` on the signal cluster views, walks the cluster for the
 *   `mobile_combo` group, hides the native group and injects a self-drawn container
 *   (the out-signal reading: capsule bars for the primary SIM, a dot row - or a second
 *   bar row with the stacked style - for the secondary SIM, plus the data-activity
 *   arrows when 「显示流量活动图标」 is on) and an optional network-type label.
 * - Signal levels are read directly from TelephonyManager/SubscriptionManager rather
 *   than from the host's StateFlow; a 250 ms-throttled global-layout listener, a 2 s
 *   self-rescheduling refresh and a per-frame Choreographer colour sync keep the
 *   reading fresh and following dark-mode tint changes.
 * - Mutually exclusive with the trio glyph: while the glyph draws the signal itself
 *   ([io.github.odomu.honorduo.model.TrioAppearance.foldsMobile]) the dual rows step
 *   aside - the container is hidden and the native group is left to the trio's own
 *   suppression. The same path hands the group back when the feature is switched off.
 *
 * Everything here is best-effort: any failure inside a hook callback is swallowed and
 * logged, never thrown into the host SystemUI process.
 */
internal object DualSignalHook {

    // ---- Mount points (onAttachedToWindow is a declared override on each class) ----

    private const val CLS_SIGNAL_CLUSTER_VIEW = "com.android.systemui.statusbar.SignalClusterView"
    private const val CLS_SIGNAL_UNIT_NORMAL = "com.android.systemui.statusbar.SignalUnitNormalView"
    private const val CLS_SIGNAL_UNIT_UCT = "com.android.systemui.statusbar.SignalUnitUCTView"
    private const val METHOD_ATTACH = "onAttachedToWindow"

    // ---- DualSignal plugin conflict guard ----

    /** The DualSignal plugin's package; its own container wins when both are present. */
    private const val DUAL_SIGNAL_PACKAGE = "com.mod.dualsignal"

    /** The DualSignal 3.3 container's tag (TAG_CONTAINER = "dual_container"). */
    private const val FOREIGN_CONTAINER_TAG = "dual_container"

    // ---- Own view tags (prefixed, so they cannot collide with the plugin's) ----

    private const val TAG_CONTAINER = "hd_dual_container"
    private const val TAG_SIGNAL = "hd_dual_signal"
    private const val TAG_LABEL = "hd_dual_label"

    // Right margin fixed at 2dp; left margin dynamic, tightened to 0dp against text neighbours.
    private const val HOST_MARGIN_DP = 2.0f
    private const val HOST_MARGIN_TEXT_DP = 0.0f

    // ---- Type-label visual constants (upstream ensureContainer values) ----

    private const val LABEL_OFFSET_Y_DP = 0.7f
    private const val LABEL_MARGIN_LEFT_DP = 5.0f
    private const val LABEL_TEXT_SIZE_SP = 13.0f

    private const val LAYOUT_THROTTLE_MS = 250L
    private const val REFRESH_INTERVAL_MS = 2000L

    /** 颜色同步的周期：主题切换是低频事件，没必要每一帧都跟随 VSYNC。 */
    private const val COLOUR_INTERVAL_MS = 1000L

    // ---- Runtime resource ids (never hard-code the numeric ids; they drift per build) ----

    /** The per-card signal group (horizontal LinearLayout the dual reading replaces). */
    private const val NAME_MOBILE_COMBO = "mobile_combo"

    /** The FrameLayout shell of `mobile_combo`; the injection host. */
    private const val NAME_MOBILE_COMBO_CARD = "mobile_combo_card"

    /** Fallback anchor for ordering before Wi-Fi. */
    private const val NAME_ETHERNET_COMBO = "ethernet_combo"

    @Volatile private var idMobileCombo = 0
    @Volatile private var idMobileComboCard = 0
    @Volatile private var idEthernetCombo = 0

    private fun resolveResourceIdsOnce(context: Context) {
        if (idMobileCombo != 0) return
        runCatching {
            val pkg = context.packageName
            idMobileCombo = context.resources.getIdentifier(NAME_MOBILE_COMBO, "id", pkg)
            idMobileComboCard = context.resources.getIdentifier(NAME_MOBILE_COMBO_CARD, "id", pkg)
            idEthernetCombo = context.resources.getIdentifier(NAME_ETHERNET_COMBO, "id", pkg)
        }
    }

    // ---- Switches (written by [install] and [updateConfig], read on the main thread) ----

    @Volatile private var sEnabled = false
    @Volatile private var sLabelEnabled = false

    // ---- Maintenance state (all accessed on the main thread) ----

    /** Hosts already taken over, guarding against double injection. */
    private val maintained = WeakHashMap<FrameLayout, Boolean>()

    /** The native group belonging to each host, so [updateConfig] can re-run [maintain]. */
    private val groups = WeakHashMap<FrameLayout, LinearLayout>()

    /** Cluster roots seen at attach time, so a feature switched on later can still restyle. */
    private val roots = WeakHashMap<View, Boolean>()

    /** Hosts that yielded to the DualSignal plugin; never injected again. */
    private val backedOff = WeakHashMap<FrameLayout, Boolean>()

    /**
     * 每个宿主在途的维护/着色任务。
     *
     * 息屏、功能关闭或让位给三合一时必须移除：这两条循环原本无条件自续，
     * 熄屏后仍每 2 秒读一次电话状态、每帧同步一次颜色，是 SystemUI 后台耗电的主因。
     */
    private val refreshTickers = WeakHashMap<FrameLayout, Runnable>()
    private val colourTickers = WeakHashMap<FrameLayout, Runnable>()
    private var screenListenerAdded = false

    /** Throttle timestamp for the global-layout listener, shared across hosts. */
    @Volatile private var lastLayoutMs = 0L

    /** One-per-process probe of the DualSignal plugin package, for the log only. */
    private val pkgProbed = AtomicBoolean(false)

    /**
     * Installs the attach hooks. The hooks stay in place whatever the switch says -
     * the reading gates on [sEnabled] at draw time so a settings change applies
     * without a SystemUI restart.
     */
    fun install(module: XposedModule, cl: ClassLoader): Int {
        val a = TrioConfig.appearance()
        sEnabled = a.dualSignal
        sLabelEnabled = a.dualSignalLabel
        var hooked = 0
        hooked += hookAttach(module, cl, CLS_SIGNAL_CLUSTER_VIEW, "honorduo-dual-cluster")
        hooked += hookAttach(module, cl, CLS_SIGNAL_UNIT_NORMAL, "honorduo-dual-unit-normal")
        hooked += hookAttach(module, cl, CLS_SIGNAL_UNIT_UCT, "honorduo-dual-unit-uct")
        return hooked
    }

    /**
     * Applies a changed configuration: remembers the switches and re-runs maintenance
     * on every known host (hiding or restoring as the new state asks), and - when the
     * feature was just switched on - restyles every cluster root seen so far.
     */
    fun updateConfig(enabled: Boolean, label: Boolean) {
        val wasEnabled = sEnabled
        sEnabled = enabled
        sLabelEnabled = label
        try {
            if (sEnabled && !wasEnabled) {
                for (root in roots.keys) {
                    root.post { safeRestyle(root) }
                }
            }
            for ((host, group) in groups) {
                maintain(host, group, force = true)
                if (sEnabled && !TrioConfig.appearance().foldsMobile()) {
                    startTickers(host)
                } else {
                    stopTickers(host)
                }
            }
        } catch (t: Throwable) {
            TrioHooks.log(TrioHooks.LOG_WARN, "dual-signal config apply failed: $t")
        }
    }

    private fun hookAttach(module: XposedModule, cl: ClassLoader, className: String, id: String): Int {
        val c = Refl.cls(className, cl) ?: return 0
        val m = TrioHooks.methodNamed(c, METHOD_ATTACH, 0) ?: return 0
        return TrioHooks.hook(module, m, id) { chain ->
            chain.proceed()
            try {
                val self = chain.thisObject as? View
                if (self != null) {
                    // Fall back to self when the cluster root is not found; walking a
                    // small subtree is harmless.
                    val root = if (className == CLS_SIGNAL_CLUSTER_VIEW) self else findClusterRoot(self) ?: self
                    roots[root] = true
                    schedule(root)
                }
            } catch (t: Throwable) {
                TrioHooks.log(TrioHooks.LOG_WARN, "dual-signal attach failed: $t")
            }
            null
        }
    }

    /** Walks up to 8 levels from a signal unit view for an ancestor named "SignalCluster". */
    private fun findClusterRoot(v: View): View? {
        var pp = v.parent
        var depth = 0
        while (pp is View && depth < 8) {
            if (pp.javaClass.name.contains("SignalCluster")) return pp
            pp = pp.parent
            depth++
        }
        return null
    }

    /** Restyles immediately and once more after 500 ms, catching late async builds. */
    private fun schedule(root: View) {
        root.post { safeRestyle(root) }
        root.postDelayed({ safeRestyle(root) }, 500L)
    }

    /** 息屏监听只注册一次：熄屏停摆，亮屏重新拉起所有已接管的宿主。 */
    private fun ensureScreenListener() {
        if (screenListenerAdded) return
        screenListenerAdded = true
        ScreenStateTracker.addListener { on ->
            if (!on) {
                for (host in groups.keys) stopTickers(host)
            } else if (sEnabled && !TrioConfig.appearance().foldsMobile()) {
                for (host in groups.keys) startTickers(host)
            }
        }
    }

    /** 两条循环是否还有理由继续：功能开着、亮屏、宿主仍挂在窗口上、且没有让位给三合一或生效范围。 */
    private fun loopAllowed(host: FrameLayout): Boolean =
        sEnabled && ScreenStateTracker.isScreenOn && host.isAttachedToWindow &&
            !TrioConfig.appearance().foldsMobile() && !StatusBarRowManager.suppressed &&
            groups.containsKey(host)

    private fun startTickers(host: FrameLayout) {
        startRefreshTicker(host)
        startColourTicker(host)
    }

    private fun stopTickers(host: FrameLayout) {
        refreshTickers.remove(host)?.let(host::removeCallbacks)
        colourTickers.remove(host)?.let(host::removeCallbacks)
    }

    /** 2 秒一次的信号维护；熄屏、关闭或让位后自行退出，不再续期。 */
    private fun startRefreshTicker(host: FrameLayout) {
        if (!loopAllowed(host)) return
        refreshTickers.remove(host)?.let(host::removeCallbacks)
        val tick = object : Runnable {
            override fun run() {
                if (!loopAllowed(host)) {
                    refreshTickers.remove(host)
                    return
                }
                try {
                    groups[host]?.let { maintain(host, it, force = true) }
                } catch (t: Throwable) {
                    TrioHooks.log(TrioHooks.LOG_WARN, "dual-signal refresh failed: $t")
                }
                host.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }
        refreshTickers[host] = tick
        host.postDelayed(tick, REFRESH_INTERVAL_MS)
    }

    /** 一秒一次的颜色同步；原先每一帧回调一次 VSYNC，熄屏后仍在跑，是最重的耗电源。 */
    private fun startColourTicker(host: FrameLayout) {
        if (!loopAllowed(host)) return
        colourTickers.remove(host)?.let(host::removeCallbacks)
        val tick = object : Runnable {
            private var lastColor = Int.MIN_VALUE
            override fun run() {
                if (!loopAllowed(host)) {
                    colourTickers.remove(host)
                    return
                }
                try {
                    val container = findContainer(host)
                    val signal = container?.let { findSignal(it) }
                    val color = tintFromRow(host)
                    if (signal != null && color != null && color != lastColor) {
                        lastColor = color
                        signal.setColor(color)
                        container?.let { findLabel(it)?.setTextColor(color) }
                    }
                    // 流量箭头每秒跟一次采样：不另开定时器，蹭这一秒一拍的颜色同步。
                    signal?.invalidate()
                } catch (_: Throwable) {
                }
                host.postDelayed(this, COLOUR_INTERVAL_MS)
            }
        }
        colourTickers[host] = tick
        host.postDelayed(tick, COLOUR_INTERVAL_MS)
    }

    private fun safeRestyle(root: View) {
        try {
            resolveResourceIdsOnce(root.context)
            walkAndFix(root)
        } catch (t: Throwable) {
            TrioHooks.log(TrioHooks.LOG_WARN, "dual-signal restyle failed: $t")
        }
    }

    /** Finds candidate groups: `mobile_combo` by id, or a horizontal LinearLayout with a mobile child. */
    private fun walkAndFix(v: View) {
        if (v is LinearLayout) {
            val byId = idMobileCombo != 0 && v.id == idMobileCombo
            val byCount = v.orientation == LinearLayout.HORIZONTAL && countMobileChildren(v) >= 1
            if (byId || byCount) {
                fixGroup(v)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { walkAndFix(it) }
            }
        }
    }

    private fun countMobileChildren(ll: LinearLayout): Int {
        var c = 0
        for (i in 0 until ll.childCount) {
            if (isMobileUnit(ll.getChildAt(i))) c++
        }
        return c
    }

    /** A mobile signal unit: by class name or by `mobile_combo` family id, never Wi-Fi. */
    private fun isMobileUnit(v: View?): Boolean {
        if (v == null) return false
        val cn = v.javaClass.name
        if (cn.contains("Wifi") || cn.contains("WiFi")) return false
        if (cn.contains("SignalUnit") || cn.contains("ModernStatusBarMobileView")) return true
        val id = v.id
        return (idMobileCombo != 0 && id == idMobileCombo) ||
            (idMobileComboCard != 0 && id == idMobileComboCard)
    }

    /**
     * Takes one group over: the host is the group's FrameLayout shell. Hides the native
     * group, injects the own container and registers the three maintenance paths.
     */
    private fun fixGroup(group: LinearLayout) {
        if (!sEnabled) return
        if (TrioConfig.appearance().foldsMobile()) return   // the trio glyph owns the signal
        val host = group.parent as? FrameLayout ?: return   // hard constraint: the shell is a FrameLayout
        if (backedOff[host] == true) return
        // Conflict guard, first come first served: the plugin's container already
        // present means this host stays the plugin's.
        if (hasForeignContainer(host)) {
            backOff(host)
            return
        }
        if (group.visibility != View.GONE) group.visibility = View.GONE

        val firstTime = !maintained.containsKey(host)
        if (firstTime) maintained[host] = true
        groups[host] = group
        ensureContainer(host)
        maintain(host, group, force = true)

        if (firstTime) {
            // Path 1: follow the host's relayouts (rebuilds, SIM swaps, tint changes).
            host.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val now = SystemClock.uptimeMillis()
                    if (now - lastLayoutMs < LAYOUT_THROTTLE_MS) return
                    lastLayoutMs = now
                    try {
                        maintain(host, group)
                    } catch (t: Throwable) {
                        TrioHooks.log(TrioHooks.LOG_WARN, "dual-signal maintain failed: $t")
                    }
                }
            })
            // Path 2/3：低频维护与着色。都带息屏/关闭/让位守卫，
            // 息屏时立即停摆，亮屏后由 ScreenStateTracker 监听重新拉起。
            ensureScreenListener()
            startTickers(host)
        }
        host.requestLayout()
    }

    /**
     * Periodic maintenance: keeps the native group hidden, the container alive, levels /
     * tint / label fresh, the host ordered before Wi-Fi and its margins tight. Any step
     * failing is swallowed by the callers.
     */
    private fun maintain(host: FrameLayout, group: LinearLayout, force: Boolean = false) {
        if (backedOff[host] == true) return
        probeDualSignalPackageOnce(host.context)
        // The plugin's container can appear after our injection (hook order is not
        // guaranteed) - recheck every round and yield the moment it shows up.
        if (hasForeignContainer(host)) {
            backOff(host)
            return
        }

        val a = TrioConfig.appearance()
        val trioOwns = a.foldsMobile()
        val container = findContainer(host)
        if (!sEnabled || trioOwns || StatusBarRowManager.suppressed) {
            // Stepping aside: hide the own reading. The native group is only touched
            // when the trio does not own it - while it does, hiding it is the trio's job.
            container?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
            if (!trioOwns && group.visibility != View.VISIBLE) group.visibility = View.VISIBLE
            return
        }
        if (group.visibility != View.GONE) group.visibility = View.GONE

        val live = container ?: run {
            ensureContainer(host)
            findContainer(host) ?: return
        }
        val signal = findSignal(live)
        val label = findLabel(live)

        // 布局路径不读电话状态：只有 [force] 为真时才跨进程查一次（首次接管、改配置、
        // 2 秒一次的维护），其余时候一律用缓存值。
        if (force) readReadings(host.context)
        val simCount = cachedSimCount
        if (simCount == 0) {
            if (live.visibility != View.GONE) live.visibility = View.GONE
            return
        }
        if (live.visibility != View.VISIBLE) live.visibility = View.VISIBLE

        val color = pickTint(group, host) ?: Color.WHITE
        signal?.setColor(color)
        label?.setTextColor(color)   // the label wears the signal's colour

        signal?.setInfo(
            simCount,
            cachedLevels.getOrElse(0) { 4 },
            cachedLevels.getOrNull(1),
        )

        updateLabel(label)

        moveBeforeWifi(host)

        // Right margin fixed at 2dp; the left one reads the first visible sibling before
        // the host - a TextView or a "speed" view (network speed) tightens it to 0dp.
        var leftDp = HOST_MARGIN_DP
        val row = host.parent as? ViewGroup
        if (row != null) {
            var i = row.indexOfChild(host) - 1
            while (i >= 0) {
                val pv = row.getChildAt(i)
                if (pv.visibility != View.VISIBLE) {
                    i--
                    continue
                }
                leftDp =
                    if (pv is TextView || pv.javaClass.name.lowercase().contains("speed")) {
                        HOST_MARGIN_TEXT_DP
                    } else {
                        HOST_MARGIN_DP
                    }
                break
            }
        }
        val hp = host.layoutParams
        if (hp is ViewGroup.MarginLayoutParams) {
            val wantR = dp(host, HOST_MARGIN_DP)
            val wantL = dp(host, leftDp)
            if (hp.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
                hp.rightMargin != wantR || hp.leftMargin != wantL
            ) {
                hp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                hp.rightMargin = wantR
                hp.leftMargin = wantL
                host.layoutParams = hp
            }
        }
    }

    /**
     * Conflict guard: probes the host's direct children for the DualSignal plugin's
     * container tag. Both modules hook the same attach points and their order is not
     * guaranteed; whoever finds the other's container first yields, and a late plugin
     * injection is caught here within one maintenance round.
     */
    private fun hasForeignContainer(host: FrameLayout): Boolean {
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (c is LinearLayout && (c.tag as? String) == FOREIGN_CONTAINER_TAG) return true
        }
        return false
    }

    private fun backOff(host: FrameLayout) {
        if (backedOff.put(host, true) == true) return
        maintained.remove(host)
        findContainer(host)?.let { host.removeView(it) }
        TrioHooks.log(
            TrioHooks.LOG_WARN,
            "dual-signal: DualSignal plugin container found (tag $FOREIGN_CONTAINER_TAG)," +
                " this host backs off (first come first served)",
        )
    }

    /** Logs once whether the DualSignal plugin package is installed; the guard itself keys on the tag. */
    private fun probeDualSignalPackageOnce(context: Context) {
        if (!pkgProbed.compareAndSet(false, true)) return
        val installed = runCatching {
            context.packageManager.getPackageInfo(DUAL_SIGNAL_PACKAGE, 0)
            true
        }.getOrDefault(false)
        TrioHooks.log(
            TrioHooks.LOG_INFO,
            if (installed) {
                "dual-signal: DualSignal plugin installed ($DUAL_SIGNAL_PACKAGE) - per-host backoff applies"
            } else {
                "dual-signal: DualSignal plugin not installed, no conflict"
            },
        )
    }

    /**
     * Injects the own container: a LinearLayout (tag [TAG_CONTAINER]) holding the drawn
     * signal view, plus the type label (tag [TAG_LABEL]) after it.
     *
     * The label view is created even while the label switch is off - it starts GONE and
     * [updateLabel] shows it when the switch comes on - so the sub-switch applies
     * without waiting for the host to be rebuilt.
     */
    private fun ensureContainer(host: FrameLayout) {
        if (findContainer(host) != null) return
        val ctx = host.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_CONTAINER
        }
        val signal = DualSignalBarsView(ctx)
        signal.tag = TAG_SIGNAL
        container.addView(signal, LinearLayout.LayoutParams(WRAP, WRAP))
        // Starts GONE: visibility is decided per round by updateLabel.
        val label = TextView(ctx)
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SIZE_SP)
        label.typeface = Typeface.DEFAULT_BOLD
        label.setIncludeFontPadding(false)
        label.tag = TAG_LABEL
        label.translationY = dp(host, LABEL_OFFSET_Y_DP).toFloat()
        label.visibility = View.GONE
        val llp = LinearLayout.LayoutParams(WRAP, WRAP)
        llp.leftMargin = dp(host, LABEL_MARGIN_LEFT_DP)
        llp.gravity = Gravity.CENTER_VERTICAL
        container.addView(label, llp)
        val flp = FrameLayout.LayoutParams(WRAP, WRAP)
        flp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        host.addView(container, flp)
    }

    private fun findContainer(host: FrameLayout): LinearLayout? {
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (c is LinearLayout && (c.tag as? String) == TAG_CONTAINER) return c
        }
        return null
    }

    private fun findSignal(container: LinearLayout): DualSignalBarsView? {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is DualSignalBarsView) return c
        }
        return null
    }

    private fun findLabel(container: LinearLayout): TextView? {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is TextView && (c.tag as? String) == TAG_LABEL) return c
        }
        return null
    }

    /**
     * Colour source: prefer a tinted ImageView inside the native group (computed by the
     * host's own dispatcher), fall back to the host's row siblings, then to white. Never
     * computes an inversion itself, so dark mode is followed automatically.
     */
    private fun pickTint(group: LinearLayout, host: FrameLayout): Int? {
        firstImageViewWithTint(group)?.imageTintList?.let { return it.defaultColor }
        return tintFromRow(host)
    }

    /** The colour of the first visible tinted ImageView in the host's row, or null. */
    private fun tintFromRow(host: FrameLayout): Int? {
        val row = host.parent as? ViewGroup ?: return null
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            firstImageViewWithTint(c)?.imageTintList?.let { return it.defaultColor }
        }
        return null
    }

    private fun firstImageViewWithTint(v: View?): ImageView? {
        if (v is ImageView) {
            if (v.imageTintList != null) return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                firstImageViewWithTint(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** Orders the host (with the drawn rows) just before the row's Wi-Fi view. */
    private fun moveBeforeWifi(host: FrameLayout) {
        val row = host.parent as? ViewGroup ?: return
        var wifi: View? = null
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            // One pass, no visibility filter: a class named wifi or the ethernet_combo id.
            if (c !== host &&
                (c.javaClass.name.lowercase().contains("wifi") ||
                    (idEthernetCombo != 0 && c.id == idEthernetCombo))
            ) {
                wifi = c
                break
            }
        }
        if (wifi == null) return
        val wi = row.indexOfChild(wifi)
        val hi = row.indexOfChild(host)
        // hi != wi-1: already immediately before Wi-Fi needs no remove/add churn.
        if (hi >= 0 && wi >= 0 && hi != wi - 1) {
            row.removeView(host)
            row.addView(host, wi - 1)
        }
    }

    // ---- Level reading (the SystemUI process holds the permissions; failures keep the last reading) ----

    /**
     * 缓存的读数。
     *
     * 下拉状态栏时窗口几乎每一帧都在 layout，而全局布局监听原来每次都要查
     * `activeSubscriptionInfoList`（一次读回整份 SubscriptionInfo 列表）和每张卡的
     * `signalStrength`：都是跨进程调用，单次就可能十几毫秒，正好吃掉拖拽中的那几帧。
     * 现在读数只在这三处刷新：首次接管、改配置、2 秒一次的维护；布局路径只用缓存值。
     */
    private var cachedSimCount = 2
    private var cachedLevels: List<Int> = emptyList()
    private var cachedLabel: String? = null

    /** 一次读取替换掉原来的 getSimCount + getActiveLevels 两次查询，只拉一遍订阅列表。 */
    private fun readReadings(ctx: Context) {
        val subs = runCatching {
            (ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager)
                ?.activeSubscriptionInfoList
        }.getOrNull() ?: return  // 读失败：保留上一次的读数
        cachedSimCount = subs.size
        cachedLevels = runCatching {
            val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return@runCatching emptyList<Int>()
            subs.sortedBy { it.simSlotIndex }.map { s ->
                runCatching {
                    tm.createForSubscriptionId(s.subscriptionId).signalStrength?.level ?: 4
                }.getOrDefault(4)
            }
        }.getOrDefault(emptyList())
        cachedLabel = if (sLabelEnabled && isMobileDataEnabled(ctx)) getNetworkLabel(ctx) else null
    }

    // ---- Type-label data chain ----

    /**
     * The type text for the default data SIM: `getDataNetworkType()`, falling back to
     * `getNetworkType()` when that reports 0; null when the manager itself cannot be
     * read (the caller treats null as "no text", i.e. hidden).
     *
     * Literal values rather than TelephonyManager constants: 19 (LTE_CA) is a
     * @SystemApi constant missing from the public android.jar.
     */
    private fun getNetworkLabel(ctx: Context): String? = runCatching {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val dtm = if (dataSub < 0) tm else tm.createForSubscriptionId(dataSub)
        @Suppress("DEPRECATION")   // getNetworkType only backs up a 0 from getDataNetworkType
        val type = dtm.dataNetworkType.let { if (it == 0) dtm.networkType else it }
        when (type) {
            20 -> "5G"   // NR
            13, 18, 19 -> "4G"   // LTE / IWLAN / LTE_CA
            3, 8, 9, 10, 15, 17 -> "3G"   // UMTS / HSDPA / HSUPA / HSPA / HSPAP / TD_SCDMA
            // GPRS / EDGE / CDMA / 1xRTT / EVDO_0 / EVDO_A / IDEN / EVDO_B / EHRPD / GSM
            1, 2, 4, 5, 6, 7, 11, 12, 14, 16 -> "2G"
            else -> "4G"   // unknown and new types default to 4G, as the upstream does
        }
    }.getOrDefault("4G")

    /**
     * `Settings.Global.getInt(cr, "mobile_data", 1) == 1`; an unreadable setting
     * (SecurityException etc.) reports true - better a stray label than a silent one.
     */
    private fun isMobileDataEnabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 1
    }.getOrDefault(true)

    /**
     * Label visibility: the label switch on AND mobile data on shows the type text;
     * anything else hides it. Unlike the upstream there is no Wi-Fi gate - the native
     * bar shows the cellular type alongside Wi-Fi too.
     */
    private fun updateLabel(label: TextView?) {
        if (label == null) return
        val text = cachedLabel
        if (text.isNullOrEmpty()) {
            if (label.visibility != View.GONE) label.visibility = View.GONE
            return
        }
        label.text = text
        if (label.visibility != View.VISIBLE) label.visibility = View.VISIBLE
    }

    private fun dp(host: View, value: Float): Int =
        Math.round(host.resources.displayMetrics.density * value)

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * The drawn dual-row signal. Rendering is delegated to [TrioRenderer.drawOutSignal] -
     * the exact code path that draws the out-of-ring SIM reading: capsule bars with the
     * dot row beneath for the second card, the compact two-row style when the stacked
     * style is selected, and the data-activity arrows when 「显示流量活动图标」 is on.
     * The view only supplies the box (sized from the out-signal appearance) and the
     * readings, which come straight from TelephonyManager rather than TrioState.
     */
    private class DualSignalBarsView(context: Context) : View(context) {
        private var dual = true
        private var mainLevel = 4
        private var subLevel = 4
        private var color = Color.WHITE

        fun setInfo(simCount: Int, main: Int, sub: Int?) {
            val dualNow = simCount >= 2 && sub != null
            val s = sub ?: 4
            if (dualNow != dual || main != mainLevel || s != subLevel) {
                dual = dualNow
                mainLevel = main
                subLevel = s
                requestLayout()
                invalidate()
            }
        }

        fun setColor(color: Int) {
            if (color != this.color) {
                this.color = color
                invalidate()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val a = TrioConfig.appearance()
            val density = resources.displayMetrics.density
            val h = TrioRenderer.outSignalHeight(a, a.outSignalSize, density, dual)
            val w = TrioRenderer.outSignalWidth(h, a, null, null, dual)
            setMeasuredDimension(w, h)
        }

        override fun onDraw(canvas: Canvas) {
            // onDraw 异常防护：渲染异常不能穿透 SystemUI。
            try {
                TrioRenderer.drawOutSignal(
                    canvas, width, height, mainLevel, if (dual) subLevel else -1,
                    color, TrioConfig.appearance(),
                )
            } catch (t: Throwable) {
                TrioHooks.log(TrioHooks.LOG_ERROR, "DualSignalBarsView.onDraw crashed: ${t.message}")
            }
        }
    }
}
