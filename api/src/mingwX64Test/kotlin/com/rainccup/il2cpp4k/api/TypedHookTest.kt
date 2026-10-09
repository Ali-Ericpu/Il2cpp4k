package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.core.Memory
import com.rainccup.il2cpp4k.api.hook.Hook
import com.rainccup.il2cpp4k.api.hook.hook
import kotlinx.cinterop.*
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** 类型安全 hook：类型即参数签名，运行时自动解包参数、按 lambda 返回值装箱返回。 */
@OptIn(ExperimentalForeignApi::class)
class TypedHookTest {
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

    /** f(a: Int, b: Int) = a + b（8 nop + lea eax,[rdx+rcx] + ret = 12 字节） */
    private fun makeAdd(): Long = makeFunction(
        8,
        byteArrayOf(0x8D.toByte(), 0x04.toByte(), 0x11.toByte(), 0xC3.toByte()),
    )

    private fun Long.asIntIntFn(): CPointer<CFunction<(Int, Int) -> Int>> = this.toCPointer()!!

    private fun Long.asFloatFn(): CPointer<CFunction<(Float) -> Float>> = this.toCPointer()!!

    @Test
    fun typedHookReplacesResultWithoutCallingOriginal() {
        val fn = makeAdd()
        hooks.add(hook<Int, Int, Int>(fn) { a, b -> a * b })
        assertEquals(12, fn.asIntIntFn()(3, 4))
    }

    @Test
    fun typedHookCanCallOriginalWithModifiedArguments() {
        val fn = makeAdd()
        hooks.add(hook<Int, Int, Int>(fn) { a, b -> original(a * 10, b) })
        assertEquals(34, fn.asIntIntFn()(3, 4))
    }

    @Test
    fun typedHookCanPostProcessOriginalResult() {
        val fn = makeAdd()
        hooks.add(hook<Int, Int, Int>(fn) { a, b -> original(a, b) * 2 })
        assertEquals(14, fn.asIntIntFn()(3, 4))
    }

    @Test
    fun typedHookMapsFloatTrack() {
        // f(x: Float) -> Float 恒等（11 nop + ret）
        val fn = makeFunction(11, byteArrayOf(0xC3.toByte()))
        var seen = 0f
        hooks.add(
            hook<Float, Float>(fn) { x ->
                seen = x
                original(x) + 1f
            },
        )
        assertEquals(3.5f, fn.asFloatFn()(2.5f))
        assertEquals(2.5f, seen)
    }

    @Test
    fun typedHookOnMethodPointer() {
        val fake = FakeIl2Api()
        Il2Cpp.initialize(fake)
        val image = fake.registerImage("Assembly-CSharp.dll")
        val klass = fake.registerClass(image, "Calc")
        val fn = makeAdd()
        fake.registerMethod(klass, "Add", 2, pointer = fn, isStatic = true)

        val method = Il2Cpp.image("Assembly-CSharp.dll").classOf("Calc").method("Add", 2)
        hooks.add(method.hook<Int, Int, Int> { a, b -> a + b + 1 })
        assertEquals(8, fn.asIntIntFn()(3, 4))
    }

    @Test
    fun typedHookArityZeroWorks() {
        val fn = makeFunction(11, byteArrayOf(0xC3.toByte()))
        hooks.add(hook<Int>(fn) { 77 })
        assertEquals(77, fn.toCPointer<CFunction<() -> Int>>()!!())
    }
}
