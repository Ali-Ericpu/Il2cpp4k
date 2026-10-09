package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 引擎端到端闭环：同进程内 hook 真实机器码函数，验证参数解包、返回值控制、
 * 原函数转发/调用与异常吞掉。
 *
 * 注意：混合 Int/Float 签名的调用方必须是按 MS x64 ABI 手写的机器码
 * （Kotlin/Native 的 CFunction 调用对混签采用按位置分配寄存器，与 MSVC 不一致）。
 */
@OptIn(ExperimentalForeignApi::class)
class HookEngineE2ETest {
    private val allocated = mutableListOf<Long>()
    private val hooks = mutableListOf<EngineHook>()

    @AfterTest
    fun cleanup() {
        hooks.forEach { it.unhook() }
        allocated.forEach { Memory.free(it) }
    }

    /** 前置 nop 填充 + 函数体；填充使拷贝区恰好以 ret 结尾（12 字节）。 */
    private fun makeFunction(padNops: Int, body: ByteArray): Long {
        val code = ByteArray(padNops + body.size) { 0x90.toByte() }
        body.copyInto(code, padNops)
        val addr = Memory.alloc(code.size.toLong(), exec = true)
        Memory.writeBytes(addr, code)
        allocated.add(addr)
        return addr
    }

    /** f(a: Int, b: Int) = a + b → lea eax,[rdx+rcx]; ret（8 nop + 4 字节 = 12 字节） */
    private fun makeAdd(): Long = makeFunction(
        8,
        byteArrayOf(0x8D.toByte(), 0x04.toByte(), 0x11.toByte(), 0xC3.toByte()),
    )

    /** f(x: Float) -> Float 恒等：仅 ret（11 nop + 1 字节 = 12 字节） */
    private fun makeIdentityFloat(): Long = makeFunction(11, byteArrayOf(0xC3.toByte()))

    /**
     * 按 MS x64 ABI 装载混合参数并调用 [target] 的调用方机器码：
     * a=7→ecx、b=2.5f→xmm0、c=9→edx、d=4.5f→xmm1，返回目标函数返回值。
     */
    private fun makeMixedCaller(target: Long): Long {
        val code = ArrayList<Byte>()
        fun b(vararg v: Int) = v.forEach { code.add(it.toByte()) }
        fun imm32(v: Int) { for (i in 0 until 4) b((v ushr (8 * i)) and 0xFF) }
        fun imm64(v: Long) { for (i in 0 until 8) b(((v ushr (8 * i)) and 0xFF).toInt()) }

        b(0x48, 0x83, 0xEC, 0x28) // sub rsp, 0x28（shadow + 对齐）
        b(0xB9); imm32(7) // mov ecx, 7
        b(0xB8); imm32(0x40200000) // mov eax, 2.5f 位模式
        b(0x66, 0x0F, 0x6E, 0xC0) // movd xmm0, eax
        b(0xBA); imm32(9) // mov edx, 9
        b(0xB8); imm32(0x40900000) // mov eax, 4.5f 位模式
        b(0x66, 0x0F, 0x6E, 0xC8) // movd xmm1, eax
        b(0x48, 0xB8); imm64(target) // mov rax, target
        b(0xFF, 0xD0) // call rax
        b(0x48, 0x83, 0xC4, 0x28) // add rsp, 0x28
        b(0xC3) // ret

        val bytes = code.toByteArray()
        val addr = Memory.alloc(bytes.size.toLong(), exec = true)
        Memory.writeBytes(addr, bytes)
        allocated.add(addr)
        return addr
    }

    private fun Long.fnIntIntInt(): CPointer<CFunction<(Int, Int) -> Int>> = this.toCPointer()!!

    private fun Long.fnFloatFloat(): CPointer<CFunction<(Float) -> Float>> = this.toCPointer()!!

    private fun Long.fnUnitInt(): CPointer<CFunction<() -> Int>> = this.toCPointer()!!

    @Test
    fun callbackReceivesUnpackedArgumentsAndCanReplaceResult() {
        val fn = makeAdd()
        var seenA = -1L
        var seenB = -1L
        hooks.add(
            HookEngine.hookByAddress(fn, WINDOWS_X64, X64StubGenerator) { ctx ->
                seenA = ctx.argLong(0)
                seenB = ctx.argLong(1)
                ctx.resultLong(1000)
            },
        )
        assertEquals(1000, fn.fnIntIntInt()(3, 4))
        assertEquals(3L, seenA)
        assertEquals(4L, seenB)
    }

    @Test
    fun withoutResultSettingArgumentsAreForwardedToOriginal() {
        val fn = makeAdd()
        hooks.add(
            HookEngine.hookByAddress(fn, WINDOWS_X64, X64StubGenerator) { ctx ->
                ctx.setArgLong(0, ctx.argLong(0) * 10) // 3 → 30
            },
        )
        assertEquals(34, fn.fnIntIntInt()(3, 4)) // 30 + 4
    }

    @Test
    fun callOriginalReturnsOriginalResultAndKeepsArguments() {
        val fn = makeAdd()
        var original = -1L
        hooks.add(
            HookEngine.hookByAddress(fn, WINDOWS_X64, X64StubGenerator) { ctx ->
                original = ctx.callOriginal()
            },
        )
        assertEquals(7, fn.fnIntIntInt()(3, 4))
        assertEquals(7L, original)
    }

    @Test
    fun floatArgumentsUseFloatingPointTrack() {
        val fn = makeIdentityFloat()
        var seen = 0f
        hooks.add(
            HookEngine.hookByAddress(fn, WINDOWS_X64, X64StubGenerator) { ctx ->
                seen = ctx.argFloat(0)
            },
        )
        val result = fn.fnFloatFloat()(1.5f)
        assertEquals(1.5f, seen)
        assertEquals(1.5f, result)
    }

    @Test
    fun mixedIntegerAndFloatTracksFollowMsX64Abi() {
        val fn = makeIdentityFloat()
        var a = 0L
        var b = 0f
        var c = 0L
        var d = 0f
        hooks.add(
            HookEngine.hookByAddress(fn, WINDOWS_X64, X64StubGenerator) { ctx ->
                a = ctx.argLong(0)
                b = ctx.argFloat(0)
                c = ctx.argLong(1)
                d = ctx.argFloat(1)
                ctx.resultLong(12345)
            },
        )
        val caller = makeMixedCaller(fn)
        assertEquals(12345, caller.fnUnitInt()())
        assertEquals(7L, a)
        assertEquals(2.5f, b)
        assertEquals(9L, c)
        assertEquals(4.5f, d)
    }

    @Test
    fun callbackExceptionIsSwallowedAndOriginalResultReturned() {
        val fn = makeAdd()
        var captured: Throwable? = null
        val previous = HookEngine.onHookError
        HookEngine.onHookError = { captured = it }
        try {
            hooks.add(
                HookEngine.hookByAddress(fn, WINDOWS_X64, X64StubGenerator) { error("boom") },
            )
            assertEquals(7, fn.fnIntIntInt()(3, 4))
        } finally {
            HookEngine.onHookError = previous
        }
        assertEquals("boom", captured?.message)
    }
}
