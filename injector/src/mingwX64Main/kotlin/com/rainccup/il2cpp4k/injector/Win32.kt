@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.windows.CloseHandle
import platform.windows.CreateProcessW
import platform.windows.GetLastError
import platform.windows.GetFullPathNameW
import platform.windows.OpenProcess
import platform.windows.OpenThread
import platform.windows.PROCESS_INFORMATION
import platform.windows.PROCESS_TERMINATE
import platform.windows.ResumeThread
import platform.windows.STARTUPINFOW
import platform.windows.SYNCHRONIZE
import platform.windows.TerminateProcess
import platform.windows.THREAD_SUSPEND_RESUME
import platform.windows.WaitForSingleObject

private const val WAIT_TIMEOUT = 258u
private const val ERROR_ACCESS_DENIED = 5u

/** `CreateProcessW` 的 CREATE_SUSPENDED：进程创建但主线程挂起，未执行任何指令。 */
private const val CREATE_SUSPENDED = 0x4u

/** SuspendThread/ResumeThread 失败时返回的哨兵值 (0xFFFFFFFF)。 */
private const val THREAD_CALL_FAILED = 0xFFFFFFFFu

/** `CreateProcessW` 在目标 exe 的清单声明 `requireAdministrator` 且调用方未提升时返回它。 */
private const val ERROR_ELEVATION_REQUIRED = 740u

/**
 * `CreateProcessW` 失败消息。
 *
 * 740 与 5 都指向「需要管理员」这一族，必须显式说出来 —— 原始错误码对使用者没有可操作性。
 * 其余码只报原始码，不臆测原因。
 */
internal fun launchFailureMessage(exePath: String, errorCode: UInt): String = when (errorCode) {
    ERROR_ELEVATION_REQUIRED ->
        "CreateProcessW failed for $exePath (error 740: the target requires elevation — " +
            "run this injector as Administrator)"

    ERROR_ACCESS_DENIED ->
        "CreateProcessW failed for $exePath (error 5: Access denied — " +
            "run this injector as Administrator)"

    else -> "CreateProcessW failed for $exePath (error $errorCode)"
}

/** Win32 门面：进程/模块枚举（见 [Toolhelp]）+ 进程启动、存活判定与终止。 */
internal object Win32 {
    fun listProcesses(): List<Pair<Int, String>> = Toolhelp.processEntries()

    fun listModules(pid: Int): List<ModuleInfo> = Toolhelp.moduleEntries(pid)

    /**
     * 启动 [exePath] 并返回 pid。
     *
     * 句柄立即关闭（只保留 pid，不泄漏）；[workingDir] 为 null 时继承当前工作目录。
     * 用 `CreateProcessW` 而非 A 版，非 ASCII 路径可用。
     */
    fun launchProcess(exePath: String, args: String?, workingDir: String?): Int =
        launchProcess(exePath, args, workingDir, suspended = false)

    /** 同上，[suspended] = true 时以 CREATE_SUSPENDED 启动：主线程挂起、未跑任何指令。 */
    fun launchProcess(exePath: String, args: String?, workingDir: String?, suspended: Boolean): Int = memScoped {
        // `CreateProcessW` 的 lpCurrentDirectory 要求**完整**路径，相对目录会直接报 ERROR_DIRECTORY(267)；
        // 而用户完全可能给相对 `--exe`（此时工作目录由 parentDirectoryOf 派生，同样是相对的）。
        // 故两者都在这里解析成绝对路径。
        val exe = absolutePathOf(exePath)
            ?: throw InjectFailure(InjectError.OPEN_FAILED, "cannot resolve the target path: $exePath")
        val cwdPath = workingDir?.let {
            absolutePathOf(it)
                ?: throw InjectFailure(InjectError.OPEN_FAILED, "cannot resolve the working directory: $it")
        }

        val startup = alloc<STARTUPINFOW>()
        startup.cb = sizeOf<STARTUPINFOW>().toUInt()
        val info = alloc<PROCESS_INFORMATION>()

        // lpCommandLine 可被 CreateProcessW 就地改写，故必须是可写缓冲；exe 路径加引号以容忍空格
        val commandLine = buildString {
            append('"').append(exe).append('"')
            if (!args.isNullOrBlank()) append(' ').append(args)
        }.toWideChars()

        // dwCreationFlags：--spawn 时挂起启动，主线程未运行即获得注入窗口
        val creationFlags = if (suspended) CREATE_SUSPENDED else 0u

        val started = commandLine.usePinned { command ->
            exe.toWideChars().usePinned { application ->
                val cwd = cwdPath?.toWideChars()
                if (cwd != null) {
                    cwd.usePinned { pinned ->
                        CreateProcessW(
                            application.addressOf(0), command.addressOf(0), null, null, 0, creationFlags,
                            null, pinned.addressOf(0), startup.ptr, info.ptr,
                        )
                    }
                } else {
                    CreateProcessW(
                        application.addressOf(0), command.addressOf(0), null, null, 0, creationFlags,
                        null, null, startup.ptr, info.ptr,
                    )
                }
            }
        }
        if (started == 0) {
            throw InjectFailure(InjectError.OPEN_FAILED, launchFailureMessage(exe, GetLastError()))
        }
        info.hThread?.let { CloseHandle(it) }
        info.hProcess?.let { CloseHandle(it) }
        info.dwProcessId.toInt()
    }

    /** 进程是否仍在运行。打开失败（进程已消失或权限不足）按「已退出」处理。 */
    fun isProcessAlive(pid: Int): Boolean {
        val handle = OpenProcess(SYNCHRONIZE.toUInt(), 0, pid.toUInt()) ?: return false
        return try {
            WaitForSingleObject(handle, 0u) == WAIT_TIMEOUT
        } finally {
            CloseHandle(handle)
        }
    }

    /** 终止 [pid]。测试与清理用；生产路径绝不调用（注入器不接管游戏生命周期）。 */
    fun terminateProcess(pid: Int): Boolean {
        val handle = OpenProcess(PROCESS_TERMINATE.toUInt(), 0, pid.toUInt()) ?: return false
        return try {
            TerminateProcess(handle, 0u) != 0
        } finally {
            CloseHandle(handle)
        }
    }

    /**
     * 恢复 [pid] 的主线程（spawn 模式专用）。
     *
     * CREATE_SUSPENDED 启动的进程，其挂起主线程就是进程首个线程；注入完成后
     * 由它带着模块继续正常的进程初始化。找不到线程（目标已退出）按 OPEN_FAILED 报。
     */
    fun resumeMainThread(pid: Int) {
        val threadId = Toolhelp.firstThreadOf(pid)
            ?: throw InjectFailure(InjectError.OPEN_FAILED, "no thread found for pid $pid to resume")
        val thread = OpenThread(THREAD_SUSPEND_RESUME.toUInt(), 0, threadId.toUInt())
            ?: throw InjectFailure(
                InjectError.OPEN_FAILED,
                "OpenThread failed for tid $threadId (error ${GetLastError()})",
            )
        try {
            if (ResumeThread(thread) == THREAD_CALL_FAILED) {
                throw InjectFailure(InjectError.INJECT_FAILED, "ResumeThread failed for tid $threadId")
            }
        } finally {
            CloseHandle(thread)
        }
    }
}

/** NUL 结尾的 UTF-16 可写缓冲，供 W 系列 Win32 API 使用。 */
internal fun String.toWideChars(): UShortArray {
    val out = UShortArray(length + 1)
    forEachIndexed { index, c -> out[index] = c.code.toUShort() }
    return out
}

/**
 * 把 [path] 解析为绝对路径（`GetFullPathNameW`）；失败返回 null。
 *
 * **为什么必须做**：这个字符串会被写进**目标进程**再交给它的 `LoadLibraryW`，而相对路径在目标里
 * 是按**目标的当前工作目录**解析的 —— 那是注入器不控制的东西。`--exe` 启动路径还会把目标 cwd
 * 设成 exe 所在目录，因此相对 `--lib` 在那条路径上必然错位（实测：目标报 `LoadLibraryW returned null`）。
 */
internal fun absolutePathOf(path: String): String? {
    val source = path.toWideChars()
    return source.usePinned { pinned ->
        val src = pinned.addressOf(0)
        // 缓冲为 0 时 GetFullPathNameW 返回所需 TCHAR 数（含结尾 NUL）；0 表示失败
        val required = GetFullPathNameW(src, 0u, null, null)
        if (required == 0u) return@usePinned null
        val buffer = UShortArray(required.toInt())
        buffer.usePinned { out ->
            if (GetFullPathNameW(src, required, out.addressOf(0), null) == 0u) return@usePinned null
            val end = buffer.indexOf(0.toUShort()).let { if (it < 0) buffer.size else it }
            buildString(end) { for (i in 0 until end) append(buffer[i].toInt().toChar()) }
                // 归一化分隔符：GetFullPathNameW 不把 '/' 转成 '\'，而混用分隔符的目录路径
                // 交给 CreateProcessW 的 lpCurrentDirectory 并不可靠（实测 ERROR_DIRECTORY 267）
                .replace('/', '\\')
        }
    }
}
