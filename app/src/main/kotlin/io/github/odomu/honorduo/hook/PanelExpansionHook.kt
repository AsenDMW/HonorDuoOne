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

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.TrioConfig
import io.github.odomu.honorduo.model.TrioState
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * Panel expansion tracking: feeds both the pull-down morph progress and the
 * "native icons while expanded" switch.
 *
 * Progress is collected through several channels because no single one fires on
 * every path: `HwPanelView.setExpandedHeightInternal` ticks per frame, the
 * `PanelView` endpoints mark expand and collapse, and the ShadeInteractor
 * queries cover the host reading its own state. The latest value wins.
 *
 * Everything is gated at run time through [TrioConfig.appearance], so flipping
 * a switch applies without a SystemUI restart.
 */
internal object PanelExpansionHook {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var fractionField: Field? = null
    @Volatile private var headsUpMethod: Method? = null
    @Volatile private var controllerPanelField: Field? = null

    /** Latest progress reported by a real channel, before the debug override. */
    @Volatile private var realFraction = 0f

    /** Progress source label, used by the diagnostic toast. */
    private var lastSource = ""

    /** Expanded-side memory for the 0.5 flip that fires the toast. */
    private var toastExpanded = false

    /** Debug-slider poll: lets the slider apply without any panel event. */
    private var pollStarted = false

    /** 息屏监听是否已经注册（只注册一次）。 */
    private var screenListenerAdded = false

    /** Collapse-settle polling state (main thread only). */
    private var collapseSettleController: WeakReference<Any>? = null
    private var collapseSettleAttempts = 0

    private const val SETTLE_INTERVAL_MS = 100L
    private const val SETTLE_MAX_ATTEMPTS = 10
    private const val POLL_INTERVAL_MS = 400L

    fun install(module: XposedModule, cl: ClassLoader): Int {
        var n = 0
        n += hookPanelView(module, cl)
        n += hookPanelBase(module, cl)
        n += hookShade(module, cl)
        n += hookShowingType(module, cl)
        ensureScreenListener()
        startPolling()
        return n
    }

    /**
     * `HwPanelView.setExpandedHeightInternal(float)`: per-frame during drags and
     * animations. Reads `mExpandedFraction` after proceed, feeds the morph driver
     * and flips the panel-expanded state.
     */
    private fun hookPanelView(module: XposedModule, cl: ClassLoader): Int {
        val panel = Refl.cls("com.android.systemui.statusbar.phone.HwPanelView", cl) ?: return 0
        return TrioHooks.hook(
            module, TrioHooks.methodNamed(panel, "setExpandedHeightInternal", 1),
            "honorduo-panel-expand",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                val self = chain.thisObject as? View ?: return@runCatching
                val f = fractionField(self.javaClass)
                    ?.let { Refl.get(it, self) as? Number }?.toFloat() ?: 0f
                onPanelFraction(self, f)
            }
            result
        }
    }

    /**
     * `PanelView` endpoints: expand/collapse give the target states,
     * `setExpandedFraction` gives per-frame interpolation on animated paths.
     */
    private fun hookPanelBase(module: XposedModule, cl: ClassLoader): Int {
        val base = Refl.cls("com.android.systemui.statusbar.phone.PanelView", cl) ?: return 0
        var n = 0
        n += TrioHooks.hook(
            module, TrioHooks.methodNamed(base, "setExpandedFraction", 1),
            "honorduo-panel-fraction",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                onMorphProgress((chain.getArg(0) as? Number)?.toFloat() ?: 0f, "setExpandedFraction")
            }
            result
        }
        n += TrioHooks.hook(
            module, TrioHooks.methodNamed(base, "expand", 1),
            "honorduo-panel-expand-call",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                val f = (chain.thisObject as? View)?.let { self ->
                    fractionField(self.javaClass)
                        ?.let { Refl.get(it, self) as? Number }?.toFloat()
                } ?: 1f
                onMorphProgress(if (f > 0f) f else 1f, "expand")
            }
            result
        }
        n += TrioHooks.hook(
            module, TrioHooks.methodNamed(base, "collapse", 0),
            "honorduo-panel-collapse",
        ) { chain ->
            val result = chain.proceed()
            runCatching { onMorphProgress(0f, "collapse") }
            result
        }
        n += TrioHooks.hook(
            module, TrioHooks.methodNamed(base, "instantCollapse", 0),
            "honorduo-panel-instant-collapse",
        ) { chain ->
            val result = chain.proceed()
            runCatching { onMorphProgress(0f, "instantCollapse") }
            result
        }
        return n
    }

    /** ShadeInteractor queries: covers the host reading expansion state itself. */
    private fun hookShade(module: XposedModule, cl: ClassLoader): Int {
        val shade = Refl.cls("com.android.systemui.shade.ShadeInteractorImpl", cl) ?: return 0
        var n = 0
        n += TrioHooks.hook(
            module, TrioHooks.methodNamed(shade, "getShadeExpansion", 0),
            "honorduo-shade-expansion",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                onMorphProgress((result as? Number)?.toFloat() ?: 0f, "shadeExpansion")
            }
            result
        }
        n += TrioHooks.hook(
            module, TrioHooks.methodNamed(shade, "isNotificationsExpanded", 0),
            "honorduo-shade-notifs",
        ) { chain ->
            val result = chain.proceed()
            runCatching { onMorphProgress(if (result == true) 1f else 0f, "notifsExpanded") }
            result
        }
        return n
    }

    /**
     * `HnPanelViewControllerImpl.setPanelShowingType(byte)`: 0 is the collapse
     * funnel every path ends in. It fires when the collapse animation starts, so
     * the fraction has not reached zero yet - a poll waits for the real zero.
     */
    private fun hookShowingType(module: XposedModule, cl: ClassLoader): Int {
        val controller =
            Refl.cls("com.android.systemui.statusbar.phone.HnPanelViewControllerImpl", cl)
                ?: return 0
        return TrioHooks.hook(
            module, TrioHooks.methodNamed(controller, "setPanelShowingType", 1),
            "honorduo-showing-type",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                if ((chain.getArg(0) as? Byte)?.toInt() == 0) {
                    chain.thisObject?.let { scheduleCollapseSettle(it) }
                }
            }
            result
        }
    }

    /**
     * Per-frame fraction update: drives the morph and flips the expanded state.
     *
     * A heads-up raise counts as expanded by the fraction but the user never
     * pulled the panel, so the bar row must keep the glyph; the check runs only
     * on the 0→1 flip, not per frame.
     */
    private fun onPanelFraction(panel: View, f: Float) {
        onMorphProgress(f, "setExpandedHeightInternal")
        var expanded = f > 0f
        if (expanded && !TrioState.sPanelExpanded) {
            val headsUp = headsUpMethod(panel.javaClass)
            if (headsUp != null && (Refl.invoke(headsUp, panel) as? Boolean) == true) {
                expanded = false
            }
        }
        if (expanded != TrioState.sPanelExpanded) {
            TrioState.sPanelExpanded = expanded
            // Rows re-settle: native icons come back and the glyph hides while the
            // panel is open (and the other way round on collapse).
            StatusBarRowManager.syncAll(TrioConfig.appearance(), force = true)
        }
    }

    /** Any channel reporting progress; safe from any thread. */
    private fun onMorphProgress(f: Float, source: String) {
        if (f.isNaN()) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyReal(f, source)
        } else {
            mainHandler.post { applyReal(f, source) }
        }
    }

    private fun applyReal(f: Float, source: String) {
        realFraction = f.coerceIn(0f, 1f)
        lastSource = source
        push()
    }

    /** The debug slider, when not on "auto", overrides the real progress. */
    private fun target(): Float {
        val dbg = TrioConfig.appearance().trioMorphDebug
        return if (dbg >= 0) dbg / 100f else realFraction
    }

    private fun push() {
        val a = TrioConfig.appearance()
        if (!a.morphLive) {
            if (TrioState.sMorphFraction != 0f) {
                TrioState.sMorphFraction = 0f
                StatusBarRowManager.invalidateAll()
            }
            return
        }
        val t = target()
        if (abs(t - TrioState.sMorphFraction) > 0.001f) {
            TrioState.sMorphFraction = t
            StatusBarRowManager.invalidateAll()
        }
        val nowExpanded = t > 0.5f
        if (nowExpanded != toastExpanded) {
            toastExpanded = nowExpanded
            if (a.trioMorphToast) {
                val ctx = StatusBarRowManager.snapshot().firstOrNull()?.row?.context
                if (ctx != null) {
                    runCatching {
                        Toast.makeText(
                            ctx,
                            "Trio morph ${if (nowExpanded) "expanded" else "collapsed"} ($lastSource)",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    /** Low-frequency poll so the debug slider applies with no panel event. */
    private val pollTask = object : Runnable {
        override fun run() {
            if (!ScreenStateTracker.isScreenOn) {
                // 熄屏后没有任何面板事件需要同步：停摆，亮屏时由监听重新拉起。
                pollStarted = false
                return
            }
            runCatching { push() }
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun startPolling() {
        if (pollStarted) return
        pollStarted = true
        mainHandler.postDelayed(pollTask, POLL_INTERVAL_MS)
    }

    /** 熄屏停摆、亮屏恢复：这条 400ms 轮询是常驻的，不能让它跨越息屏继续跑。 */
    private fun ensureScreenListener() {
        if (screenListenerAdded) return
        screenListenerAdded = true
        ScreenStateTracker.addListener { on ->
            mainHandler.post {
                if (on) {
                    startPolling()
                } else {
                    pollStarted = false
                    mainHandler.removeCallbacks(pollTask)
                }
            }
        }
    }

    /**
     * Collapse-settle poll: `setPanelShowingType(0)` arrives at the start of the
     * collapse animation, so every 100ms the fraction is re-read until it reaches
     * zero - only then is the glyph restored. A re-open mid-poll keeps the
     * fraction above zero and the poll gives up on its own.
     */
    private val collapseSettleTask = object : Runnable {
        override fun run() {
            val controller = collapseSettleController?.get() ?: return
            val fraction = readPanelFraction(controller)
            if (fraction <= 0f) {
                collapseSettleController = null
                onMorphProgress(0f, "collapseSettle")
                if (TrioState.sPanelExpanded) {
                    TrioState.sPanelExpanded = false
                    StatusBarRowManager.syncAll(TrioConfig.appearance(), force = true)
                }
                return
            }
            if (++collapseSettleAttempts >= SETTLE_MAX_ATTEMPTS) {
                collapseSettleController = null
                return
            }
            mainHandler.postDelayed(this, SETTLE_INTERVAL_MS)
        }
    }

    private fun scheduleCollapseSettle(controller: Any) {
        // The host repeats setPanelShowingType(0) two or three times per collapse;
        // an already running poll is reused rather than restarted.
        if (collapseSettleController != null) return
        collapseSettleAttempts = 0
        collapseSettleController = WeakReference(controller)
        mainHandler.removeCallbacks(collapseSettleTask)
        mainHandler.postDelayed(collapseSettleTask, SETTLE_INTERVAL_MS)
    }

    /** Reads `controller.mPanelView.mExpandedFraction`; -1 means unreadable. */
    private fun readPanelFraction(controller: Any): Float {
        val pvField = controllerPanelField
            ?: Refl.field(controller.javaClass, "mPanelView")?.also { controllerPanelField = it }
            ?: return -1f
        val panel = Refl.get(pvField, controller) ?: return -1f
        val fField = fractionField(panel.javaClass) ?: return -1f
        return (Refl.get(fField, panel) as? Number)?.toFloat() ?: -1f
    }

    private fun fractionField(owner: Class<*>): Field? {
        return fractionField ?: Refl.field(owner, "mExpandedFraction")?.also { fractionField = it }
    }

    private fun headsUpMethod(owner: Class<*>): Method? {
        return headsUpMethod ?: runCatching {
            owner.getMethod("isPanelVisibleBecauseOfHeadsUp")
        }.getOrNull()?.also { headsUpMethod = it }
    }
}
