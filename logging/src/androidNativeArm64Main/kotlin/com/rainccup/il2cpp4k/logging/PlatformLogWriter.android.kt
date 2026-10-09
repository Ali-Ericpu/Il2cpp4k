@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.cinterop.*
import platform.posix.RTLD_NOW
import platform.posix.dlopen
import platform.posix.dlsym

/** 与 `android/log.h` 的优先级常量一致。 */
private const val ANDROID_LOG_DEBUG = 3
private const val ANDROID_LOG_INFO = 4
private const val ANDROID_LOG_WARN = 5
private const val ANDROID_LOG_ERROR = 6

/**
 * Android 默认输出：写 stdout/stderr（应用标准输出会被 Android 重定向进 logcat，
 * 以 `System.out`/`System.err` 出现）；若能通过 `liblog` 取得 `__android_log_write`，
 * 则额外写入一条带真实 tag 的 logcat 记录。
 */
internal actual fun defaultLogWriter(): LogWriter = object : LogWriter() {
    private val logWrite: CPointer<CFunction<(Int, CPointer<ByteVar>, CPointer<ByteVar>) -> Int>>? by lazy {
        resolveAndroidLogWrite()
    }

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = formatRecord(severity, tag, message, throwable)
        if (severity >= Severity.Warn) println(line) else println(line)

        val write = logWrite ?: return
        memScoped {
            write.invoke(priorityOf(severity), tag.cstr.ptr, line.cstr.ptr)
        }
    }
}

private fun priorityOf(severity: Severity): Int = when (severity) {
    Severity.Verbose, Severity.Debug -> ANDROID_LOG_DEBUG
    Severity.Info -> ANDROID_LOG_INFO
    Severity.Warn -> ANDROID_LOG_WARN
    else -> ANDROID_LOG_ERROR
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveAndroidLogWrite(): CPointer<CFunction<(Int, CPointer<ByteVar>, CPointer<ByteVar>) -> Int>>? {
    val handle = dlopen("liblog.so", RTLD_NOW) ?: return null
    val symbol = memScoped { dlsym(handle, "__android_log_write") } ?: return null
    return symbol.reinterpret()
}
