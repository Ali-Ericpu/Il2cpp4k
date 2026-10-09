@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.*
import com.rainccup.il2cpp4k.logging.Log
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.memcpy
import platform.posix.usleep
import platform.windows.CONTEXT
import platform.windows.CONTEXT_CONTROL
import platform.windows.CONTEXT_INTEGER
import platform.windows.CloseHandle
import platform.windows.CreateRemoteThread
import platform.windows.GetExitCodeThread
import platform.windows.GetLastError
import platform.windows.GetModuleHandleA
import platform.windows.GetProcAddress
import platform.windows.GetThreadContext
import platform.windows.HANDLE
import platform.windows.MEM_COMMIT
import platform.windows.MEM_RELEASE
import platform.windows.MEM_RESERVE
import platform.windows.OpenProcess
import platform.windows.OpenThread
import platform.windows.PAGE_EXECUTE_READWRITE
import platform.windows.PAGE_READWRITE
import platform.windows.PROCESS_CREATE_THREAD
import platform.windows.PROCESS_QUERY_INFORMATION
import platform.windows.PROCESS_VM_OPERATION
import platform.windows.PROCESS_VM_READ
import platform.windows.PROCESS_VM_WRITE
import platform.windows.ReadProcessMemory
import platform.windows.ResumeThread
import platform.windows.SetThreadContext
import platform.windows.SuspendThread
import platform.windows.THREAD_GET_CONTEXT
import platform.windows.THREAD_QUERY_INFORMATION
import platform.windows.THREAD_SET_CONTEXT
import platform.windows.THREAD_SUSPEND_RESUME
import platform.windows.VirtualAllocEx
import platform.windows.VirtualFreeEx
import platform.windows.WaitForSingleObject
import platform.windows.WriteProcessMemory

private const val WAIT_OBJECT_0 = 0u
private const val ERROR_ACCESS_DENIED = 5u
private const val THREAD_CALL_FAILED = 0xFFFFFFFFu

/** 远程 stub 的固定尺寸。 */
private const val STUB_SIZE = 64L

/** 注入流程：加载库（可选调用入口）。返回目标中的模块句柄。 */
internal object WinInjector {
    fun inject(pid: Int, libPath: String, entry: String?, timeoutMs: Int, verbose: Boolean): Long {
        val image = readFile(libPath)
            ?: throw InjectFailure(InjectError.LIB_NOT_FOUND, "library not readable: $libPath")
        val process = openTargetProcess(pid)
        try {
            val moduleHandle = residentModuleBase(pid, libPath)?.let { resident ->
                // 已驻留 → **整个跳过加载调用**。对受监控的目标，这次调用正是反作弊要拦的
                // 东西（真机实证 2026-10：对运行中的受保护目标二次 LoadLibraryW 被主动拒绝，
                // 返回 INVALID_HANDLE_VALUE，而诞生时注入的模块始终驻留）。
                if (verbose) Log.d { "module already resident at 0x${resident.toString(16)}; skipping LoadLibraryW" }
                resident and 0xFFFFFFFFL // 与加载路径口径一致：模块基址的低 32 位
            } ?: loadLibrary(process, libPath, timeoutMs, verbose)
            if (entry != null) {
                callEntryInProcess(process, image, pid, libPath, entry, timeoutMs, verbose)
            }
            return moduleHandle
        } finally {
            CloseHandle(process)
        }
    }

    /**
     * 在已注入 [libPath] 的目标内**单独**调用导出入口（spawn 模式分步流程的第二段：
     * 诞生时已加载，resume + 等待 + 延迟之后再触发）。
     */
    fun callEntry(pid: Int, libPath: String, entry: String, timeoutMs: Int, verbose: Boolean) {
        val image = readFile(libPath)
            ?: throw InjectFailure(InjectError.LIB_NOT_FOUND, "library not readable: $libPath")
        val process = openTargetProcess(pid)
        try {
            callEntryInProcess(process, image, pid, libPath, entry, timeoutMs, verbose)
        } finally {
            CloseHandle(process)
        }
    }

    private fun openTargetProcess(pid: Int): HANDLE {
        val access = PROCESS_CREATE_THREAD or PROCESS_QUERY_INFORMATION or PROCESS_VM_OPERATION or
            PROCESS_VM_WRITE or PROCESS_VM_READ
        return OpenProcess(access.toUInt(), 0, pid.toUInt())
            ?: run {
                val code = GetLastError()
                val hint = if (code == ERROR_ACCESS_DENIED) " (administrator privileges required)" else ""
                throw InjectFailure(InjectError.OPEN_FAILED, "OpenProcess failed for pid $pid (error $code)$hint")
            }
    }

    /**
     * 目标是否已驻留 [libPath] 对应的模块；驻留则返回其 **64 位真实基址**，否则 null。
     *
     * 模块名按文件名匹配（与加载器对同名模块的短路语义一致），大小写不敏感。
     * 供两处使用：[inject] 的「已驻留 → 跳过加载」决定，与 [callEntryInProcess] 的入口地址计算。
     */
    internal fun residentModuleBase(pid: Int, libPath: String): Long? {
        val moduleName = libPath.substringAfterLast('/').substringAfterLast('\\')
        return Toolhelp.moduleEntries(pid)
            .firstOrNull { it.name.equals(moduleName, ignoreCase = true) }?.base
    }

    /** 加载库：远程线程调 LoadLibraryW（只做加载，不进入目标库代码，故裸线程安全）。 */
    private fun loadLibrary(process: HANDLE, libPath: String, timeoutMs: Int, verbose: Boolean): Long {
        // 相对路径必须在**注入器**这边解析成绝对路径：写进目标后，相对路径会按目标的 cwd 解析，
        // 而那个目录注入器不控制（--exe 启动路径会把目标 cwd 设成 exe 所在目录）。
        val absolute = absolutePathOf(libPath)
            ?: throw InjectFailure(
                InjectError.INJECT_FAILED,
                "cannot resolve an absolute path for the library: $libPath",
            )
        if (verbose && absolute != libPath) Log.d { "resolved $libPath -> $absolute" }
        val pathBytes = absolute.toUtf16Bytes()
        val remote = VirtualAllocEx(
            process,
            null,
            pathBytes.size.toULong(),
            (MEM_COMMIT or MEM_RESERVE).toUInt(),
            PAGE_READWRITE.toUInt(),
        ) ?: throw InjectFailure(InjectError.INJECT_FAILED, "VirtualAllocEx failed")

        try {
            pathBytes.usePinned { pinned ->
                val written = WriteProcessMemory(
                    process,
                    remote,
                    pinned.addressOf(0),
                    pathBytes.size.toULong(),
                    null,
                )
                if (written == 0) throw InjectFailure(InjectError.INJECT_FAILED, "WriteProcessMemory failed")
            }

            val kernel = GetModuleHandleA("kernel32.dll")
                ?: throw InjectFailure(InjectError.INJECT_FAILED, "GetModuleHandleA(kernel32) failed")
            val loadLibraryW = GetProcAddress(kernel, "LoadLibraryW")
                ?.reinterpret<CFunction<(CPointer<out CPointed>?) -> UInt>>()
                ?: throw InjectFailure(InjectError.INJECT_FAILED, "GetProcAddress(LoadLibraryW) failed")

            val thread = CreateRemoteThread(process, null, 0uL, loadLibraryW, remote, 0u, null)
                ?: throw InjectFailure(InjectError.INJECT_FAILED, "CreateRemoteThread(LoadLibraryW) failed")
            val moduleHandle = try {
                waitThread(thread, timeoutMs, "LoadLibraryW")
                readExitCode(thread)
            } finally {
                CloseHandle(thread)
            }
            // 报绝对路径：失败时告诉人的应该是"目标实际尝试加载的那个路径"
            loadFailureMessage(moduleHandle, absolute)?.let { reason ->
                throw InjectFailure(InjectError.INJECT_FAILED, reason)
            }
            if (verbose) Log.d { "LoadLibraryW -> 0x${moduleHandle.toString(16)}" }
            return moduleHandle
        } finally {
            VirtualFreeEx(process, remote, 0uL, MEM_RELEASE.toUInt())
        }
    }

/**
 * 调用导出入口：**劫持目标已有线程**。
 *
 * 入口地址 = 目标加载器快照中的模块基址 + 导出 RVA。
 *
 * **历史 bug 之一（2026-10 修复，见 WinEntryCallE2ETest）**：曾用 `CreateRemoteThread(LoadLibraryW)`
 * 的线程退出码当模块基址 —— `GetExitCodeThread` 只有 32 位，x64 上 DLL 基址（0x7FF8…）
 * 的高 32 位被截断，入口地址因此是野指针（崩溃日志：0xc0000005 / faulting module unknown）。
 * 现在基址取自 `MODULEENTRY32W.modBaseAddr`（Toolhelp 快照，64 位）。
 *
 * **历史 bug 之二（同上）**：stub 曾把线程切到 VirtualAllocEx 的合成栈上再调入口
 * （表面上「不依赖剩余栈空间」）。但线程 TEB 记录的栈界仍是真栈，
 * CRT/Kotlin-Native 运行时（GC 扫栈、栈探测、winpthreads/emutls 检查）在这种
 * TEB-栈不一致下会主动 abort（崩溃日志：0x20474343，mingw CRT 自杀码）。
 * 而目标自身线程在同一入口上直调（SELFCALL）实测可行 —— 差异不在线程、不在地址，
 * 只在栈。现在 stub 直接在被劫持线程的**原栈**上运行：入口返回后置标志并自旋，
 * 注入器恢复挂起时的完整上下文（含 Rsp），stub 遗留帧落在 rsp 之下，无害。
 *
 * 为什么劫持目标线程而不是起远程线程调入口：外部裸线程从未经过目标进程的线程
 * 初始化，进入 Kotlin 代码的线程状态无从谈起；目标自身线程满足一切前提。
 * 流程：挂起 → 保存上下文 → Rip 指向 stub → 恢复执行 → stub 在原栈上调入口、
 * 置完成标志后自旋等待 → 注入器恢复原上下文。
 *
 * 注意：stub 缓冲区不在成功后释放（线程若未及时恢复会执行到已释放内存）；
 * 每次入口调用在目标内留下约 72 字节，v1 接受该开销。
 */
    private fun callEntryInProcess(
        process: HANDLE,
        image: ByteArray,
        pid: Int,
        libPath: String,
        entry: String,
        timeoutMs: Int,
        verbose: Boolean,
    ) {
        val entryRva = PeExports.exportRva(image, entry)
            ?: throw InjectFailure(InjectError.SYMBOL_NOT_FOUND, "export not found: $entry")
        val moduleBase = residentModuleBase(pid, libPath)
            ?: throw InjectFailure(
                InjectError.INJECT_FAILED,
                "module ${libPath.substringAfterLast('/').substringAfterLast('\\')} not found in pid $pid; " +
                    "cannot resolve the address of entry $entry",
            )
        val entryAddress = moduleBase + entryRva

        val threadId = Toolhelp.firstThreadOf(pid)
            ?: throw InjectFailure(InjectError.OPEN_FAILED, "no thread found for pid $pid")
        val access = THREAD_SUSPEND_RESUME or THREAD_GET_CONTEXT or THREAD_SET_CONTEXT or THREAD_QUERY_INFORMATION
        val thread = OpenThread(access.toUInt(), 0, threadId.toUInt())
            ?: throw InjectFailure(
                InjectError.OPEN_FAILED,
                "OpenThread failed for tid $threadId (error ${GetLastError()})",
            )

        val blockPointer = VirtualAllocEx(
            process,
            null,
            (8L + STUB_SIZE).toULong(),
            (MEM_COMMIT or MEM_RESERVE).toUInt(),
            PAGE_EXECUTE_READWRITE.toUInt(),
        ) ?: run {
            CloseHandle(thread)
            throw InjectFailure(InjectError.INJECT_FAILED, "VirtualAllocEx(stub) failed")
        }
        val block = blockPointer.toLong()
        val flagAddress = block
        val stubAddress = block + 8

        try {
            writeRemote(process, flagAddress, ByteArray(8))
            writeRemote(process, stubAddress, buildEntryStub(entryAddress, flagAddress))
            if (verbose) {
                println(
                    "verbose: $entry via thread hijack tid=$threadId " +
                        "rva=0x${entryRva.toString(16)} remote=0x${entryAddress.toString(16)}",
                )
            }

            if (SuspendThread(thread) == THREAD_CALL_FAILED) {
                throw InjectFailure(InjectError.INJECT_FAILED, "SuspendThread failed")
            }

            memScoped {
                val saved = alloc<CONTEXT>()
                saved.ContextFlags = (CONTEXT_CONTROL or CONTEXT_INTEGER).toUInt()
                if (GetThreadContext(thread, saved.ptr) == 0) {
                    ResumeThread(thread)
                    throw InjectFailure(InjectError.INJECT_FAILED, "GetThreadContext failed")
                }

                val hijacked = alloc<CONTEXT>()
                memcpy(hijacked.ptr, saved.ptr, sizeOf<CONTEXT>().toULong())
                hijacked.Rip = stubAddress.toULong()
                hijacked.Rcx = 0uL
                if (SetThreadContext(thread, hijacked.ptr) == 0) {
                    ResumeThread(thread)
                    throw InjectFailure(InjectError.INJECT_FAILED, "SetThreadContext failed")
                }
                if (ResumeThread(thread) == THREAD_CALL_FAILED) {
                    throw InjectFailure(InjectError.INJECT_FAILED, "ResumeThread failed")
                }

                try {
                    var waited = 0
                    while (waited < timeoutMs) {
                        if (readRemoteByte(process, flagAddress) == 1.toByte()) break
                        usleep(10_000u)
                        waited += 10
                    }
                    if (waited >= timeoutMs) {
                        throw InjectFailure(InjectError.TIMEOUT, "entry $entry did not finish within $timeoutMs ms")
                    }
                    if (verbose) Log.d { "entry finished in ~$waited ms" }
                } finally {
                    // 线程此刻停在 stub 的 `jmp $` 上：挂起 → 恢复原上下文 → 继续
                    if (SuspendThread(thread) != THREAD_CALL_FAILED) {
                        SetThreadContext(thread, saved.ptr)
                        ResumeThread(thread)
                    }
                }
            }
        } finally {
            CloseHandle(thread)
        }
    }

    private fun waitThread(thread: HANDLE, timeoutMs: Int, what: String) {
        val result = WaitForSingleObject(thread, timeoutMs.toUInt())
        if (result != WAIT_OBJECT_0) {
            throw InjectFailure(InjectError.TIMEOUT, "waiting for $what timed out after $timeoutMs ms (result $result)")
        }
    }

    private fun readExitCode(thread: HANDLE): Long = memScoped {
        val code = alloc<UIntVar>()
        if (GetExitCodeThread(thread, code.ptr) == 0) {
            throw InjectFailure(InjectError.INJECT_FAILED, "GetExitCodeThread failed")
        }
        code.value.toLong()
    }

    private fun writeRemote(process: HANDLE, address: Long, bytes: ByteArray) {
        bytes.usePinned { pinned ->
            val written = WriteProcessMemory(
                process,
                address.toCPointer(),
                pinned.addressOf(0),
                bytes.size.toULong(),
                null,
            )
            if (written == 0) throw InjectFailure(InjectError.INJECT_FAILED, "WriteProcessMemory(stub) failed")
        }
    }

    private fun readRemoteByte(process: HANDLE, address: Long): Byte {
        val buffer = ByteArray(1)
        buffer.usePinned { pinned ->
            val read = ReadProcessMemory(process, address.toCPointer(), pinned.addressOf(0), 1uL, null)
            if (read == 0) throw InjectFailure(InjectError.INJECT_FAILED, "ReadProcessMemory(flag) failed")
        }
        return buffer[0]
    }
}

/**
 * 入口 stub（在**被劫持的目标线程**的原栈上运行）：
 * 对齐栈 → 调用入口 → 置完成标志 → 自旋等待（由注入器恢复上下文）。
 *
 * **不切换栈**：线程 TEB 记录的栈界必须与实际 Rsp 所在栈一致，
 * CRT / Kotlin-Native 运行时（GC 扫栈、栈探测、线程检查）才按预期工作
 * （合成栈上实测主动 abort 0x20474343，见 callEntryInProcess 的历史记录）。
 * 挂起点处 Rsp 的对齐状态未知，`and rsp, -16` 兜底；`call` 之后入口处 Rsp%16==8，
 * 符合 x64 ABI。stub 的遗留帧在注入器恢复 Rsp 后落在栈的死区，无害。
 */
private fun buildEntryStub(entry: Long, flag: Long): ByteArray =
    StubBuilder().apply {
        movRegImm64(0, entry)         // rax = entry
        bytes(0x48, 0x83, 0xE4, 0xF0) // and rsp, -16（挂起点对齐未知，兜底）
        bytes(0x48, 0x83, 0xEC, 0x20) // sub rsp, 0x20（shadow space）
        bytes(0xFF, 0xD0)             // call rax
        movRegImm64(1, flag)          // rcx = &flag
        bytes(0xC6, 0x01, 0x01)       // mov byte ptr [rcx], 1
        bytes(0xEB, 0xFE)             // jmp $
    }.build()

private class StubBuilder {
    private val out = ArrayList<Byte>(STUB_SIZE.toInt())

    fun byte(v: Int) { out.add(v.toByte()) }
    fun bytes(vararg v: Int) { v.forEach { byte(it) } }
    fun imm64(v: Long) { for (i in 0 until 8) byte(((v ushr (i * 8)) and 0xFF).toInt()) }

    fun movRegImm64(reg: Int, value: Long) {
        bytes(0x48 or (if (reg >= 8) 1 else 0), 0xB8 + (reg and 7))
        imm64(value)
    }

    fun build(): ByteArray {
        val bytes = out.toByteArray()
        check(bytes.size <= STUB_SIZE) { "stub too large: ${bytes.size}" }
        return bytes
    }
}

/**
 * 远程 `LoadLibraryW` 的退出码是否表示"没有加载成功"；是则返回失败消息，否则返回 null。
 *
 * 只否掉两个**可以证明不可能来自一次成功加载**的值：
 * - `0`（NULL）：加载失败（文件不存在、镜像无法映射、`DllMain` 返回 FALSE…）
 * - `0xFFFFFFFF`（`INVALID_HANDLE_VALUE` / -1）：**调用被拒绝或被拦截**的哨兵值 ——
 *   `LoadLibraryW` 失败时返回 NULL，永远不会返回 -1，所以看到 -1 说明"成功"是假的
 *
 * 为什么只否这两个是**可靠**的：模块基址按分配粒度（64 KB）对齐，低 16 位必为 0，
 * 因此低 32 位不可能等于 `0xFFFFFFFF`。**这与"按数值区间猜异常码"是两回事** —— 那种做法已被
 * 证伪并移除（见报告 Journey Log）：`0xC0000000`–`0xFFFFFFFF` 在 64 位用户态里是合法地址范围，
 * 除 `0xFFFFFFFF` 本身外一律必须放行。
 */
internal fun loadFailureMessage(exitCode: Long, libPath: String): String? = when (exitCode) {
    0L ->
        "LoadLibraryW returned null in target: the library could not be loaded ($libPath)"

    0xFFFFFFFFL ->
        "LoadLibraryW returned INVALID_HANDLE_VALUE (0xffffffff) in target: the load was refused or " +
            "intercepted and no module was loaded ($libPath)"

    else -> null
}

/** UTF-16（含结尾 NUL）字节序列。 */
internal fun String.toUtf16Bytes(): ByteArray {
    val out = ByteArray((length + 1) * 2)
    forEachIndexed { index, c ->
        val code = c.code
        out[index * 2] = (code and 0xFF).toByte()
        out[index * 2 + 1] = ((code ushr 8) and 0xFF).toByte()
    }
    return out
}

/** 读取整个文件；不存在或读失败返回 null。 */
internal fun readFile(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size <= 0L) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        val out = ByteArray(size.toInt())
        var readBytes = 0uL
        out.usePinned { pinned ->
            readBytes = fread(pinned.addressOf(0), 1uL, size.toULong(), file)
        }
        return if (readBytes == size.toULong()) out else null
    } finally {
        fclose(file)
    }
}
