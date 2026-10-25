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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import io.github.odomu.honorduo.model.TrioState

/**
 * Event-driven Wi-Fi presence, independent of the icons this module hides.
 *
 * The icon chain only reports a level while Wi-Fi is connected, so switching
 * the radio off would leave the last level behind and the arcs would never go
 * away. Presence therefore comes from the framework: the radio's own state, the
 * connectivity snapshot, and a bounded low-frequency recheck for the deliveries
 * MagicOS occasionally drops.
 *
 * Nothing here runs on the drawing path; every entry is a broadcast, a network
 * callback or a posted watchdog tick.
 */
internal object WifiPresence {

    private val main = Handler(Looper.getMainLooper())

    private var registered = false
    private var appContext: Context? = null

    /** The live answer, or `null` while the framework has not said yet. */
    @Volatile
    private var connected: Boolean? = null

    val isConnected: Boolean
        get() = connected == true

    private var pendingRefresh: Runnable? = null

    /** Framework state when it is known, the icon view's own state otherwise. */
    fun resolve(iconVisible: Boolean, cachedLevel: Int): Boolean =
        connected ?: (iconVisible || cachedLevel >= 0)

    @Synchronized
    fun start(context: Context) {
        val app = context.applicationContext ?: context
        appContext = app
        ScreenStateTracker.addListener { screenOn ->
            if (screenOn) {
                main.post { scheduleRefresh(app) }
            } else {
                main.post { pendingRefresh?.let(main::removeCallbacks) }
            }
        }
        if (registered) return

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ignored: Context?, intent: Intent?) {
                // Re-query the framework rather than trust broadcast extras.
                refresh(context)
                scheduleRefresh(context)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // These can originate from the privileged Wi-Fi UID rather than
                // the system UID, so NOT_EXPORTED would drop them. Every action
                // here is a protected broadcast and nothing is read from the
                // delivery's extras, so EXPORTED grants nothing.
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            registered = true
            refresh(context)
            context.getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) = scheduleRefresh(context)
                    override fun onLost(network: Network) = scheduleRefresh(context)
                    override fun onCapabilitiesChanged(
                        network: Network,
                        capabilities: NetworkCapabilities,
                    ) = scheduleRefresh(context)
                },
            )
        } catch (_: RuntimeException) {
            // An unknown state stays on the icon view's own fallback.
        }
    }

    /**
     * Coalesces a delayed recheck.
     *
     * A broadcast can be delivered before ConnectivityManager's connected
     * snapshot, and the callbacks arrive after it; one recheck a second later
     * covers both orders without polling.
     */
    private fun scheduleRefresh(context: Context) {
        main.post {
            refresh(context)
            pendingRefresh?.let(main::removeCallbacks)
            val again = Runnable { refresh(context) }
            pendingRefresh = again
            main.postDelayed(again, RECHECK_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun refresh(context: Context) {
        try {
            val wifi = context.getSystemService(WifiManager::class.java) ?: return
            if (!wifi.isWifiEnabled) {
                // A definitive radio-off answer must not depend on a following
                // connectivity query, which can throw on a permission mismatch.
                publish(false)
                return
            }
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val network = connectivity?.getNetworkInfo(ConnectivityManager.TYPE_WIFI)
            if (connectivity == null) return

            val next = network != null && network.isConnected
            publish(next)
            TrioState.sWifiLevel = if (!next) {
                -1
            } else {
                // A hidden native icon may not rebind after a reconnect, so the
                // level is taken from the link itself here.
                val info = wifi.connectionInfo
                if (info != null && info.rssi > -127) {
                    WifiManager.calculateSignalLevel(info.rssi, WIFI_LEVELS)
                } else {
                    TrioState.sWifiLevel
                }
            }
            // The five-tier scale the glyph draws, from the same RSSI. Six buckets
            // give 0..5, and 0 (no signal at all) folds into the level the icon
            // chain already reports as absent.
            TrioState.sWifiLevel5 = if (!next) {
                -1
            } else {
                val info = wifi.connectionInfo
                if (info != null && info.networkId != -1 && info.rssi in -126..-1) {
                    (WifiManager.calculateSignalLevel(info.rssi, WIFI_TIERS) + 1).coerceIn(1, 5)
                } else {
                    TrioState.sWifiLevel5
                }
            }
            // Wi-Fi generation badge: getWifiStandard needs API 30, and anything
            // unreadable is 0 - "no badge" - rather than a crash.
            TrioState.sWifiStandard = if (TrioState.sWifiLevel5 > 0 &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
            ) {
                runCatching { wifi.connectionInfo?.wifiStandard ?: 0 }.getOrDefault(0)
            } else {
                0
            }
            sampleWifiTraffic(next)
            TrioHooks.invalidateHosts()
        } catch (_: RuntimeException) {
            // Permissions or ROM differences must never crash SystemUI.
        }
    }

    /**
     * The Wi-Fi half of the traffic arrows: a TrafficStats delta sample.
     *
     * The deltas are read on every refresh - broadcasts, network callbacks and the
     * one-second recheck all land here, which is a natural ~1..2s cadence. A positive
     * delta since the last sample lights the arrow for that direction; the baseline
     * is re-seeded on every call so the arrows decay on their own when traffic stops.
     */
    private fun sampleWifiTraffic(wifiUp: Boolean) {
        if (!wifiUp) {
            sLastRxBytes = -1L
            sLastTxBytes = -1L
            if (TrioState.sWifiTrafficUp || TrioState.sWifiTrafficDown) {
                TrioState.sWifiTrafficUp = false
                TrioState.sWifiTrafficDown = false
            }
            return
        }
        val rx = runCatching { android.net.TrafficStats.getTotalRxBytes() }.getOrDefault(-1L)
        val tx = runCatching { android.net.TrafficStats.getTotalTxBytes() }.getOrDefault(-1L)
        TrioState.sWifiTrafficDown = sLastRxBytes >= 0 && rx > sLastRxBytes
        TrioState.sWifiTrafficUp = sLastTxBytes >= 0 && tx > sLastTxBytes
        sLastRxBytes = rx
        sLastTxBytes = tx
    }

    /** TrafficStats baselines for the Wi-Fi delta sample; -1 = no baseline yet. */
    @Volatile
    private var sLastRxBytes = -1L

    @Volatile
    private var sLastTxBytes = -1L

    private fun publish(next: Boolean) {
        connected = next
        if (!next) {
            TrioState.sWifiLevel = -1
            TrioState.sWifiLevel5 = -1
            TrioState.sWifiStandard = 0
        }
        if (TrioState.setWifiPresent(next)) TrioHooks.invalidateHosts()
    }

    /** Levels the glyph draws: five states, 0..4. */
    private const val WIFI_LEVELS = 5

    /** Bucket count for the five-tier scale: calculateSignalLevel(rssi, 6) is 0..5. */
    private const val WIFI_TIERS = 6

    /** How long after a broadcast the framework is asked again. */
    private const val RECHECK_MS = 1_000L
}
