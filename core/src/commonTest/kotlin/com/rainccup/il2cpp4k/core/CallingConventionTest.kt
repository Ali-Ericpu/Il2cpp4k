package com.rainccup.il2cpp4k.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallingConventionTest {
    @Test
    fun windowsX64AssignsTracksIndependently() {
        val slots = WINDOWS_X64.assignSlots(
            listOf(ParamTrack.GPR, ParamTrack.FPR, ParamTrack.GPR, ParamTrack.FPR),
        )
        assertEquals(ArgSlot(ParamTrack.GPR, 0, false), slots[0])
        assertEquals(ArgSlot(ParamTrack.FPR, 0, false), slots[1])
        assertEquals(ArgSlot(ParamTrack.GPR, 1, false), slots[2])
        assertEquals(ArgSlot(ParamTrack.FPR, 1, false), slots[3])
    }

    @Test
    fun overflowGoesToStackInDeclarationOrder() {
        val slots = WINDOWS_X64.assignSlots(List(6) { ParamTrack.GPR })
        assertEquals(ArgSlot(ParamTrack.GPR, 3, false), slots[3])
        assertEquals(ArgSlot(ParamTrack.GPR, 0, true), slots[4])
        assertEquals(ArgSlot(ParamTrack.GPR, 1, true), slots[5])
    }

    @Test
    fun arm64HasEightRegisterSlotsPerTrack() {
        val slots = ANDROID_ARM64.assignSlots(List(9) { ParamTrack.FPR })
        assertEquals(ArgSlot(ParamTrack.FPR, 7, false), slots[7])
        assertEquals(ArgSlot(ParamTrack.FPR, 0, true), slots[8])
    }

    @Test
    fun offsetsAreStableAndNonOverlapping() {
        val cc = WINDOWS_X64
        assertEquals(0L, cc.gprBase)
        assertEquals(4 * 8L, cc.fprBase)
        assertEquals(4 * 8L + 4 * 8L, cc.stackBase)
        assertEquals(cc.stackBase + 8 * 8L, cc.retIntOffset)
        assertEquals(cc.retIntOffset + 8L, cc.retFloatOffset)
        assertEquals(cc.retFloatOffset + 8L, cc.ctxSize)
        val gpr3 = ArgSlot(ParamTrack.GPR, 3, false)
        val fpr0 = ArgSlot(ParamTrack.FPR, 0, false)
        val stack0 = ArgSlot(ParamTrack.GPR, 0, true)
        assertTrue(cc.offsetOf(gpr3) < cc.offsetOf(fpr0))
        assertTrue(cc.offsetOf(fpr0) < cc.offsetOf(stack0))
        assertTrue(cc.offsetOf(stack0) < cc.retIntOffset)
    }
}
