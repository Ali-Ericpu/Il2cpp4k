package com.rainccup.il2cpp4k.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Arm64StubGeneratorTest {
    private val cc = ANDROID_ARM64

    private fun wordAt(bytes: ByteArray, index: Int): Int {
        var v = 0
        for (k in 0 until 4) v = v or ((bytes[index + k].toInt() and 0xFF) shl (8 * k))
        return v
    }

    @Test
    fun encodersMatchWellKnownInstructions() {
        val e1 = Arm64Emitter()
        e1.stpXPre(29, 30, 31, -16)
        assertEquals(0xA9BF7BFD.toInt(), wordAt(e1.toByteArray(), 0))

        val e2 = Arm64Emitter()
        e2.ldpXPost(29, 30, 31, 16)
        assertEquals(0xA8C17BFD.toInt(), wordAt(e2.toByteArray(), 0))

        val e3 = Arm64Emitter()
        e3.ret()
        assertEquals(0xD65F03C0.toInt(), wordAt(e3.toByteArray(), 0))

        val e4 = Arm64Emitter()
        e4.br(16)
        assertEquals(0xD61F0200.toInt(), wordAt(e4.toByteArray(), 0))

        val e5 = Arm64Emitter()
        e5.blr(16)
        assertEquals(0xD63F0200.toInt(), wordAt(e5.toByteArray(), 0))

        val e6 = Arm64Emitter()
        e6.strX(0, 31, 0)
        assertEquals(0xF90003E0.toInt(), wordAt(e6.toByteArray(), 0))

        val e7 = Arm64Emitter()
        e7.ldrX(0, 31, 0)
        assertEquals(0xF94003E0.toInt(), wordAt(e7.toByteArray(), 0))

        val e8 = Arm64Emitter()
        e8.strD(0, 31, 0)
        assertEquals(0xFD0003E0.toInt(), wordAt(e8.toByteArray(), 0))

        val e9 = Arm64Emitter()
        e9.ldrD(0, 31, 0)
        assertEquals(0xFD4003E0.toInt(), wordAt(e9.toByteArray(), 0))

        val e10 = Arm64Emitter()
        e10.movz(9, 0)
        assertEquals(0xD2800009.toInt(), wordAt(e10.toByteArray(), 0))
    }

    @Test
    fun movImm64RoundTrips() {
        val value = 0x123456789ABCDEF0L
        val e = Arm64Emitter()
        e.movImm64(9, value)
        val bytes = e.toByteArray()
        var rebuilt = 0L
        for (w in 0 until 4) {
            val word = wordAt(bytes, w * 4)
            val imm = (word ushr 5) and 0xFFFF
            rebuilt = rebuilt or (imm.toLong() shl (16 * w))
        }
        assertEquals(value, rebuilt)
    }

    @Test
    fun entryPatchLoadsLiteralAndBranches() {
        val patch = Arm64StubGenerator.entryPatch(0x1122334455667788L)
        assertEquals(16, patch.size)
        assertEquals(0x58000050, wordAt(patch, 0))          // ldr x16, [pc, #8]
        assertEquals(0xD61F0200.toInt(), wordAt(patch, 4))  // br x16
        var target = 0L
        for (i in 0 until 8) target = target or ((patch[8 + i].toLong() and 0xFF) shl (8 * i))
        assertEquals(0x1122334455667788L, target)
        assertEquals(16, Arm64StubGenerator.patchLength)
    }

    @Test
    fun entryStubSavesFramesCallsInvokerAndLoadsReturns() {
        val stub = Arm64StubGenerator.entryStub(cc, ctxAddr = 0x1000L, hookId = 7L, invokerAddr = 0x2000L)
        assertEquals(0xA9BF7BFD.toInt(), wordAt(stub, 0))              // stp x29, x30, [sp,#-16]!
        assertEquals(0xD65F03C0.toInt(), wordAt(stub, stub.size - 4))  // ret
        assertTrue(containsImm64(stub, 0x2000L))                       // invoker 地址
        assertTrue(containsImm64(stub, 7L))                            // hookId
        assertTrue(containsImm64(stub, 0x1000L))                       // ctx 地址
    }

    @Test
    fun callThunkSavesCalleeSavedAndTarget() {
        val thunk = Arm64StubGenerator.callThunk(cc, target = 0xDEADBEEFL)
        assertEquals(0xA9BF7BFD.toInt(), wordAt(thunk, 4))              // 第二条：stp x29, x30, [sp,#-16]!
        assertEquals(0xD65F03C0.toInt(), wordAt(thunk, thunk.size - 4)) // ret
        assertTrue(containsImm64(thunk, 0xDEADBEEFL))
    }

    /** 扫描 movz/movk 四连，重建 64 位常量并比对。 */
    private fun containsImm64(bytes: ByteArray, value: Long): Boolean {
        var i = 0
        while (i + 16 <= bytes.size) {
            var rebuilt = 0L
            var ok = true
            for (w in 0 until 4) {
                val word = wordAt(bytes, i + w * 4)
                // bits 31..23 为固定段：movz = 0x1A5、movk = 0x1E5（hw 在 bits 22..21，需单独比较）
                val opcode = word ushr 23
                if (w == 0 && opcode != 0x1A5) { ok = false; break }
                if (w > 0 && opcode != 0x1E5) { ok = false; break }
                val hw = (word ushr 21) and 3
                if (hw != w) { ok = false; break }
                rebuilt = rebuilt or (((word ushr 5) and 0xFFFF).toLong() shl (16 * w))
            }
            if (ok && rebuilt == value) return true
            i += 4
        }
        return false
    }
}
