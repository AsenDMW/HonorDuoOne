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

import android.os.SystemClock
import io.github.libxposed.api.XposedModule
import io.github.odomu.honorduo.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 诊断快照：在 SystemUI 进程里把「每条 hook 挂没挂上、为什么没挂上、堆叠信号的探测
 * 结果、最近的日志」写进模块自己的远端偏好，设置 App 的「诊断信息」页直接读同一份
 * 文件，用来给用户和开发排查问题。
 *
 * 为什么必须走远端偏好：hook 跑在 com.android.systemui 进程（system uid），设置页跑在
 * 模块自己的进程，两边内存互不可见。libxposed 的 `getRemotePreferences` 就是为这种
 * 「目标进程写、模块 App 读」的通道准备的，文件落在模块的 shared_prefs 下。
 *
 * 开销：只在 SystemUI 启动时完整采集一次（本来就要跑的那些 hook 安装），日志只是
 * 把已有的 log 调用抄进一个环形缓冲；写盘按 [FLUSH_GAP_MS] 节流，默认关闭调试日志时
 * 几乎不会触发。设置页打开时**不再**触发任何 hook 侧动作，只读文件。
 */
internal object HookReport {

    /** 远端偏好组名，设置页读 `shared_prefs/diagnostics.xml` 用的就是它。 */
    const val PREFS = "diagnostics"

    /** 快照格式。两端的解析逻辑都以这个值为准，改格式要一起改。 */
    const val FORMAT = "1"

    const val KEY_FORMAT = "format"
    const val KEY_STAMP = "stamp"
    const val KEY_PID = "pid"
    const val KEY_INSTALLED = "installed"
    const val KEY_HOOKS = "hooks"
    const val KEY_STACKED = "stacked"
    const val KEY_LOGS = "logs"

    /** hook 行内的分隔符；类名/方法名里不会出现制表符。 */
    const val SEP = "\t"

    private const val MAX_LOGS = 200
    private const val FLUSH_GAP_MS = 15_000L

    private val lock = Any()

    @Volatile
    private var module: XposedModule? = null

    private val hooks = ArrayList<String>()
    private val stacked = ArrayList<String>()
    private val logs = ArrayDeque<String>()

    private var lastFlushAt = 0L
    private var flushQueued = false

    private val worker by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "HonorDuoDiagnostics").apply { isDaemon = true }
        }
    }

    /** 最近一次反射查找的结果，用来判定 hook 没挂上的具体原因。 */
    private class Lookup(
        val className: String?,
        val classFound: Boolean,
        val methodName: String?,
        val arity: Int,
        val methodFound: Boolean,
    )

    private val lastLookup = ThreadLocal<Lookup?>()

    /** SystemUI 进程启动、开始装 hook 之前调用：清掉上一轮的记录。 */
    fun attach(module: XposedModule) {
        synchronized(lock) {
            this.module = module
            hooks.clear()
            stacked.clear()
            logs.clear()
            lastFlushAt = 0L
            flushQueued = false
        }
        appendLog("attach pid=${android.os.Process.myPid()} ver=${BuildConfig.VERSION_NAME}")
    }

    fun noteClass(name: String, found: Boolean) {
        if (module == null) return
        lastLookup.set(Lookup(name, found, null, -1, false))
    }

    fun noteMethod(className: String?, name: String, arity: Int, found: Boolean) {
        if (module == null) return
        lastLookup.set(Lookup(className, className != null, name, arity, found))
    }

    /** 记录一条 hook 的最终结果。[target] 形如 `类名#方法名`。 */
    fun record(id: String, target: String, ok: Boolean, reason: String = "") {
        if (module == null) return
        val line = if (ok) {
            "OK$SEP$id$SEP$target"
        } else {
            "FAIL$SEP$id$SEP$target$SEP${reason.replace('\n', ' ').replace('\t', ' ')}"
        }
        synchronized(lock) { hooks.add(line) }
    }

    /** [TrioHooks.hook] 没拿到方法时，用上一次查找结果推出原因。 */
    fun missingReason(): String {
        val lookup = lastLookup.get() ?: return "未定位到目标方法"
        val className = lookup.className ?: return "未定位到目标类"
        if (!lookup.classFound) return "类不存在：$className"
        val methodName = lookup.methodName ?: return "方法不存在：$className"
        if (!lookup.methodFound) {
            return "方法签名不匹配：$className#$methodName(${lookup.arity} 个参数)"
        }
        return "未定位到目标方法"
    }

    fun addStacked(line: String) {
        if (module == null) return
        synchronized(lock) { stacked.add(line) }
    }

    /** 日志环形缓冲。传入的文本已经是最终一行，不再附加时间。 */
    fun appendLog(message: String) {
        if (module == null) return
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lock) {
            logs.addLast("$stamp $message")
            while (logs.size > MAX_LOGS) logs.removeFirst()
        }
        maybeFlush()
    }

    /** 安装流程结束：无视节流，立刻落一次盘。 */
    fun finish() {
        if (module == null) return
        worker.execute {
            runCatching { flushNow() }
        }
    }

    private fun maybeFlush() {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (module == null || flushQueued) return
            if (now - lastFlushAt < FLUSH_GAP_MS) return
            flushQueued = true
        }
        worker.execute {
            try {
                flushNow()
            } catch (_: Throwable) {
                // 诊断写盘失败不能影响 hook 本身
            } finally {
                synchronized(lock) { flushQueued = false }
            }
        }
    }

    private fun flushNow() {
        val m = module ?: return
        val hookLines: String
        val stackedLines: String
        val logLines: String
        val stamp: Long
        synchronized(lock) {
            hookLines = hooks.joinToString("\n")
            stackedLines = (stacked + StackedSignalHook.statsLines()).joinToString("\n")
            logLines = logs.joinToString("\n")
            stamp = System.currentTimeMillis()
            lastFlushAt = SystemClock.elapsedRealtime()
        }
        runCatching {
            val prefs = m.getRemotePreferences(PREFS)
            prefs.edit().apply {
                putString(KEY_FORMAT, FORMAT)
                putString(KEY_STAMP, stamp.toString())
                putString(KEY_PID, android.os.Process.myPid().toString())
                putString(KEY_INSTALLED, "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                putString(KEY_HOOKS, hookLines)
                putString(KEY_STACKED, stackedLines)
                putString(KEY_LOGS, logLines)
            }.commit()
        }
    }
}
