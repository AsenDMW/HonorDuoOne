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

package io.github.odomu.honorduo.ui


import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.odomu.honorduo.R
import io.github.odomu.honorduo.model.Prefs
import io.github.odomu.honorduo.model.TrioAppearance
import io.github.odomu.honorduo.model.TrioSettings
import io.github.odomu.honorduo.render.TrioZones
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * Content controls shared by General and the visual editor.
 *
 * **Declaration order is the order they appear in a component's sheet**, and it
 * follows one rule: *where a thing goes, before how it looks*. A row that decides
 * placement comes first, because it answers the question the user opened the sheet with
 * ("where is this drawn?"). Size, weight and colour rows live in [geometrySliders] and
 * come after these, in their own groups.
 *
 * Only one placement choice is left, and only one piece has it: the network type's
 * "show it beside the ring". Every other piece is placed or released - add it to a third
 * and it is drawn there, do not and MagicOS keeps its own icon - so the old "signal in ring /
 * out of ring" switch, and the "stacked signal" style that went with it, were asking the
 * user to choose between states the layout already decides.
 */
internal enum class ContentControl(val owner: TrioSettingsPart) {
    Enabled(TrioSettingsPart.Appearance),
    MergeIcons(TrioSettingsPart.Appearance),
    Style(TrioSettingsPart.Appearance),
    AntiBurnIn(TrioSettingsPart.Appearance),
    SignalColumns(TrioSettingsPart.Mobile),
    Wifi(TrioSettingsPart.Wifi),
    ReplaceWifiIcon(TrioSettingsPart.Wifi),
    ReplaceStatusIcon(TrioSettingsPart.Appearance),
    /**
     * The signal card: one switch for the aggregate meter, then one per card.
     *
     * The two per-card rows are rows of THIS card rather than cards of their own.
     * They were separate cards, and both sheets resolved to the same set of settings -
     * two entries in the library that could only ever repeat each other. The ink is
     * one kind of thing (a row of level dots); which reading it shows is a property of
     * the row, not a second component.
     */
    Mobile(TrioSettingsPart.Mobile),
    DataSim(TrioSettingsPart.Mobile),
    /**
     * The out-of-ring rendering: the module's own stacked reading, or MagicOS' icon.
     *
     * Owned by the signal card rather than being a piece of its own, because it is a
     * property of where the signal is drawn, not a third reading. It sits directly under
     * the position row it depends on.
     */
    Stacked(TrioSettingsPart.Mobile),
    MobileActivity(TrioSettingsPart.Mobile),
    /**
     * Bluetooth earbud battery in place of the SIM 1 dots: off, an arc, or a number.
     *
     * 由「蓝牙电量」卡片持有，而不是信号卡：设置页那张卡原来只说明排布页里可以放一个
     * 正方环组件，点进去却没有任何属于它的行，是一张空弹层。画法本身仍借用卡 1 的
     * 点位（字形把弧线/数字画在主卡信号点原本的位置），但行的归属跟着用户找它的地方走。
     */
    BtBattery(TrioSettingsPart.BtBattery),
    /**
     * The dual-row signal: the module's own two-row reading in place of the native
     * cluster. A property of the signal card rather than a piece of the glyph, and
     * mutually exclusive with it - while the glyph draws the signal, the dual rows
     * step aside.
     */
    DualSignal(TrioSettingsPart.Mobile),
    /** The network-type label beside the dual rows; only exists with [DualSignal] on. */
    DualSignalLabel(TrioSettingsPart.Mobile),
    Sim1(TrioSettingsPart.Mobile),
    Sim2(TrioSettingsPart.Mobile),
    Value(TrioSettingsPart.Battery),
    Percent(TrioSettingsPart.Battery),
    /**
     * A charger puts the number in the ring's centre, over whatever the layout put there.
     *
     * Owned by the percentage: it is a rule about that reading rather than a third kind
     * of content. Resolved per frame, so nothing stored changes when the cable moves.
     */
    ChargingShowsValue(TrioSettingsPart.Battery),
    /**
     * The fill breathing while charging: a property of the battery ink, not a reading.
     */
    ChargeBreath(TrioSettingsPart.Battery),
    Bolt(TrioSettingsPart.Bolt),
    BoltAnimEnabled(TrioSettingsPart.Bolt),
    BoltAnim(TrioSettingsPart.Bolt),
    /**
     * Network type, and the ONE piece that has a position choice of its own.
     *
     * Every other piece is placed or released: add it to a third and it is drawn there,
     * do not and MagicOS keeps its own icon. There is no third option, which is why the old
     * "signal in ring / out of ring" switch is gone. The type is different - it is text with
     * no native counterpart to hand back - so "show it beside the ring" is a real choice
     * rather than a way of expressing the same thing twice.
     */
    Type(TrioSettingsPart.Type),
    ShowOutType(TrioSettingsPart.Type),
    Power(TrioSettingsPart.Readouts),
    StackStyle(TrioSettingsPart.Mobile),
    StackLabel(TrioSettingsPart.Type),
    /**
     * The type steps aside while Wi-Fi is up.
     *
     * Owned by the type: it is a rule about when that label is worth drawing, not a
     * reading of its own. One switch for every arrangement - the label says what the
     * cellular radio is doing, and with Wi-Fi up it shares the bar with the arcs the
     * user is actually reading.
     */
    TypeHideOnWifi(TrioSettingsPart.Type),
    RunningPower(TrioSettingsPart.Readouts),
    Temperature(TrioSettingsPart.Readouts),
    /** The temperature shows only while the charger is attached (#45). */
    TempChargingOnly(TrioSettingsPart.Readouts),
    /**
     * The frame rate, in the same sheet as the two readouts.
     *
     * It is a reading like the wattage and the temperature, so it is switched on in the
     * same place; what differs is that it never shares their two-line strip. That strip
     * is sized to hold exactly those two, and a third line would scale all three down -
     * so a frame rate that has no band of its own is drawn in a view beside the strip.
     */
    Fps(TrioSettingsPart.Readouts),
    Clock(TrioSettingsPart.Clock),
    HideStatusClock(TrioSettingsPart.Clock),
    ClockLunar(TrioSettingsPart.Clock),
    ClockSeconds(TrioSettingsPart.Clock),
    ClockDate(TrioSettingsPart.Clock),
    ClockDateFormat(TrioSettingsPart.Clock),
    ClockWeekday(TrioSettingsPart.Clock),
    ClockWeekdayFormat(TrioSettingsPart.Clock),
    ClockWeather(TrioSettingsPart.Clock),
    ClockWeatherStyle(TrioSettingsPart.Clock),
    ClockStyle(TrioSettingsPart.Clock),
    HideAlarmIcon(TrioSettingsPart.Appearance),
    HideBluetoothIcon(TrioSettingsPart.Appearance),
    /** The secondary SIM's native signal icon removed from the status bar clusters. */
    HideSim2(TrioSettingsPart.Appearance),
    /** The native 5G/4G label beside the signal bars removed, bars untouched. */
    HideNetType(TrioSettingsPart.Appearance),
    /**
     * Pull-down morph: the glyph becomes a horizontal row while the panel expands.
     *
     * A rule about the glyph as a whole rather than one piece, so it is owned by the
     * appearance card. Mutually exclusive with [PanelNativeIcons] - morph wins.
     */
    TrioMorph(TrioSettingsPart.Appearance),
    /** Diagnostics toast when the morph flips state; only exists with [TrioMorph] on. */
    MorphToast(TrioSettingsPart.Appearance),
    /**
     * The fixed icon-slot padding dropped, so the row packs by real content width.
     *
     * A status-bar row behaviour rather than glyph ink, but it is the glyph's manager
     * that applies it, so it lives with the appearance rows.
     */
    CompactRow(TrioSettingsPart.Appearance),
    /** Keep status icons visible while the live capsule is expanded. */
    KeepIcons(TrioSettingsPart.Appearance),
    /** Stop the ROM's status-bar shrink while the live capsule is docked. */
    CapsuleNoScale(TrioSettingsPart.Appearance),
    /**
     * Hand the row back to the native icons while the panel is expanded.
     *
     * Mutually exclusive with [TrioMorph]; the renderer gives morph priority when both
     * are on.
     */
    PanelNativeIcons(TrioSettingsPart.Appearance),
    /** MagicOS 10 反色兼容开关：字形颜色跟随系统反色。 */
    Magicos10Invert(TrioSettingsPart.Appearance),
    ShadeHidePower(TrioSettingsPart.Shade),
    ShadeHideBell(TrioSettingsPart.Shade),
    /** 控制中心音量条长按弹荣耀媒体输出弹窗（HonorDuoBar 1.1.3）。 */
    CcVolumeLongpress(TrioSettingsPart.Shade),
    /** The charging notification filtered out of the shade, lock screen and banners. */
    NotifHideCharging(TrioSettingsPart.Shade),
    /** The hotspot tethering notification filtered out. */
    NotifHideHotspot(TrioSettingsPart.Shade),
    /** The USB-debugging notification filtered out. */
    NotifHideUsb(TrioSettingsPart.Shade),
    ShadeLunar(TrioSettingsPart.Shade),
    ShadeWeather(TrioSettingsPart.Shade),
    ShadeWeatherStyle(TrioSettingsPart.Shade),
    /**
     * 模块生效范围：控制中心 / 通知面板 / 锁屏界面里，哪些位置交给宿主。
     *
     * 一个全局规则，不属于任何一件组件，所以归在外观卡；它只在排布页画出来
     * （见 [contentControlsFor] 的排除项），弹层里没有这一行。
     */
    EffectiveScope(TrioSettingsPart.Appearance),
}

internal fun contentControlsFor(part: TrioSettingsPart): List<ContentControl> =
    ContentControl.entries.filter {
        val matchesOwner = it.owner == part || (part == TrioSettingsPart.Battery && it == ContentControl.Bolt)
        matchesOwner && !(part == TrioSettingsPart.Appearance &&
            (it == ContentControl.Enabled || it == ContentControl.MergeIcons || it == ContentControl.Style ||
                it == ContentControl.AntiBurnIn || it == ContentControl.EffectiveScope))
    }

/**
 * 设置搜索里一行结果要用的文案。
 *
 * @param titleRes 行标题，必须与 [ContentControlRow] 里画出来的那个标题一致。
 * @param summaryRes 行说明，没有说明的行走 null。
 */
internal class ContentSearchLabel(val titleRes: Int, val summaryRes: Int? = null)

/**
 * 该项在搜索里的文案，null 表示它不参与搜索。
 *
 * 只有五类返回 null：`Enabled`、`MergeIcons`、`Style`、`AntiBurnIn`、`EffectiveScope`
 * 被 [contentControlsFor] 排除在基础外观分区之外（前三个和 `AntiBurnIn` 在排布页
 * 顶部的功能区，`EffectiveScope` 也在排布页），弹层里根本没有这一行，
 * 搜出来也只会跳到一个没有它的弹层，所以干脆不列。
 */
internal fun ContentControl.searchLabel(): ContentSearchLabel? = when (this) {
    ContentControl.Enabled,
    ContentControl.MergeIcons,
    ContentControl.Style,
    ContentControl.AntiBurnIn,
    ContentControl.EffectiveScope -> null
    ContentControl.SignalColumns -> ContentSearchLabel(R.string.signal_columns_title, R.string.signal_columns_summary)
    ContentControl.Wifi -> ContentSearchLabel(R.string.ring_position_wifi)
    ContentControl.ReplaceWifiIcon -> ContentSearchLabel(R.string.replace_wifi_icon_title, R.string.replace_wifi_icon_summary)
    ContentControl.ReplaceStatusIcon -> ContentSearchLabel(R.string.replace_status_icon_title, R.string.replace_status_icon_summary)
    ContentControl.Mobile -> ContentSearchLabel(R.string.ring_position_mobile)
    ContentControl.DataSim -> ContentSearchLabel(R.string.data_sim_only_title)
    ContentControl.Stacked -> ContentSearchLabel(R.string.stacked_signal_title, R.string.stacked_signal_summary)
    ContentControl.MobileActivity -> ContentSearchLabel(R.string.show_mobile_activity_title, R.string.show_mobile_activity_summary)
    ContentControl.BtBattery -> ContentSearchLabel(R.string.trio_bt_battery_title, R.string.trio_bt_battery_summary)
    ContentControl.DualSignal -> ContentSearchLabel(R.string.dual_signal_title, R.string.dual_signal_summary)
    ContentControl.DualSignalLabel -> ContentSearchLabel(R.string.dual_signal_label_title, R.string.dual_signal_label_summary)
    ContentControl.Sim1 -> ContentSearchLabel(R.string.show_sim_1_title)
    ContentControl.Sim2 -> ContentSearchLabel(R.string.show_sim_2_title)
    ContentControl.Value -> ContentSearchLabel(R.string.show_value_title)
    ContentControl.Percent -> ContentSearchLabel(R.string.show_percent_title)
    ContentControl.ChargingShowsValue -> ContentSearchLabel(R.string.charging_shows_value_title, R.string.charging_shows_value_summary)
    ContentControl.ChargeBreath -> ContentSearchLabel(R.string.trio_charge_breath_title, R.string.trio_charge_breath_summary)
    ContentControl.Bolt -> ContentSearchLabel(R.string.show_bolt_title)
    ContentControl.BoltAnimEnabled -> ContentSearchLabel(R.string.bolt_anim_enabled_title)
    ContentControl.BoltAnim -> ContentSearchLabel(R.string.bolt_anim_style_title, R.string.bolt_anim_summary)
    ContentControl.Type -> ContentSearchLabel(R.string.ring_position_type)
    ContentControl.ShowOutType -> ContentSearchLabel(R.string.show_out_type_title, R.string.show_out_type_summary)
    ContentControl.Power -> ContentSearchLabel(R.string.perf_power_title)
    ContentControl.StackStyle -> ContentSearchLabel(R.string.stack_style_title)
    ContentControl.StackLabel -> ContentSearchLabel(R.string.stack_label_title)
    ContentControl.TypeHideOnWifi -> ContentSearchLabel(R.string.type_hide_on_wifi_title, R.string.type_hide_on_wifi_summary)
    ContentControl.RunningPower -> ContentSearchLabel(R.string.perf_running_title)
    ContentControl.Temperature -> ContentSearchLabel(R.string.perf_temp_title)
    ContentControl.TempChargingOnly -> ContentSearchLabel(R.string.perf_temp_charging_only_title, R.string.perf_temp_charging_only_summary)
    ContentControl.Fps -> ContentSearchLabel(R.string.perf_fps_title, R.string.perf_fps_summary)
    ContentControl.Clock -> ContentSearchLabel(R.string.ring_position_clock)
    ContentControl.HideStatusClock -> ContentSearchLabel(R.string.hide_status_clock_title, R.string.hide_status_clock_summary)
    ContentControl.ClockLunar -> ContentSearchLabel(R.string.clock_lunar_title, R.string.clock_lunar_summary)
    ContentControl.ClockSeconds -> ContentSearchLabel(R.string.clock_seconds_title, R.string.clock_seconds_summary)
    ContentControl.ClockDate -> ContentSearchLabel(R.string.clock_date_title, R.string.clock_date_summary)
    ContentControl.ClockDateFormat -> ContentSearchLabel(R.string.clock_date_format_title)
    ContentControl.ClockWeekday -> ContentSearchLabel(R.string.clock_weekday_title, R.string.clock_weekday_summary)
    ContentControl.ClockWeekdayFormat -> ContentSearchLabel(R.string.clock_weekday_format_title)
    ContentControl.ClockWeather -> ContentSearchLabel(R.string.clock_weather_title, R.string.clock_weather_summary)
    ContentControl.ClockWeatherStyle -> ContentSearchLabel(R.string.clock_weather_style_title)
    ContentControl.ClockStyle -> ContentSearchLabel(R.string.clock_layout_style_title)
    ContentControl.HideAlarmIcon -> ContentSearchLabel(R.string.hide_alarm_icon_title, R.string.hide_alarm_icon_summary)
    ContentControl.HideBluetoothIcon -> ContentSearchLabel(R.string.hide_bluetooth_icon_title, R.string.hide_bluetooth_icon_summary)
    ContentControl.HideSim2 -> ContentSearchLabel(R.string.hide_sim2_title, R.string.hide_sim2_summary)
    ContentControl.HideNetType -> ContentSearchLabel(R.string.hide_net_type_title, R.string.hide_net_type_summary)
    ContentControl.TrioMorph -> ContentSearchLabel(R.string.trio_morph_title, R.string.trio_morph_summary)
    ContentControl.MorphToast -> ContentSearchLabel(R.string.trio_morph_toast_title, R.string.trio_morph_toast_summary)
    ContentControl.CompactRow -> ContentSearchLabel(R.string.trio_compact_row_title, R.string.trio_compact_row_summary)
    ContentControl.KeepIcons -> ContentSearchLabel(R.string.trio_keep_icons_title, R.string.trio_keep_icons_summary)
    ContentControl.CapsuleNoScale -> ContentSearchLabel(R.string.capsule_no_scale_title, R.string.capsule_no_scale_summary)
    ContentControl.PanelNativeIcons -> ContentSearchLabel(R.string.panel_native_icons_title, R.string.panel_native_icons_summary)
    ContentControl.Magicos10Invert -> ContentSearchLabel(R.string.magicos10_invert_title, R.string.magicos10_invert_summary)
    ContentControl.ShadeHidePower -> ContentSearchLabel(R.string.shade_hide_power_title, R.string.shade_hide_power_summary)
    ContentControl.ShadeHideBell -> ContentSearchLabel(R.string.shade_hide_bell_title, R.string.shade_hide_bell_summary)
    ContentControl.CcVolumeLongpress -> ContentSearchLabel(R.string.cc_volume_longpress_title, R.string.cc_volume_longpress_summary)
    ContentControl.NotifHideCharging -> ContentSearchLabel(R.string.notif_hide_charging_title, R.string.notif_hide_charging_summary)
    ContentControl.NotifHideHotspot -> ContentSearchLabel(R.string.notif_hide_hotspot_title, R.string.notif_hide_hotspot_summary)
    ContentControl.NotifHideUsb -> ContentSearchLabel(R.string.notif_hide_usb_title, R.string.notif_hide_usb_summary)
    ContentControl.ShadeLunar -> ContentSearchLabel(R.string.shade_lunar_title, R.string.shade_lunar_summary)
    ContentControl.ShadeWeather -> ContentSearchLabel(R.string.shade_weather_title, R.string.shade_weather_summary)
    ContentControl.ShadeWeatherStyle -> ContentSearchLabel(R.string.weather_style_title)
}

@Composable
internal fun ContentControlRow(
    control: ContentControl,
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
    ringSettings: TrioSettings = settings,
) {
    val a = TrioAppearance.of(settings)
    val master = listOf(a.glyph to R.string.master_title)

    val positioned = when (control) {
        ContentControl.Value -> TrioZones.Part.VALUE
        ContentControl.Bolt -> TrioZones.Part.BOLT
        ContentControl.Type -> TrioZones.Part.TYPE
        ContentControl.Clock -> TrioZones.Part.CLOCK
        ContentControl.Fps -> TrioZones.Part.FPS
        ContentControl.Power -> TrioZones.Part.POWER
        ContentControl.RunningPower -> TrioZones.Part.RUNNING
        ContentControl.Temperature -> TrioZones.Part.TEMP
        else -> null
    }
    if (control == ContentControl.Type) {
        TypePositionControl(settings, update, zonesAllowed = a.slotted)
        return
    }
    if ((a.ring || a.capsule) && positioned != null && !a.rect) {
        RingPositionControl(positioned, ringSettings, update)
        if (control == ContentControl.Clock) return
    }
    if (control == ContentControl.Mobile) {
        SignalPositionControl(settings, update)
    }
    if (control == ContentControl.Sim1 && a.slotted) {
        SignalCardPositionControl(TrioZones.Part.SIM_1, settings, update, visible = signalInsideRing(a))
    }
    if (control == ContentControl.Sim2 && a.slotted) {
        SignalCardPositionControl(TrioZones.Part.SIM_2, settings, update, visible = signalInsideRing(a))
    }

    when (control) {
        ContentControl.Enabled -> ContentToggle(settings.enabled, R.string.master_module_title,
            R.string.master_module_summary, emptyList()) { value -> update { it.setEnabled(value) } }
        ContentControl.MergeIcons -> ContentToggle(settings.mergeIcons, R.string.master_title,
            R.string.merge_icons_summary, listOf(settings.enabled to R.string.master_module_title)) { value -> update { it.setMergeIcons(value) } }
        ContentControl.Style -> {
            val styles = listOf(Prefs.STYLE_RING, Prefs.STYLE_CAPSULE)
            ContentChoice(styles.indexOf(a.style).coerceAtLeast(0), R.string.trio_style_title,
                null,
                listOf(R.string.trio_style_ring, R.string.trio_style_capsule),
                master) { index -> update { it.setTrioStyle(styles[index]) } }
        }
        ContentControl.AntiBurnIn -> ContentToggle(
            checked = settings.antiBurnIn,
            title = R.string.anti_burn_in_title,
            summaryRes = R.string.anti_burn_in_summary,
            dependencies = emptyList(),
        ) { value -> update { it.setAntiBurnIn(value) } }
        // 4柱/5柱对「双排信号」同样生效（双排就是这组柱子本身），所以双排开着时不能藏起来，
        // 否则这个设置找不到入口，只能继承上次的值。
        ContentControl.SignalColumns -> if (a.dualSignal || a.stackedOut()) {
            val columns = listOf(4, 5)
            ContentChoice(
                selected = columns.indexOf(settings.signalColumns).coerceAtLeast(0),
                title = R.string.signal_columns_title,
                summaryRes = R.string.signal_columns_summary,
                options = listOf(R.string.signal_columns_4, R.string.signal_columns_5),
                dependencies = if (a.dualSignal) {
                    listOf(true to R.string.dual_signal_title)
                } else {
                    listOf(a.mobile to R.string.show_mobile_title)
                },
            ) { index -> update { it.setSignalColumns(columns[index]) } }
        } else Unit
        ContentControl.Wifi -> WifiPositionControl(settings, ringSettings, update)
        ContentControl.ReplaceWifiIcon -> ContentToggle(
            checked = settings.replaceWifiIcon,
            title = R.string.replace_wifi_icon_title,
            summaryRes = R.string.replace_wifi_icon_summary,
            dependencies = emptyList(),
        ) { value -> update { it.setReplaceWifiIcon(value) } }
        ContentControl.ReplaceStatusIcon -> ContentToggle(
            checked = settings.replaceStatusIcon,
            title = R.string.replace_status_icon_title,
            summaryRes = R.string.replace_status_icon_summary,
            dependencies = emptyList(),
        ) { value -> update { it.setReplaceStatusIcon(value) } }
        ContentControl.Mobile -> Unit
        ContentControl.DataSim -> ContentToggle(
            checked = a.dataSimOnly,
            title = R.string.data_sim_only_title,
            summaryRes = null,
            dependencies = emptyList(),
            enabled = !a.dualSignal,
            hintRes = if (a.dualSignal) R.string.dual_signal_disables else null,
        ) { value -> update { it.setDataSimOnly(value) } }
        // 环外堆叠读数在双排信号接管后就不再绘制，这个开关会变成空档，直接藏起来。
        ContentControl.Stacked -> if (!a.dualSignal) {
            ContentToggle(
                checked = a.stackedSignal,
                title = R.string.stacked_signal_title,
                summaryRes = R.string.stacked_signal_summary,
                dependencies = listOf(a.signalOutOfRing to R.string.ring_position_mobile),
            ) { value -> update { it.setStackedSignal(value) } }
        } else Unit
        // 双排信号开着时字形不画，环内/环外就无从谈起；而双排的箭头正是由这个开关决定，
        // 所以此时必须照常显示，否则箭头永远开不起来。
        ContentControl.MobileActivity -> if (a.dualSignal || a.signalOutOfRing) {
            ContentToggle(
                checked = settings.showMobileActivity,
                title = R.string.show_mobile_activity_title,
                summaryRes = R.string.show_mobile_activity_summary,
                dependencies = if (a.dualSignal) {
                    listOf(true to R.string.dual_signal_title)
                } else {
                    listOf(true to R.string.ring_position_mobile)
                },
            ) { value -> update { it.setShowMobileActivity(value) } }
        } else Unit
        // 双排信号的开关已经移到「排布 → 合并风格」里，与圆环/胶囊并列且互斥，
        // 这里不再重复放一个开关。
        ContentControl.DualSignal -> Unit
        ContentControl.DualSignalLabel -> ContentToggle(
            checked = settings.dualSignalLabel,
            title = R.string.dual_signal_label_title,
            summaryRes = R.string.dual_signal_label_summary,
            dependencies = listOf(settings.dualSignal to R.string.dual_signal_title),
        ) { value -> update { it.setDualSignalLabel(value) } }
        ContentControl.Sim1 -> ContentToggle(
            checked = a.showSim1,
            title = R.string.show_sim_1_title,
            summaryRes = null,
            dependencies = listOf(a.mobile to R.string.show_mobile_title) +
                (signalInsideRing(a) to R.string.ring_position_mobile),
            enabled = !settings.dualSignal,
            hintRes = if (settings.dualSignal) R.string.dual_signal_disables else null,
        ) { value -> update { it.setShowSim1(value) } }
        ContentControl.Sim2 -> ContentToggle(
            checked = a.showSim2,
            title = R.string.show_sim_2_title,
            summaryRes = null,
            dependencies = listOf(a.mobile to R.string.show_mobile_title) +
                (signalInsideRing(a) to R.string.ring_position_mobile),
            enabled = !settings.dualSignal,
            hintRes = if (settings.dualSignal) R.string.dual_signal_disables else null,
        ) { value -> update { it.setShowSim2(value) } }
        ContentControl.Value -> ContentToggle(a.value, R.string.show_value_title, null, emptyList()) { value -> update { it.setShowValue(value) } }
        ContentControl.Percent -> ContentToggle(
            checked = settings.showPercent,
            title = R.string.show_percent_title,
            summaryRes = null,
            dependencies = listOf(a.value to R.string.show_value_title),
        ) { value -> update { it.setShowPercent(value) } }
        ContentControl.ChargingShowsValue -> ContentToggle(
            checked = settings.chargingShowsValue,
            title = R.string.charging_shows_value_title,
            summaryRes = R.string.charging_shows_value_summary,
            // The ring is the only arrangement with a centre; the capsule and the
            // rectangle have no zone this could occupy.
            dependencies = listOf(!a.ring to R.string.trio_style_title),
        ) { value -> update { it.setChargingShowsValue(value) } }
        ContentControl.Bolt -> ContentToggle(a.boltWanted, R.string.show_bolt_title, null, emptyList()) { value -> update { it.setShowBolt(value) } }
        ContentControl.BoltAnimEnabled -> ContentToggle(
            checked = settings.boltAnim != Prefs.BOLT_ANIM_OFF,
            title = R.string.bolt_anim_enabled_title,
            summaryRes = null,
            dependencies = listOf(a.boltWanted to R.string.show_bolt_title),
        ) { enabled ->
            update {
                it.setBoltAnim(if (enabled) Prefs.BOLT_ANIM_PULSE else Prefs.BOLT_ANIM_OFF)
            }
        }
        ContentControl.BoltAnim -> if (settings.boltAnim != Prefs.BOLT_ANIM_OFF) {
            val styles = listOf(Prefs.BOLT_ANIM_PULSE, Prefs.BOLT_ANIM_FLOW, Prefs.BOLT_ANIM_SPARK)
            val selectedIndex = styles.indexOf(settings.boltAnim).coerceAtLeast(0)
            ContentChoice(
                selected = selectedIndex,
                title = R.string.bolt_anim_style_title,
                summaryRes = R.string.bolt_anim_summary,
                options = listOf(
                    R.string.bolt_anim_pulse,
                    R.string.bolt_anim_flow,
                    R.string.bolt_anim_spark,
                ),
                dependencies = listOf(a.boltWanted to R.string.show_bolt_title),
            ) { index -> update { it.setBoltAnim(styles[index]) } }
        } else Unit
        ContentControl.Type -> TypePositionControl(settings, update, zonesAllowed = a.slotted)
        ContentControl.TypeHideOnWifi -> ContentToggle(
            checked = settings.typeHideOnWifi,
            title = R.string.type_hide_on_wifi_title,
            summaryRes = R.string.type_hide_on_wifi_summary,
            dependencies = listOf(!a.typeAnywhere() to R.string.show_mobile_type_title),
        ) { value -> update { it.setTypeHideOnWifi(value) } }
        ContentControl.ShowOutType -> if (!a.typeInRing) {
            ContentToggle(
                checked = settings.mobileTypeMode == Prefs.MOBILE_TYPE_OUT_RING,
                title = R.string.show_out_type_title,
                summaryRes = R.string.show_out_type_summary,
                dependencies = listOf(settings.enabled to R.string.master_title),
            ) { value ->
                update {
                    it.setMobileTypeMode(if (value) Prefs.MOBILE_TYPE_OUT_RING else Prefs.MOBILE_TYPE_OFF)
                }
            }
        } else Unit
        ContentControl.ClockWeather -> ContentToggle(
            checked = settings.clockWeather,
            title = R.string.clock_weather_title,
            summaryRes = R.string.clock_weather_summary,
            dependencies = emptyList(),
        ) { value -> update { it.setClockWeather(value) } }
        ContentControl.ClockWeatherStyle -> if (settings.clockWeather) {
            ContentChoice(
                selected = settings.clockWeatherStyle.coerceIn(0, Prefs.WEATHER_STYLE_COUNT - 1),
                title = R.string.clock_weather_style_title,
                summaryRes = null,
                options = listOf(
                    R.string.weather_style_text,
                    R.string.weather_style_icon,
                    R.string.weather_style_icon_text,
                    R.string.weather_style_symbol,
                    R.string.weather_style_symbol_text,
                ),
                dependencies = listOf(settings.clockWeather to R.string.clock_weather_title),
            ) { value -> update { it.setClockWeatherStyle(value) } }
        } else Unit
        ContentControl.ClockStyle -> ContentChoice(
            selected = settings.clockStyle.coerceIn(0, 1),
            title = R.string.clock_layout_style_title,
            summaryRes = null,
            options = listOf(R.string.clock_layout_style_classic, R.string.clock_layout_style_balanced),
            dependencies = emptyList(),
        ) { value -> update { it.setClockStyle(value) } }
        ContentControl.HideAlarmIcon -> ContentToggle(
            checked = settings.hideAlarmIcon,
            title = R.string.hide_alarm_icon_title,
            summaryRes = R.string.hide_alarm_icon_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
        ) { value -> update { it.setHideAlarmIcon(value) } }
        ContentControl.HideBluetoothIcon -> ContentToggle(
            checked = settings.hideBluetoothIcon,
            title = R.string.hide_bluetooth_icon_title,
            summaryRes = R.string.hide_bluetooth_icon_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
        ) { value -> update { it.setHideBluetoothIcon(value) } }
        ContentControl.HideSim2 -> ContentToggle(
            checked = settings.hideSim2,
            title = R.string.hide_sim2_title,
            summaryRes = R.string.hide_sim2_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
            enabled = !settings.dualSignal,
            hintRes = if (settings.dualSignal) R.string.dual_signal_disables else null,
        ) { value -> update { it.setHideSim2(value) } }
        ContentControl.HideNetType -> ContentToggle(
            checked = settings.hideNetType,
            title = R.string.hide_net_type_title,
            summaryRes = R.string.hide_net_type_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
        ) { value -> update { it.setHideNetType(value) } }
        // 通知面板分区的每一行都只和模块总开关联动。这里不能走 ContentGate：它在开关
        // 关闭时会把整行藏掉，而这个分区所有行都受同一个开关约束，于是总开关一关，
        // 分区就一行不剩、弹层看上去是空的。改成把行照常画出来、只置灰，保证分区
        // 始终有可见的通知面板选项。
        ContentControl.ShadeHidePower -> ContentToggle(
            checked = settings.shadeHidePower,
            title = R.string.shade_hide_power_title,
            summaryRes = R.string.shade_hide_power_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setShadeHidePower(value) } }
        ContentControl.ShadeHideBell -> ContentToggle(
            checked = settings.shadeHideBell,
            title = R.string.shade_hide_bell_title,
            summaryRes = R.string.shade_hide_bell_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setShadeHideBell(value) } }
        ContentControl.CcVolumeLongpress -> ContentToggle(
            checked = settings.ccVolumeLongpress,
            title = R.string.cc_volume_longpress_title,
            summaryRes = R.string.cc_volume_longpress_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setCcVolumeLongpress(value) } }
        ContentControl.NotifHideCharging -> ContentToggle(
            checked = settings.notifHideCharging,
            title = R.string.notif_hide_charging_title,
            summaryRes = R.string.notif_hide_charging_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setNotifHideCharging(value) } }
        ContentControl.NotifHideHotspot -> ContentToggle(
            checked = settings.notifHideHotspot,
            title = R.string.notif_hide_hotspot_title,
            summaryRes = R.string.notif_hide_hotspot_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setNotifHideHotspot(value) } }
        ContentControl.NotifHideUsb -> ContentToggle(
            checked = settings.notifHideUsb,
            title = R.string.notif_hide_usb_title,
            summaryRes = R.string.notif_hide_usb_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setNotifHideUsb(value) } }
        ContentControl.ShadeLunar -> ContentToggle(
            checked = settings.shadeLunar,
            title = R.string.shade_lunar_title,
            summaryRes = R.string.shade_lunar_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setShadeLunar(value) } }
        ContentControl.ShadeWeather -> ContentToggle(
            checked = settings.shadeWeather,
            title = R.string.shade_weather_title,
            summaryRes = R.string.shade_weather_summary,
            dependencies = emptyList(),
            enabled = settings.enabled,
        ) { value -> update { it.setShadeWeather(value) } }
        ContentControl.ShadeWeatherStyle -> if (settings.shadeWeather) {
            ContentChoice(
                selected = settings.shadeWeatherStyle.coerceIn(0, 2),
                title = R.string.weather_style_title,
                summaryRes = null,
                options = listOf(
                    R.string.weather_style_text,
                    R.string.weather_style_icon,
                    R.string.weather_style_icon_text,
                ),
                dependencies = listOf(settings.shadeWeather to R.string.shade_weather_title),
            ) { value -> update { it.setShadeWeatherStyle(value) } }
        } else Unit
        ContentControl.Power -> ContentToggle(settings.perfPower, R.string.perf_power_title, null, emptyList()) { value -> update { it.setPerfPower(value) } }
        ContentControl.RunningPower -> ContentToggle(checked = settings.perfRunning, title = R.string.perf_running_title, summaryRes = null,
            dependencies = listOf(settings.perfPower to R.string.perf_power_title)) { value -> update { it.setPerfRunning(value) } }
        // Absent (not greyed) when the caption has nowhere to sit: type inside the
        // glyph, or the stacked reading off.
        ContentControl.StackLabel ->
            if (a.typeOutOfRing && a.stackedOut()) {
            ContentChoice(
                settings.stackLabel, R.string.stack_label_title, null,
                listOf(R.string.stack_label_left, R.string.stack_label_above),
                emptyList(),
            ) { value -> update { it.setStackLabel(value) } }
        }
        // With one card both styles draw the same row, so the choice changes nothing.
        ContentControl.StackStyle ->
            if (!a.dataSimOnly) {
                ContentChoice(
                    settings.stackStyle, R.string.stack_style_title, null,
                    listOf(R.string.stack_style_dots, R.string.stack_style_rows),
                    listOf(
                        (a.dualSignal || a.stackedOut()) to R.string.stacked_signal_title
                    ),
                ) { value -> update { it.setStackStyle(value) } }
            }
        ContentControl.TempChargingOnly -> ContentToggle(
            checked = settings.perfTempChargingOnly,
            title = R.string.perf_temp_charging_only_title,
            summaryRes = R.string.perf_temp_charging_only_summary,
            dependencies = listOf(settings.perfTemp to R.string.perf_temp_title),
        ) { value -> update { it.setPerfTempChargingOnly(value) } }
        ContentControl.Temperature -> ContentToggle(settings.perfTemp, R.string.perf_temp_title, null, emptyList()) { value -> update { it.setPerfTemp(value) } }
        ContentControl.Fps -> ContentToggle(checked = settings.perfFps, title = R.string.perf_fps_title, summaryRes = R.string.perf_fps_summary,
            dependencies = emptyList()) { value -> update { it.setPerfFps(value) } }
        ContentControl.Clock -> Unit
        ContentControl.HideStatusClock -> ContentToggle(
            checked = settings.hideStatusClock,
            title = R.string.hide_status_clock_title,
            summaryRes = R.string.hide_status_clock_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
        ) { value -> update { it.setHideStatusClock(value) } }
        ContentControl.ClockLunar -> ContentToggle(
            checked = settings.clockLunar,
            title = R.string.clock_lunar_title,
            summaryRes = R.string.clock_lunar_summary,
            dependencies = listOf(!a.hasRingPart(Prefs.PART_CLOCK) to R.string.ring_position_clock),
        ) { value -> update { it.setClockLunar(value) } }
        ContentControl.ClockSeconds -> ContentToggle(
            checked = settings.clockSeconds,
            title = R.string.clock_seconds_title,
            summaryRes = R.string.clock_seconds_summary,
            dependencies = listOf(!a.hasRingPart(Prefs.PART_CLOCK) to R.string.ring_position_clock),
        ) { value -> update { it.setClockSeconds(value) } }
        ContentControl.ClockDate -> ContentToggle(
            checked = settings.clockDate,
            title = R.string.clock_date_title,
            summaryRes = R.string.clock_date_summary,
            dependencies = listOf(!a.hasRingPart(Prefs.PART_CLOCK) to R.string.ring_position_clock),
        ) { value -> update { it.setClockDate(value) } }
        ContentControl.ClockDateFormat -> if (settings.clockDate) {
            ContentChoice(
                selected = settings.clockDateFormat,
                title = R.string.clock_date_format_title,
                summaryRes = null,
                options = listOf(
                    R.string.clock_date_format_slash_md,
                    R.string.clock_date_format_slash_ymd,
                    R.string.clock_date_format_zh_md,
                    R.string.clock_date_format_zh_ymd,
                ),
                dependencies = listOf(!a.hasRingPart(Prefs.PART_CLOCK) to R.string.ring_position_clock),
                onChange = { value -> update { it.setClockDateFormat(value) } },
            )
        } else Unit
        ContentControl.ClockWeekday -> ContentToggle(
            checked = settings.clockWeekday,
            title = R.string.clock_weekday_title,
            summaryRes = R.string.clock_weekday_summary,
            dependencies = listOf(!a.hasRingPart(Prefs.PART_CLOCK) to R.string.ring_position_clock),
        ) { value -> update { it.setClockWeekday(value) } }
        ContentControl.ClockWeekdayFormat -> if (settings.clockWeekday) {
            ContentChoice(
                selected = settings.clockWeekdayFormat,
                title = R.string.clock_weekday_format_title,
                summaryRes = null,
                options = listOf(
                    R.string.clock_weekday_format_zh_short,
                    R.string.clock_weekday_format_zh_full,
                    R.string.clock_weekday_format_en_short,
                ),
                dependencies = listOf(!a.hasRingPart(Prefs.PART_CLOCK) to R.string.ring_position_clock),
                onChange = { value -> update { it.setClockWeekdayFormat(value) } },
            )
        } else Unit
        ContentControl.ChargeBreath -> ContentToggle(
            checked = settings.trioChargeBreath,
            title = R.string.trio_charge_breath_title,
            summaryRes = R.string.trio_charge_breath_summary,
            dependencies = master,
        ) { value -> update { it.setTrioChargeBreath(value) } }
        ContentControl.BtBattery -> ContentChoice(
            selected = settings.trioBtBattery.coerceIn(0, Prefs.MAX_TRIO_BT_BATTERY),
            title = R.string.trio_bt_battery_title,
            summaryRes = R.string.trio_bt_battery_summary,
            options = listOf(
                R.string.trio_bt_battery_off,
                R.string.trio_bt_battery_arc,
                R.string.trio_bt_battery_number,
            ),
            dependencies = master,
        ) { index -> update { it.setTrioBtBattery(index) } }
        // The morph and the panel hand-off only exist for the slotted arrangements: the
        // rectangle has no ring or capsule to unfold from.
        ContentControl.TrioMorph -> ContentToggle(
            checked = settings.trioMorph,
            title = R.string.trio_morph_title,
            summaryRes = R.string.trio_morph_summary,
            dependencies = master + listOf(a.slotted to R.string.trio_style_title),
        ) { value -> update { it.setTrioMorph(value) } }
        ContentControl.MorphToast -> ContentToggle(
            checked = settings.trioMorphToast,
            title = R.string.trio_morph_toast_title,
            summaryRes = R.string.trio_morph_toast_summary,
            dependencies = master + listOf(
                a.slotted to R.string.trio_style_title,
                settings.trioMorph to R.string.trio_morph_title,
            ),
        ) { value -> update { it.setTrioMorphToast(value) } }
        ContentControl.PanelNativeIcons -> ContentToggle(
            checked = settings.panelNativeIcons,
            title = R.string.panel_native_icons_title,
            summaryRes = R.string.panel_native_icons_summary,
            dependencies = master + listOf(a.slotted to R.string.trio_style_title),
        ) { value -> update { it.setPanelNativeIcons(value) } }
        ContentControl.Magicos10Invert -> ContentToggle(
            checked = settings.magicos10Invert,
            title = R.string.magicos10_invert_title,
            summaryRes = R.string.magicos10_invert_summary,
            dependencies = master + listOf(a.slotted to R.string.trio_style_title),
        ) { value -> update { it.setMagicos10Invert(value) } }
        // The row behaviours apply to the status bar itself, so they follow the module's
        // own master switch rather than the glyph's.
        ContentControl.CompactRow -> ContentToggle(
            checked = settings.trioCompactRow,
            title = R.string.trio_compact_row_title,
            summaryRes = R.string.trio_compact_row_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
        ) { value -> update { it.setTrioCompactRow(value) } }
        ContentControl.KeepIcons -> ContentToggle(
            checked = settings.trioKeepIcons,
            title = R.string.trio_keep_icons_title,
            summaryRes = R.string.trio_keep_icons_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
        ) { value -> update { it.setTrioKeepIcons(value) } }
        ContentControl.CapsuleNoScale -> ContentToggle(
            checked = settings.capsuleNoScale,
            title = R.string.capsule_no_scale_title,
            summaryRes = R.string.capsule_no_scale_summary,
            dependencies = listOf(settings.enabled to R.string.master_module_title),
        ) { value -> update { it.setCapsuleNoScale(value) } }
        // 生效范围管的是整个模块（含双排信号），所以依赖模块总开关而不是字形开关。
        ContentControl.EffectiveScope -> ContentMultiChoice(
            selected = scopeActiveSet(settings.effectiveScope),
            title = R.string.effective_scope_title,
            options = listOf(
                R.string.scope_option_cc,
                R.string.scope_option_shade,
                R.string.scope_option_keyguard,
            ),
            dependencies = listOf(settings.enabled to R.string.master_module_title),
            minimumSelected = 1,
        ) { next -> update { it.setEffectiveScope(scopeMaskOf(next)) } }
    }
}

/**
 * The signal's own two-way position: in the ring, or back to the system.
 *
 * This is the one position row that is not a slot list, because the signal is the one
 * piece drawn from per-card rows rather than from a single slot. "Outside the ring" here
 * means exactly what "return it to the system" means on every other row - the glyph draws
 * appears.
 */
@Composable
private fun SignalPositionControl(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    val outside = settings.signalMode == Prefs.SIGNAL_OUT_RING
    // 双排信号接管后三合一不再绘制信号，环内/环外没有意义：保留可见但不可点，切回来还能直接改。
    ContentChoice(
        selected = if (outside) 1 else 0,
        title = R.string.ring_position_mobile,
        summaryRes = if (settings.dualSignal) R.string.dual_signal_disables else null,
        options = listOf(R.string.signal_mode_in_ring, R.string.signal_mode_out_ring),
        dependencies = listOf(settings.enabled to R.string.master_title),
        enabled = !settings.dualSignal,
        onChange = { value ->
            update { it.setSignalMode(if (value == 1) Prefs.SIGNAL_OUT_RING else Prefs.SIGNAL_IN_RING) }
        },
    )
}

/**
 * Whether the signal is drawn inside the glyph, which is what enables the per-card rows.
 *
 * Covers the capsule as well as the ring: both draw the rows in a band, so both can place
 * them. The rectangle cannot, and [TrioAppearance.slotted] is what the callers gate on, so
 * this only has to answer the shared part.
 */
private fun signalInsideRing(a: TrioAppearance): Boolean =
    a.mobile && a.slotted && !a.signalOutOfRing

/**
 * One card's third, with the thirds the other card holds disabled.
 *
 * The two cards cannot share a third: two rows of four dots in one slot would overlap
 * and the layout would have to pick one of them, so the second card's choice is expressed
 * by disabling the option rather than by silently moving the first card. A disabled row
 * that cannot be picked is also the honest presentation of "one of these is taken".
 *
 * The middle is disabled for every card, always, because the ring does not hold a signal
 * row there - see [TrioZones.isSignalRow] and the reason in `Prefs.zoneAccepts`. That is not
 * the other card taking it: it is a band the row has no business in, so it is blocked
 * whatever the twin is doing.
 */
@Composable
private fun SignalCardPositionControl(
    part: TrioZones.Part,
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
    visible: Boolean,
) {
    // Hidden rather than greyed, like every other gated row: a signal card has no slot
    // to pick while the signal is drawn outside the glyph.
    if (!visible) {
        return
    }
    val id = TrioZones.encodePart(part)
    val twin = if (part == TrioZones.Part.SIM_1) TrioZones.Part.SIM_2 else TrioZones.Part.SIM_1
    val twinId = TrioZones.encodePart(twin)
    val zones = settings.ringZones
    val placedAt = zones.indexOfFirst { it.contains(id) } + 1
    val takenByTwin = zones.indexOfFirst { it.contains(twinId) }
    // Option 0 is "not placed" and is always allowed; 1..3 mirror the zones. The middle is
    // refused for the row itself, and the one the twin holds is refused because it is taken.
    val blocked = buildSet {
        add(TrioZones.Zone.CENTRE.ordinal + 1)
        if (takenByTwin >= 0) add(takenByTwin + 1)
    }
    ContentChoice(
        selected = placedAt,
        title = if (part == TrioZones.Part.SIM_1) R.string.ring_position_sim_1
        else R.string.ring_position_sim_2,
        summaryRes = null,
        options = listOf(R.string.ring_position_native, R.string.drawing_hint_top,
            R.string.drawing_hint_centre, R.string.drawing_hint_bottom),
        dependencies = listOf(settings.enabled to R.string.master_title),
        blockedIndices = blocked,
        onChange = { value -> update { it.setRingPartPosition(part, value) } },
    )
}

/**
 * Wi-Fi's position, with "off" as one of the choices.
 *
 * Wi-Fi is the one piece whose switch and position always changed together: switching it
 * off and removing it from the ring produced the same picture, and switching it on with
 * nothing placed produced none. Two rows for one decision invited the pair to disagree, so
 * they are one row here - index 0 means off-and-unplaced, 1..3 mean on-and-in-that-third.
 *
 * Both facts are written together through [SettingsRepository.setWifiPosition], because
 * writing them separately would leave a frame in between where the switch and the layout
 * disagree.
 */
@Composable
private fun WifiPositionControl(
    settings: TrioSettings,
    ringSettings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    val id = TrioZones.encodePart(TrioZones.Part.WIFI)
    val placedAt = ringSettings.ringZones.indexOfFirst { it.contains(id) } + 1
    // Off wins over "placed somewhere": a switch that is off draws no arcs whatever the
    // layout says, so reporting a slot here would name a place that produces nothing.
    val selected = if (!settings.showWifi) 0 else placedAt
    ContentChoice(selected, R.string.ring_position_wifi, null,
        listOf(R.string.ring_position_native, R.string.drawing_hint_top,
            R.string.drawing_hint_centre, R.string.drawing_hint_bottom),
        listOf(settings.enabled to R.string.master_title)) { value ->
        update { it.setWifiPosition(value) }
    }
}

/**
 * The one option every position dropdown has to be able to refuse: the ring's middle does
 * not hold a signal row.
 *
 * Written as a set of option indices rather than as a boolean so the caller can add the
 * options it has its own reason to disable - the third the other card already holds - and
 * hand one set to [ContentChoice]. The index is the zone's ordinal plus one: zero is "not
 * placed" in every one of these dropdowns, and 1..3 mirror the three bands.
 *
 * Only meaningful where the row is actually a ring row. The per-card rows are ring-only,
 * and the Wi-Fi row never names a signal row, so in practice this is the card rows' own
 * constant - kept a function so the arithmetic cannot be written slightly differently in
 * two places.
 */
/**
 * The network type's position: outside first, then the three bands, then the system.
 *
 * "Outside" leads because it is the default the type has always had on this bar, and
 * because a type that is not in the ring must land somewhere - removing it from the band
 * list used to leave it unplaced and therefore invisible. Index 0 writes the outside
 * mode, 1..3 place it in a band, 4 hands it back to the system.
 */
@Composable
private fun TypePositionControl(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
    zonesAllowed: Boolean,
) {
    val id = TrioZones.encodePart(TrioZones.Part.TYPE)
    val placedAt = settings.ringZones.indexOfFirst { it.contains(id) } + 1
    val selected = if (placedAt in 1..3) placedAt else 0
    ContentChoice(
        selected = selected,
        title = R.string.ring_position_type,
        summaryRes = null,
        options = listOf(
            R.string.mobile_type_outside,
            R.string.drawing_hint_top,
            R.string.drawing_hint_centre,
            R.string.drawing_hint_bottom,
        ),
        dependencies = listOf(settings.enabled to R.string.master_title),
        blockedIndices = if (zonesAllowed) emptySet() else setOf(1, 2, 3),
    ) { value ->
        update {
            when (value) {
                0 -> {
                    it.setRingPartPosition(TrioZones.Part.TYPE, 0)
                    if (settings.mobileTypeMode == Prefs.MOBILE_TYPE_IN_RING) {
                        it.setMobileTypeMode(Prefs.MOBILE_TYPE_OUT_RING)
                    }
                }
                else -> {
                    it.setRingPartPosition(TrioZones.Part.TYPE, value)
                    it.setMobileTypeMode(Prefs.MOBILE_TYPE_IN_RING)
                }
            }
        }
    }
}

private fun middleBlocked(part: TrioZones.Part): Set<Int> =
    if (TrioZones.isSignalRow(part)) setOf(TrioZones.Zone.CENTRE.ordinal + 1) else emptySet()

@Composable
private fun RingPositionControl(
    part: TrioZones.Part,
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    val id = TrioZones.encodePart(part)
    // Which zone's list currently holds this piece, or 0 for "not placed". The dropdown is a shortcut into the priority lists, not a second source of truth.
    val zones = settings.ringZones
    val placedAt = zones.indexOfFirst { it.contains(id) } + 1
    val selected = if (part == TrioZones.Part.TYPE
        && settings.mobileTypeMode == Prefs.MOBILE_TYPE_OUT_RING) 4 else placedAt
    // The row's title is the SLOT it picks, never the component's own switch name.
    val title = when (part) {
        TrioZones.Part.WIFI -> R.string.ring_position_wifi
        TrioZones.Part.MOBILE -> R.string.ring_position_mobile
        TrioZones.Part.SIM_1 -> R.string.ring_position_sim_1
        TrioZones.Part.SIM_2 -> R.string.ring_position_sim_2
        TrioZones.Part.VALUE -> R.string.ring_position_value
        TrioZones.Part.BOLT -> R.string.ring_position_bolt
        TrioZones.Part.TYPE -> R.string.ring_position_type
        TrioZones.Part.CLOCK -> R.string.ring_position_clock
        TrioZones.Part.POWER -> R.string.ring_position_power
        TrioZones.Part.TEMP -> R.string.ring_position_temp
        TrioZones.Part.RUNNING -> R.string.ring_position_running
        TrioZones.Part.FPS -> R.string.ring_position_fps
        TrioZones.Part.TRAFFIC -> R.string.ring_position_traffic
        TrioZones.Part.BT_BATTERY -> R.string.ring_position_bt_battery
    }
    ContentChoice(selected, title,
        null,
        listOf(R.string.ring_position_native, R.string.drawing_hint_top,
            R.string.drawing_hint_centre, R.string.drawing_hint_bottom) +
            if (part == TrioZones.Part.TYPE) listOf(R.string.mobile_type_outside) else emptyList(),
        listOf(settings.enabled to R.string.master_title),
        // A signal row cannot be put in the middle band - see [middleBlocked].
        blockedIndices = middleBlocked(part),
    ) { value ->
        update { it.setRingPartPosition(part, value) }
    }
}

/**
 * A switch row, optionally with a description under its title.
 *
 * [summaryRes] is null for the rows that explain themselves: the master switch
 * (whose own title already says what it turns on) and the ring-position dropdown
 * (whose options name the slots). Miuix drops the description line entirely when
 * the summary is null, so the row is not left with an empty second line.
 *
 * @param enabled 独立于 [dependencies] 的置灰开关：通知面板分区用它保证总开关关闭时
 *   选项仍然照常显示（只是不可点），而不是整块分区变空。
 */
@Composable
private fun ContentToggle(
    checked: Boolean,
    title: Int,
    summaryRes: Int?,
    dependencies: List<Pair<Boolean, Int>>,
    enabled: Boolean = true,
    hintRes: Int? = null,
    onChange: (Boolean) -> Unit,
) {
    ContentGate(dependencies) {
        // 说明行 + 置灰原因：Miuix 只给一行摘要，所以拼成一句话，而不是丢掉原说明。
        val summary = listOfNotNull(
            summaryRes?.let { stringResource(it) },
            hintRes?.let { stringResource(it) },
        ).joinToString(" ")
        SwitchPreference(
            checked = checked,
            title = stringResource(title),
            summary = summary.ifEmpty { null },
            insideMargin = SettingsItemMargin,
            enabled = enabled,
            onCheckedChange = onChange,
        )
    }
}

/**
 * A dropdown row, optionally with a description and with individual options disabled.
 *
 * [blockedIndices] is for the options that exist but may not be chosen right now - the
 * third already held by the other card. Disabling the entry rather than dropping it keeps
 * the list the same length as the other cards' lists, so the numeric order the row writes
 * stays the same everywhere.
 *
 * @param enabled a gate independent of [dependencies]: the row can be live while depending
 *   on nothing, which is what the per-card rows need when the signal is outside the ring.
 */
@Composable
private fun ContentChoice(
    selected: Int,
    title: Int,
    summaryRes: Int?,
    options: List<Int>,
    dependencies: List<Pair<Boolean, Int>>,
    enabled: Boolean = true,
    blockedIndices: Set<Int> = emptySet(),
    onChange: (Int) -> Unit,
) {
    ContentGate(dependencies) {
        if (blockedIndices.isEmpty()) {
            GlassDropdownPreference(
                items = options.map { stringResource(it) },
                selectedIndex = selected,
                title = stringResource(title),
                summary = summaryRes?.let { stringResource(it) },
                insideMargin = SettingsItemMargin,
                enabled = enabled && dependencies.all { it.first },
                onSelectedIndexChange = onChange,
            )
        } else {
            GlassDropdownPreference(
                entry = DropdownEntry(
                    items = options.mapIndexed { index, res ->
                        DropdownItem(
                            text = stringResource(res),
                            enabled = index !in blockedIndices,
                            selected = index == selected,
                            // The list carries its own per-item state, so each entry has
                            // to report its own choice back.
                            onClick = { onChange(index) },
                        )
                    },
                    enabled = enabled && dependencies.all { it.first },
                ),
                title = stringResource(title),
                summary = summaryRes?.let { stringResource(it) },
                insideMargin = SettingsItemMargin,
                enabled = enabled && dependencies.all { it.first },
            )
        }
    }
}

/**
 * 生效范围的三项，按选项下标表示：0 控制中心、1 通知面板、2 锁屏界面。
 *
 * 存的是排除位，所以某一位没置才是「这一处生效」。
 */
private fun scopeActiveSet(mask: Int): Set<Int> = buildSet {
    if ((mask and Prefs.SCOPE_BIT_CC) == 0) add(0)
    if ((mask and Prefs.SCOPE_BIT_SHADE) == 0) add(1)
    if ((mask and Prefs.SCOPE_BIT_KEYGUARD) == 0) add(2)
}

/** [scopeActiveSet] 的反向：把勾选集合写回排除位掩码。 */
private fun scopeMaskOf(active: Set<Int>): Int {
    var mask = 0
    if (0 !in active) mask = mask or Prefs.SCOPE_BIT_CC
    if (1 !in active) mask = mask or Prefs.SCOPE_BIT_SHADE
    if (2 !in active) mask = mask or Prefs.SCOPE_BIT_KEYGUARD
    return mask
}

/**
 * 多选下拉行，用 [GlassMultiDropdownPreference]（每项左侧勾选框）。
 *
 * @param minimumSelected 至少保留几项勾选。生效范围是唯一设它的调用方：全不勾意味着
 *   模块哪都不显示，那不是能选的地方，读起来像出错而不是一个选择。所以最后一项点不掉，
 *   这一行永远说明一个范围。
 */
@Composable
private fun ContentMultiChoice(
    selected: Set<Int>,
    title: Int,
    options: List<Int>,
    dependencies: List<Pair<Boolean, Int>>,
    minimumSelected: Int = 0,
    onChange: (Set<Int>) -> Unit,
) {
    ContentGate(dependencies) {
        val labels = options.map { stringResource(it) }
        val summary = if (selected.size >= options.size) {
            stringResource(R.string.scope_all)
        } else {
            options.indices.filter { it in selected }
                .joinToString(stringResource(R.string.scope_list_separator)) { labels[it] }
        }
        GlassMultiDropdownPreference(
            title = stringResource(title),
            options = labels,
            selectedIndices = selected,
            onToggleIndex = { index ->
                val next = selected.toMutableSet()
                if (!next.remove(index)) next.add(index)
                if (next.size >= minimumSelected) onChange(next)
            },
            summary = summary,
            insideMargin = SettingsItemMargin,
            enabled = dependencies.all { it.first },
        )
    }
}

@Composable
private fun ContentGate(dependencies: List<Pair<Boolean, Int>>, content: @Composable () -> Unit) {
    // Switched off means switched off: the row is not drawn at all.
    if (dependencies.any { !it.first }) {
        return
    }
    content()
}
