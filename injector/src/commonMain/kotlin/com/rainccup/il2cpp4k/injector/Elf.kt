package com.rainccup.il2cpp4k.injector

/**
 * 最小 ELF64 解析：从 PT_DYNAMIC 取动态符号表，按名返回 `st_value`（相对模块基址的偏移）。
 * 仅用于「本地读取目标进程里同名 ELF 文件、按名取偏移」；不做重定位、不校验完整性。
 */
object Elf {
    private const val PT_LOAD = 1
    private const val PT_DYNAMIC = 2
    private const val DT_NULL = 0L
    private const val DT_STRTAB = 5L
    private const val DT_SYMTAB = 6L
    private const val DT_STRSZ = 10L
    private const val DT_SYMENT = 11L
    private const val ELF64_SYM_SIZE = 24
    private const val DYNAMIC_ENTRY_SIZE = 16

    private class LoadSegment(val vaddr: Long, val filesz: Long, val offset: Long)

    fun symbolOffset(bytes: ByteArray, symbol: String): Long? {
        if (bytes.size < 64) return null
        if (readU32(bytes, 0) != 0x464C457F.toInt()) return null

        val phoff = readU64(bytes, 32)
        val phentsize = readU16(bytes, 54)
        val phnum = readU16(bytes, 56)
        if (phoff == 0L || phentsize == 0 || phnum == 0) return null

        val loads = ArrayList<LoadSegment>(phnum)
        var dynamicVaddr = 0L
        var dynamicSize = 0L
        for (i in 0 until phnum) {
            val base = (phoff + i.toLong() * phentsize).toInt()
            if (base + 56 > bytes.size) return null
            when (readU32(bytes, base)) {
                PT_LOAD -> loads.add(
                    LoadSegment(
                        vaddr = readU64(bytes, base + 16),
                        filesz = readU64(bytes, base + 32),
                        offset = readU64(bytes, base + 8),
                    ),
                )
                PT_DYNAMIC -> {
                    dynamicVaddr = readU64(bytes, base + 16)
                    dynamicSize = readU64(bytes, base + 32)
                }
            }
        }
        if (dynamicVaddr == 0L) return null
        val dynamicOffset = vaddrToFileOffset(loads, dynamicVaddr) ?: return null

        var symtab = 0L
        var strtab = 0L
        var strsz = 0L
        var syment = ELF64_SYM_SIZE.toLong()
        var cursor = dynamicOffset
        val dynamicEnd = minOf(dynamicOffset + dynamicSize, bytes.size.toLong())
        while (cursor + DYNAMIC_ENTRY_SIZE <= dynamicEnd) {
            val tag = readU64(bytes, cursor.toInt())
            val value = readU64(bytes, cursor.toInt() + 8)
            when (tag) {
                DT_NULL -> break
                DT_STRTAB -> strtab = value
                DT_SYMTAB -> symtab = value
                DT_STRSZ -> strsz = value
                DT_SYMENT -> syment = value
            }
            cursor += DYNAMIC_ENTRY_SIZE
        }
        if (symtab == 0L || strtab == 0L || syment == 0L) return null
        val symOffset = vaddrToFileOffset(loads, symtab) ?: return null
        val strOffset = vaddrToFileOffset(loads, strtab) ?: return null

        // 动态符号表没有显式条目数（在 DT_HASH/GNU_HASH 中）：按文件边界迭代，靠名字比对收口。
        var index = 0L
        while (symOffset + index * syment + ELF64_SYM_SIZE <= bytes.size) {
            val at = (symOffset + index * syment).toInt()
            val nameOffset = readU32(bytes, at).toLong()
            if (nameOffset < strsz) {
                if (readCString(bytes, (strOffset + nameOffset).toInt()) == symbol) {
                    return readU64(bytes, at + 8)
                }
            }
            index++
        }
        return null
    }

    private fun vaddrToFileOffset(loads: List<LoadSegment>, vaddr: Long): Long? =
        loads.firstOrNull { vaddr >= it.vaddr && vaddr < it.vaddr + it.filesz }
            ?.let { vaddr - it.vaddr + it.offset }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((bytes[offset + i].toInt() and 0xFF) shl (8 * i))
        return v
    }

    private fun readU64(bytes: ByteArray, offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i))
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
