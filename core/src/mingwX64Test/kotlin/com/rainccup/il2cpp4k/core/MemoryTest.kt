package com.rainccup.il2cpp4k.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MemoryTest {
    @Test
    fun allocReadWriteRoundTrip() {
        val addr = Memory.alloc(64, exec = false)
        assertNotEquals(0L, addr)
        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        Memory.writeBytes(addr, payload)
        assertTrue(payload.contentEquals(Memory.readBytes(addr, 4)))
        Memory.free(addr)
    }

    @Test
    fun execMemoryIsWritableAndReadable() {
        val addr = Memory.alloc(64, exec = true)
        assertNotEquals(0L, addr)
        Memory.writeBytes(addr, byteArrayOf(0xC3.toByte()))
        assertEquals(0xC3.toByte(), Memory.readBytes(addr, 1)[0])
        Memory.free(addr)
    }

    @Test
    fun moduleBaseAndSymbolLookup() {
        assertNotEquals(0L, Module.base(null))
        assertNotEquals(null, Module.symbol("kernel32.dll", "GetCurrentProcessId"))
        assertEquals(null, Module.symbol("kernel32.dll", "ThisSymbolDoesNotExist123"))
    }
}
