package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.core.Memory
import com.rainccup.il2cpp4k.api.hook.Hook
import com.rainccup.il2cpp4k.api.hook.hook
import com.rainccup.il2cpp4k.api.runtime.Il2CppClass
import com.rainccup.il2cpp4k.api.runtime.Il2CppObject
import com.rainccup.il2cpp4k.api.types.Il2CppKind
import com.rainccup.il2cpp4k.api.types.Int32Value
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Method 直接调用、对象构造与基础 Hook 门面的端到端验证。 */
@OptIn(ExperimentalForeignApi::class)
class InvokeNewTest {
    private val allocated = mutableListOf<Long>()
    private val hooks = mutableListOf<Hook>()

    @AfterTest
    fun cleanup() {
        hooks.forEach { it.unhook() }
        allocated.forEach { Memory.free(it) }
        Il2Cpp.reset()
    }

    private fun makeFunction(padNops: Int, body: ByteArray): Long {
        val code = ByteArray(padNops + body.size) { 0x90.toByte() }
        body.copyInto(code, padNops)
        val addr = Memory.alloc(code.size.toLong(), exec = true)
        Memory.writeBytes(addr, code)
        allocated.add(addr)
        return addr
    }

    /** f(a: Int, b: Int) = a + b */
    private fun makeAdd(): Long = makeFunction(
        8,
        byteArrayOf(0x8D.toByte(), 0x04.toByte(), 0x11.toByte(), 0xC3.toByte()),
    )

    /** f(this, a: Int, b: Int) = a + b → lea eax,[r8+rdx]; ret */
    private fun makeInstanceAdd(): Long = makeFunction(
        8,
        byteArrayOf(0x41.toByte(), 0x8D.toByte(), 0x04.toByte(), 0x10.toByte(), 0xC3.toByte()),
    )

    /** 无参恒等函数：仅 ret */
    private fun makeRet(): Long = makeFunction(11, byteArrayOf(0xC3.toByte()))

    private fun setup(): Pair<FakeIl2Api, Il2CppClass> {
        val fake = FakeIl2Api()
        Il2Cpp.initialize(fake)
        val image = fake.registerImage("Assembly-CSharp.dll")
        fake.registerClass(image, "Calc")
        return fake to Il2Cpp.image("Assembly-CSharp.dll").classOf("Calc")
    }

    @Test
    fun invokeStaticMethodWithValueWrappers() {
        val (fake, klass) = setup()
        fake.registerMethod(
            klass.address,
            "Add",
            2,
            pointer = makeAdd(),
            isStatic = true,
            returnKind = Il2CppKind.INT32,
        )
        val result = klass.method("Add", 2).invoke(null, Il2Cpp.Int(2), Il2Cpp.Int(5))
        assertEquals(7, (result as Int32Value).value)
    }

    @Test
    fun typedCallUnpacksArgumentsAndReturnValue() {
        val (fake, klass) = setup()
        fake.registerMethod(
            klass.address,
            "Add",
            2,
            pointer = makeAdd(),
            isStatic = true,
            returnKind = Il2CppKind.INT32,
        )
        assertEquals(9, klass.method("Add", 2).call<Int>(null, 4, 5))
    }

    @Test
    fun instanceMethodReceivesThisPointerInFirstSlot() {
        val (fake, klass) = setup()
        fake.registerMethod(
            klass.address,
            "Add",
            2,
            pointer = makeInstanceAdd(),
            isStatic = false,
            returnKind = Il2CppKind.INT32,
        )
        val instance = Il2CppObject(0x5000L)
        assertEquals(11, klass.method("Add", 2).call<Int>(instance, 5, 6))
    }

    @Test
    fun classNewAllocatesAndInvokesConstructor() {
        val (fake, klass) = setup()
        fake.registerMethod(
            klass.address,
            ".ctor",
            0,
            pointer = makeRet(),
            isStatic = false,
            returnKind = Il2CppKind.VOID,
        )
        val instance = klass.new()
        assertTrue(instance.address != 0L)
        assertEquals(1, fake.objects.size)
        assertEquals("Calc", klass.name)
    }

    @Test
    fun valueWrappedNewCarriesArgumentsToConstructor() {
        val (fake, klass) = setup()
        // .ctor(this, a: Int)：mov eax, edx; ret
        val ctor = makeFunction(9, byteArrayOf(0x89.toByte(), 0xD0.toByte(), 0xC3.toByte()))
        fake.registerMethod(
            klass.address,
            ".ctor",
            1,
            pointer = ctor,
            isStatic = false,
            returnKind = Il2CppKind.VOID,
        )
        val instance = klass.new(Il2Cpp.Int(7))
        assertTrue(instance.address != 0L)
        assertEquals(1, fake.objects.size)
    }

    @Test
    fun typedCreateBoxesArgumentsAndInvokesConstructor() {
        val (fake, klass) = setup()
        // .ctor(this, a: Int)：mov eax, edx; ret
        val ctor = makeFunction(9, byteArrayOf(0x89.toByte(), 0xD0.toByte(), 0xC3.toByte()))
        fake.registerMethod(
            klass.address,
            ".ctor",
            1,
            pointer = ctor,
            isStatic = false,
            returnKind = Il2CppKind.VOID,
        )
        val instance = klass.create(7)
        assertTrue(instance.address != 0L)
        assertEquals(1, fake.objects.size)
    }

    @Test
    fun basicHookFacadeRejectsUnknownModule() {
        // 门面的「模块 + 偏移」解析路径：未知模块必须抛 Il2CppException（真实目标的成功路径
        // 需要在含 il2cpp 的进程内验证，属交付后验证项）。
        assertFailsWith<Il2CppException> { hook("NoSuchModule.dll", 0x1000L) { } }
    }
}
