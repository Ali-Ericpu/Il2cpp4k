package com.rainccup.il2cpp4k.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstructionDecoderTest {
    private fun len(vararg b: Int) =
        InstructionDecoder.decodeX64(ByteArray(b.size) { b[it].toByte() }, 0)!!.length

    @Test
    fun decodesCommonPrologueInstructions() {
        assertEquals(1, len(0x55))                                     // push rbp
        assertEquals(1, len(0x53))                                     // push rbx
        assertEquals(2, len(0x41, 0x54))                               // push r12
        assertEquals(4, len(0x48, 0x83, 0xEC, 0x28))                   // sub rsp, 0x28
        assertEquals(7, len(0x48, 0x81, 0xEC, 0x88, 0x00, 0x00, 0x00)) // sub rsp, 0x88
        assertEquals(5, len(0x48, 0x89, 0x5C, 0x24, 0x08))             // mov [rsp+8], rbx
        assertEquals(3, len(0x48, 0x8B, 0xEC))                         // mov rbp, rsp
        assertEquals(3, len(0x48, 0x89, 0xE5))                         // mov rbp, rsp
        assertEquals(4, len(0xF3, 0x0F, 0x1E, 0xFA))                   // endbr64
        assertEquals(10, len(0x48, 0xB8, 1, 2, 3, 4, 5, 6, 7, 8))      // mov rax, imm64
        assertEquals(2, len(0xFF, 0xE0))                               // jmp rax
        assertEquals(5, len(0x0F, 0x29, 0x74, 0x24, 0x10))             // movaps [rsp+0x10], xmm6
        assertEquals(5, len(0x44, 0x89, 0x44, 0x24, 0x18))             // mov [rsp+0x18], r8d
        assertEquals(2, len(0x8B, 0xC1))                               // mov eax, ecx
        assertEquals(3, len(0x83, 0xC0, 0x2A))                         // add eax, 0x2A
        assertEquals(1, len(0xC3))                                     // ret
    }

    @Test
    fun reportsRipRelativeDisp() {
        val bytes = byteArrayOf(0x48.toByte(), 0x8D.toByte(), 0x05, 0x00, 0x00, 0x00, 0x00)
        val r = InstructionDecoder.decodeX64(bytes, 0)!!
        assertEquals(7, r.length)
        assertEquals(true, r.ripRelative)
        assertEquals(3, r.dispOffset)

        val noDisp = InstructionDecoder.decodeX64(byteArrayOf(0x48.toByte(), 0x8B.toByte(), 0xEC.toByte()), 0)!!
        assertEquals(false, noDisp.ripRelative)
        assertEquals(-1, noDisp.dispOffset)
    }

    @Test
    fun rejectsUnsupportedOpcodes() {
        assertNull(InstructionDecoder.decodeX64(byteArrayOf(0xD4.toByte(), 0x10.toByte()), 0)) // aam
        assertNull(InstructionDecoder.decodeX64(byteArrayOf(0x0F.toByte(), 0x38.toByte(), 0x00.toByte()), 0)) // AVX 转义
    }

    @Test
    fun rejectsTruncatedInput() {
        assertNull(InstructionDecoder.decodeX64(byteArrayOf(0x48.toByte(), 0x83.toByte(), 0xEC.toByte()), 0)) // 缺 imm8
        assertNull(InstructionDecoder.decodeX64(byteArrayOf(0x48.toByte(), 0xB8.toByte(), 1, 2), 0))          // 缺 imm64
    }
}
