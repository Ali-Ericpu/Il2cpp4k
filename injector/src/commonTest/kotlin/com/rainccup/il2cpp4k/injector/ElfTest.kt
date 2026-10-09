package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ElfTest {
    /** PT_LOAD 恒等映射 + PT_DYNAMIC；strtab 一个名字、symtab 一项，符号 st_value = 0x1234。 */
    private fun fixture(): ByteArray {
        val bytes = ByteArray(0x600)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = 1 // little endian
        bytes[6] = 1 // version
        putU64(bytes, 32, 0x40) // e_phoff
        putU16(bytes, 54, 56)   // e_phentsize
        putU16(bytes, 56, 2)    // e_phnum

        putU32(bytes, 0x40, 1) // phdr[0] = PT_LOAD，vaddr == offset
        putU64(bytes, 0x40 + 8, 0)
        putU64(bytes, 0x40 + 16, 0)
        putU64(bytes, 0x40 + 32, 0x600)
        putU64(bytes, 0x40 + 40, 0x600)

        putU32(bytes, 0x78, 2) // phdr[1] = PT_DYNAMIC
        putU64(bytes, 0x78 + 8, 0x200)
        putU64(bytes, 0x78 + 16, 0x200)
        putU64(bytes, 0x78 + 32, 0x40)

        var p = 0x200
        putU64(bytes, p, 5); putU64(bytes, p + 8, 0x300); p += 16 // DT_STRTAB
        putU64(bytes, p, 6); putU64(bytes, p + 8, 0x400); p += 16 // DT_SYMTAB
        putU64(bytes, p, 10); putU64(bytes, p + 8, 0x20); p += 16 // DT_STRSZ
        putU64(bytes, p, 11); putU64(bytes, p + 8, 24); p += 16   // DT_SYMENT
        putU64(bytes, p, 0)                                        // DT_NULL

        "\u0000il2cpp4k_init\u0000".forEachIndexed { i, c -> bytes[0x300 + i] = c.code.toByte() }

        putU32(bytes, 0x400 + 24, 1)          // 符号 1 的名字偏移
        putU64(bytes, 0x400 + 24 + 8, 0x1234) // st_value
        return bytes
    }

    @Test
    fun findsSymbolStValue() {
        assertEquals(0x1234L, Elf.symbolOffset(fixture(), "il2cpp4k_init"))
    }

    @Test
    fun returnsNullForUnknownSymbol() {
        assertNull(Elf.symbolOffset(fixture(), "not_there"))
    }

    @Test
    fun returnsNullForTruncatedOrForeignInput() {
        assertNull(Elf.symbolOffset(ByteArray(16), "il2cpp4k_init"))
        assertNull(Elf.symbolOffset(ByteArray(0x600), "il2cpp4k_init"))
    }

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        for (i in 0 until 4) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }

    private fun putU64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}
