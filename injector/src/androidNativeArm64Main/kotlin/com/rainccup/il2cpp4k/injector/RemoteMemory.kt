@file:OptIn(ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.*

/**
 * 目标进程内存读写：优先 `/proc/<pid>/mem`；失败（EIO/权限）回退 ptrace PEEK/POKE。
 * 前提：调用方已 `PTRACE_ATTACH`（或以 root 运行）。
 */
internal class RemoteMemory(private val pid: Int) {
    fun read(address: Long, size: Int): ByteArray {
        val out = ByteArray(size)
        if (readViaProcMem(address, out)) return out
        var offset = 0
        while (offset < size) {
            val word = Ptrace.peek(pid, address + offset)
            for (b in 0 until 8) {
                if (offset + b >= size) break
                out[offset + b] = ((word ushr (8 * b)) and 0xFF).toByte()
            }
            offset += 8
        }
        return out
    }

    fun write(address: Long, bytes: ByteArray) {
        if (writeViaProcMem(address, bytes)) return
        var offset = 0
        while (offset < bytes.size) {
            val existing = Ptrace.peek(pid, address + offset)
            var word = 0L
            for (b in 0 until 8) {
                val index = offset + b
                val byte = if (index < bytes.size) {
                    bytes[index].toLong() and 0xFF
                } else {
                    (existing ushr (8 * b)) and 0xFF
                }
                word = word or (byte shl (8 * b))
            }
            Ptrace.poke(pid, address + offset, word)
            offset += 8
        }
    }

    private fun readViaProcMem(address: Long, out: ByteArray): Boolean {
        val fd = open("/proc/$pid/mem", O_RDONLY)
        if (fd < 0) return false
        try {
            var read = 0L
            out.usePinned { pinned ->
                read = pread(fd, pinned.addressOf(0), out.size.toULong(), address)
            }
            return read == out.size.toLong()
        } finally {
            close(fd)
        }
    }

    private fun writeViaProcMem(address: Long, bytes: ByteArray): Boolean {
        val fd = open("/proc/$pid/mem", O_RDWR)
        if (fd < 0) return false
        try {
            var written = 0L
            bytes.usePinned { pinned ->
                written = pwrite(fd, pinned.addressOf(0), bytes.size.toULong(), address)
            }
            return written == bytes.size.toLong()
        } finally {
            close(fd)
        }
    }
}
