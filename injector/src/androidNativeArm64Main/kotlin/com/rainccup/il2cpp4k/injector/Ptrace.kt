@file:OptIn(ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import com.rainccup.il2cpp4k.logging.Log
import kotlinx.cinterop.*
import platform.posix.memcpy
import platform.posix.ptrace
import platform.posix.usleep
import platform.posix.waitpid

private const val PTRACE_PEEKDATA = 2
private const val PTRACE_POKEDATA = 5
private const val PTRACE_CONT = 7
private const val PTRACE_ATTACH = 16
private const val PTRACE_DETACH = 17
private const val PTRACE_GETREGSET = 0x4204
private const val PTRACE_SETREGSET = 0x4205
private const val NT_PRSTATUS = 1L
private const val WNOHANG = 1
private const val STOP_EVENT = 0x7F

/** 调用约定：最多 8 个整型/指针参数（x0–x7）。 */
internal const val RTLD_NOW_VALUE = 2L

/**
 * 远程调用在原目标线程栈下方的布局（arm64 栈向下增长，sp 以下为空闲区且无红区）：
 *   [sp-0x10, sp-0x08)    陷阱指令 `brk #0`（作为返回地址）
 *   [sp-0x0800, sp-0x0400) 参数字符串区
 *   sp-0x2000             调用栈顶（向下增长）
 */
private const val TRAP_DELTA = 0x10L
private const val STRING_DELTA = 0x800L
private const val STRING_SLOT_STRIDE = 0x100L
private const val STACK_DELTA = 0x2000L

/** `brk #0`（陷阱）小端字节。 */
internal val TRAP_INSTRUCTION = byteArrayOf(0x00, 0x00, 0x20, 0xD4.toByte())

internal object Ptrace {
    fun attach(pid: Int, timeoutMs: Int, verbose: Boolean) {
        if (ptrace(PTRACE_ATTACH, pid, null, null) != 0L) {
            throw InjectFailure(InjectError.OPEN_FAILED, "PTRACE_ATTACH failed for pid $pid")
        }
        val signal = waitStopped(pid, timeoutMs)
        if (verbose) Log.d { "attached pid $pid (stop signal $signal)" }
    }

    fun detach(pid: Int) {
        ptrace(PTRACE_DETACH, pid, null, null)
    }

    fun cont(pid: Int) {
        ptrace(PTRACE_CONT, pid, null, null)
    }

    /** 等待目标停止，返回停止信号；超时抛 [InjectFailure]（TIMEOUT）。 */
    fun waitStopped(pid: Int, timeoutMs: Int): Int = memScoped {
        val status = alloc<IntVar>()
        var waited = 0
        while (waited < timeoutMs) {
            val result = waitpid(pid, status.ptr, WNOHANG)
            if (result == pid) {
                val raw = status.value
                if ((raw and 0xFF) == STOP_EVENT) {
                    return (raw ushr 8) and 0xFF
                }
            }
            usleep(5_000u)
            waited += 5
        }
        throw InjectFailure(InjectError.TIMEOUT, "waiting for pid $pid to stop timed out")
    }

    fun getRegs(pid: Int): LongArray = memScoped {
        val buffer = allocArray<ByteVar>(Regs.SIZE_BYTES)
        val iov = allocArray<LongVar>(2)
        iov[0] = buffer.rawValue.toLong()
        iov[1] = Regs.SIZE_BYTES.toLong()
        val result = ptrace(PTRACE_GETREGSET, pid, NT_PRSTATUS.toCPointer<ByteVar>(), iov)
        if (result != 0L) throw InjectFailure(InjectError.INJECT_FAILED, "PTRACE_GETREGSET failed")
        Regs.decode(buffer.readBytes(Regs.SIZE_BYTES))
    }

    fun setRegs(pid: Int, values: LongArray) = memScoped {
        val encoded = Regs.encode(values)
        val buffer = allocArray<ByteVar>(Regs.SIZE_BYTES)
        encoded.usePinned { pinned -> memcpy(buffer, pinned.addressOf(0), Regs.SIZE_BYTES.toULong()) }
        val iov = allocArray<LongVar>(2)
        iov[0] = buffer.rawValue.toLong()
        iov[1] = Regs.SIZE_BYTES.toLong()
        val result = ptrace(PTRACE_SETREGSET, pid, NT_PRSTATUS.toCPointer<ByteVar>(), iov)
        if (result != 0L) throw InjectFailure(InjectError.INJECT_FAILED, "PTRACE_SETREGSET failed")
        Unit
    }

    fun peek(pid: Int, address: Long): Long {
        val value = ptrace(PTRACE_PEEKDATA, pid, address.toCPointer<ByteVar>(), null)
        if (value == -1L) {
            throw InjectFailure(InjectError.INJECT_FAILED, "PTRACE_PEEKDATA failed at 0x${address.toString(16)}")
        }
        return value
    }

    fun poke(pid: Int, address: Long, value: Long) {
        val result = ptrace(
            PTRACE_POKEDATA,
            pid,
            address.toCPointer<ByteVar>(),
            value.toCPointer<ByteVar>(),
        )
        if (result != 0L) {
            throw InjectFailure(InjectError.INJECT_FAILED, "PTRACE_POKEDATA failed at 0x${address.toString(16)}")
        }
    }
}

/** 一次 PTRACE 会话内的远程调用：借用目标栈做陷阱与调用栈，调用前后保存/恢复寄存器。 */
internal class RemoteCaller(
    private val pid: Int,
    private val memory: RemoteMemory,
    private val timeoutMs: Int,
    private val verbose: Boolean,
) {
    fun call(function: Long, args: LongArray): Long {
        require(args.size <= 8) { "at most 8 integer arguments are supported" }
        val saved = Ptrace.getRegs(pid)
        val stack = saved[Regs.SP]
        try {
            memory.write(stack - TRAP_DELTA, TRAP_INSTRUCTION)
            val regs = saved.copyOf()
            for (i in args.indices) {
                regs[Regs.X0 + i] = args[i]
            }
            regs[Regs.LR] = stack - TRAP_DELTA
            regs[Regs.SP] = stack - STACK_DELTA
            regs[Regs.PC] = function
            Ptrace.setRegs(pid, regs)
            Ptrace.cont(pid)
            val signal = Ptrace.waitStopped(pid, timeoutMs)
            if (signal != SIGTRAP) {
                throw InjectFailure(
                    InjectError.INJECT_FAILED,
                    "remote call stopped by signal $signal (expected SIGTRAP)",
                )
            }
            val result = Ptrace.getRegs(pid)[Regs.X0]
            if (verbose) Log.d { "remote call 0x${function.toString(16)} -> 0x${result.toString(16)}" }
            return result
        } finally {
            Ptrace.setRegs(pid, saved)
        }
    }

    /** 把 [text] 写入目标字符串区并返回其远程地址；[slot] 区分同一会话内的多个字符串。 */
    fun writeString(text: String, slot: Int): Long {
        val stack = Ptrace.getRegs(pid)[Regs.SP]
        val address = stack - STRING_DELTA + slot * STRING_SLOT_STRIDE
        memory.write(address, (text + "\u0000").map { it.code.toByte() }.toByteArray())
        return address
    }
}

/** SIGTRAP 在 Linux/Android 上为 5。 */
internal const val SIGTRAP = 5
