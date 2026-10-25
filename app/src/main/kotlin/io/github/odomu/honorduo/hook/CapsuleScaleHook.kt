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

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.TrioConfig
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * The always-on-capsule switch: while the capsule sits on the island the host
 * scales the whole status bar down; with the switch on, the bar keeps its size.
 *
 * The main path rides the system's own restore animation: once the shrink
 * animation ends, `startStatusBarScaleAnim(false, false)` (the same entry the
 * system uses when the capsule closes) springs the bar back. The onLayout
 * fallback in [SystemBarHook] snaps any leftover scale set without an
 * animation.
 */
internal object CapsuleScaleHook {

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var scaleAnimField: Field? = null
    @Volatile private var expandStateField: Field? = null
    @Volatile private var startScaleAnimMethod: Method? = null

    /** True while the system's restore spring runs; the layout fallback stays out. */
    @Volatile private var restoreRunning = false

    fun install(module: XposedModule, cl: ClassLoader): Int {
        val controller = Refl.cls(
            "com.android.systemui.statusbar.phone.HnPhoneStatusBarAnimController", cl,
        ) ?: return 0
        return TrioHooks.hook(
            module, TrioHooks.methodNamed(controller, "updateCapsuleState", 1),
            "honorduo-capsule-state",
        ) { chain ->
            val result = chain.proceed()
            runCatching {
                if (!TrioConfig.appearance().capsuleNoScale) return@runCatching
                // state 1 = capsule resident (shrink animation just started). Only 1
                // is restored: 2 is the tap-to-expand (bar gone on purpose) and 0 is
                // the close, which the system springs back itself.
                val state = (chain.getArg(0) as? Number)?.toInt()
                if (state == 1) chain.thisObject?.let { scheduleScaleRestore(it) }
            }
            result
        }
    }

    /**
     * Waits for the shrink animation to end, then triggers the system's restore.
     * A null animator is the forbid path (no animation at all), so the state is
     * flipped back at once.
     */
    private fun scheduleScaleRestore(controller: Any) {
        val field = scaleAnimField
            ?: Refl.field(controller.javaClass, "mStatusBarScaleAnim")?.also { scaleAnimField = it }
            ?: return
        val anim = Refl.get(field, controller) as? AnimatorSet
        if (anim != null) {
            restoreRunning = true
            anim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // Posted out of the callback stack: updateCapsuleState(0) would
                    // otherwise re-enter through end().
                    mainHandler.post { runCatching { invokeNativeScaleRestore(controller) } }
                }
            })
        } else {
            invokeNativeScaleRestore(controller)
        }
    }

    /** Calls `startStatusBarScaleAnim(false, false)`, the capsule-close restore entry. */
    private fun invokeNativeScaleRestore(controller: Any) {
        // Restore only while the capsule is still resident (state 1); when the user
        // already closed it the system springs back on its own.
        val stateField = expandStateField
            ?: Refl.field(controller.javaClass, "mCapsuleExpandState")?.also { expandStateField = it }
        if (stateField != null && Refl.getInt(stateField, controller, -1) != 1) {
            restoreRunning = false
            return
        }
        val method = startScaleAnimMethod ?: runCatching {
            controller.javaClass.getDeclaredMethod(
                "startStatusBarScaleAnim",
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
            ).also { it.isAccessible = true }
        }.getOrNull()?.also { startScaleAnimMethod = it } ?: run {
            restoreRunning = false
            return
        }
        runCatching { method.invoke(controller, false, false) }
        val upAnim = scaleAnimField?.let { Refl.get(it, controller) } as? AnimatorSet
        if (upAnim != null) {
            upAnim.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    restoreRunning = false
                }
            })
        } else {
            restoreRunning = false
        }
    }

    /**
     * Layout fallback: snaps the subtree's scale back to 1 for the animation-less
     * paths (the forbid mode sets scale directly). Skipped while the system's
     * restore spring runs, so the spring is never cut short.
     */
    fun resetSubtreeScale(view: View) {
        if (restoreRunning) return
        if (view.scaleX != 1.0f || view.scaleY != 1.0f) {
            view.scaleX = 1.0f
            view.scaleY = 1.0f
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                view.getChildAt(i)?.let { resetSubtreeScale(it) }
            }
        }
    }
}
