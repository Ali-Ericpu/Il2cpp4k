@file:OptIn(ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.usleep

internal actual val il2cppModuleName: String = "libil2cpp.so"

internal actual fun launchProcess(exePath: String, args: String?, workingDir: String?): Int =
    throw InjectFailure(
        InjectError.USAGE,
        "launching a target process is supported on Windows only (got --exe $exePath)",
    )

internal actual fun launchProcessSuspended(exePath: String, args: String?, workingDir: String?): Int =
    throw InjectFailure(
        InjectError.USAGE,
        "spawn mode is Windows-only: Android 的 ptrace 注入没有「诞生时」可用的挂起点 " +
            "(got --spawn with --exe $exePath)",
    )

internal actual fun resumeProcess(pid: Int): Unit =
    throw InjectFailure(InjectError.USAGE, "resumeProcess is Windows-only (spawn mode)")

internal actual fun callExportedEntry(
    pid: Int,
    libPath: String,
    entry: String,
    timeoutMs: Int,
    verbose: Boolean,
): Unit = throw InjectFailure(
    InjectError.USAGE,
    "callExportedEntry is Windows-only; on Android use inject --call-entry " +
        "(入口在 ptrace 会话内一并调用)",
)

internal actual fun isProcessAlive(pid: Int): Boolean = AndroidInjector.readProcFile(pid, "stat") != null

internal actual fun sleepMillis(ms: Long) {
    usleep((ms * 1000L).toUInt())
}

internal actual fun listAllProcesses(withIl2cppFlag: Boolean): List<TargetProcess> {
    val entries = ArrayList<TargetProcess>()
    val directory = opendir("/proc") ?: return emptyList()
    try {
        while (true) {
            val item = readdir(directory) ?: break
            val name = item.pointed.d_name.toKString()
            val pid = name.toIntOrNull() ?: continue
            val cmdline = AndroidInjector.readProcFile(pid, "cmdline") ?: continue
            val processName = cmdline.split('\u0000').firstOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val module =
                if (withIl2cppFlag && isModuleLoaded(pid, il2cppModuleName)) il2cppModuleName else null
            entries.add(TargetProcess(pid, processName, module))
        }
    } finally {
        closedir(directory)
    }
    return entries.sortedBy { it.pid }
}

internal actual fun isModuleLoaded(pid: Int, moduleName: String): Boolean {
    val maps = AndroidInjector.readProcFile(pid, "maps") ?: return false
    return isModuleLoaded(parseMaps(maps), moduleName)
}

/** maps 读不到即无法枚举（进程已退出）；活进程的 maps 至少含自身映射。 */
internal actual fun moduleNames(pid: Int): List<String>? {
    val maps = AndroidInjector.readProcFile(pid, "maps") ?: return null
    return parseMaps(maps)
        .mapNotNull { it.path.substringAfterLast('/').takeIf(String::isNotEmpty) }
        .distinct()
}

internal actual fun injectAndCall(
    pid: Int,
    libPath: String,
    entry: String?,
    timeoutMs: Int,
    verbose: Boolean,
): Long = AndroidInjector.inject(pid, libPath, entry, timeoutMs, verbose)
