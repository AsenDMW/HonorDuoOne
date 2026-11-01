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

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.Prefs
import io.github.odomu.honorduo.model.TrioConfig
import io.github.odomu.honorduo.model.TrioState
import java.lang.reflect.Field

/**
 * Bluetooth earphone battery level for the square-ring component.
 *
 * Rides the native cache: after `HwPhoneStatusBarPolicy.updateBluetooth` runs,
 * `mBluetooth.mBluetoothBatteryLevel` holds the level the stock icon just used,
 * so reading it adds no polling of its own.
 *
 * When the level is unknown (typical right after a SystemUI restart, before any
 * BATTERY_LEVEL_CHANGED broadcast) the level is read through the HEADSET
 * profile proxy (`BluetoothDevice.getBatteryLevel`), without touching the
 * native cache or icons.
 */
internal object BtBatteryHook {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var policyBluetoothField: Field? = null
    @Volatile private var controllerLevelField: Field? = null

    /** 只读状态播种的防重入位（见 [seedState]）。 */
    @Volatile private var stateSeeding = false

    /** 只读播种的兜底解锁时间：代理回调不来时不让守卫永久卡住。 */
    private const val STATE_SEED_TIMEOUT_MS = 5_000L

    /** 一次已排队但还没跑的重画（见 [requestRedraw]）。 */
    @Volatile private var redrawPending = false

    private val redrawTask = Runnable {
        redrawPending = false
        TrioHooks.invalidateGlyphs()
    }

    /**
     * 合并重画：TWS 左右耳和充电仓各自上报电量，一次变化可能连来好几条，
     * 每条都单独 post 会让主线程排队重画。字形只重画自身，不触发时钟重排。
     */
    private fun requestRedraw() {
        if (redrawPending) return
        redrawPending = true
        mainHandler.post(redrawTask)
    }

    fun install(module: XposedModule, cl: ClassLoader): Int {
        val policy = Refl.cls(
            "com.android.systemui.statusbar.phone.HwPhoneStatusBarPolicy", cl,
        ) ?: return 0
        return TrioHooks.hook(
            module, TrioHooks.methodNamed(policy, "updateBluetooth", 0),
            "honorduo-bt-battery",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                val a = TrioConfig.appearance()
                if (!a.enabled) return@runCatching
                // 这个电量现在只有一个使用方：排布页里的蓝牙电量正方环。没放这一件就
                // 不需要取。
                if (!a.placed(Prefs.PART_BT_BATTERY)) return@runCatching
                val self = chain.thisObject ?: return@runCatching
                val policyField = policyBluetoothField
                    ?: Refl.field(self.javaClass, "mBluetooth")?.also { policyBluetoothField = it }
                    ?: return@runCatching
                val controller = Refl.get(policyField, self) ?: return@runCatching
                val levelField = controllerLevelField
                    ?: Refl.field(controller.javaClass, "mBluetoothBatteryLevel")
                        ?.also { controllerLevelField = it }
                    ?: return@runCatching
                val v = (Refl.get(levelField, controller) as? Number)?.toInt() ?: -1
                if (v != TrioState.sBtBatteryLevel) {
                    val wasKnown = TrioState.sBtBatteryLevel in 0..100
                    TrioState.sBtBatteryLevel = v
                    // 只重画字形：电量每跳一格都走 invalidateHosts 会把时钟重新排版并
                    // requestLayout 整条状态栏，连着跳几格下拉就掉帧。
                    requestRedraw()
                    // 仅在蓝牙融合态翻转（未知↔已知）时重排原生图标可见性。
                    // 电量在 1-100 之间微调不影响融合态，递归遍历整个状态栏视图树
                    // （含反射读 mSlot）会把下拉状态栏拖卡，故跳过。
                    if (wasKnown != (v in 0..100)) {
                        mainHandler.post { StatusBarIconManager.applyIconVisibility() }
                    }
                }
                if (v !in 0..100) {
                    // 只补模块自己的电量状态，不碰原生缓存与图标。
                    seedState(self)
                }
            }
            result
        }
    }

    /**
     * 只补模块状态的兜底播种。
     *
     * 走 HEADSET 代理读 `getBatteryLevel`，但**不写原生缓存、不调
     * `updateBluetoothEarBattery`**，所以原生蓝牙图标保持原样。重启 SystemUI 后
     * 电量广播到来之前，正方环也能立即出数；查不到设备（断开/无电量）时置 -1 清掉旧值。
     */
    private fun seedState(policy: Any) {
        if (stateSeeding) return
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            mainHandler.post {
                TrioState.sBtBatteryLevel = -1
                requestRedraw()
            }
            return
        }
        val contextField = Refl.field(policy.javaClass, "mContext") ?: return
        val context = Refl.get(contextField, policy) as? Context ?: return
        stateSeeding = true
        // 代理连接回调可能永远不来（蓝牙恰好在关、或 getProfileProxy 被拒），超时后
        // 解锁防重入位，让下一次 updateBluetooth 还能再试一次，不必等 SystemUI 重启。
        mainHandler.postDelayed({ stateSeeding = false }, STATE_SEED_TIMEOUT_MS)
        runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    var level = -1
                    if (profile == BluetoothProfile.HEADSET) {
                        val device = runCatching { proxy.connectedDevices?.firstOrNull() }.getOrNull()
                        level = device?.let {
                            runCatching {
                                it.javaClass.getMethod("getBatteryLevel").invoke(it) as Int
                            }.getOrDefault(-1)
                        } ?: -1
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    mainHandler.post {
                        TrioState.sBtBatteryLevel = level
                        requestRedraw()
                        stateSeeding = false
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    stateSeeding = false
                }
            }, BluetoothProfile.HEADSET)
        }.onFailure { stateSeeding = false }
    }
}
