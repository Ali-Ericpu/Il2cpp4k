package com.rainccup.il2cpp4k.injector

/**
 * 从 PE 文件字节取导出符号的 RVA（相对模块基址的偏移）。
 * 只处理 PE32+（Magic 0x20B），不支持导出转发（forwarder）。
 */
object PeExports {
    private const val DOS_MAGIC = 0x5A4D
    private const val PE_SIGNATURE = 0x00004550
    private const val OPTIONAL_MAGIC_PE32PLUS = 0x20B

    private class Section(val virtualAddress: Int, val virtualSize: Int, val rawPointer: Int, val rawSize: Int)

    fun exportRva(bytes: ByteArray, symbol: String): Int? {
        if (bytes.size < 0x40 || readU16(bytes, 0) != DOS_MAGIC) return null
        val peOffset = readU32(bytes, 0x3C)
        if (peOffset + 24 > bytes.size || readU32(bytes, peOffset) != PE_SIGNATURE) return null

        val coffOffset = peOffset + 4
        val numberOfSections = readU16(bytes, coffOffset + 2)
        val sizeOfOptionalHeader = readU16(bytes, coffOffset + 16)
        val optional = coffOffset + 20
        if (optional + 120 > bytes.size) return null
        if (readU16(bytes, optional) != OPTIONAL_MAGIC_PE32PLUS) return null

        val exportRva = readU32(bytes, optional + 112)
        if (exportRva == 0) return null

        val sectionsOffset = optional + sizeOfOptionalHeader
        val sections = (0 until numberOfSections).mapNotNull { i ->
            val at = sectionsOffset + i * 40
            if (at + 40 > bytes.size) {
                null
            } else {
                Section(
                    virtualAddress = readU32(bytes, at + 12),
                    virtualSize = readU32(bytes, at + 8),
                    rawPointer = readU32(bytes, at + 20),
                    rawSize = readU32(bytes, at + 16),
                )
            }
        }

        val exportOffset = rvaToFileOffset(sections, exportRva) ?: return null
        if (exportOffset + 40 > bytes.size) return null
        val numberOfNames = readU32(bytes, exportOffset + 24)
        val namesOffset = rvaToFileOffset(sections, readU32(bytes, exportOffset + 32)) ?: return null
        val ordinalsOffset = rvaToFileOffset(sections, readU32(bytes, exportOffset + 36)) ?: return null
        val functionsOffset = rvaToFileOffset(sections, readU32(bytes, exportOffset + 28)) ?: return null

        for (i in 0 until numberOfNames) {
            val nameOffset = rvaToFileOffset(sections, readU32(bytes, namesOffset + i * 4)) ?: continue
            if (readCString(bytes, nameOffset) != symbol) continue
            val ordinal = readU16(bytes, ordinalsOffset + i * 2)
            return readU32(bytes, functionsOffset + ordinal * 4)
        }
        return null
    }

    private fun rvaToFileOffset(sections: List<Section>, rva: Int): Int? =
        sections
            .firstOrNull { rva >= it.virtualAddress && rva < it.virtualAddress + maxOf(it.virtualSize, it.rawSize) }
            ?.let { rva - it.virtualAddress + it.rawPointer }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun readCString(bytes: ByteArray, offset: Int): String {
        val sb = StringBuilder()
        var i = offset
        while (i < bytes.size && bytes[i].toInt() != 0) {
            sb.append(bytes[i].toInt().toChar())
            i++
        }
        return sb.toString()
    }
}
