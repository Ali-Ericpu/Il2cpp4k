package com.rainccup.il2cpp4k.core

// 通用寄存器：x0..x30 = 0..30，sp = 31；浮点寄存器 d0..d31 = 0..31（独立编号空间）
internal class Arm64Emitter {
    private val out = ArrayList<Byte>(256)

    fun byte(v: Int) { out.add(v.toByte()) }
    fun word(w: Int) { for (i in 0 until 4) byte((w ushr (8 * i)) and 0xFF) }
    fun quad(value: Long) { for (i in 0 until 8) byte(((value ushr (8 * i)) and 0xFF).toInt()) }
    fun toByteArray(): ByteArray = out.toByteArray()

    /** movz xD, #imm16, lsl #(16*hw) */
    fun movz(rd: Int, imm16: Int, hw: Int = 0) =
        word(0xD2800000.toInt() or (hw shl 21) or (imm16 shl 5) or rd)

    /** movk xD, #imm16, lsl #(16*hw) */
    fun movk(rd: Int, imm16: Int, hw: Int) =
        word(0xF2800000.toInt() or (hw shl 21) or (imm16 shl 5) or rd)

    /** 64 位常量装入寄存器：movz + 3×movk */
    fun movImm64(rd: Int, value: Long) {
        movz(rd, (value and 0xFFFF).toInt(), 0)
        movk(rd, ((value ushr 16) and 0xFFFF).toInt(), 1)
        movk(rd, ((value ushr 32) and 0xFFFF).toInt(), 2)
        movk(rd, ((value ushr 48) and 0xFFFF).toInt(), 3)
    }

    /** stp xA, xB, [xN + imm]（signed offset，imm 为 8 的倍数） */
    fun stpX(rt: Int, rt2: Int, rn: Int, imm: Int) =
        word(0xA9000000.toInt() or ((imm / 8) shl 15) or (rt2 shl 10) or (rn shl 5) or rt)

    /** stp xA, xB, [xN + imm]!（pre-index） */
    fun stpXPre(rt: Int, rt2: Int, rn: Int, imm: Int) =
        word(0xA9800000.toInt() or (((imm / 8) and 0x7F) shl 15) or (rt2 shl 10) or (rn shl 5) or rt)

    /** ldp xA, xB, [xN], imm（post-index） */
    fun ldpXPost(rt: Int, rt2: Int, rn: Int, imm: Int) =
        word(0xA8C00000.toInt() or (((imm / 8) and 0x7F) shl 15) or (rt2 shl 10) or (rn shl 5) or rt)

    /** ldr xT, [xN + imm]（imm 为 8 的倍数） */
    fun ldrX(rt: Int, rn: Int, imm: Int) =
        word(0xF9400000.toInt() or ((imm / 8) shl 10) or (rn shl 5) or rt)

    /** str xT, [xN + imm] */
    fun strX(rt: Int, rn: Int, imm: Int) =
        word(0xF9000000.toInt() or ((imm / 8) shl 10) or (rn shl 5) or rt)

    /** ldr dT, [xN + imm] */
    fun ldrD(rt: Int, rn: Int, imm: Int) =
        word(0xFD400000.toInt() or ((imm / 8) shl 10) or (rn shl 5) or rt)

    /** str dT, [xN + imm] */
    fun strD(rt: Int, rn: Int, imm: Int) =
        word(0xFD000000.toInt() or ((imm / 8) shl 10) or (rn shl 5) or rt)

    /** mov xD, xS（orr xD, xzr, xS） */
    fun movReg(rd: Int, rs: Int) = word(0xAA0003E0.toInt() or (rs shl 16) or rd)

    /** br xN */
    fun br(rn: Int) = word(0xD61F0000.toInt() or (rn shl 5))

    /** blr xN */
    fun blr(rn: Int) = word(0xD63F0000.toInt() or (rn shl 5))

    /** ret */
    fun ret() = word(0xD65F03C0.toInt())

    /** ldr xT, [pc + imm]（字面量加载，imm 为 4 的倍数） */
    fun ldrLiteral(rt: Int, imm: Int) = word(0x58000000.toInt() or ((imm / 4) shl 5) or rt)
}

object Arm64StubGenerator : StubGenerator {
    private const val SP = 31
    private const val X9 = 9
    private const val X10 = 10
    private const val X16 = 16
    private const val X19 = 19
    private const val X20 = 20

    override val patchLength: Int = 16

    /** `ldr x16, [pc,#8]; br x16; .quad target`：x16 是 ABI 规定的 IP0 暂存寄存器，不承载返回值。 */
    override val jumpPatchLength: Int = 16

    override fun entryPatch(target: Long): ByteArray = Arm64Emitter().apply {
        ldrLiteral(X16, 8)
        br(X16)
        quad(target)
    }.toByteArray()

    override fun jumpPatch(target: Long): ByteArray = entryPatch(target)

    override fun entryStub(
        cc: CallingConvention,
        ctxAddr: Long,
        hookId: Long,
        invokerAddr: Long,
    ): ByteArray {
        val e = Arm64Emitter()
        e.stpXPre(29, 30, SP, -16) // 保护帧指针与返回地址
        e.movImm64(X9, ctxAddr)
        for (i in 0 until 4) {
            e.stpX(i * 2, i * 2 + 1, X9, (cc.gprBase + i * 16L).toInt()) // x0..x7
        }
        for (i in 0 until 8) {
            e.strD(i, X9, (cc.fprBase + i * 8L).toInt()) // d0..d7
        }
        for (i in 0 until cc.maxStackSlots) {
            e.ldrX(X10, SP, 16 + i * 8) // 栈参数位于 [sp + 16 + i*8]
            e.strX(X10, X9, (cc.stackBase + i * 8L).toInt())
        }
        e.movImm64(0, hookId)
        e.movImm64(1, ctxAddr)
        e.movImm64(X16, invokerAddr)
        e.blr(X16)
        e.movImm64(X9, ctxAddr)
        e.ldrX(0, X9, cc.retIntOffset.toInt())
        e.ldrD(0, X9, cc.retFloatOffset.toInt())
        e.ldpXPost(29, 30, SP, 16)
        e.ret()
        return e.toByteArray()
    }

    override fun callThunk(cc: CallingConvention, target: Long): ByteArray {
        val e = Arm64Emitter()
        e.stpXPre(X19, X20, SP, -16) // 保护 callee-saved
        e.stpXPre(29, 30, SP, -16)
        e.movReg(X19, 0) // x19 = ctx（x0 入参）
        for (i in 0 until 8) {
            e.ldrX(i, X19, (cc.gprBase + i * 8L).toInt())
        }
        for (i in 0 until 8) {
            e.ldrD(i, X19, (cc.fprBase + i * 8L).toInt())
        }
        e.movImm64(X16, target)
        e.blr(X16)
        e.strX(0, X19, cc.retIntOffset.toInt())
        e.strD(0, X19, cc.retFloatOffset.toInt())
        e.ldpXPost(29, 30, SP, 16)
        e.ldpXPost(X19, X20, SP, 16)
        e.ret()
        return e.toByteArray()
    }

    override fun copyPrologue(target: Long, destAddr: Long, patchLength: Int): Copied {
        val count = (patchLength + 3) / 4 * 4
        val raw = Memory.readBytes(target, count)
        var i = 0
        while (i < count) {
            if (isPcRelative(readIntLe(raw, i))) {
                throw HookInstallException("pc-relative instruction at 0x${(target + i).toString(16)}")
            }
            i += 4
        }
        return Copied(raw, count)
    }

    /** v1 无法在 trampoline 中安全重定位的 pc-relative 指令族。 */
    private fun isPcRelative(word: Int): Boolean = when {
        (word and 0x9F000000.toInt()) == 0x10000000 -> true // ADR
        (word and 0x9F000000.toInt()) == 0x90000000.toInt() -> true // ADRP
        (word and 0x3B000000) == 0x18000000 -> true // LDR（字面量）
        (word and 0x7C000000.toInt()) == 0x14000000 -> true // B / BL
        (word and 0xFF000010.toInt()) == 0x54000000 -> true // B.cond
        (word and 0x7E000000) == 0x34000000 -> true // CBZ / CBNZ
        (word and 0x7E000000) == 0x36000000 -> true // TBZ / TBNZ
        else -> false
    }
}
