package com.rainccup.il2cpp4k.core

class HookInstallException(message: String) : RuntimeException(message)

/** 从 [target] 拷贝到 [destAddr] 的指令字节：bytes 已按新地址修正相对寻址。 */
data class Copied(val bytes: ByteArray, val byteCount: Int)

interface StubGenerator {
    val patchLength: Int

    /** jumpPatch 的固定长度（用于计算 trampoline 缓冲区大小）。 */
    val jumpPatchLength: Int

    /** 入口补丁：绝对跳转到 [target]。 */
    fun entryPatch(target: Long): ByteArray

    /** trampoline 尾部：跳回原函数续接点 [target]。 */
    fun jumpPatch(target: Long): ByteArray

    /**
     * 入口 stub。进入时栈指针处于原函数入口状态（x64: rsp ≡ 8 mod 16）。
     * 保存参数 → 调 invoker(hookId, ctx) → 从 ctx 取返回值返回。
     */
    fun entryStub(cc: CallingConvention, ctxAddr: Long, hookId: Long, invokerAddr: Long): ByteArray

    /** 原函数调用 thunk：thunk(ctx) → 按约定装参数 → 调 [target] → 结果写回 ctx。 */
    fun callThunk(cc: CallingConvention, target: Long): ByteArray

    /**
     * 从 [target] 拷贝覆盖 [patchLength] 字节的整条指令，并为放置在 [destAddr] 做相对寻址重定位。
     * 遇到不支持或无法安全重定位的指令抛 [HookInstallException]。
     */
    fun copyPrologue(target: Long, destAddr: Long, patchLength: Int): Copied
}

internal fun readIntLe(bytes: ByteArray, offset: Int): Int {
    var v = 0
    for (i in 0 until 4) v = v or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
    return v
}

internal fun writeIntLe(bytes: ByteArray, offset: Int, value: Int) {
    for (i in 0 until 4) bytes[offset + i] = ((value ushr (8 * i)) and 0xFF).toByte()
}

expect val platformStubGenerator: StubGenerator
