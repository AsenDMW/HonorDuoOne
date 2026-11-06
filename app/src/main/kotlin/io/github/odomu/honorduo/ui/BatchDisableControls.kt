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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.odomu.honorduo.R
import io.github.odomu.honorduo.model.Prefs
import io.github.odomu.honorduo.model.TrioSettings
import io.github.odomu.honorduo.render.TrioZones
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 一个可以在「关闭全部」里被批量关掉的功能项。
 *
 * 只收**真正的开关**：读的是用户在某一行上看到的状态，写的是该行原本的写入口，
 * 所以批量关闭和手点那一下的结果完全一致，不会绕过任何一行的语义（比如 Wi-Fi 的
 * 开关与位置是同一个决定）。
 *
 * 收总开关：关闭全部 = 整机停用，所以模块总开关也在批量里，且排在第一位。
 * 不收「鸿蒙堆叠信号」互斥项：它是让位开关而不是功能项；它开着时整页本来就是只读的。
 */
internal data class FeatureToggle(
    val titleRes: Int,
    val isOn: (TrioSettings) -> Boolean,
    val turnOff: (SettingsRepository) -> Unit,
)

internal val featureToggles: List<FeatureToggle> = listOf(
    // 模块总开关排在最前：关闭全部即整机停用，界面上第一眼就能看到它被关掉。
    FeatureToggle(R.string.master_module_title, { it.enabled }, { r -> r.setEnabled(false) }),
    // 显示组件：与排布页的功能开关一一对应。
    FeatureToggle(R.string.master_title, { it.mergeIcons }, { r -> r.setMergeIcons(false) }),
    FeatureToggle(R.string.ring_position_wifi, { it.showWifi }, { r -> r.setShowWifi(false) }),
    FeatureToggle(R.string.ring_position_mobile, { it.showMobile }, { r -> r.setShowMobile(false) }),
    FeatureToggle(R.string.dual_signal_title, { it.dualSignal }, { r -> r.setDualSignal(false) }),
    FeatureToggle(R.string.dual_signal_label_title, { it.dualSignalLabel }, { r -> r.setDualSignalLabel(false) }),
    FeatureToggle(R.string.data_sim_only_title, { it.dataSimOnly }, { r -> r.setDataSimOnly(false) }),
    FeatureToggle(R.string.show_mobile_activity_title, { it.showMobileActivity }, { r -> r.setShowMobileActivity(false) }),
    FeatureToggle(R.string.stacked_signal_title, { it.stackedSignal }, { r -> r.setStackedSignal(false) }),
    FeatureToggle(R.string.show_value_title, { it.showValue }, { r -> r.setShowValue(false) }),
    FeatureToggle(R.string.show_percent_title, { it.showPercent }, { r -> r.setShowPercent(false) }),
    FeatureToggle(R.string.charging_shows_value_title, { it.chargingShowsValue }, { r -> r.setChargingShowsValue(false) }),
    FeatureToggle(R.string.show_bolt_title, { it.showBolt }, { r -> r.setShowBolt(false) }),
    FeatureToggle(R.string.trio_charge_breath_title, { it.trioChargeBreath }, { r -> r.setTrioChargeBreath(false) }),
    FeatureToggle(R.string.trio_bt_battery_title, { it.trioBtBattery != 0 }, { r -> r.setTrioBtBattery(0) }),
    FeatureToggle(
        R.string.show_mobile_type_title,
        { it.mobileTypeMode != Prefs.MOBILE_TYPE_OFF },
        { r -> r.setMobileTypeMode(Prefs.MOBILE_TYPE_OFF) },
    ),
    // 时钟按「有没有放进环里」算开启，与排布页那一行的判定一致。
    FeatureToggle(
        R.string.ring_position_clock,
        { s -> s.ringZones.any { it.contains(Prefs.PART_CLOCK) } },
        { r -> r.setRingPartPosition(TrioZones.Part.CLOCK, 0) },
    ),
    FeatureToggle(R.string.clock_seconds_title, { it.clockSeconds }, { r -> r.setClockSeconds(false) }),
    FeatureToggle(R.string.clock_date_title, { it.clockDate }, { r -> r.setClockDate(false) }),
    FeatureToggle(R.string.clock_weekday_title, { it.clockWeekday }, { r -> r.setClockWeekday(false) }),
    FeatureToggle(R.string.clock_lunar_title, { it.clockLunar }, { r -> r.setClockLunar(false) }),
    FeatureToggle(R.string.clock_weather_title, { it.clockWeather }, { r -> r.setClockWeather(false) }),
    // 环外读数。
    FeatureToggle(R.string.perf_power_title, { it.perfPower }, { r -> r.setPerfPower(false) }),
    FeatureToggle(R.string.perf_running_title, { it.perfRunning }, { r -> r.setPerfRunning(false) }),
    FeatureToggle(R.string.perf_temp_title, { it.perfTemp }, { r -> r.setPerfTemp(false) }),
    FeatureToggle(R.string.perf_fps_title, { it.perfFps }, { r -> r.setPerfFps(false) }),
    // 状态栏原生图标与外观。
    FeatureToggle(R.string.replace_wifi_icon_title, { it.replaceWifiIcon }, { r -> r.setReplaceWifiIcon(false) }),
    FeatureToggle(R.string.replace_status_icon_title, { it.replaceStatusIcon }, { r -> r.setReplaceStatusIcon(false) }),
    FeatureToggle(R.string.hide_status_clock_title, { it.hideStatusClock }, { r -> r.setHideStatusClock(false) }),
    FeatureToggle(R.string.hide_alarm_icon_title, { it.hideAlarmIcon }, { r -> r.setHideAlarmIcon(false) }),
    FeatureToggle(R.string.hide_bluetooth_icon_title, { it.hideBluetoothIcon }, { r -> r.setHideBluetoothIcon(false) }),
    FeatureToggle(R.string.hide_privacy_chip_title, { it.hidePrivacyChip }, { r -> r.setHidePrivacyChip(false) }),
    FeatureToggle(R.string.hide_sim2_title, { it.hideSim2 }, { r -> r.setHideSim2(false) }),
    FeatureToggle(R.string.hide_net_type_title, { it.hideNetType }, { r -> r.setHideNetType(false) }),
    FeatureToggle(R.string.anti_burn_in_title, { it.antiBurnIn }, { r -> r.setAntiBurnIn(false) }),
    FeatureToggle(R.string.trio_morph_title, { it.trioMorph }, { r -> r.setTrioMorph(false) }),
    FeatureToggle(R.string.trio_morph_toast_title, { it.trioMorphToast }, { r -> r.setTrioMorphToast(false) }),
    FeatureToggle(R.string.panel_native_icons_title, { it.panelNativeIcons }, { r -> r.setPanelNativeIcons(false) }),
    FeatureToggle(R.string.trio_compact_row_title, { it.trioCompactRow }, { r -> r.setTrioCompactRow(false) }),
    FeatureToggle(R.string.trio_keep_icons_title, { it.trioKeepIcons }, { r -> r.setTrioKeepIcons(false) }),
    FeatureToggle(R.string.capsule_no_scale_title, { it.capsuleNoScale }, { r -> r.setCapsuleNoScale(false) }),
    FeatureToggle(R.string.magicos10_invert_title, { it.magicos10Invert }, { r -> r.setMagicos10Invert(false) }),
    // 通知中心。
    FeatureToggle(R.string.shade_hide_power_title, { it.shadeHidePower }, { r -> r.setShadeHidePower(false) }),
    FeatureToggle(R.string.shade_hide_bell_title, { it.shadeHideBell }, { r -> r.setShadeHideBell(false) }),
    FeatureToggle(R.string.cc_volume_longpress_title, { it.ccVolumeLongpress }, { r -> r.setCcVolumeLongpress(false) }),
    FeatureToggle(R.string.notif_hide_charging_title, { it.notifHideCharging }, { r -> r.setNotifHideCharging(false) }),
    FeatureToggle(R.string.notif_hide_hotspot_title, { it.notifHideHotspot }, { r -> r.setNotifHideHotspot(false) }),
    FeatureToggle(R.string.notif_hide_usb_title, { it.notifHideUsb }, { r -> r.setNotifHideUsb(false) }),
    FeatureToggle(R.string.shade_lunar_title, { it.shadeLunar }, { r -> r.setShadeLunar(false) }),
    FeatureToggle(R.string.shade_weather_title, { it.shadeWeather }, { r -> r.setShadeWeather(false) }),
    FeatureToggle(R.string.shade_weather_opens_app_title, { it.shadeWeatherOpensApp }, { r -> r.setShadeWeatherOpensApp(false) }),
)

/** 当前处于开启状态的功能项。列表页与「已开启」计数都从这里取，两者不会对不上。 */
internal fun enabledToggles(settings: TrioSettings): List<FeatureToggle> =
    featureToggles.filter { it.isOn(settings) }

/**
 * 排布页「功能开关」里的「关闭全部」入口。
 *
 * 是一个动作行而不是开关：点一下先弹确认（说明影响与不可逆），确认后才逐项写回关闭。
 * 互斥项开着时整行置灰——那正是模块整体停用的状态，这时批量关闭只是改存储、不改变
 * 行为，不能给用户「点了就生效」的错觉。
 */
@Composable
internal fun CloseAllControl(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val count = enabledToggles(settings).size
    val locked = settings.harmonyStacked

    ArrowPreference(
        title = stringResource(R.string.batch_close_all_title),
        summary = when {
            locked -> stringResource(R.string.batch_close_all_locked)
            count == 0 -> stringResource(R.string.batch_close_all_empty)
            else -> stringResource(R.string.batch_close_all_summary, count)
        },
        insideMargin = SettingsItemMargin,
        enabled = !locked && count > 0,
        onClick = { confirming = true },
    )

    if (confirming) {
        BatchDisableConfirm(
            count = count,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                update { repo -> enabledToggles(settings).forEach { it.turnOff(repo) } }
            },
        )
    }
}

/**
 * 批量关闭前的确认框。
 *
 * 与「鸿蒙堆叠信号」的确认共用一套写法：标题、影响说明、取消 / 确认两个等宽按钮，
 * 样式仍是 Miuix 的 OverlayDialog。
 */
@Composable
internal fun BatchDisableConfirm(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayDialog(
        show = true,
        title = stringResource(R.string.batch_close_all_confirm_title),
        summary = stringResource(R.string.batch_close_all_confirm_summary, count),
        onDismissRequest = onDismiss,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.batch_close_all_action))
            }
        }
    }
}

/**
 * 关于页「已开启显示项」点进来的清单。
 *
 * 只列当前为开的项，每一项都能单独关掉；底部还有一个「全部关闭」，走同一个确认框。
 * 互斥项开启时整页只读：行置灰、全部关闭不可点，但依然能看清当时开着哪些——
 * 这也是「别出现绕过闸门的入口」的落点。
 */
@Composable
internal fun EnabledItemsSheet(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val locked = settings.harmonyStacked
    val items = enabledToggles(settings)
    var confirming by remember { mutableStateOf(false) }
    val windowHeight = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.height.toDp()
    }

    OverlayDialog(
        show = !confirming,
        title = stringResource(R.string.enabled_items_title),
        summary = stringResource(R.string.enabled_items_summary, items.size),
        onDismissRequest = onDismiss,
    ) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = windowHeight * 0.68f)) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
            ) {
                if (locked) {
                    Text(
                        text = stringResource(R.string.enabled_items_locked),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (items.isEmpty()) {
                    Text(text = stringResource(R.string.enabled_items_empty))
                }
                // 行显示为「开」但不可再打开：这里的动作只有关闭，所以把开关画成常开、
                // 只处理被拨到关的那一下；这样它和排布页的同一项行为一致。
                items.forEach { toggle ->
                    SwitchPreference(
                        checked = true,
                        title = stringResource(toggle.titleRes),
                        summary = null,
                        insideMargin = SettingsItemMargin,
                        enabled = !locked,
                        onCheckedChange = { on ->
                            if (!on) update { repo -> toggle.turnOff(repo) }
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.enabled_items_close),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { confirming = true },
                    colors = ButtonDefaults.buttonColorsPrimary(),
                    enabled = !locked && items.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.batch_close_all_action))
                }
            }
        }
    }

    if (confirming) {
        BatchDisableConfirm(
            count = items.size,
            onDismiss = { confirming = false },
            onConfirm = {
                confirming = false
                update { repo -> items.forEach { it.turnOff(repo) } }
            },
        )
    }
}
