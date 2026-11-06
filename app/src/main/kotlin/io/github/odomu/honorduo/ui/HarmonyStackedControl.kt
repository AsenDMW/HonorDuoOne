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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.odomu.honorduo.R
import io.github.odomu.honorduo.model.TrioSettings
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 堆叠信号资源与判定逻辑的出处：原模块 StackedSignal_5G 的酷安帖子。
 *
 * 设置页这一行只负责把链接交给系统浏览器，不做内置浏览器或弹窗预览。
 * 关于页致谢区块也用这个地址，所以不设私有可见性，避免两处各写一份。
 */
internal const val STACKED_SOURCE_URL =
    "https://www.coolapk.com/feed/74608300?s=NTAxYmJmN2QyZDUxYzFnNmFlZGRjYmJ6a1662"

/**
 * 鸿蒙堆叠信号互斥开关。
 *
 * 开启会让本模块自带功能整体停用（Hook 全部透传、状态栏字形撤离），信号改由
 * [StackedSignalHook] 按移植进来的 StackedSignal_5G 资源与判定绘制；因为是一次
 * 整体停用动作，先弹 [OverlayDialog] 说明影响，用户确认后才落盘，不做「一拨即生效」。
 *
 * 关闭不做确认：它只是撤掉互斥标记，其它开关的持久化值从未被改动，会按原值恢复。
 *
 * 样式沿用 Miuix 的 [SwitchPreference]，与排布页其它功能开关同排、同内边距。
 */
@Composable
internal fun HarmonyStackedControl(
    settings: TrioSettings,
    update: ((SettingsRepository) -> Unit) -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val context = LocalContext.current

    SwitchPreference(
        checked = settings.harmonyStacked,
        title = stringResource(R.string.harmony_stacked_title),
        summary = stringResource(
            if (settings.harmonyStacked) {
                R.string.harmony_stacked_summary_on
            } else {
                R.string.harmony_stacked_summary_off
            },
        ),
        insideMargin = SettingsItemMargin,
        // 总开关关掉时整块本来就是停用的，互斥项没有意义；互斥开启后本项仍可操作，
        // 否则用户没有办法把它关回去。
        enabled = settings.enabled || settings.harmonyStacked,
        onCheckedChange = { checked ->
            if (checked) {
                confirming = true
            } else {
                update { it.setHarmonyStacked(false) }
            }
        },
    )

    // 出处标注紧贴开关说明文字，字号与 tint 同 Miuix 的说明行；点击交给系统浏览器。
    Text(
        text = stringResource(R.string.harmony_stacked_source),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openLink(context, STACKED_SOURCE_URL) }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )

    OverlayDialog(
        show = confirming,
        title = stringResource(R.string.harmony_stacked_confirm_title),
        summary = stringResource(R.string.harmony_stacked_confirm_summary),
        onDismissRequest = { confirming = false },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(
                text = stringResource(R.string.cancel),
                onClick = { confirming = false },
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    confirming = false
                    update { it.setHarmonyStacked(true) }
                },
                colors = ButtonDefaults.buttonColorsPrimary(),
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.harmony_stacked_confirm_action))
            }
        }
    }
}
