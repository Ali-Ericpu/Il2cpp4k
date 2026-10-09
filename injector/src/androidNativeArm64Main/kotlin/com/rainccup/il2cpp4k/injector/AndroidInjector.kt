@file:OptIn(ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import com.rainccup.il2cpp4k.logging.Log
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.geteuid

/** bionic 的 linker 入口（优先）与 libdl 回退。 */
private const val LINKER_DLOPEN = "__loader_dlopen"
private const val LINKER_DLSYM = "__loader_dlsym"
private const val LIBDL_DLOPEN = "dlopen"
private const val LIBDL_DLSYM = "dlsym"

/**
 * Android 注入编排（需 root）：
 * ptrace 附加目标线程 → 借其栈做远程调用 → `dlopen(lib)` → `dlsym(handle, entry)` → 调用入口。
 * 用 `dlsym` 取入口而不是「handle + 本地符号偏移」：`dlopen` 返回的是 linker 句柄而非模块基址。
 */
internal object AndroidInjector {
    fun inject(pid: Int, libPath: String, entry: String?, timeoutMs: Int, verbose: Boolean): Long {
        if (geteuid() != 0u) {
            throw InjectFailure(
                InjectError.OPEN_FAILED,
                "root privileges required (run via: adb shell su -c ...)",
            )
        }
        if (readFileBytes(libPath) == null) {
            throw InjectFailure(InjectError.LIB_NOT_FOUND, "library not readable: $libPath")
        }

        Ptrace.attach(pid, timeoutMs, verbose)
        try {
            val memory = RemoteMemory(pid)
            val caller = RemoteCaller(pid, memory, timeoutMs, verbose)
            val mapsText = readProcFile(pid, "maps")
                ?: throw InjectFailure(InjectError.OPEN_FAILED, "cannot read maps of pid $pid")
            val maps = parseMaps(mapsText)

            val (dlopenAddress, dlsymAddress) = resolveLoaderSymbols(maps, verbose)

            val pathAddress = caller.writeString(libPath, slot = 0)
            val handle = caller.call(dlopenAddress, longArrayOf(pathAddress, RTLD_NOW_VALUE, 0L))
            if (handle == 0L) throw InjectFailure(InjectError.INJECT_FAILED, "dlopen returned null for $libPath")
            if (verbose) Log.d { "dlopen($libPath) -> 0x${handle.toString(16)}" }

            if (entry != null) {
                val symbolAddress = caller.writeString(entry, slot = 1)
                val entryAddress = caller.call(dlsymAddress, longArrayOf(handle, symbolAddress, 0L))
                if (entryAddress == 0L) throw InjectFailure(InjectError.SYMBOL_NOT_FOUND, "dlsym failed for $entry")
                if (verbose) Log.d { "dlsym -> 0x${entryAddress.toString(16)}" }
                caller.call(entryAddress, longArrayOf())
            }
            return handle
        } finally {
            Ptrace.detach(pid)
        }
    }

    /** `linker64` 的 `__loader_*` 优先；不可用时回退 `libdl.so` 的 `dlopen`/`dlsym`。 */
    private fun resolveLoaderSymbols(maps: List<MapEntry>, verbose: Boolean): Pair<Long, Long> {
        resolvePair(maps, "linker64", LINKER_DLOPEN, LINKER_DLSYM)?.let {
            if (verbose) Log.d { "using linker64 __loader_* symbols" }
            return it
        }
        val fromLibdl = resolvePair(maps, "libdl.so", LIBDL_DLOPEN, LIBDL_DLSYM)
            ?: throw InjectFailure(InjectError.SYMBOL_NOT_FOUND, "cannot resolve dlopen/dlsym in target")
        if (verbose) Log.d { "using libdl.so dlopen/dlsym symbols" }
        return fromLibdl
    }

    private fun resolvePair(
        maps: List<MapEntry>,
        moduleName: String,
        openSymbol: String,
        symbolSymbol: String,
    ): Pair<Long, Long>? {
        val (base, localPath) = symbolSource(maps, moduleName) ?: return null
        val bytes = readFileBytes(localPath) ?: return null
        val openOffset = Elf.symbolOffset(bytes, openSymbol) ?: return null
        val symbolOffset = Elf.symbolOffset(bytes, symbolSymbol) ?: return null
        return (base + openOffset) to (base + symbolOffset)
    }

    internal fun readProcFile(pid: Int, name: String): String? =
        readFileBytes("/proc/$pid/$name")?.decodeToString()
}

/** 读取整个文件；不存在或读失败返回 null。 */
internal fun readFileBytes(path: String): ByteArray? {
    val file = fopen(path, "rb") ?: return null
    try {
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size <= 0L) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        val out = ByteArray(size.toInt())
        var read = 0uL
        out.usePinned { pinned -> read = fread(pinned.addressOf(0), 1uL, size.toULong(), file) }
        return if (read == size.toULong()) out else null
    } finally {
        fclose(file)
    }
}
