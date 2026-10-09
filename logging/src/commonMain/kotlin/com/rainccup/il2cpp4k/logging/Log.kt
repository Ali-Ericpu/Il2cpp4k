package com.rainccup.il2cpp4k.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity

/** 默认 tag；可用 [Log.tag] 派生带 tag 的视图。 */
const val DEFAULT_TAG = "il2cpp4k"

/**
 * 框架与调用者共用的日志门面（基于 Kermit）。
 *
 * ```kotlin
 * Log.i { "hook installed at 0x${addr.toString(16)}" }   // 惰性消息：级别关闭时零开销
 * Log.e(error) { "il2cpp symbol missing: $name" }
 * Log.tag("Player").i { "TakeDamage blocked" }
 * Log.level = Severity.Debug                             // 调级别
 * Log.useWriter(myWriter)                                // 换输出
 * Log.useFile()                                          // 双写：平台输出 + 文件（注入场景事后可读）
 * ```
 *
 * 平台默认输出：Windows 写 stdout + `OutputDebugStringA`；Android 写 stdout/stderr
 * （应用标准输出会被重定向进 logcat），可用时额外经 `liblog` 写入带真实 tag 的记录。
 * 注入到无控制台目标时，两条默认通道都可能不可见 —— 用 [useFile] 落盘。
 */
object Log {
    /** 带默认 tag 的实例；与全局 config 共享，故 setMinSeverity/setLogWriters 对其同样生效。 */
    private val logger: Logger = Logger.withTag(DEFAULT_TAG)

    var level: Severity = Severity.Info
        set(value) {
            field = value
            Logger.setMinSeverity(value)
        }

    /** 替换输出目标（如写文件或测试用捕获 writer）。 */
    fun useWriter(writer: LogWriter) {
        Logger.setLogWriters(writer)
    }

    /**
     * 输出目标增加文件（默认 [defaultLogFilePath]，即 `%TEMP%\il2cpp4k.log`），
     **保留**平台默认输出（双写）。只要文件时用 `useWriter(LogFileWriter(path))`。
     */
    fun useFile(path: String = defaultLogFilePath()) {
        useWriter(CompositeLogWriter(defaultLogWriter(), LogFileWriter(path)))
    }

    /** 恢复平台默认输出。 */
    fun useDefaultWriter() {
        Logger.setLogWriters(defaultLogWriter())
    }

    /** 派生带 tag 的日志视图。 */
    fun tag(tag: String): TaggedLog = TaggedLog(Logger.withTag(tag))

    fun v(message: () -> String) = logger.v(message = message)
    fun d(message: () -> String) = logger.d(message = message)
    fun i(message: () -> String) = logger.i(message = message)
    fun w(message: () -> String) = logger.w(message = message)
    fun e(message: () -> String) = logger.e(message = message)

    fun v(throwable: Throwable?, message: () -> String) = logger.v(throwable = throwable, message = message)
    fun d(throwable: Throwable?, message: () -> String) = logger.d(throwable = throwable, message = message)
    fun i(throwable: Throwable?, message: () -> String) = logger.i(throwable = throwable, message = message)
    fun w(throwable: Throwable?, message: () -> String) = logger.w(throwable = throwable, message = message)
    fun e(throwable: Throwable?, message: () -> String) = logger.e(throwable = throwable, message = message)

    init {
        useDefaultWriter()
    }
}

/** 带固定 tag 的日志视图。 */
class TaggedLog internal constructor(private val logger: Logger) {
    fun v(message: () -> String) = logger.v(message = message)
    fun d(message: () -> String) = logger.d(message = message)
    fun i(message: () -> String) = logger.i(message = message)
    fun w(message: () -> String) = logger.w(message = message)
    fun e(message: () -> String) = logger.e(message = message)

    fun v(throwable: Throwable?, message: () -> String) = logger.v(throwable = throwable, message = message)
    fun d(throwable: Throwable?, message: () -> String) = logger.d(throwable = throwable, message = message)
    fun i(throwable: Throwable?, message: () -> String) = logger.i(throwable = throwable, message = message)
    fun w(throwable: Throwable?, message: () -> String) = logger.w(throwable = throwable, message = message)
    fun e(throwable: Throwable?, message: () -> String) = logger.e(throwable = throwable, message = message)
}

/** 平台默认 writer。 */
internal expect fun defaultLogWriter(): LogWriter

/** 统一的记录格式（供各平台 writer 与测试复用）。 */
fun formatRecord(severity: Severity, tag: String, message: String, throwable: Throwable?): String {
    val level = severity.name.uppercase()
    val suffix = if (throwable == null) "" else ": ${throwable::class.simpleName}: ${throwable.message}"
    return "[$level][$tag] $message$suffix"
}
