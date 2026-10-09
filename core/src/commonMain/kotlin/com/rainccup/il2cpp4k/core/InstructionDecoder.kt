package com.rainccup.il2cpp4k.core

/** [dispOffset] 相对指令起点；无 disp 时为 -1。解码失败返回 null。 */
data class DecodeResult(val length: Int, val ripRelative: Boolean, val dispOffset: Int)

private class ModRm(val nextPos: Int, val ripRelative: Boolean, val dispOffset: Int)

object InstructionDecoder {
    private val LEGACY_PREFIXES =
        setOf(0x66, 0x67, 0xF0, 0xF2, 0xF3, 0x2E, 0x36, 0x3E, 0x26, 0x64, 0x65)

    private val MODRM_ONE_BYTE = setOf(
        0x00, 0x01, 0x02, 0x03, 0x08, 0x09, 0x0A, 0x0B, 0x10, 0x11, 0x12, 0x13,
        0x18, 0x19, 0x1A, 0x1B, 0x20, 0x21, 0x22, 0x23, 0x28, 0x29, 0x2A, 0x2B,
        0x30, 0x31, 0x32, 0x33, 0x38, 0x39, 0x3A, 0x3B, 0x62, 0x63, 0x69, 0x6B,
        0x80, 0x81, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C,
        0x8D, 0x8E, 0x8F, 0xC0, 0xC1, 0xC6, 0xC7, 0xD0, 0xD1, 0xD2, 0xD3, 0xD8,
        0xD9, 0xDA, 0xDB, 0xDC, 0xDD, 0xDE, 0xDF, 0xF6, 0xF7, 0xFE, 0xFF,
    )
    private val MODRM_IMM8 = setOf(0x6B, 0x80, 0x83, 0xC0, 0xC1, 0xC6)
    private val MODRM_IMM32 = setOf(0x69, 0x81, 0xC7)

    private val MODRM_TWO_BYTE = setOf(
        0x10, 0x11, 0x28, 0x29, 0x2E, 0x2F, 0x1E, 0x1F, 0xD6, 0xEF,
        0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47,
        0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F,
        0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57,
        0x58, 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F,
        0x6E, 0x6F, 0x7E, 0x7F,
    )
    private val MODRM_TWO_BYTE_IMM8 = setOf(0x70, 0x71, 0x72, 0x73, 0xBA, 0xC2, 0xC4, 0xC5, 0xC6)

    fun decodeX64(bytes: ByteArray, offset: Int): DecodeResult? {
        var i = offset
        var has66 = false
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b !in LEGACY_PREFIXES) break
            if (b == 0x66) has66 = true
            i++
        }

        var rexW = false
        if (i < bytes.size && ((bytes[i].toInt() and 0xFF) and 0xF0) == 0x40) {
            rexW = ((bytes[i].toInt() and 0xFF) and 0x08) != 0
            i++
        }
        if (i >= bytes.size) return null

        var op = bytes[i].toInt() and 0xFF
        i++

        var immLen = 0
        var usesModRm = false

        if (op == 0x0F) {
            if (i >= bytes.size) return null
            op = bytes[i].toInt() and 0xFF
            i++
            when (op) {
                in MODRM_TWO_BYTE -> usesModRm = true
                in MODRM_TWO_BYTE_IMM8 -> {
                    usesModRm = true
                    immLen = 1
                }
                else -> return null
            }
        } else {
            when (op) {
                in MODRM_ONE_BYTE -> {
                    usesModRm = true
                    immLen = when {
                        op in MODRM_IMM8 -> 1
                        op in MODRM_IMM32 -> 4
                        else -> 0
                    }
                    if (op == 0xF6 || op == 0xF7) {
                        if (i >= bytes.size) return null
                        val reg = ((bytes[i].toInt() and 0xFF) ushr 3) and 7
                        immLen = if (reg <= 1) (if (op == 0xF6) 1 else 4) else 0
                    }
                }
                in 0x50..0x5F -> immLen = 0
                0x90, 0x98, 0x99, 0xC3, 0xC9, 0xCC, 0xF4, 0x9C, 0x9D -> immLen = 0
                0x68 -> immLen = 4
                0x6A -> immLen = 1
                in 0xB0..0xB7 -> immLen = 1
                in 0xB8..0xBF -> immLen = if (rexW) 8 else if (has66) 2 else 4
                in 0x70..0x7F -> immLen = 1
                0xE8, 0xE9 -> immLen = 4
                0xEB -> immLen = 1
                in 0xE0..0xE3 -> immLen = 1
                0xC2, 0xCA -> immLen = 2
                0xCD -> immLen = 1
                else -> return null
            }
        }

        if (!usesModRm) {
            val end = i + immLen
            if (end > bytes.size) return null
            return DecodeResult(end - offset, ripRelative = false, dispOffset = -1)
        }

        val modRm = decodeModRm(bytes, i) ?: return null
        val end = modRm.nextPos + immLen
        if (end > bytes.size) return null
        val disp = if (modRm.dispOffset < 0) -1 else modRm.dispOffset - offset
        return DecodeResult(end - offset, modRm.ripRelative, disp)
    }

    private fun decodeModRm(bytes: ByteArray, pos: Int): ModRm? {
        if (pos >= bytes.size) return null
        val modrm = bytes[pos].toInt() and 0xFF
        val mod = modrm ushr 6
        val rm = modrm and 7
        var p = pos + 1
        var ripRelative = false
        var dispOffset = -1

        if (mod != 3 && rm == 4) {
            if (p >= bytes.size) return null
            val sib = bytes[p].toInt() and 0xFF
            val base = sib and 7
            p++
            if (mod == 0 && base == 5) {
                dispOffset = p
                p += 4
            }
        }
        when (mod) {
            0 -> if (rm == 5) {
                dispOffset = p
                ripRelative = true
                p += 4
            }
            1 -> {
                dispOffset = p
                p += 1
            }
            2 -> {
                dispOffset = p
                p += 4
            }
        }
        if (p > bytes.size) return null
        return ModRm(p, ripRelative, dispOffset)
    }
}
