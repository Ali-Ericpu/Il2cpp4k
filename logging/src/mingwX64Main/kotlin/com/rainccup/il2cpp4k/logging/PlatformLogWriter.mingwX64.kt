@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.OutputDebugStringA

/**
 * Windows 默认输出：stdout（目标进程有控制台时可见）+ `OutputDebugStringA`
 * （无控制台时由 DebugView 一类工具捕获；未附加调试器时该调用为空操作，无副作用）。
 */
internal actual fun defaultLogWriter(): LogWriter = object : LogWriter() {
    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = formatRecord(severity, tag, message, throwable)
        println(line)
        memScoped { OutputDebugStringA("$line\n".cstr.ptr) }
    }
}
