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
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.TrioConfig
import io.github.odomu.honorduo.model.TrioState
import java.lang.reflect.Method

/**
 * Main Xposed hook coordinator and lifecycle dispatcher for MagicOS SystemUI.
 *
 * Dispatches hook installation and configuration updates to modular handlers:
 * - [SystemBarHook]: status-bar rows, battery, visibility suppression, and tint updates.
 * - [SignalIconHook]: cellular signal indicators and network type views.
 * - [StatusBarIconHook]: custom built-in Wi-Fi and system status icon replacements.
 * - [BarClockManager]: status-bar clock styling, dual-row date/weekday stacking, and positioning.
 * - [StatusBarRowManager]: container management, glyph views, capsule avoidance, and anti-burn-in.
 * - [PanelExpansionHook]: pull-down panel progress for the morph and native-icons switches.
 * - [CapsuleScaleHook]: keeps the bar unscaled while the capsule stays resident.
 * - [BtBatteryHook]: Bluetooth earphone battery level for the merged glyph.
 * - [SystemUiTweakHook]: hides the SIM 2 icon, the native network-type label, and
 *   the charging/hotspot/USB-debugging notifications.
 */
internal object TrioHooks {

    const val TAG = "HonorDuo"

    const val LOG_INFO = 4
    const val LOG_WARN = 5
    const val LOG_ERROR = 6

    @Volatile
    private var sModule: XposedModule? = null

    private var sScreenTrackerStarted = false

    fun install(module: XposedModule, cl: ClassLoader) {
        sModule = module
        TrioConfig.install(module)
        // 设置改动不热应用：改完（含恢复备份）需要重启 SystemUI 才生效。
        // 这里刻意不 addListener，TrioConfig 也只在进程启动时读一次。

        StatusBarRowManager.initFields(Refl.cls("com.android.systemui.statusbar.HwSystemIcons", cl) ?: Any::class.java)

        var hooked = 0
        hooked += SystemBarHook.install(module, cl)
        hooked += SignalIconHook.install(module, cl)
        hooked += BarClockManager.hookPivotXY(module, cl)
        hooked += StatusBarIconHook.install(module, cl)
        hooked += DualSignalHook.install(module, cl)
        hooked += SystemUiTweakHook.install(module, cl)
        hooked += CcVolumeHook.install(module, cl)
        hooked += ShadeHook.install(module, cl)
        hooked += PanelExpansionHook.install(module, cl)
        hooked += CapsuleScaleHook.install(module, cl)
        hooked += BtBatteryHook.install(module, cl)

        log(
            module,
            "HonorDuo installed, hooks=$hooked enabled=${TrioConfig.get().enabled}",
        )
    }

    private fun applyConfigChange() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { applyConfigChange() }
            return
        }
        val a = TrioConfig.appearance()
        if (debugLog()) {
            log(LOG_INFO, "config changed, rows=${StatusBarRowManager.rowsCount()} enabled=${a.glyph} style=${a.style}")
        }
        StatusBarRowManager.syncAll(a, force = true)
        BarClockManager.sync(a)
        StatusBarIconManager.applyIconVisibility(a)
        SystemBarHook.applyPrivacyChipVisibility(a)
        StatusBarIconHook.updateConfig(
            a.replaceWifiIcon,
            a.replaceStatusIcon,
            a.hideAlarmIcon,
            a.hideBluetoothIcon,
        )
        DualSignalHook.updateConfig(a.dualSignal, a.dualSignalLabel)
        SystemUiTweakHook.updateConfig(
            a.hideSim2,
            a.hideNetType,
            a.notifHideCharging,
            a.notifHideHotspot,
            a.notifHideUsb,
        )
        FpsTicker.onConfigChanged()
        BatteryPresence.onConfigChanged()
        TrioState.ensureDataActivitySampler()
    }

    fun ensureScreenStateTracker(context: Context) {
        if (sScreenTrackerStarted) return
        sScreenTrackerStarted = true
        ScreenStateTracker.start(context)
        ScreenStateTracker.addListener { screenOn ->
            val handler = Handler(Looper.getMainLooper())
            handler.post { onScreenStateChanged(screenOn) }
        }
    }

    private fun onScreenStateChanged(screenOn: Boolean) {
        StatusBarRowManager.onScreenStateChanged(screenOn)
        BarClockManager.onScreenStateChanged(screenOn)
        StatusBarIconManager.onScreenStateChanged(screenOn)
        if (screenOn) {
            TrioState.ensureDataActivitySampler()
            invalidateHosts()
            StatusBarIconManager.applyIconVisibility()
        }
    }

    fun invalidateHosts() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Handler(Looper.getMainLooper()).post { invalidateHosts() }
            return
        }
        StatusBarRowManager.invalidateAll()
        BarClockManager.sync(TrioConfig.appearance())
    }

    fun hook(
        module: XposedModule,
        m: Method?,
        id: String,
        hooker: XposedInterface.Hooker,
    ): Int {
        if (m == null) {
            log(module, "skip $id: method not found")
            return 0
        }
        return try {
            module.hook(m).setId(id).intercept { chain ->
                // 模块总开关是真正的「全部功能失效」闸门：关闭时所有 hook 一律透传，
                // 原生状态栏保持原样，设置里的其它改动也不会被任何 hook 采纳。
                if (!TrioConfig.appearance().enabled) {
                    chain.proceed()
                } else {
                    hooker.intercept(chain)
                }
            }
            1
        } catch (t: Throwable) {
            log(module, "skip $id: $t")
            0
        }
    }

    fun methodNamed(c: Class<*>?, name: String, arity: Int): Method? {
        if (c == null) return null
        return try {
            c.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.size == arity }
                ?.also { it.isAccessible = true }
        } catch (ignored: Throwable) {
            null
        }
    }

    fun log(module: XposedModule, msg: String) {
        try {
            module.log(LOG_INFO, TAG, msg)
        } catch (ignored: Throwable) {
            // logging must never break hook installation.
        }
    }

    /** Logs through the handle captured at install time. */
    fun log(priority: Int, msg: String) {
        val module = sModule ?: return
        try {
            module.log(priority, TAG, msg)
        } catch (ignored: Throwable) {
            // logging must never break a config change.
        }
    }

    /** True when the user asked for verbose logging. */
    fun debugLog(): Boolean = TrioConfig.debugLog()
}
