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
                if (TrioConfig.appearance().trioBtBattery <= 0) return@runCatching
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
                    // 粘滞位清理必须在播种之前：断开/改连无电量设备时原生会残留
                    // 「B+电量」图标；清掉后重调 updateBluetooth，原生按连接态重绘。
                    clearSticky(controller, self)
                    seed(controller, levelField, self)
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
}
