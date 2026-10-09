package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class InlineHookTest {
    private val allocated = mutableListOf<Long>()

    @AfterTest
    fun cleanup() {
        allocated.forEach { Memory.free(it) }
    }

    /** 前置 nop 填充 + 函数体；填充保证入口补丁长度落在函数体内且跳回点正好是 ret。 */
    private fun makeFunction(padNops: Int, body: ByteArray): Long {
        val code = ByteArray(padNops + body.size) { 0x90.toByte() }
        body.copyInto(code, padNops)
        val addr = Memory.alloc(code.size.toLong(), exec = true)
        Memory.writeBytes(addr, code)
        allocated.add(addr)
        return addr
    }

    private fun Long.fnIntInt(): CPointer<CFunction<(Int) -> Int>> = this.toCPointer()!!

    private fun Long.fnIntLong(): CPointer<CFunction<(Int) -> Long>> = this.toCPointer()!!

    @Test
    fun trampolineExecutesCopiedPrologueAndReturns() {
        // 7 nop + mov eax, ecx + add eax, 0x2A + ret：拷贝 12 字节后跳回 ret
        val entry = makeFunction(
            7,
            byteArrayOf(0x8B.toByte(), 0xC1.toByte(), 0x83.toByte(), 0xC0.toByte(), 0x2A.toByte(), 0xC3.toByte()),
        )
        val hook = InlineHook(entry, WINDOWS_X64, X64StubGenerator)
        hook.prepare()
        assertEquals(47, hook.trampoline.fnIntInt()(5))
        hook.unhook()
    }

    @Test
    fun trampolineRelocatesRipRelativeOperand() {
        // 5 nop + lea rax, [rip+0] + ret：原语义 rax = 该 lea 之后一条指令的地址
        val entry = makeFunction(
            5,
            byteArrayOf(
                0x48.toByte(), 0x8D.toByte(), 0x05.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xC3.toByte(),
            ),
        )
        val hook = InlineHook(entry, WINDOWS_X64, X64StubGenerator)
        hook.prepare()
        assertEquals(entry + 12, hook.trampoline.fnIntLong()(0))
        hook.unhook()
    }

    @Test
    fun installRedirectsEntryAndUnhookRestores() {
        val entry = makeFunction(
            7,
            byteArrayOf(0xB8.toByte(), 0x2A.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0xC3.toByte()),
        )
        val replacement = makeFunction(
            7,
            byteArrayOf(0xB8.toByte(), 0xE7.toByte(), 0x03.toByte(), 0x00.toByte(), 0x00.toByte(), 0xC3.toByte()),
        )
        val before = Memory.readBytes(entry, 12)

        val hook = InlineHook(entry, WINDOWS_X64, X64StubGenerator)
        hook.prepare()
        hook.install(replacement)
        assertEquals(999, entry.fnIntInt()(0))
        assertTrue(!before.contentEquals(Memory.readBytes(entry, 12)))

        hook.unhook()
        assertEquals(42, entry.fnIntInt()(0))
        assertTrue(before.contentEquals(Memory.readBytes(entry, 12)))
    }

    @Test
    fun unsupportedPrologueIsRejectedWithoutModifyingTarget() {
        val entry = makeFunction(0, byteArrayOf(0xD4.toByte(), 0x10.toByte(), 0xC3.toByte()))
        val before = Memory.readBytes(entry, 3)
        val hook = InlineHook(entry, WINDOWS_X64, X64StubGenerator)
        assertFailsWith<HookInstallException> { hook.prepare() }
        assertTrue(before.contentEquals(Memory.readBytes(entry, 3)))
    }
}
