package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PeExportsTest {
    /** 单节 PE32+：导出目录 + 1 个名字 + 1 个序数 + 1 个函数 RVA = 0x2000。 */
    private fun fixture(): ByteArray {
        val bytes = ByteArray(0x400)
        putU16(bytes, 0, 0x5A4D)          // 'MZ'
        putU32(bytes, 0x3C, 0x40)         // e_lfanew
        putU32(bytes, 0x40, 0x00004550)   // 'PE\0\0'
        putU16(bytes, 0x44, 0x8664)       // machine x64
        putU16(bytes, 0x46, 1)            // NumberOfSections
        putU16(bytes, 0x54, 0xF0)         // SizeOfOptionalHeader
        val optional = 0x58
        putU16(bytes, optional, 0x20B)    // PE32+
        putU32(bytes, optional + 108, 16) // NumberOfRvaAndSizes
        putU32(bytes, optional + 112, 0x1000) // 导出表 RVA
        putU32(bytes, optional + 116, 0x80)   // 导出表大小
        val section = optional + 0xF0
        ".rdata".forEachIndexed { i, c -> bytes[section + i] = c.code.toByte() }
        putU32(bytes, section + 8, 0x200)  // VirtualSize
        putU32(bytes, section + 12, 0x1000) // VirtualAddress
        putU32(bytes, section + 16, 0x200) // SizeOfRawData
        putU32(bytes, section + 20, 0x200) // PointerToRawData

        // .rdata：文件 0x200 == RVA 0x1000
        val exportDir = 0x200
        putU32(bytes, exportDir + 24, 1)      // NumberOfNames
        putU32(bytes, exportDir + 28, 0x1060) // AddressOfFunctions
        putU32(bytes, exportDir + 32, 0x1064) // AddressOfNames
        putU32(bytes, exportDir + 36, 0x1068) // AddressOfNameOrdinals
        "il2cpp4k_init\u0000".forEachIndexed { i, c -> bytes[0x240 + i] = c.code.toByte() }
        putU32(bytes, 0x260, 0x2000) // 函数表[0] = 目标 RVA
        putU32(bytes, 0x264, 0x1040) // 名字表[0] = 名字 RVA
        putU16(bytes, 0x268, 0)      // 序数表[0] = 0
        return bytes
    }

    @Test
    fun findsExportRva() {
        assertEquals(0x2000, PeExports.exportRva(fixture(), "il2cpp4k_init"))
    }

    @Test
    fun returnsNullForUnknownOrInvalidInput() {
        assertNull(PeExports.exportRva(fixture(), "missing"))
        assertNull(PeExports.exportRva(ByteArray(0x400), "il2cpp4k_init"))
        assertNull(PeExports.exportRva(ByteArray(8), "il2cpp4k_init"))
    }

    private fun putU16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, o: Int, v: Int) {
        for (i in 0 until 4) b[o + i] = ((v ushr (8 * i)) and 0xFF).toByte()
    }
}
