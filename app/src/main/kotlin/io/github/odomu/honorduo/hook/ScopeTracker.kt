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

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * 模块生效范围的两个输入：锁屏来自平台自己的回调（不挂钩子），下拉面板来自唯一
 * 那个只上报切换的钩子。
 *
 * 面板类型拆成两个独立的事实——**哪一页**展开了、以及它是不是压在锁屏上。分开
 * 保留正是为了避免歧义：旧编码只把 2 当成控制中心，于是压在锁屏上的控制中心（4）
 * 被读成通知面板，「排除控制中心」在那一次下拉里就静默失效了。
 *
 * 移植自上游 HonorDuo 1.0.5 的 `hook/tracker/ScopeTracker.kt`，语义逐条对齐。
 */
internal object ScopeTracker {

    private const val PANEL_NONE = 0
    private const val PANEL_SHADE = 1
    private const val PANEL_CONTROL_CENTER = 2
    private const val PANEL_SHADE_OVER_KEYGUARD = 3
    private const val PANEL_CONTROL_CENTER_OVER_KEYGUARD = 4

    @Volatile
    private var panelType = PANEL_NONE

    @Volatile
    private var keyguardActive = false

    private var keyguardWatched = false

    /** 有面板展开着，不管是哪一页、也不管是不是压在锁屏上。 */
    val panelExpanded: Boolean
        get() = panelType != PANEL_NONE

    val shadeActive: Boolean
        get() = panelType == PANEL_SHADE || panelType == PANEL_SHADE_OVER_KEYGUARD

    val controlCenterActive: Boolean
        get() = panelType == PANEL_CONTROL_CENTER || panelType == PANEL_CONTROL_CENTER_OVER_KEYGUARD

    /**
     * 锁屏这一项。
     *
     * 刻意与面板独立：面板收起后类型可能在下一帧还非零（或某个 ROM 上那个切换钩子
     * 干脆不再触发），此时若把锁屏判断挂在面板上，「排除了锁屏却仍然显示」就会
     * 原样复现。锁屏就是锁屏，压不压着面板都一样。
     */
    val lockscreenActive: Boolean
        get() = keyguardActive

    /** 平台的锁屏服务，留给 [refreshKeyguard] 兜底查询。 */
    @Volatile
    private var keyguard: KeyguardManager? = null

    /**
     * 平台回调不可用时重读一次锁屏状态。
     *
     * 有些机型拒绝 `addKeyguardLockedStateListener`，那么标志位永远没人置：用户看到
     * 的是这一行失效，而不是监听没注册上。查询是一次 binder 调用，所以只在评估生效
     * 范围时跑（面板切换、设置变化），绝不逐帧。
     */
    fun refreshKeyguard() {
        val manager = keyguard ?: return
        val locked = try {
            manager.isKeyguardLocked
        } catch (_: Throwable) {
            return
        }
        if (locked != keyguardActive) keyguardActive = locked
    }

    @SuppressLint("NewApi", "MissingPermission")
    fun start(context: Context?) {
        val app = context?.applicationContext ?: context ?: return
        if (keyguardWatched) return
        val manager = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager ?: return
        keyguard = manager
        keyguardWatched = true
        // 平台回调：不挂钩子也不轮询。`isKeyguardLocked` 本身已经把「有 Activity 盖在
        // 锁屏上」的 occluded 情况算进去了。
        val listener = KeyguardManager.KeyguardLockedStateListener { locked ->
            if (locked == keyguardActive) return@KeyguardLockedStateListener
            keyguardActive = locked
            SystemBarHook.updateEffectiveScopeState()
        }
        try {
            manager.addKeyguardLockedStateListener(app.mainExecutor, listener)
            keyguardActive = manager.isKeyguardLocked
        } catch (_: Throwable) {
            keyguardWatched = false
            return
        }
        // 解锁广播兜底：个别 ROM 在解锁时不回调上面的 listener，只发这条广播。
        try {
            val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
            val unlockReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    keyguardActive = false
                    SystemBarHook.updateEffectiveScopeState()
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(unlockReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                app.registerReceiver(unlockReceiver, filter)
            }
        } catch (_: Throwable) {
        }
        SystemBarHook.updateEffectiveScopeState()
    }

    /**
     * 面板钩子喂进来的类型。
     *
     * 平台的编码是 0 收起、1 通知面板、2 控制中心，3/4 是同两页压在锁屏上——顺序
     * 就是这个，所以两个「压在锁屏上」的编号按面板类型读，而不是另立一个锁屏标志。
     */
    fun onPanelType(type: Int) {
        if (type == panelType) return
        panelType = type
        SystemBarHook.updateEffectiveScopeState()
    }
}
