package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.*
import platform.posix.*

// aarch64 Linux/Android 的 __NR_memfd_create
private const val SYS_MEMFD_CREATE_ARM64 = 279L
private const val MFD_CLOEXEC = 0x0001
private const val PAGE_SIZE = 4096L

@OptIn(ExperimentalForeignApi::class)
internal fun Long.toPointer() =
    if (this == 0L) null else this.toCPointer<ByteVar>()

/**
 * Android 侧内存原语。
 *
 * 可执行内存分配策略：优先匿名 RWX 映射；若被拒（Android 10+ 对 targetSdk>=29 的进程禁用匿名
 * 可执行映射），回退 memfd 文件映射（文件映射不受 execmem 限制）。两条路径均以 RWX 建立，
 * 写入后由调用方 [protectExec] 收窄为 RX。
 */
@OptIn(ExperimentalForeignApi::class)
actual object Memory {
    /** 记录每个分配的长度供 munmap 使用（假定分配与释放在同一线程）。 */
    private val allocationSizes = mutableMapOf<Long, Long>()

    actual fun alloc(size: Long, exec: Boolean): Long {
        val prot = if (exec) PROT_READ or PROT_WRITE or PROT_EXEC else PROT_READ or PROT_WRITE
        val anonymous = mmap(null, size.toULong(), prot, MAP_PRIVATE or MAP_ANONYMOUS, -1, 0L)
        if (anonymous != MAP_FAILED) {
            val addr = anonymous.toLong()
            allocationSizes[addr] = roundUpToPage(size)
            return addr
        }

        val fd = memScoped { syscall(SYS_MEMFD_CREATE_ARM64, "il2cpp4k".cstr.ptr, MFD_CLOEXEC) }
        if (fd < 0L) return 0L
        if (ftruncate(fd.toInt(), size) != 0) {
            close(fd.toInt())
            return 0L
        }
        val fileBacked = mmap(null, size.toULong(), prot, MAP_SHARED, fd.toInt(), 0L)
        close(fd.toInt())
        if (fileBacked == MAP_FAILED) return 0L
        val addr = fileBacked.toLong()
        allocationSizes[addr] = roundUpToPage(size)
        return addr
    }

    actual fun free(addr: Long) {
        if (addr == 0L) return
        val size = allocationSizes.remove(addr) ?: return
        munmap(addr.toPointer(), size.toULong())
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

    actual fun protectExec(addr: Long, size: Long) {
        mprotect(addr.toPointer(), size.toULong(), PROT_READ or PROT_EXEC)
    }

    actual fun protectRW(addr: Long, size: Long) {
        mprotect(addr.toPointer(), size.toULong(), PROT_READ or PROT_WRITE)
    }

    private fun roundUpToPage(size: Long): Long = (size + PAGE_SIZE - 1) / PAGE_SIZE * PAGE_SIZE
}
