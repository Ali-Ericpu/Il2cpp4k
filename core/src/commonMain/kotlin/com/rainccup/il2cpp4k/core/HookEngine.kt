package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.*
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal fun longToBytes(value: Long): ByteArray {
    val b = ByteArray(8)
    for (i in 0 until 8) b[i] = ((value ushr (8 * i)) and 0xFF).toByte()
    return b
}

internal fun bytesToLong(bytes: ByteArray): Long {
    var v = 0L
    for (i in 0 until 8) v = v or ((bytes[i].toLong() and 0xFF) shl (8 * i))
    return v
}

private class HookRecord(
    val cc: CallingConvention,
    val ctxAddr: Long,
    val thunkAddr: Long,
    val callback: (RawHookContext) -> Unit,
) {
    fun dispatch(ctxAddr: Long) {
        val ctx = RawHookContext(cc, ctxAddr, thunkAddr)
        try {
            callback(ctx)
        } catch (e: Throwable) {
            HookEngine.onHookError?.invoke(e)
        }
        if (!ctx.resultWasSet) {
            ctx.forwardToOriginal()
        }
    }
}

/**
 * 原始槽位上下文：直接读写目标函数的参数/返回值槽。
 * 语义：回调既未设置返回值又未调用 [callOriginal] 时，自动把当前槽位值转发给原函数并采用其结果。
 */
@OptIn(ExperimentalForeignApi::class)
class RawHookContext internal constructor(
    private val cc: CallingConvention,
    private val ctxAddr: Long,
    private val thunkAddr: Long,
) {
    val contextAddress: Long get() = ctxAddr

    var resultWasSet: Boolean = false
        private set

    var originalInt: Long = 0L
        private set

    var originalFloat: Float = 0f
        private set

    fun argLong(slot: Int): Long = readLong(cc.gprBase + slot * 8L)

    fun argLongStack(index: Int): Long = readLong(cc.stackBase + index * 8L)

    fun argFloat(slot: Int): Float = Float.fromBits(readLong(cc.fprBase + slot * 8L).toInt())

    fun argDouble(slot: Int): Double = Double.fromBits(readLong(cc.fprBase + slot * 8L))

    fun setArgLong(slot: Int, value: Long) = writeLong(cc.gprBase + slot * 8L, value)

    fun setArgLongStack(index: Int, value: Long) = writeLong(cc.stackBase + index * 8L, value)

    fun setArgFloat(slot: Int, value: Float) = writeLong(cc.fprBase + slot * 8L, value.toRawBits().toLong())

    fun setArgDouble(slot: Int, value: Double) = writeLong(cc.fprBase + slot * 8L, value.toRawBits())

    /** 用当前槽位值调用原函数；结果写入返回槽并标记已设置。返回整型槽原始位模式。 */
    fun callOriginal(): Long {
        invokeThunk()
        originalInt = readLong(cc.retIntOffset)
        originalFloat = Float.fromBits(readLong(cc.retFloatOffset).toInt())
        resultWasSet = true
        return originalInt
    }

    fun resultLong(value: Long) {
        writeLong(cc.retIntOffset, value)
        resultWasSet = true
    }

    fun resultFloat(value: Float) {
        writeLong(cc.retFloatOffset, value.toRawBits().toLong())
        resultWasSet = true
    }

    fun resultDouble(value: Double) {
        writeLong(cc.retFloatOffset, value.toRawBits())
        resultWasSet = true
    }

    internal fun forwardToOriginal() {
        invokeThunk()
        resultWasSet = true
    }

    private fun invokeThunk() {
        val fn = thunkAddr.toCPointer<CFunction<(COpaquePointer?) -> Unit>>() ?: return
        fn.invoke(ctxAddr.toCPointer())
    }

    private fun readLong(offset: Long): Long = bytesToLong(Memory.readBytes(ctxAddr + offset, 8))

    private fun writeLong(offset: Long, value: Long) = Memory.writeBytes(ctxAddr + offset, longToBytes(value))
}

class EngineHook internal constructor(
    val target: Long,
    val ctxAddr: Long,
    val trampoline: Long,
    internal val hookId: Long,
    private val thunkAddr: Long,
    private val stubAddr: Long,
    private val inlineHook: InlineHook,
) {
    fun unhook() {
        inlineHook.unhook()
        HookEngine.remove(hookId)
        Memory.free(thunkAddr)
        Memory.free(stubAddr)
        Memory.free(ctxAddr)
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
object HookEngine {
    /** 回调内异常的观察点（跨 C 边界不允许抛出，异常在此上报后吞掉）。 */
    var onHookError: ((Throwable) -> Unit)? = null

    private val nextHookId = AtomicLong(1L)
    private val registry = AtomicReference<Map<Long, HookRecord>>(emptyMap())

    private fun lookUp(hookId: Long): HookRecord? = registry.load()[hookId]

    private fun register(hookId: Long, record: HookRecord) {
        registry.store(registry.load() + (hookId to record))
    }

    internal fun remove(hookId: Long) {
        registry.store(registry.load() - hookId)
    }

    /** C 侧调用约定：invoker(hookId, ctx)。无捕获，仅通过全局注册表查表。 */
    private val invoker: CPointer<CFunction<(Long, COpaquePointer?) -> Unit>> =
        staticCFunction { hookId: Long, ctx: COpaquePointer? ->
            val address = ctx?.toLong() ?: 0L
            if (address != 0L) {
                lookUp(hookId)?.dispatch(address)
            }
            Unit
        }

    fun hookByAddress(
        target: Long,
        cc: CallingConvention = platformCallingConvention,
        stubGen: StubGenerator = platformStubGenerator,
        callback: (RawHookContext) -> Unit,
    ): EngineHook {
        val hookId = nextHookId.fetchAndAdd(1L)
        val ctxAddr = Memory.alloc(cc.ctxSize, exec = false)
        if (ctxAddr == 0L) throw HookInstallException("failed to allocate hook context")
        Memory.writeBytes(ctxAddr, ByteArray(cc.ctxSize.toInt()))

        val inlineHook = InlineHook(target, cc, stubGen)
        inlineHook.prepare()

        val thunkAddr = allocExecutable(stubGen.callThunk(cc, inlineHook.trampoline))
        val stubAddr = allocExecutable(stubGen.entryStub(cc, ctxAddr, hookId, invoker.toLong()))
        inlineHook.install(stubAddr)

        register(hookId, HookRecord(cc, ctxAddr, thunkAddr, callback))
        return EngineHook(target, ctxAddr, inlineHook.trampoline, hookId, thunkAddr, stubAddr, inlineHook)
    }

    private fun allocExecutable(code: ByteArray): Long {
        val addr = Memory.alloc(code.size.toLong(), exec = true)
        if (addr == 0L) throw HookInstallException("failed to allocate code buffer")
        Memory.writeBytes(addr, code)
        Memory.protectExec(addr, code.size.toLong())
        return addr
    }
}
