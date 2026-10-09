package com.rainccup.il2cpp4k.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity

/**
 * 文件输出 writer：每条记录以 [formatRecord] 格式追加一行（LF 行尾）到 [path]。
 *
 * 设计取舍（注入场景优先）：
 * - **每行 open-append-close**：不做缓冲与句柄复用，慢（每条几十 µs）—— 但目标进程
 *   在任意时刻崩溃/被杀，已写下的行都已在盘上。这正是注入脚本最需要的性质：
 *   崩溃现场的最后一条日志必然可读。
 * - **失败静默丢弃**：日志设施不能反过来用日志报告日志失败。路径不可写时无任何
 *   报错，排查时先检查 [path] 与目标进程对该目录的写权限。
 * - **非线程安全**：多线程 hook 回调并发写同一文件可能行间交错；v1 接受
 *   （典型入口脚本为单线程驱动）。
 *
 * 动机（2026-10 受保护目标真机实验）：注入到游戏里的脚本日志在两条默认通道上都
 * 不可见 —— stdout（窗口应用无控制台）与 OutputDebugStringA（无 DBWin 捕获器
 * 在场时直接丢弃，不可追溯）。文件通道让日志**事后可读**。
 */
class LogFileWriter(private val path: String) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        appendFileLine(path, formatRecord(severity, tag, message, throwable))
    }
}

/**
 * 组合 writer：把每条记录依次交给所有底层 writer。
 *
 * 典型用法：平台默认输出 + 文件双写（见 [Log.useFile]）。底层 writer 各自保证
 * 不抛异常（[LogFileWriter] 失败静默返回，平台 writer 只做输出调用）。
 */
class CompositeLogWriter(private vararg val writers: LogWriter) : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        writers.forEach { it.log(severity, message, tag, throwable) }
    }
}

/** 把 [line] 追加一行到 [path]；失败（路径不可写等）返回 false。 */
internal expect fun appendFileLine(path: String, line: String): Boolean

/**
 * 平台默认日志文件路径。
 * - mingw：`%TEMP%\il2cpp4k.log`（目标进程为提升进程时为其自身用户的 TEMP，事后可读）
 * - android：`/data/local/tmp/il2cpp4k.log`（root 下 adb 可取回）
 */
expect fun defaultLogFilePath(): String
