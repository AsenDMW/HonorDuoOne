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

import android.content.Intent
import android.media.session.MediaSessionManager
import android.os.Process
import android.os.UserHandle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.TrioConfig
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 控制中心音量条长按弹荣耀媒体输出弹窗（移植自 HonorDuoBar 1.1.3 的
 * CcVolumeLongPressFeature，与「媒体控件」磁贴右上角按钮同款：HnMediaDialogImpl）。
 *
 * 不依赖媒体卡片是否创建——直接复刻系统入口
 * `MediaOutputSwitcherDialogUI.showMediaOutputSwitcher`：
 *   1. hook `MediaOutputSwitcherDialogUI.start()` 缓存实例，取其
 *      `mMediaOutputDialogManager` / `mHnMediaDialogExProvider`；
 *   2. 长按时 `HnMediaDialogEx.inflateDialogViewAsync(true)` 预载视图，再以当前活跃媒体
 *      会话（MediaSessionManager.getActiveSessions 首个，无媒体时按原生兜底用 systemui
 *      包名）调用 `MediaOutputDialogManager.create(...)`；
 *   3. 缓存缺失等异常路径回退系统广播 `LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG`。
 *
 * 长按检测：hook `HnCentralSliderView.dispatchTouchEvent`（按实例类名过滤，仅响应
 * QsVolumeContainer 音量条，亮度条不受影响）；DOWN 后 postDelayed(长按阈值)，MOVE 超
 * touch slop 或 UP/CANCEL 取消；事件一律放行，滑动调音量不受影响。
 */
internal object CcVolumeHook {

    private const val HOST_PACKAGE = "com.android.systemui"
    private const val CLS_SLIDER_VIEW = "com.android.systemui.settings.brightness.HnCentralSliderView"
    private const val CLS_QS_VOLUME = "com.android.systemui.qsvolume.QsVolumeContainer"
    private const val CLS_SWITCHER_UI = "com.android.systemui.media.dialog.MediaOutputSwitcherDialogUI"
    private const val CLS_CC_MEDIA_VIEW = "com.android.systemui.media.HnControlCenterMediaView"
    private const val CLS_LOADING_ICON = "com.android.systemui.media.HnMediaLoadingIconControllerImpl"
    private const val ACTION_MEDIA_OUTPUT =
        "com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG"

    /** 每个容器实例的在途长按任务（identityHashCode → Runnable，UP/移动时 removeCallbacks）。 */
    private val pending = ConcurrentHashMap<Int, Runnable>()

    /** DOWN 起点坐标（identityHashCode → [x, y]），用于 MOVE 超 slop 取消。 */
    private val downPos = ConcurrentHashMap<Int, FloatArray>()

    /** MediaOutputSwitcherDialogUI 实例（start() 时缓存）。 */
    @Volatile
    private var switcherUiRef: WeakReference<Any>? = null

    /** MediaOutputDialogManager.create 的 8 参方法（start 挂载时解析缓存）。 */
    @Volatile
    private var createDialogMethod: Method? = null

    fun install(module: XposedModule, cl: ClassLoader): Int {
        var n = 0
        n += installSwitcher(module, cl)
        n += installSlider(module, cl)
        return n
    }

    /** 缓存司仪实例与 create 方法；CoreStartable，SystemUI 启动必走。 */
    private fun installSwitcher(module: XposedModule, cl: ClassLoader): Int {
        val cls = Refl.cls(CLS_SWITCHER_UI, cl) ?: return 0
        return TrioHooks.hook(module, Refl.method(cls, "start"), "honorduo-ccvol-switcher") { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { ui ->
                switcherUiRef = WeakReference(ui)
                if (createDialogMethod == null) {
                    createDialogMethod = runCatching {
                        val clsUi = ui.javaClass
                        val f = clsUi.getDeclaredField("mMediaOutputDialogManager")
                            .also { it.isAccessible = true }
                        val manager = f.get(ui)
                        manager.javaClass.getMethod(
                            "create",
                            String::class.java,
                            Boolean::class.javaPrimitiveType,
                            cl.loadClass(CLS_CC_MEDIA_VIEW),
                            cl.loadClass(CLS_LOADING_ICON),
                            Boolean::class.javaPrimitiveType,
                            UserHandle::class.java,
                            android.media.session.MediaSession.Token::class.java,
                            Boolean::class.javaPrimitiveType,
                        )
                    }.onFailure {
                        TrioHooks.log(TrioHooks.LOG_WARN, "ccvol: 解析 create 方法失败: ${it.message}")
                    }.getOrNull()
                }
            }
            result
        }
    }

    private fun installSlider(module: XposedModule, cl: ClassLoader): Int {
        val cls = Refl.cls(CLS_SLIDER_VIEW, cl) ?: return 0
        return TrioHooks.hook(
            module, Refl.method(cls, "dispatchTouchEvent", MotionEvent::class.java),
            "honorduo-ccvol-slider",
        ) { chain ->
            val view = chain.thisObject as? View
            val event = chain.args.firstOrNull() as? MotionEvent
            if (view != null && event != null && view.javaClass.name == CLS_QS_VOLUME &&
                TrioConfig.appearance().ccVolumeLongpress
            ) {
                runCatching {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> startLongPress(view, event.x, event.y, module)
                        MotionEvent.ACTION_MOVE -> {
                            val start = downPos[view.key()]
                            if (start != null) {
                                val slop = ViewConfiguration.get(view.context).scaledTouchSlop
                                if (kotlin.math.abs(event.x - start[0]) > slop ||
                                    kotlin.math.abs(event.y - start[1]) > slop
                                ) {
                                    cancelLongPress(view)
                                }
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> cancelLongPress(view)
                    }
                }
            }
            chain.proceed()
        }
    }

    private fun View.key(): Int = System.identityHashCode(this)

    private fun startLongPress(view: View, x: Float, y: Float, module: XposedModule) {
        cancelLongPress(view)
        downPos[view.key()] = floatArrayOf(x, y)
        val runnable = Runnable {
            pending.remove(view.key())
            downPos.remove(view.key())
            runCatching { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
            triggerMediaOutput(view, module)
        }
        pending[view.key()] = runnable
        view.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
    }

    private fun cancelLongPress(view: View) {
        pending.remove(view.key())?.let { view.removeCallbacks(it) }
        downPos.remove(view.key())
    }

    /**
     * 复刻 MediaOutputSwitcherDialogUI.showMediaOutputSwitcher：
     * inflateDialogViewAsync(true) + manager.create(pkg, true, null, null, true, user, token, true)。
     * 异常/缓存缺失时回退系统广播（AOSP 设备面板）。
     */
    private fun triggerMediaOutput(view: View, module: XposedModule) {
        val ui = switcherUiRef?.get()
        val create = createDialogMethod
        if (ui != null && create != null) {
            val ok = runCatching {
                val cls = ui.javaClass
                val manager = cls.getDeclaredField("mMediaOutputDialogManager")
                    .also { it.isAccessible = true }.get(ui)
                val provider = cls.getDeclaredField("mHnMediaDialogExProvider")
                    .also { it.isAccessible = true }.get(ui)
                val dialogEx = provider?.javaClass?.getMethod("get")?.invoke(provider)
                if (manager == null || dialogEx == null) return@runCatching false
                // 当前活跃媒体会话（SystemUI 持 MEDIA_CONTENT_CONTROL，可取）
                val msm = view.context.getSystemService(MediaSessionManager::class.java)
                val controller = runCatching { msm.getActiveSessions(null).firstOrNull() }.getOrNull()
                val pkg = controller?.packageName?.takeIf { it.isNotEmpty() } ?: HOST_PACKAGE
                val token = controller?.sessionToken
                dialogEx.javaClass.getMethod("inflateDialogViewAsync", Boolean::class.javaPrimitiveType)
                    .invoke(dialogEx, true)
                create.invoke(manager, pkg, true, null, null, true, Process.myUserHandle(), token, true)
                true
            }.getOrElse {
                TrioHooks.log(TrioHooks.LOG_WARN, "ccvol: 荣耀媒体弹窗调用失败: ${it.message}")
                false
            }
            if (ok) {
                TrioHooks.log(TrioHooks.LOG_INFO, "ccvol: 长按音量条，已弹出荣耀媒体输出弹窗")
                return
            }
        }
        runCatching {
            view.context.sendBroadcast(Intent(ACTION_MEDIA_OUTPUT).setPackage(HOST_PACKAGE))
            TrioHooks.log(TrioHooks.LOG_INFO, "ccvol: 长按音量条，回退弹出系统媒体输出面板")
        }.onFailure {
            TrioHooks.log(TrioHooks.LOG_WARN, "ccvol: 弹出媒体输出面板失败: ${it.message}")
        }
    }
}
