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
 * Bluetooth earphone battery level for the merged glyph.
 *
 * Rides the native cache: after `HwPhoneStatusBarPolicy.updateBluetooth` runs,
 * `mBluetooth.mBluetoothBatteryLevel` holds the level the stock icon just used,
 * so reading it adds no polling of its own.
 *
 * When the level is unknown (typical right after a SystemUI restart, before any
 * BATTERY_LEVEL_CHANGED broadcast) the level is seeded through the HEADSET
 * profile proxy: `BluetoothDevice.getBatteryLevel` reads the system-side cache,
 * and writing it back into the native field makes both the stock icon and the
 * glyph show the level without a headset reconnect.
 */
internal object BtBatteryHook {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var policyBluetoothField: Field? = null
    @Volatile private var controllerLevelField: Field? = null

    /** 原生「B+电量」粘滞位；见 [clearSticky]。 */
    @Volatile private var earBatteryFlagField: Field? = null
    @Volatile private var batteryEnableMethod: java.lang.reflect.Method? = null

    /** 粘滞态清理进行中：清理后重调 updateBluetooth 会再次进入本 hook。 */
    @Volatile private var clearing = false

    /** Seeding re-entry guard: the seeded updateBluetooth re-enters this hook. */
    @Volatile private var seeding = false

    /** 只读状态播种的防重入位（见 [seedState]）。 */
    @Volatile private var stateSeeding = false

    /** 只读播种的兜底解锁时间：代理回调不来时不让守卫永久卡住。 */
    private const val STATE_SEED_TIMEOUT_MS = 5_000L

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
                // 电量有两个使用方：卡 1 信号点上的弧线/数字（由开关决定），以及排布页
                // 里的蓝牙电量正方环（由「有没有放这一件」决定）。所以门控是「开关开着
                // 或放了这一件」，只看开关会让只放正方环的用户永远取不到电量，格子空着。
                if (a.trioBtBattery <= 0 && !a.placed(Prefs.PART_BT_BATTERY)) return@runCatching
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
                    TrioState.sBtBatteryLevel = v
                    mainHandler.post {
                        TrioHooks.invalidateHosts()
                        // 电量已知→融合态生效，原生蓝牙单件随之隐藏；断开/未知→交还宿主。
                        StatusBarIconManager.applyIconVisibility()
                    }
                }
                if (v !in 0..100) {
                    if (a.trioBtBattery > 0) {
                        // 粘滞位清理必须在播种之前：断开/改连无电量设备时原生会残留
                        // 「B+电量」图标；清掉后重调 updateBluetooth，原生按连接态重绘。
                        clearSticky(controller, self)
                        seed(controller, levelField, self)
                    } else {
                        // 只放正方环：不碰原生缓存与图标，只补模块自己的电量状态。
                        seedState(self)
                    }
                }
            }
            result
        }
    }

    /**
     * 清理原生「B+电量」粘滞态（移植自 HonorDuoBar 1.1.3）。
     *
     * `mIsBluetoothEarBattery` 只在蓝牙整体关闭时复位，设备断开（含改连电动车等无电量
     * 设备）时原生 receiver 的清理会被 `getBatteryLevel` 缓存防护跳过，导致
     * `getSuggestBluetoothIcon` 用残留电量图标覆盖普通 B 图标。条件：电量未知
     * （`mBluetoothBatteryLevel ∉ 0..100`）且粘滞位仍置位——置 false 后重调
     * `updateBluetooth` 让原生按当前连接态重绘普通图标；重调会再过一次本钩子，此时
     * 粘滞位已清，不再重复清理（另有 [clearing] 防重入）。
     */
    private fun clearSticky(controller: Any, policy: Any) {
        if (clearing) return
        val flag = earBatteryFlagField
            ?: Refl.field(controller.javaClass, "mIsBluetoothEarBattery")
                ?.also { earBatteryFlagField = it } ?: return
        if (!Refl.getBool(flag, controller, false)) return
        val setter = batteryEnableMethod ?: runCatching {
            controller.javaClass.getMethod("setBluetoothBatteryEnable", Boolean::class.javaPrimitiveType)
        }.getOrNull()?.also { batteryEnableMethod = it } ?: return
        clearing = true
        try {
            setter.invoke(controller, false)
            policy.javaClass.getMethod("updateBluetooth").invoke(policy)
            TrioHooks.log(TrioHooks.LOG_INFO, "bt: 已清理蓝牙电量图标粘滞态（无电量设备/未连接）")
        } catch (_: Throwable) {
            // 清理失败不影响后续播种。
        } finally {
            clearing = false
        }
    }

    /**
     * Seeds the native battery cache from the HEADSET profile proxy.
     *
     * The native seeding path hands the raw 0..100 value to
     * `updateBluetoothEarBattery`, which only accepts 1..10 tiers and rejects
     * it, so the stock icon itself also waits for the next broadcast. The level
     * is written back with the receiver's own rounding, then `updateBluetooth`
     * is invoked to refresh the icon - that call re-enters this hook with a
     * known level, so no second seeding happens.
     */
    private fun seed(controller: Any, levelField: Field, policy: Any) {
        if (seeding) return
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) return
        val contextField = Refl.field(policy.javaClass, "mContext") ?: return
        val context = Refl.get(contextField, policy) as? Context ?: return
        seeding = true
        runCatching {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        val device = runCatching { proxy.connectedDevices?.firstOrNull() }.getOrNull()
                        // getBatteryLevel is not in the compile-time android.jar (it
                        // exists at runtime and the stock code calls it directly), so
                        // it is invoked reflectively.
                        val level = device?.let {
                            runCatching {
                                it.javaClass.getMethod("getBatteryLevel").invoke(it) as Int
                            }.getOrDefault(-1)
                        } ?: -1
                        val address = device?.address
                        if (level in 1..100 && !address.isNullOrEmpty()) {
                            mainHandler.post {
                                runCatching {
                                    levelField.setInt(controller, level)
                                    val rounded = if (level > 10) Math.round(level / 10f) else 1
                                    controller.javaClass
                                        .getMethod(
                                            "updateBluetoothEarBattery",
                                            Int::class.javaPrimitiveType,
                                            String::class.java,
                                        )
                                        .invoke(controller, rounded, address)
                                    policy.javaClass.getMethod("updateBluetooth").invoke(policy)
                                }
                            }
                        }
                    }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    seeding = false
                }

                override fun onServiceDisconnected(profile: Int) {
                    seeding = false
                }
            }, BluetoothProfile.HEADSET)
        }.onFailure { seeding = false }
    }

    /**
     * 只补模块状态的兜底播种（开关关闭、只放了正方环时用）。
     *
     * 与 [seed] 走同一个 HEADSET 代理读同一个 `getBatteryLevel`，但**不写原生缓存、
     * 不调 `updateBluetoothEarBattery`**，所以原生蓝牙图标保持原样。重启 SystemUI 后
     * 电量广播到来之前，正方环也能立即出数；查不到设备（断开/无电量）时置 -1 清掉旧值。
     */
    private fun seedState(policy: Any) {
        if (stateSeeding) return
        val adapter = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()
        if (adapter == null || !runCatching { adapter.isEnabled }.getOrDefault(false)) {
            mainHandler.post {
                TrioState.sBtBatteryLevel = -1
                TrioHooks.invalidateHosts()
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
                        TrioHooks.invalidateHosts()
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
