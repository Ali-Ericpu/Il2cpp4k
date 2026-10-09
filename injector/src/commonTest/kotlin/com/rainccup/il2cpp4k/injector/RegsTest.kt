package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RegsTest {
    @Test
    fun roundTripsAllFields() {
        val values = LongArray(Regs.COUNT) { (it + 1).toLong() }
        val decoded = Regs.decode(Regs.encode(values))
        assertEquals(Regs.COUNT, decoded.size)
        values.forEachIndexed { i, v -> assertEquals(v, decoded[i]) }
    }

    @Test
    fun layoutMatchesArm64UserPtRegs() {
        assertEquals(0, Regs.X0)
        assertEquals(30, Regs.LR)
        assertEquals(31, Regs.SP)
        assertEquals(32, Regs.PC)
        assertEquals(33, Regs.PSTATE)
        assertEquals(272, Regs.SIZE_BYTES)
    }

    @Test
    fun rejectsWrongSize() {
        assertFailsWith<IllegalArgumentException> { Regs.encode(LongArray(3)) }
        assertFailsWith<IllegalArgumentException> { Regs.decode(ByteArray(8)) }
    }
}
