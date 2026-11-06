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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.github.odomu.honorduo.model.TrioConfig
import io.github.odomu.honorduo.model.TrioState
import java.net.URLDecoder
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Manages real-time weather query from MagicOS TotemWeather ContentProvider,
 * listening for content updates and broadcasts with strict power-saving guards.
 */
internal object WeatherManager {

    private val WEATHER_URI = "content://com.hihonor.android.weather/weatherInfo".toUri()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "HonorDuo-Weather").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())

    private val WEATHER_KEYWORDS = arrayOf(
        "雷阵雨", "冰雹", "暴雨", "大雨", "中雨", "小雨", "雨夹雪", "阵雨", "雨",
        "暴雪", "大雪", "中雪", "小雪", "阵雪", "雪",
        "沙尘暴", "浮尘", "扬沙", "大雾", "雾", "霾", "阴", "多云", "晴",
    )

    data class WeatherData(val condition: String, val tempC: Int) {
        fun text(): String = "$condition ${tempC}°C"
        fun tempText(): String = "${tempC}°C"
    }

    @Volatile
    var data: WeatherData? = null
        private set

    @Volatile
    var currentWeather: String = ""
        private set

    @Volatile private var started = false
    @Volatile private var lastFetchTime = 0L
    private const val CACHE_EXPIRY_MS = 30 * 60 * 1000L // 30 minutes to eliminate battery drain

    /** 供应商连续 notify 的合并窗口：一串通知只查一次。 */
    private const val NOTIFY_DEBOUNCE_MS = 600L

    /** 自己这次查询引起的回响通知在这个窗口内丢掉，避免「查→notify→查」自激。 */
    private const val NOTIFY_SUPPRESS_MS = 1_500L

    /** 通知驱动的查询最小间隔：天气没有秒级变化，一分钟一次已经足够。 */
    private const val NOTIFY_MIN_GAP_MS = 60_000L

    @Volatile private var notifyScheduled = false
    @Volatile private var suppressNotifyUntil = 0L
    @Volatile private var lastNotifyFetch = 0L

    fun start(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext ?: context

        try {
            app.contentResolver.registerContentObserver(WEATHER_URI, true, object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) {
                    onProviderChanged(app)
                }
            })
        } catch (_: Throwable) {}

        try {
            val filter = IntentFilter().apply {
                addAction("com.hihonor.android.action.WEATHER_CHANGE")
                addAction("com.hihonor.android.action.WIDGET_CHANGE")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    onProviderChanged(app)
                }
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } catch (_: Throwable) {}

        ScreenStateTracker.addListener { on ->
            if (on) onScreenOn(app)
        }

        fetchAsync(app, force = true)
    }

    /**
     * 供应商数据变化的唯一入口（内容观察 + 广播都走这里）。
     *
     * 以前通知一到就 `fetchAsync(force = true)`：force 会绕过 30 分钟缓存，而供应商
     * 在自己的数据被读取/更新后往往还会再 notify 一次，于是「查询 → notify → 查询」
     * 可以自激；通知中心展开时正撞上这串查询，主线程连续收到状态栏重排与日期重算，
     * 展开动画就掉帧。现在把一串通知合并成一次查询，并让查询走缓存判定。
     */
    private fun onProviderChanged(app: Context) {
        if (!ScreenStateTracker.isScreenOn) return
        // 这次通知就是上一次查询自己引起的回响，丢掉。
        val now = SystemClock.elapsedRealtime()
        if (now < suppressNotifyUntil) return
        if (now - lastNotifyFetch < NOTIFY_MIN_GAP_MS) return
        if (notifyScheduled) return
        notifyScheduled = true
        main.postDelayed({
            notifyScheduled = false
            if (!ScreenStateTracker.isScreenOn) return@postDelayed
            lastNotifyFetch = SystemClock.elapsedRealtime()
            // 数据确实变了：只把旧缓存打旧，查询仍走非强制路径（由时间戳与上面的
            // 静默窗口兜底），这样即使供应商反复 notify 也只会退化成一次查询。
            lastFetchTime = 0L
            fetchAsync(app)
        }, NOTIFY_DEBOUNCE_MS)
    }

    fun onScreenOn(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFetchTime > CACHE_EXPIRY_MS) {
            fetchAsync(context, force = false)
        }
    }

    fun fetchAsync(context: Context, force: Boolean = false) {
        // Suspend weather queries during screen-off to eliminate battery drain
        if (!ScreenStateTracker.isScreenOn) return
        val a = TrioConfig.appearance()
        if (!a.enabled || (!a.clockWeather && !a.shadeWeather)) return
        val now = System.currentTimeMillis()
        // The stamp alone answers "did we try recently", so a failed or still-empty
        // read is not re-attempted on every caller. It used to require `data != null`,
        // which meant the cache never applied to the one case that needed it: no data yet.
        if (!force && now - lastFetchTime < CACHE_EXPIRY_MS) return
        lastFetchTime = now
        executor.execute {
            if (!ScreenStateTracker.isScreenOn) return@execute
            // 查询本身可能让供应商再 notify 一次：先开静默窗口，回响不再触发查询。
            suppressNotifyUntil = SystemClock.elapsedRealtime() + NOTIFY_SUPPRESS_MS
            try {
                val queried = queryWeather(context)
                if (queried != null && queried != data) {
                    data = queried
                    currentWeather = queried.text()
                    TrioState.sWeather = queried.text()
                    main.post {
                        val a = TrioConfig.appearance()
                        // 只有状态栏时钟会用天气参与字形排版；只开通知中心天气时再跑
                        // 一遍 sync，等于在面板动画里白排一次整条状态栏。
                        if (a.clockWeather) BarClockManager.sync(a)
                        ShadeHook.onWeatherChanged()
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    /**
     * Re-reads the weather after a settings change that could turn the feature on.
     */
    fun onConfigChanged(context: Context) {
        val a = TrioConfig.appearance()
        if (a.enabled && (a.clockWeather || a.shadeWeather)) {
            fetchAsync(context, force = true)
        }
    }

    private fun queryWeather(context: Context): WeatherData? =
        queryFromCall(context) ?: queryFromCursor(context)

    /**
     * Queries weather via the system weather provider's `call` method (MagicOS
     * standard). Some ROMs expose current weather only through this call and leave
     * the cursor table empty, so the call is tried first.
     */
    private fun queryFromCall(context: Context): WeatherData? = runCatching {
        val cr = context.contentResolver ?: return null
        val extras = android.os.Bundle().apply {
            putInt("city_type", 10)
            putInt("dayAndNightWeather", 1)
        }
        val bundle = cr.call(WEATHER_URI, "query_home_city_weather", null, extras) ?: return null
        val json = bundle.getString("key_home_city_weather")?.takeIf { it.isNotEmpty() } ?: return null
        val root = org.json.JSONObject(json)
        val days = root.optJSONArray("weather") ?: return null
        val dayItems = (0 until days.length()).mapNotNull { days.optJSONObject(it) }
        val today = dayItems.firstOrNull { it.optInt("day_index") == 1 } ?: return null

        val now = System.currentTimeMillis()
        val sunrise = today.optLong("sunrise_time")
        val sunset = today.optLong("sunset_time")
        val isNight = sunrise > 0L && sunset > 0L && (now < sunrise || now > sunset)
        val prefix = if (isNight) "night_" else ""

        val temp = today.optDouble("${prefix}current_temperature", Double.NaN)
            .takeIf { !it.isNaN() } ?: return null
        val fahrenheit = root.optString("temp_unit").equals("F", true)
        val condition = today.optString("${prefix}weather_native_des").trim()
            .takeIf { it.isNotEmpty() }
            ?: mapIconToCondition(today.optInt("${prefix}weather_icon", -1))

        val tempC = if (fahrenheit) ((temp - 32.0) * 5.0 / 9.0).roundToInt() else temp.roundToInt()
        WeatherData(condition, tempC)
    }.getOrNull()

    /** Fallback query via ContentProvider table cursor. */
    private fun queryFromCursor(context: Context): WeatherData? {
        val cr = context.contentResolver ?: return null
        return cr.query(
            WEATHER_URI,
            arrayOf("temperature", "weather_text", "weather_icon", "mobile_link"),
            null, null,
            "update_time DESC LIMIT 1",
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val tempIdx = cursor.getColumnIndex("temperature")
            val iconIdx = cursor.getColumnIndex("weather_icon")
            val textIdx = cursor.getColumnIndex("weather_text")
            val linkIdx = cursor.getColumnIndex("mobile_link")

            val tempVal = if (tempIdx >= 0 && !cursor.isNull(tempIdx)) cursor.getFloat(tempIdx) else Float.NaN
            if (tempVal.isNaN()) return null

            val tempC = if (tempVal > 45f) {
                ((tempVal - 32f) * 5f / 9f).roundToInt()
            } else {
                tempVal.roundToInt()
            }

            var condition: String? = null
            if (textIdx >= 0 && !cursor.isNull(textIdx)) {
                val t = cursor.getString(textIdx)
                if (!t.isNullOrBlank()) condition = t.trim()
            }

            if (condition == null && linkIdx >= 0 && !cursor.isNull(linkIdx)) {
                condition = parseConditionFromLink(cursor.getString(linkIdx))
            }

            if (condition == null && iconIdx >= 0 && !cursor.isNull(iconIdx)) {
                condition = mapIconToCondition(cursor.getInt(iconIdx))
            }

            WeatherData(condition ?: "晴", tempC)
        }
    }

    private fun parseConditionFromLink(link: String?): String? {
        if (link == null) return null
        val idx = link.indexOf("diffSummary=")
        if (idx < 0) return null
        val start = idx + 12
        var end = link.indexOf('&', start)
        if (end < 0) end = link.length
        val decoded = runCatching { URLDecoder.decode(link.substring(start, end), "UTF-8") }.getOrNull() ?: return null
        return WEATHER_KEYWORDS.firstOrNull { decoded.contains(it) }
    }

    private fun mapIconToCondition(icon: Int): String = when (icon) {
        1, 2, 33 -> "晴"
        3, 4, 34, 47 -> "多云"
        7, 8, 35, 46 -> "阴"
        11, 45 -> "雾"
        in 14..15 -> "雷阵雨"
        in 16..20, 12, 13, 75 -> "雨"
        in 21..25 -> "雪"
        in 26..29 -> "沙尘"
        in 30..32 -> "霾"
        else -> "晴"
    }
}
