@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.*
import platform.windows.CloseHandle
import platform.windows.GetModuleHandleA
import platform.windows.GetProcAddress
import platform.windows.HANDLE

/**
 * Toolhelp32 快照。
 *
 * Kotlin/Native 的 `platform.windows` 未生成 tlhelp32.h，而 `external fun` 又要求
 * `@SymbolName`/`@GCUnsafeCall` 等注解（2.4 中前者已内部废弃、后者不可解析）。
 * 因此这里改为**运行时符号解析**：用 `GetProcAddress(kernel32, "…")` 取函数指针，
 * 再按签名 `reinterpret` 成 `CFunction` 调用；结构体用原始缓冲区 + 固定偏移读取。
 * 偏移均为 x64 布局。
 */
private const val TH32CS_SNAPPROCESS = 0x2u
private const val TH32CS_SNAPTHREAD = 0x4u
private const val TH32CS_SNAPMODULE = 0x8u
private const val TH32CS_SNAPMODULE32 = 0x10u

// THREADENTRY32（x64，sizeof = 28）
private const val THREAD_ENTRY_SIZE = 28
private const val THREAD_ENTRY_TID_OFFSET = 8
private const val THREAD_ENTRY_OWNER_OFFSET = 12

// PROCESSENTRY32W（x64，sizeof = 568）
private const val PROCESS_ENTRY_SIZE = 568
private const val PROCESS_ENTRY_PID_OFFSET = 8
private const val PROCESS_ENTRY_EXE_OFFSET = 44
private const val PROCESS_ENTRY_EXE_CHARS = 260

// MODULEENTRY32W（x64，sizeof = 1080）
private const val MODULE_ENTRY_SIZE = 1080
private const val MODULE_ENTRY_BASE_OFFSET = 24
private const val MODULE_ENTRY_NAME_OFFSET = 48
private const val MODULE_ENTRY_NAME_CHARS = 256
private const val MODULE_ENTRY_PATH_OFFSET = 560
private const val MODULE_ENTRY_PATH_CHARS = 260

internal data class ModuleInfo(val name: String, val path: String, val base: Long)

/** INVALID_HANDLE_VALUE == (HANDLE)-1。 */
internal fun HANDLE?.isInvalidHandle(): Boolean = this == null || this.toLong() == -1L

private fun resolveKernel32(name: String): Long? {
    val module = GetModuleHandleA("kernel32.dll") ?: return null
    return GetProcAddress(module, name)?.toLong()
}

internal object Toolhelp {
    private val createSnapshot: CPointer<CFunction<(UInt, UInt) -> HANDLE?>>? by lazy {
        resolveKernel32("CreateToolhelp32Snapshot")?.toCPointer<CFunction<(UInt, UInt) -> HANDLE?>>()
    }
    private val processFirst: CPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>? by lazy {
        resolveKernel32("Process32FirstW")?.toCPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>()
    }
    private val processNext: CPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>? by lazy {
        resolveKernel32("Process32NextW")?.toCPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>()
    }
    private val moduleFirst: CPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>? by lazy {
        resolveKernel32("Module32FirstW")?.toCPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>()
    }
    private val moduleNext: CPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>? by lazy {
        resolveKernel32("Module32NextW")?.toCPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>()
    }
    private val threadFirst: CPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>? by lazy {
        resolveKernel32("Thread32First")?.toCPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>()
    }
    private val threadNext: CPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>? by lazy {
        resolveKernel32("Thread32Next")?.toCPointer<CFunction<(HANDLE?, COpaquePointer?) -> Int>>()
    }

    /** 返回 pid 的第一个线程 id（通常为主线程）。 */
    fun firstThreadOf(pid: Int): Int? {
        val create = createSnapshot ?: return null
        val first = threadFirst ?: return null
        val next = threadNext ?: return null
        val snapshot = create.invoke(TH32CS_SNAPTHREAD, 0u)
        if (snapshot.isInvalidHandle()) return null
        try {
            val buffer = ByteArray(THREAD_ENTRY_SIZE)
            writeU32(buffer, 0, THREAD_ENTRY_SIZE)
            buffer.usePinned { pinned ->
                val pointer = pinned.addressOf(0)
                if (first.invoke(snapshot, pointer) != 0) {
                    do {
                        if (readU32(buffer, THREAD_ENTRY_OWNER_OFFSET) == pid) {
                            return readU32(buffer, THREAD_ENTRY_TID_OFFSET)
                        }
                    } while (next.invoke(snapshot, pointer) != 0)
                }
            }
            return null
        } finally {
            CloseHandle(snapshot)
        }
    }

    fun processEntries(): List<Pair<Int, String>> {
        val create = createSnapshot ?: return emptyList()
        val first = processFirst ?: return emptyList()
        val next = processNext ?: return emptyList()
        val snapshot = create.invoke(TH32CS_SNAPPROCESS, 0u)
        if (snapshot.isInvalidHandle()) return emptyList()
        try {
            val buffer = ByteArray(PROCESS_ENTRY_SIZE)
            writeU32(buffer, 0, PROCESS_ENTRY_SIZE)
            val out = ArrayList<Pair<Int, String>>()
            buffer.usePinned { pinned ->
                val pointer = pinned.addressOf(0)
                if (first.invoke(snapshot, pointer) != 0) {
                    do {
                        out.add(
                            readU32(buffer, PROCESS_ENTRY_PID_OFFSET) to
                                readUtf16(buffer, PROCESS_ENTRY_EXE_OFFSET, PROCESS_ENTRY_EXE_CHARS),
                        )
                    } while (next.invoke(snapshot, pointer) != 0)
                }
            }
            return out
        } finally {
            CloseHandle(snapshot)
        }
    }

    fun moduleEntries(pid: Int): List<ModuleInfo> {
        val create = createSnapshot ?: return emptyList()
        val first = moduleFirst ?: return emptyList()
        val next = moduleNext ?: return emptyList()
        val snapshot = create.invoke(TH32CS_SNAPMODULE or TH32CS_SNAPMODULE32, pid.toUInt())
        if (snapshot.isInvalidHandle()) return emptyList()
        try {
            val buffer = ByteArray(MODULE_ENTRY_SIZE)
            writeU32(buffer, 0, MODULE_ENTRY_SIZE)
            val out = ArrayList<ModuleInfo>()
            buffer.usePinned { pinned ->
                val pointer = pinned.addressOf(0)
                if (first.invoke(snapshot, pointer) != 0) {
                    do {
                        out.add(
                            ModuleInfo(
                                readUtf16(buffer, MODULE_ENTRY_NAME_OFFSET, MODULE_ENTRY_NAME_CHARS),
                                readUtf16(buffer, MODULE_ENTRY_PATH_OFFSET, MODULE_ENTRY_PATH_CHARS),
                                readU64(buffer, MODULE_ENTRY_BASE_OFFSET),
                            ),
                        )
                    } while (next.invoke(snapshot, pointer) != 0)
                }
            }
            return out
        } finally {
            CloseHandle(snapshot)
        }
    }
}

private fun readU32(buffer: ByteArray, offset: Int): Int {
    var v = 0
    for (i in 0 until 4) v = v or ((buffer[offset + i].toInt() and 0xFF) shl (8 * i))
    return v
}

private fun readU64(buffer: ByteArray, offset: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((buffer[offset + i].toLong() and 0xFF) shl (8 * i))
    return v
}

private fun writeU32(buffer: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) buffer[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
}

private fun readUtf16(buffer: ByteArray, offset: Int, maxChars: Int): String {
    val sb = StringBuilder()
    for (i in 0 until maxChars) {
        val code = (buffer[offset + i * 2].toInt() and 0xFF) or ((buffer[offset + i * 2 + 1].toInt() and 0xFF) shl 8)
        if (code == 0) break
        sb.append(code.toChar())
    }
    return sb.toString()
}
