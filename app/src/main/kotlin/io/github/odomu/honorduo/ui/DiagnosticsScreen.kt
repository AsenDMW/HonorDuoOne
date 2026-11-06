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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.odomu.honorduo.R
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 诊断页：把 [ModuleDiagnostics.collect] 采到的东西按卡片铺出来。
 *
 * 排布与关于页保持同一套行/卡片样式（[SectionTitle]、[InfoRow]、[ArrowPreference]），
 * 只多了一个 Hook 列表：每条 hook 一行，成功打勾、失败打叉并直接写出原因，这是
 * 「装了但没生效」时最有用的信息。
 */
internal fun LazyListScope.diagnosticsTab(
    data: DiagnosticsData?,
    onCopy: () -> Unit,
    onExport: () -> Unit,
    onReload: () -> Unit,
) {
    item {
        SectionTitle(stringResource(R.string.diag_section_actions))
    }
    item {
        Card {
            ArrowPreference(
                title = stringResource(R.string.diag_copy),
                summary = stringResource(R.string.diag_copy_summary),
                insideMargin = SettingsItemMargin,
                onClick = onCopy,
            )
            ArrowPreference(
                title = stringResource(R.string.diag_export),
                summary = stringResource(R.string.diag_export_summary),
                insideMargin = SettingsItemMargin,
                onClick = onExport,
            )
            ArrowPreference(
                title = stringResource(R.string.diag_refresh),
                summary = stringResource(R.string.diag_refresh_summary),
                insideMargin = SettingsItemMargin,
                onClick = onReload,
            )
        }
    }

    diagRows(R.string.diag_section_device, data?.device)
    diagRows(R.string.diag_section_module, data?.module)
    diagRows(R.string.diag_section_framework, data?.framework)

    item { SectionTitle(stringResource(R.string.diag_section_hooks)) }
    item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                val hooks = data?.hooks.orEmpty()
                if (hooks.isEmpty()) {
                    Note(stringResource(R.string.diag_hooks_empty))
                } else {
                    hooks.forEachIndexed { index, hook ->
                        if (index > 0) Spacer(modifier = Modifier.height(14.dp))
                        HookRow(hook)
                    }
                }
            }
        }
    }

    diagLines(R.string.diag_section_stacked, data?.stacked, R.string.diag_stacked_empty, mono = false)
    diagLines(R.string.diag_section_logs, data?.logs, R.string.diag_logs_empty, mono = true)
}

private fun LazyListScope.diagRows(titleRes: Int, rows: List<DiagRow>?) {
    if (rows.isNullOrEmpty()) return
    item { SectionTitle(stringResource(titleRes)) }
    item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                rows.forEach { InfoRow(it.label, it.value) }
            }
        }
    }
}

private fun LazyListScope.diagLines(titleRes: Int, lines: List<String>?, emptyRes: Int, mono: Boolean) {
    item { SectionTitle(stringResource(titleRes)) }
    item {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                if (lines.isNullOrEmpty()) {
                    Note(stringResource(emptyRes))
                } else {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = if (mono) {
                                MiuixTheme.textStyles.body2.copy(fontFamily = FontFamily.Monospace)
                            } else {
                                MiuixTheme.textStyles.body2
                            },
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HookRow(hook: DiagHook) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (hook.ok) "✓" else "✗",
            style = MiuixTheme.textStyles.headline1,
            color = if (hook.ok) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = hook.id,
                style = MiuixTheme.textStyles.body2,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurface,
            )
            if (hook.target.isNotEmpty()) {
                Text(
                    text = hook.target,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            if (!hook.ok && hook.reason.isNotEmpty()) {
                Text(
                    text = hook.reason,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.body2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
}
