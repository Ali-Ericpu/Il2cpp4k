package com.rainccup.il2cpp4k.injector

import platform.posix.usleep

internal actual val il2cppModuleName: String = "GameAssembly.dll"

internal actual fun launchProcess(exePath: String, args: String?, workingDir: String?): Int =
    Win32.launchProcess(exePath, args, workingDir)

internal actual fun launchProcessSuspended(exePath: String, args: String?, workingDir: String?): Int =
    Win32.launchProcess(exePath, args, workingDir, suspended = true)

internal actual fun resumeProcess(pid: Int) {
    Win32.resumeMainThread(pid)
}

internal actual fun callExportedEntry(
    pid: Int,
    libPath: String,
    entry: String,
    timeoutMs: Int,
    verbose: Boolean,
): Unit = WinInjector.callEntry(pid, libPath, entry, timeoutMs, verbose)

internal actual fun isProcessAlive(pid: Int): Boolean = Win32.isProcessAlive(pid)

internal actual fun sleepMillis(ms: Long) {
    usleep((ms * 1000L).toUInt())
}

internal actual fun listAllProcesses(withIl2cppFlag: Boolean): List<TargetProcess> =
    Win32.listProcesses()
        .map { (pid, name) ->
            TargetProcess(pid, name, if (withIl2cppFlag) il2cppModuleOf(pid) else null)
        }
        .sortedBy { it.pid }

internal actual fun injectAndCall(
    pid: Int,
    libPath: String,
    entry: String?,
    timeoutMs: Int,
    verbose: Boolean,
): Long = WinInjector.inject(pid, libPath, entry, timeoutMs, verbose)

internal actual fun isModuleLoaded(pid: Int, moduleName: String): Boolean =
    Win32.listModules(pid).any { it.name.equals(moduleName, ignoreCase = true) }

/** 活进程至少含自身 exe 与 ntdll，故快照为空必为枚举失败（进程已退出，或受保护无法快照）。 */
internal actual fun moduleNames(pid: Int): List<String>? =
    Win32.listModules(pid).takeIf { it.isNotEmpty() }?.map { it.name }

private fun il2cppModuleOf(pid: Int): String? =
    Win32.listModules(pid).firstOrNull { it.name.equals(il2cppModuleName, ignoreCase = true) }?.name
