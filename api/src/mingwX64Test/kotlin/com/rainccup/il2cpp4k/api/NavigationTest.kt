package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.api.runtime.Il2CppObject
import com.rainccup.il2cpp4k.api.types.StringValue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NavigationTest {
    @AfterTest
    fun cleanup() {
        Il2Cpp.reset()
    }

    private fun setup(): Pair<FakeIl2Api, Long> {
        val fake = FakeIl2Api()
        Il2Cpp.initialize(fake)
        val image = fake.registerImage("Assembly-CSharp.dll")
        fake.registerClass(image, "Player")
        return fake to image
    }

    @Test
    fun navigatesImageToClassToMethodToField() {
        val (fake, image) = setup()
        val klass = fake.registerClass(image, "Enemy")
        fake.registerMethod(klass, "TakeDamage", 2, pointer = 0x2000L)
        fake.registerField(klass, "hp")

        val resolved = Il2Cpp.image("Assembly-CSharp.dll").classOf("Enemy")
        assertEquals("Enemy", resolved.name)
        assertEquals("TakeDamage", resolved.method("TakeDamage", 2).name)
        assertEquals(2, resolved.method("TakeDamage", 2).paramCount)
        assertEquals("hp", resolved.field("hp").name)
        assertEquals(1, resolved.methods.size)
        assertEquals(1, resolved.fields.size)
    }

    @Test
    fun enumeratesImagesAndClasses() {
        val (fake, image) = setup()
        assertEquals(listOf("Assembly-CSharp.dll"), Il2Cpp.domain().assemblies.map { it.name })
        assertEquals(listOf("Player"), Il2Cpp.image("Assembly-CSharp.dll").classes.map { it.name })
        fake.registerClass(image, "Enemy")
        assertEquals(2, Il2Cpp.image("Assembly-CSharp.dll").classes.size)
    }

    @Test
    fun missingClassAndMethodThrow() {
        setup()
        val image = Il2Cpp.image("Assembly-CSharp.dll")
        assertFailsWith<Il2CppException> { image.classOf("Missing") }
        assertFailsWith<Il2CppException> { image.classOf("Player").method("nope", 0) }
        assertFailsWith<Il2CppException> { Il2Cpp.image("NoSuch.dll") }
        assertFailsWith<Il2CppException> { image.classOf("Player").field("nope") }
    }

    @Test
    fun methodPointerAndStaticFlagAreExposed() {
        val (fake, image) = setup()
        val klass = fake.registerClass(image, "Enemy")
        fake.registerMethod(klass, "stat", 0, pointer = 0x3000L, isStatic = true)
        val m = Il2Cpp.image("Assembly-CSharp.dll").classOf("Enemy").method("stat", 0)
        assertEquals(0x3000L, m.pointer)
        assertEquals(true, m.isStatic)
        assertEquals("stat", m.name)
        assertEquals(0, m.paramCount)
    }

    @Test
    fun stringConstructionGoesThroughApi() {
        val (fake, _) = setup()
        val value = Il2Cpp.String("hello") as StringValue
        assertEquals(5, fake.stringLength(value.address))
        assertEquals("hello", Il2Cpp.String.toKString(value.address))
    }

    @Test
    fun objectWrapperReadsKlassPointerFromFirstField() {
        val obj = Il2CppObject(address = 0L)
        assertEquals(0L, obj.klassPointer)
    }
}
