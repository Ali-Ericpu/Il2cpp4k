package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import platform.windows.MEM_COMMIT
import platform.windows.MEM_RELEASE
import platform.windows.MEM_RESERVE
import platform.windows.PAGE_EXECUTE_READWRITE
import platform.windows.PAGE_READWRITE
import platform.windows.VirtualAlloc
import platform.windows.VirtualFree
import platform.windows.VirtualProtect

@OptIn(ExperimentalForeignApi::class)
internal fun Long.toPointer(): CPointer<ByteVar>? =
    if (this == 0L) null else this.toCPointer<ByteVar>()

@OptIn(ExperimentalForeignApi::class)
actual object Memory {
    actual fun alloc(size: Long, exec: Boolean): Long {
        val protection = if (exec) PAGE_EXECUTE_READWRITE else PAGE_READWRITE
        val ptr = VirtualAlloc(
            null,
            size.toULong(),
            (MEM_COMMIT or MEM_RESERVE).toUInt(),
            protection.toUInt(),
        ) ?: return 0L
        return ptr.rawValue.toLong()
    }

    actual fun free(addr: Long) {
        if (addr == 0L) return
        VirtualFree(addr.toPointer(), 0uL, MEM_RELEASE.toUInt())
    }

    actual fun readBytes(addr: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        if (size == 0) return out
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), addr.toPointer(), size.toULong())
        }
        return out
    }

    actual fun writeBytes(addr: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        bytes.usePinned { pinned ->
            memcpy(addr.toPointer(), pinned.addressOf(0), bytes.size.toULong())
        }
    }

    actual fun protectExec(addr: Long, size: Long) = protect(addr, size, PAGE_EXECUTE_READWRITE)

    actual fun protectRW(addr: Long, size: Long) = protect(addr, size, PAGE_READWRITE)

    private fun protect(addr: Long, size: Long, protection: Int) = memScoped {
        val old = alloc<UIntVar>()
        VirtualProtect(addr.toPointer(), size.toULong(), protection.toUInt(), old.ptr)
        Unit
    }
}
