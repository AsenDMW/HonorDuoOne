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

import android.content.Context
import android.os.Build
import android.util.Xml
import androidx.core.content.pm.PackageInfoCompat
import io.github.libxposed.service.XposedService
import io.github.odomu.honorduo.BuildConfig
import io.github.odomu.honorduo.R
import io.github.odomu.honorduo.hook.HookReport
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class DiagRow(val label: String, val value: String)

internal data class DiagHook(val ok: Boolean, val id: String, val target: String, val reason: String)

internal data class DiagnosticsData(
    val device: List<DiagRow>,
    val module: List<DiagRow>,
    val framework: List<DiagRow>,
    val hooks: List<DiagHook>,
    val stacked: List<String>,
    val logs: List<String>,
    val generatedAt: Long,
)

/**
 * 「诊断信息」页的数据来源：一半是本进程能直接读到的（设备、模块、框架服务、
 * 作用域、目标进程），一半是 SystemUI 进程安装 hook 时写下的快照（每条 hook 的
 * 挂载结果、堆叠信号探测、最近日志）。
 *
 * 快照直接读 `shared_prefs/diagnostics.xml`：那是 libxposed 远端偏好落盘的真实文件，
 * 每次打开页面重新解析，能拿到 SystemUI 进程最新写入的值（SharedPreferences 对象
 * 会缓存，不适合这里）。
 */
internal class ModuleDiagnostics(
    private val context: Context,
    private val service: XposedService?,
) {

    fun collect(): DiagnosticsData {
        val snapshot = readSnapshot()
        val running = runCatching { service?.runningTargets }.getOrNull().orEmpty()
        val scope = runCatching { service?.scope }.getOrNull().orEmpty()
        val systemUi = running.firstOrNull { it.processName == SYSTEMUI }
        val scoped = scope.any { it == SYSTEMUI || it == "system" }

        val device = ArrayList<DiagRow>(5)
        device += DiagRow(str(R.string.diag_label_vendor), Build.MANUFACTURER)
        device += DiagRow(str(R.string.diag_label_model), "${Build.MODEL} (${Build.DEVICE})")
        device += DiagRow(str(R.string.diag_label_android), "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        device += DiagRow(str(R.string.diag_label_magicos), magicOs() ?: str(R.string.diag_unavailable))
        device += DiagRow(str(R.string.diag_label_systemui), systemUiVersion())

        val module = ArrayList<DiagRow>(3)
        module += DiagRow(
            str(R.string.diag_label_module_version),
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        )
        module += DiagRow(str(R.string.diag_label_package), context.packageName)
        snapshot[HookReport.KEY_INSTALLED]?.let {
            module += DiagRow(str(R.string.diag_label_snapshot), it)
        }
        snapshot[HookReport.KEY_STAMP]?.toLongOrNull()?.let {
            module += DiagRow(str(R.string.diag_snapshot_time), formatTime(it))
        }

        val framework = ArrayList<DiagRow>(4)
        framework += DiagRow(
            str(R.string.diag_label_framework),
            service?.let { "${it.frameworkName} ${it.frameworkVersion} (API ${it.apiVersion})" }
                ?: str(R.string.diag_not_connected),
        )
        framework += DiagRow(
            str(R.string.diag_label_scope_systemui),
            str(if (scoped) R.string.diag_scope_yes else R.string.diag_scope_no),
        )
        framework += DiagRow(
            str(R.string.diag_label_scope),
            if (scope.isEmpty()) str(R.string.diag_unavailable) else scope.joinToString(", "),
        )
        framework += DiagRow(
            str(R.string.diag_label_targets),
            when {
                systemUi == null -> str(R.string.diag_target_missing)
                systemUi.loadedVersionCode != BuildConfig.VERSION_CODE.toLong() ->
                    str(R.string.diag_target_stale, "${systemUi.loadedVersionCode}")
                else -> str(
                    R.string.diag_target_loaded,
                    systemUi.pid.toString(),
                    systemUi.loadedVersionCode.toString(),
                )
            },
        )
        running.filter { it.processName != SYSTEMUI }.forEach {
            framework += DiagRow(it.processName, "PID ${it.pid} · ${it.state} · v${it.loadedVersionCode}")
        }

        return DiagnosticsData(
            device = device,
            module = module,
            framework = framework,
            hooks = parseHooks(snapshot[HookReport.KEY_HOOKS]),
            stacked = snapshot[HookReport.KEY_STACKED].toLines(),
            logs = snapshot[HookReport.KEY_LOGS].toLines(),
            generatedAt = System.currentTimeMillis(),
        )
    }

    fun exportText(data: DiagnosticsData): String {
        val sb = StringBuilder()
        sb.append("Honor Duo One ").append(str(R.string.diag_title)).append('\n')
        sb.append(str(R.string.diag_generated)).append(": ").append(formatTime(data.generatedAt)).append("\n\n")

        fun block(title: String, rows: List<DiagRow>) {
            if (rows.isEmpty()) return
            sb.append("[").append(title).append("]\n")
            rows.forEach { sb.append(it.label).append(" = ").append(it.value).append('\n') }
            sb.append('\n')
        }
        block(str(R.string.diag_section_device), data.device)
        block(str(R.string.diag_section_module), data.module)
        block(str(R.string.diag_section_framework), data.framework)

        sb.append("[").append(str(R.string.diag_section_hooks)).append("]\n")
        if (data.hooks.isEmpty()) {
            sb.append(str(R.string.diag_hooks_empty)).append('\n')
        } else {
            data.hooks.forEach { h ->
                sb.append(if (h.ok) "OK   " else "FAIL ").append(h.id)
                if (h.target.isNotEmpty()) sb.append("  ->  ").append(h.target)
                if (h.reason.isNotEmpty()) sb.append("  (").append(h.reason).append(")")
                sb.append('\n')
            }
        }
        sb.append('\n')

        sb.append("[").append(str(R.string.diag_section_stacked)).append("]\n")
        if (data.stacked.isEmpty()) {
            sb.append(str(R.string.diag_stacked_empty)).append('\n')
        } else {
            data.stacked.forEach { sb.append(it).append('\n') }
        }
        sb.append('\n')

        sb.append("[").append(str(R.string.diag_section_logs)).append("]\n")
        if (data.logs.isEmpty()) {
            sb.append(str(R.string.diag_logs_empty)).append('\n')
        } else {
            data.logs.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- 内部

    private fun str(id: Int): String = context.getString(id)

    private fun str(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun magicOs(): String? {
        // Honor 不同 MagicOS 版本的属性名不一致，按新旧顺序取第一个有值的。
        val keys = arrayOf(
            "ro.build.version.magic",
            "ro.build.version.magicos",
            "ro.build.version.emui",
            "ro.build.display.id",
        )
        for (key in keys) {
            val v = systemProp(key)
            if (!v.isNullOrBlank()) return v
        }
        return null
    }

    private fun systemProp(key: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()

    private fun systemUiVersion(): String = runCatching {
        val info = context.packageManager.getPackageInfo(SYSTEMUI, 0)
        "${info.versionName} (${PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrNull() ?: str(R.string.diag_unavailable)

    private fun formatTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))

    private fun String?.toLines(): List<String> =
        this?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

    private fun parseHooks(text: String?): List<DiagHook> {
        val out = ArrayList<DiagHook>()
        text.toLines().forEach { line ->
            val p = line.split(HookReport.SEP)
            if (p.size < 3) return@forEach
            out += if (p[0] == "OK") {
                DiagHook(true, p[1], p[2], "")
            } else {
                DiagHook(false, p[1], p[2], p.getOrElse(3) { "" })
            }
        }
        return out
    }

    /** 读 SystemUI 进程写下的快照；文件不在时退回框架服务的远端偏好。 */
    private fun readSnapshot(): Map<String, String> {
        val fromFile = readPrefsFile()
        if (fromFile.isNotEmpty()) return fromFile
        return runCatching {
            service?.getRemotePreferences(HookReport.PREFS)?.all
                ?.mapValues { (_, v) -> v?.toString().orEmpty() }
        }.getOrNull().orEmpty()
    }

    private fun readPrefsFile(): Map<String, String> {
        val file = File(context.dataDir, "shared_prefs/${HookReport.PREFS}.xml")
        if (!file.isFile) return emptyMap()
        return try {
            val parser = Xml.newPullParser()
            file.inputStream().use { parser.setInput(it, null) }
            val out = HashMap<String, String>()
            var key: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> if (parser.name == "string") {
                        key = parser.getAttributeValue(null, "name")
                    }
                    XmlPullParser.TEXT -> {
                        val k = key
                        if (k != null) {
                            out[k] = parser.text
                            key = null
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "string") {
                        val k = key
                        if (k != null && !out.containsKey(k)) out[k] = ""
                        key = null
                    }
                }
                event = parser.next()
            }
            out
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    private companion object {
        const val SYSTEMUI = "com.android.systemui"
    }
}
