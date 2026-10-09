package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.core.ParamTrack
import com.rainccup.il2cpp4k.api.runtime.Il2CppObject
import com.rainccup.il2cpp4k.api.types.BooleanValue
import com.rainccup.il2cpp4k.api.types.DoubleValue
import com.rainccup.il2cpp4k.api.types.FloatValue
import com.rainccup.il2cpp4k.api.types.Il2CppKind
import com.rainccup.il2cpp4k.api.types.Int32Value
import com.rainccup.il2cpp4k.api.types.Int64Value
import com.rainccup.il2cpp4k.api.types.ObjectValue
import com.rainccup.il2cpp4k.api.types.PointerValue
import com.rainccup.il2cpp4k.api.types.il2cppTypeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypesTest {
    @Test
    fun descriptorsCarryIl2NameAndSlotTrack() {
        assertEquals("System.Int32", Il2Cpp.Int.il2Name)
        assertEquals(Il2CppKind.INT32, Il2Cpp.Int.kind)
        assertEquals(ParamTrack.GPR, Il2Cpp.Int.slotTrack)
        assertEquals("System.Single", Il2Cpp.Float.il2Name)
        assertEquals(ParamTrack.FPR, Il2Cpp.Float.slotTrack)
        assertEquals(ParamTrack.FPR, Il2Cpp.Double.slotTrack)
        assertEquals("System.Void", Il2Cpp.Void.il2Name)
        assertEquals(null, Il2Cpp.Void.slotTrack)
        assertEquals("System.String", Il2Cpp.String.il2Name)
        assertEquals(ParamTrack.GPR, Il2Cpp.String.slotTrack)
        assertEquals(ParamTrack.GPR, Il2Cpp.Object.slotTrack)
    }

    @Test
    fun valueConstructionKeepsTypeAndPayload() {
        val i = Il2Cpp.Int(42)
        assertEquals(Il2CppKind.INT32, i.type.kind)
        assertEquals(42, (i as Int32Value).value)

        val l = Il2Cpp.Long(1234567890123L)
        assertEquals(1234567890123L, (l as Int64Value).value)

        val f = Il2Cpp.Float(1.5f)
        assertEquals(1.5f, (f as FloatValue).value)

        val d = Il2Cpp.Double(2.5)
        assertEquals(2.5, (d as DoubleValue).value)

        val b = Il2Cpp.Boolean(true)
        assertEquals(true, (b as BooleanValue).value)

        val p = Il2Cpp.Pointer(0x1234L)
        assertEquals(0x1234L, (p as PointerValue).address)

        val o = Il2Cpp.Object(0x5678L)
        assertEquals(0x5678L, (o as ObjectValue).address)
    }

    @Test
    fun reifiedTypeMappingMatchesDescriptors() {
        assertEquals(Il2Cpp.Int, il2cppTypeOf<Int>())
        assertEquals(Il2Cpp.Long, il2cppTypeOf<Long>())
        assertEquals(Il2Cpp.Float, il2cppTypeOf<Float>())
        assertEquals(Il2Cpp.Double, il2cppTypeOf<Double>())
        assertEquals(Il2Cpp.Boolean, il2cppTypeOf<Boolean>())
        assertEquals(Il2Cpp.String, il2cppTypeOf<String>())
        assertEquals(Il2Cpp.Object, il2cppTypeOf<Il2CppObject>())
        assertEquals(Il2Cpp.Void, il2cppTypeOf<Unit>())
        assertFailsWith<Il2CppException> { il2cppTypeOf<List<Int>>() }
        assertFailsWith<Il2CppException> { il2cppTypeOf<ByteArray>() }
    }

    @Test
    fun stringConstructionRequiresInitialization() {
        Il2Cpp.reset()
        assertFailsWith<Il2CppException> { Il2Cpp.String("hi") }
        assertFailsWith<Il2CppException> { Il2Cpp.api }
    }
}
