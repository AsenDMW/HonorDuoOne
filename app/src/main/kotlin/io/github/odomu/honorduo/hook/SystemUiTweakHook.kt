/*
 * Copyright (C) 2026 odomu and HonorDuo contributors
 *
 * This file is part of HonorDuo, a fork of HyperDuo, released under the GNU
 * General Public License v3.0. See the LICENSE file at the repository root, or
 * <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package io.github.odomu.honorduo.hook

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.model.TrioConfig
import java.lang.reflect.Method

/**
 * Three standalone SystemUI removals, ported from HonorHook's SystemUiHook:
 *
 * - **Hide SIM 2** ([Prefs.KEY_HIDE_SIM2]): drops the secondary SIM's status-bar
 *   signal icon at the data source. `MobileDataControllerImpl.updateSubs(List)` is
 *   the single rebuild point every signal cluster (status bar, shade header, lock
 *   screen) goes through; the hook hands it a copy of the subscription list with
 *   the slot-1 entry removed. Only with two SIMs present - a lone secondary SIM
 *   still shows. A copy is passed rather than mutating in place, because without a
 *   VSIM the host passes the upstream flow's own list through.
 * - **Hide network type** ([Prefs.KEY_HIDE_NET_TYPE]): removes the native 5G/4G
 *   label beside the signal bars. `SignalIconBinder.bind` sets the label GONE when
 *   the per-card view is built, and `IconViewBinder.bind` is the choke point the
 *   host's network-type StateFlow re-shows it through - hit there, the label goes
 *   GONE and the call is short-circuited, so the following `setVisibility(0)` lands
 *   on an empty view. Both Resource and Loaded icon branches pass through
 *   `IconViewBinder.bind`, which is why `HnSignalUtils.setIconForView` needs no
 *   second hook (SignalIconHook already owns one there).
 * - **Notification filter** ([Prefs.KEY_NOTIF_HIDE_CHARGING] /
 *   [Prefs.KEY_NOTIF_HIDE_HOTSPOT] / [Prefs.KEY_NOTIF_HIDE_USB]): the host's two
 *   pure-query gates - `HwNotificationFilterImpl.shouldFilterOut` (AOSP pipeline
 *   finalize filter) and `HwNotificationFeatureImpl.isDisableShownInShade` (Honor's
 *   own notification-center arbiter) - are proceeded as usual and OR'ed with this
 *   module's match, so the host's own keyguard/whitelist behaviour is untouched.
 *   Identities are matched by exact equality only (fail-open: a missing channel or
 *   group never matches), per the anchors verified on-device upstream.
 *
 * The hooks stay in place whatever the switches say and gate on the volatile flags
 * at call time, so a settings change applies without a SystemUI restart. Every
 * callback swallows anything it cannot handle - nothing may throw into the host.
 */
internal object SystemUiTweakHook {

    /** Unobfuscated Dagger-bound implementation (upstream jadx evidence, baseline 17.0.0.1). */
    private const val CLS_MOBILE_DATA_CONTROLLER = "com.android.systemui.plugins.signal.MobileDataControllerImpl"

    /** Unobfuscated per-card signal icon binder; `bind` is static, six parameters. */
    private const val CLS_SIGNAL_ICON_BINDER =
        "com.android.systemui.statusbar.pipeline.mobile.ui.binder.SignalIconBinder"

    /** Choke point both icon branches (Resource and Loaded) set drawables through. */
    private const val CLS_ICON_VIEW_BINDER = "com.android.systemui.common.ui.binder.IconViewBinder"

    /** Honor's notification pipeline filter implementation (the only subclass). */
    private const val CLS_HW_NOTIFICATION_FILTER =
        "com.android.systemui.statusbar.notification.HwNotificationFilterImpl"

    /** The notification center's shade arbiter - the `hw_disable_ntf_show_in_shade` read-out. */
    private const val CLS_HW_NOTIFICATION_FEATURE =
        "com.android.systemui.statusbar.notification.HwNotificationFeatureImpl"

    /** The secondary SIM is slot 2, `SubscriptionModel.slotId` being 0-based. */
    private const val HIDDEN_SLOT_ID = 1

    /** The type label's view-id entry names (normal and uct layouts). */
    private val TYPE_VIEW_NAMES = setOf("mobile_type", "mobile_signal_type")

    /** Charging notification: posted by the system manager, fast and plain charge share the id. */
    private const val CHARGE_NOTIF_PKG = "com.hihonor.systemmanager"
    private const val CHARGE_NOTIF_ID = 200001

    /** USB-mode status notification: one slot shared by charge/file/audio, anchored on all three. */
    private const val SETTINGS_USB_NOTIF_PKG = "com.android.settings"
    private const val SETTINGS_USB_NOTIF_CHANNEL = "SETTINGS_USB"
    private const val SETTINGS_USB_NOTIF_ID = 1000002

    /** Hotspot: posted by system_server, anchored on the AOSP-hardcoded group string. */
    private const val PKG_ANDROID = "android"
    private const val HOTSPOT_NOTIF_GROUP = "TetheredNotification"

    /** USB debugging: posted by system_server, anchored on the exact AOSP channel constant. */
    private const val ADB_NOTIF_CHANNEL = "DEVELOPER_IMPORTANT"

    @Volatile private var sHideSim2 = false
    @Volatile private var sHideNetType = false
    @Volatile private var sNotifHideCharging = false
    @Volatile private var sNotifHideHotspot = false
    @Volatile private var sNotifHideUsb = false

    fun install(module: XposedModule, cl: ClassLoader): Int {
        val a = TrioConfig.appearance()
        sHideSim2 = a.hideSim2
        sHideNetType = a.hideNetType
        sNotifHideCharging = a.notifHideCharging
        sNotifHideHotspot = a.notifHideHotspot
        sNotifHideUsb = a.notifHideUsb

        var hooked = 0
        hooked += hookUpdateSubs(module, cl)
        hooked += hookSignalIconBinder(module, cl)
        hooked += hookIconViewBinder(module, cl)
        hooked += hookNotifGate(module, cl, CLS_HW_NOTIFICATION_FILTER, "shouldFilterOut",
            "honorduo-notif-filter", argIsEntry = true)
        hooked += hookNotifGate(module, cl, CLS_HW_NOTIFICATION_FEATURE, "isDisableShownInShade",
            "honorduo-notif-shade", argIsEntry = false)
        return hooked
    }

    /** Applies a changed configuration; the next matching call sees the new switches. */
    fun updateConfig(
        hideSim2: Boolean,
        hideNetType: Boolean,
        notifHideCharging: Boolean,
        notifHideHotspot: Boolean,
        notifHideUsb: Boolean,
    ) {
        sHideSim2 = hideSim2
        sHideNetType = hideNetType
        sNotifHideCharging = notifHideCharging
        sNotifHideHotspot = notifHideHotspot
        sNotifHideUsb = notifHideUsb
    }

    // ---- Hide SIM 2 ----

    /**
     * `updateSubs` has two one-parameter void overloads, `(List)` and `(MobileCallBack)`;
     * only the list one rebuilds the per-card icons, so the parameter type disambiguates.
     */
    private fun hookUpdateSubs(module: XposedModule, cl: ClassLoader): Int {
        val c = Refl.cls(CLS_MOBILE_DATA_CONTROLLER, cl) ?: return 0
        val m = runCatching {
            c.declaredMethods.firstOrNull {
                it.name == "updateSubs" && it.parameterTypes.size == 1 &&
                    List::class.java.isAssignableFrom(it.parameterTypes[0])
            }?.also { it.isAccessible = true }
        }.getOrNull()
        return TrioHooks.hook(module, m, "honorduo-hide-sim2") { chain ->
            // A void method: the callback must return null - returning the proceed result
            // throws a ClassCastException inside libxposed and crashes the host.
            if (!sHideSim2) {
                chain.proceed()
                return@hook null
            }
            val kept = runCatching { filterSubs(chain.getArg(0) as? List<*>) }.getOrNull()
            if (kept == null) {
                chain.proceed()
            } else {
                try {
                    chain.proceedWith(chain.thisObject, arrayOf(kept))
                } catch (t: Throwable) {
                    // Fall back to the unfiltered list rather than dropping the update.
                    runCatching { chain.proceed() }
                }
            }
            null
        }
    }

    /**
     * The list minus the slot-1 entry, or null when nothing should change: under two
     * SIMs, or when `slotId` is unreadable (a silent no-op is better than a wrong one).
     */
    private fun filterSubs(orig: List<*>?): List<*>? {
        if (orig == null || orig.size < 2) return null
        val kept = orig.filter { sub ->
            val slot = sub?.let {
                runCatching { it.javaClass.getField("slotId").getInt(it) }.getOrNull()
            }
            slot != HIDDEN_SLOT_ID
        }
        return if (kept.size == orig.size) null else kept
    }

    // ---- Hide network type ----

    /**
     * `SignalIconBinder.bind`'s second argument is the per-card view; the label is set
     * GONE there before the host hands it to its coroutine bindings.
     */
    private fun hookSignalIconBinder(module: XposedModule, cl: ClassLoader): Int {
        val c = Refl.cls(CLS_SIGNAL_ICON_BINDER, cl) ?: return 0
        val m = TrioHooks.methodNamed(c, "bind", 6)
        return TrioHooks.hook(module, m, "honorduo-hide-net-type-bind") { chain ->
            runCatching {
                if (sHideNetType) {
                    (chain.getArg(1) as? View)?.let { hideMobileType(it) }
                }
            }
            chain.proceed()
            null
        }
    }

    /**
     * The network-type StateFlow re-shows the label on every emission through
     * `IconViewBinder.bind`: a hit goes GONE and the call is short-circuited, so the
     * host's following `setVisibility(0)` acts on a drawable-less, margin-less view.
     */
    private fun hookIconViewBinder(module: XposedModule, cl: ClassLoader): Int {
        val c = Refl.cls(CLS_ICON_VIEW_BINDER, cl) ?: return 0
        val m = TrioHooks.methodNamed(c, "bind", 2)
        return TrioHooks.hook(module, m, "honorduo-hide-net-type-icon") { chain ->
            val hit = runCatching {
                if (!sHideNetType) {
                    null
                } else {
                    (0..1).firstNotNullOfOrNull { i ->
                        (chain.getArg(i) as? ImageView)?.takeIf { isTypeIconView(it) }
                    }
                }
            }.getOrNull()
            if (hit != null) {
                runCatching { hit.visibility = View.GONE }
            } else {
                chain.proceed()
            }
            null
        }
    }

    /** A type-label view is recognised by its id's resource entry name (ids drift per build). */
    private fun isTypeIconView(view: View): Boolean {
        val name = runCatching {
            val id = view.id
            if (id == 0) null else view.resources.getResourceEntryName(id)
        }.getOrNull()
        return name != null && name in TYPE_VIEW_NAMES
    }

    /** Sets the card's `mobile_type` child GONE and clears its margin, so no slot remains. */
    private fun hideMobileType(cardView: View) {
        val typeView = findByEntryName(cardView, "mobile_type") ?: return
        runCatching {
            typeView.visibility = View.GONE
            (typeView.layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd = 0
        }
    }

    /** Recursive child lookup by resource entry name; numeric ids are never hard-coded. */
    private fun findByEntryName(root: View, entryName: String): View? {
        val res = root.resources
        val targetId = runCatching {
            res.getIdentifier(entryName, "id", root.context.packageName)
        }.getOrNull() ?: 0
        if (targetId == 0) return null
        if (root.id == targetId) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findByEntryName(root.getChildAt(i), entryName)?.let { return it }
            }
        }
        return null
    }

    // ---- Notification filter ----

    /**
     * Hooks a one-parameter boolean query method of the notification pipeline. The host
     * answer is always collected first and this module's match is OR'ed on top.
     *
     * `shouldFilterOut` takes a NotificationEntry (the sbn sits in its public field
     * `mSbn`); `isDisableShownInShade` takes the StatusBarNotification directly.
     */
    private fun hookNotifGate(
        module: XposedModule,
        cl: ClassLoader,
        className: String,
        methodName: String,
        id: String,
        argIsEntry: Boolean,
    ): Int {
        val c = Refl.cls(className, cl) ?: return 0
        val m: Method? = runCatching {
            c.declaredMethods.firstOrNull {
                it.name == methodName && it.parameterTypes.size == 1 &&
                    it.returnType == java.lang.Boolean.TYPE
            }?.also { it.isAccessible = true }
        }.getOrNull()
        return TrioHooks.hook(module, m, id) { chain ->
            val host = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
            val hit = runCatching {
                val arg = chain.getArg(0) ?: return@runCatching null
                val sbn = if (argIsEntry) {
                    runCatching { arg.javaClass.getField("mSbn").get(arg) }.getOrNull()
                } else {
                    arg
                }
                sbn?.let { matchesHiddenNotif(it) }
            }.getOrNull()
            if (hit != null) {
                TrioHooks.log(TrioHooks.LOG_INFO, "systemui-tweak: notification hidden: $hit")
            }
            host || (hit != null)
        }
    }

    /**
     * The hidden-notification identities, exact matches only (fail-open: an unreadable
     * channel or group never matches, so a user notification is never killed by mistake):
     * - charging: system-manager id 200001, or the settings USB-mode slot
     *   (pkg + channel + id, so tethering/assistant entries on the same channel survive);
     * - hotspot: system_server with the AOSP-hardcoded `TetheredNotification` group;
     * - USB debugging: system_server on the exact `DEVELOPER_IMPORTANT` channel (a bare
     *   id match would kill unrelated system_server notifications sharing the id space).
     */
    private fun matchesHiddenNotif(sbn: Any): String? {
        val pkg = sbn.callOrNull("getPackageName") as? String ?: return null
        val id = sbn.callOrNull("getId") as? Int
        val channel = sbn.callOrNull("getNotification")?.callOrNull("getChannelId") as? String
        if (sNotifHideCharging && id != null &&
            (pkg == CHARGE_NOTIF_PKG && id == CHARGE_NOTIF_ID ||
                pkg == SETTINGS_USB_NOTIF_PKG && id == SETTINGS_USB_NOTIF_ID &&
                    channel == SETTINGS_USB_NOTIF_CHANNEL)
        ) {
            return "charging($pkg/$id)"
        }
        val group = sbn.callOrNull("getGroup") as? String
        if (sNotifHideHotspot && pkg == PKG_ANDROID && group == HOTSPOT_NOTIF_GROUP) {
            return "hotspot($pkg/group=$group)"
        }
        if (sNotifHideUsb && pkg == PKG_ANDROID && channel == ADB_NOTIF_CHANNEL) {
            return "usb-debug($pkg/id=$id/channel=$channel)"
        }
        return null
    }

    /**
     * Reflective no-arg call, null on any failure. The sbn is never cast: the compile-time
     * android.jar lacks StatusBarNotification, and a cast would clash with the host's
     * boot classpath class identity at runtime.
     */
    private fun Any?.callOrNull(name: String): Any? =
        runCatching { this?.javaClass?.getMethod(name)?.invoke(this) }.getOrNull()
}
