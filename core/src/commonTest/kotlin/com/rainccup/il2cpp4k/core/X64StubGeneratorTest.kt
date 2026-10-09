package com.rainccup.il2cpp4k.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class X64StubGeneratorTest {
    private val cc = WINDOWS_X64

    /** 在 [bytes] 中查找 `48 <opcode> <imm64>`，返回 imm64 的起始下标与值。 */
    private fun findImm64(bytes: ByteArray, opcode: Int, start: Int = 0): Pair<Int, Long>? {
        var i = start
        while (i + 9 < bytes.size) {
            if ((bytes[i].toInt() and 0xFF) == 0x48 && (bytes[i + 1].toInt() and 0xFF) == opcode) {
                var v = 0L
                for (k in 0 until 8) {
                    v = v or ((bytes[i + 2 + k].toLong() and 0xFF) shl (8 * k))
                }
                return i to v
            }
            i++
        }
        return null
    }

    @Test
    fun entryPatchIsTwelveByteAbsoluteJump() {
        val patch = X64StubGenerator.entryPatch(0x1122334455667788L)
        assertEquals(12, patch.size)
        assertEquals(0x48, patch[0].toInt() and 0xFF)
        assertEquals(0xB8, patch[1].toInt() and 0xFF)
        assertEquals(0x1122334455667788L, findImm64(patch, 0xB8)!!.second)
        assertEquals(0xFF, patch[10].toInt() and 0xFF)
        assertEquals(0xE0, patch[11].toInt() and 0xFF)
        assertEquals(12, X64StubGenerator.patchLength)
    }

    @Test
    fun entryStubSavesRegistersAndCallsInvoker() {
        val ctx = 0x1000L
        val invoker = 0x2000L
        val stub = X64StubGenerator.entryStub(cc, ctxAddr = ctx, hookId = 7L, invokerAddr = invoker)

        assertEquals(ctx, findImm64(stub, 0xB8)!!.second)     // 首条：mov rax, ctx
        assertEquals(7L, findImm64(stub, 0xB9)!!.second)      // mov rcx, hookId
        val invokerAt = findImm64(stub, 0xB8, start = 10)!!
        assertEquals(invoker, invokerAt.second)               // mov rax, invoker
        assertEquals(0xFF, stub[invokerAt.first + 10].toInt() and 0xFF)
        assertEquals(0xD0, stub[invokerAt.first + 11].toInt() and 0xFF) // call rax
        assertEquals(0xC3, stub.last().toInt() and 0xFF)      // ret

        // movsd xmm0, [rax + disp32] → F2 0F 10 80
        assertTrue(
            stub.toList().windowed(4).any {
                (it[0].toInt() and 0xFF) == 0xF2 && (it[1].toInt() and 0xFF) == 0x0F &&
                    (it[2].toInt() and 0xFF) == 0x10 && (it[3].toInt() and 0xFF) == 0x80
            },
        )
    }

    @Test
    fun callThunkPreservesCalleeSavedRegisterAndLoadsTarget() {
        val target = 0xDEADBEEFL
        val thunk = X64StubGenerator.callThunk(cc, target)
        assertEquals(0x53, thunk[0].toInt() and 0xFF)             // push rbx
        assertEquals(0x48, thunk[1].toInt() and 0xFF)             // mov rbx, rcx
        assertEquals(0x89, thunk[2].toInt() and 0xFF)
        assertEquals(0xCB, thunk[3].toInt() and 0xFF)
        assertEquals(target, findImm64(thunk, 0xB8)!!.second)
        assertEquals(0x5B, thunk[thunk.size - 2].toInt() and 0xFF) // pop rbx
        assertEquals(0xC3, thunk[thunk.size - 1].toInt() and 0xFF) // ret
    }
}
