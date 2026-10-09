package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.*

/**
 * 一次「调用任意函数指针」的会话：写参数槽 → [invoke] → 读返回值槽。
 * 与 hook 的 original 调用共用同一套调用约定 thunk。
 */
class CallSite internal constructor(
    val target: Long,
    val cc: CallingConvention,
    val ctxAddr: Long,
    private val thunkAddr: Long,
) {
    fun writeLong(offset: Long, value: Long) = Memory.writeBytes(ctxAddr + offset, longToBytes(value))

    fun writeFloatAt(offset: Long, value: Float) = writeLong(offset, value.toRawBits().toLong())

    fun writeDoubleAt(offset: Long, value: Double) = writeLong(offset, value.toRawBits())

    fun readLong(offset: Long): Long = bytesToLong(Memory.readBytes(ctxAddr + offset, 8))

    fun readFloatAt(offset: Long): Float = Float.fromBits(readLong(offset).toInt())

    fun readDoubleAt(offset: Long): Double = Double.fromBits(readLong(offset))

    @OptIn(ExperimentalForeignApi::class)
    fun invoke() {
        val fn = thunkAddr.toCPointer<CFunction<(COpaquePointer?) -> Unit>>() ?: return
        fn.invoke(ctxAddr.toCPointer())
    }

    fun close() {
        Memory.free(thunkAddr)
        Memory.free(ctxAddr)
    }
}

object FunctionCaller {
    fun create(
        target: Long,
        cc: CallingConvention = platformCallingConvention,
        stubGen: StubGenerator = platformStubGenerator,
    ): CallSite {
        val ctxAddr = Memory.alloc(cc.ctxSize, exec = false)
        if (ctxAddr == 0L) throw HookInstallException("failed to allocate call context")
        Memory.writeBytes(ctxAddr, ByteArray(cc.ctxSize.toInt()))
        val thunk = stubGen.callThunk(cc, target)
        val thunkAddr = Memory.alloc(thunk.size.toLong(), exec = true)
        if (thunkAddr == 0L) {
            Memory.free(ctxAddr)
            throw HookInstallException("failed to allocate call thunk")
        }
        Memory.writeBytes(thunkAddr, thunk)
        Memory.protectExec(thunkAddr, thunk.size.toLong())
        return CallSite(target, cc, ctxAddr, thunkAddr)
    }
}
