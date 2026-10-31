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

package io.github.odomu.honorduo.model

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import io.github.odomu.honorduo.hook.TrioHooks
import io.github.odomu.honorduo.model.TrioConfig.prefs
import io.github.odomu.honorduo.model.TrioConfig.publish
import io.github.odomu.honorduo.model.TrioConfig.snapshot
import io.github.libxposed.api.XposedInterface

/**
 * Hook-side snapshot of the user's settings.
 *
 * The remote [SharedPreferences] handed to a hooked process is read-only, but it
 * is live: the framework pushes `put`/`delete` bundles over Binder and re-fires the
 * change listeners. A single listener is registered once and every change applies
 * the changed key to the published snapshot, then pokes the rendered hosts so the
 * next frame uses the new value.
 *
 * Every read falls back to the [Prefs] default, which keeps the module working
 * when the framework is embedded and cannot serve remote preferences at all.
 */
object TrioConfig {

    /** Notified (on whatever thread the framework used) after a value changed. */
    fun interface Listener {
        fun onConfigChanged()
    }

    private val lock = Any()

    /**
     * Registered listeners, held strongly and on purpose.
     *
     * A weak list is tempting but silently wrong here: the only listeners are lambdas
     * created inside [TrioHooks.install], so nothing else keeps them reachable. They were
     * collected on the first GC after install, after which a settings change updated the
     * snapshot but repainted nothing. The module lives in the hooked process for its whole
     * lifetime, so a strong reference cannot outlive its usefulness.
     */
    private val listeners = ArrayList<Listener>()

    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var debugLog = Prefs.DEF_DEBUG_LOG

    // Values are published together, so a reader never sees a mixed snapshot.
    @Volatile
    private var snapshot: TrioSettings = TrioSettings.defaults()

    /**
     * [snapshot] resolved into the drawing rules, kept in step by [publish].
     *
     * Cached because [appearance] is read from hot paths - every `setVisibility` in
     * SystemUI goes through the suppression rule - and resolving it allocates.
     */
    @Volatile
    private var appearanceCache: TrioAppearance = TrioAppearance.of(snapshot)

    /** Publishes a new snapshot and its resolved rules; the rules first, so a reader
     *  that sees the new snapshot never pairs it with the old rules. */
    private fun publish(next: TrioSettings) {
        appearanceCache = TrioAppearance.of(next)
        snapshot = next
    }

    /**
     * Binds to the framework's remote preferences. Safe to call repeatedly; only the
     * first successful call registers a listener.
     */
    @JvmStatic
    fun install(xposed: XposedInterface) {
        if (prefs != null) return
        val remote = try {
            xposed.getRemotePreferences(Prefs.NAME)
        } catch (t: Throwable) {
            // Embedded framework (or a very old one): run on defaults.
            TrioHooks.log(TrioHooks.LOG_WARN, "remote preferences unavailable: $t")
            return
        }

        prefs = remote
        reload()
        // 刻意不注册改动监听：设置只在 SystemUI 启动时读一次，与 HonorHook / HonorDuoBar
        // 一致，改完必须重启 SystemUI 才生效（恢复备份同样如此）。

        // Re-read once the framework has had time to serve the user's values.
        //
        // install() runs inside the first hook callback of a booting SystemUI, and on a
        // cold start the remote map can still hold defaults for a few seconds: the daemon
        // has not pushed the user's file across yet. The first [reload] then publishes an
        // all-defaults snapshot, so the early layout passes do nothing and the native icons
        // stay on the bar. The change listener cannot heal that - it only fires on writes,
        // and nobody writes at boot - so one delayed re-read closes the window.
        val main = Handler(Looper.getMainLooper())
        main.postDelayed({ reloadIfChanged() }, RESYNC_DELAY_MS)
        main.postDelayed({ reloadIfChanged() }, RESYNC_DELAY_MS * 4)
    }

    /** How long after install to re-read the remote map, in milliseconds. */
    private const val RESYNC_DELAY_MS = 5_000L

    /**
     * Re-reads everything and notifies only when something actually moved - the boot-time
     * backstop for the early read having seen defaults.
     */
    private fun reloadIfChanged() {
        val previous = snapshot
        reload()
        val now = snapshot
        if (previous === now || previous == now) return
        TrioHooks.log(TrioHooks.LOG_INFO, "delayed settings re-read applied")
        notifyChanged()
    }

    /** Immutable view of every setting the renderer cares about. */
    @JvmStatic
    fun get(): TrioSettings = snapshot

    /** The same snapshot resolved into the drawing and suppression rules. */
    @JvmStatic
    fun appearance(): TrioAppearance = appearanceCache

    @JvmStatic
    fun debugLog(): Boolean = debugLog

    @JvmStatic
    fun addListener(listener: Listener?) {
        if (listener == null) return
        synchronized(lock) { listeners.add(listener) }
    }

    /*
     * 设置改动走远程偏好监听热应用（见 [install]），不再依赖重启 SystemUI。
     *
     * 这里刻意不注册任何 reload 广播接收器。上游曾用 `ACTION_RELOAD` 广播把整份
     * 快照推给宿主，但那个 action 字符串沿用自 HonorDuo 上游，任何一份仍装着旧版
     * HonorDuo 的设备上，其已激活的 SystemUI 钩子都会收到我们设置 App 发出的广播并
     * 立即应用——于是「本模块没在 LSPosed 里激活」也能改状态栏。广播主路径随之在
     * 设置 App 侧一并移除（见 SettingsRepository）。
     */

    /**
     * The framework fires once per changed key. Only that key's value is guaranteed fresh,
     * so it is applied on its own rather than by re-reading the whole snapshot, which would
     * re-publish stale values for every key whose diff never arrived.
     */
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
        if (!applyKey(changed, key)) return@OnSharedPreferenceChangeListener
        if (debugLog) {
            TrioHooks.log(
                TrioHooks.LOG_INFO,
                "remote pref changed: $key -> enabled=${snapshot.enabled}" +
                    " ring=${snapshot.ringStroke} arc=${snapshot.arcStroke}" +
                    " size=${snapshot.valueSize}",
            )
        }
        notifyChanged()
    }

    /** Copies one key's fresh value into the published snapshot. */
    private fun applyKey(changed: SharedPreferences?, key: String?): Boolean {
        if (changed == null || key == null) return false
        return try {
            val next = snapshot.copy()
            // Not a key this module renders with; nothing to repaint.
            if (!next.applyKeyFrom(TrioSettings.from(changed), key)) return false
            debugLog = next.debugLog
            publish(next)
            true
        } catch (t: Throwable) {
            // A type mismatch in the framework's cast must never break drawing.
            TrioHooks.log(TrioHooks.LOG_WARN, "cannot read preferences: $t")
            false
        }
    }

    /**
     * Reads the whole configuration once, when the framework connects. A full read is right
     * here and only here: the map is authoritative at install time and nothing has been
     * published yet that it could be stale against.
     */
    private fun reload() {
        val changed = prefs ?: return
        try {
            publish(TrioSettings.from(changed))
            debugLog = changed.getBoolean(Prefs.KEY_DEBUG_LOG, Prefs.DEF_DEBUG_LOG)
        } catch (t: Throwable) {
            TrioHooks.log(TrioHooks.LOG_WARN, "cannot read preferences: $t")
        }
    }

    private fun notifyChanged() {
        val alive: List<Listener>
        synchronized(lock) { alive = ArrayList(listeners) }
        for (l in alive) {
            try {
                l.onConfigChanged()
            } catch (t: Throwable) {
                // One bad listener must not stop the others.
                TrioHooks.log(TrioHooks.LOG_WARN, "config listener failed: $t")
            }
        }
    }
}
