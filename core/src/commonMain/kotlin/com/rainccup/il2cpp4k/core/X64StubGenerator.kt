package com.rainccup.il2cpp4k.core

// 寄存器编号：rax0 rcx1 rdx2 rbx3 rsp4 rbp5 rsi6 rdi7 r8=8 r9=9 r10=10 r11=11 r12=12
internal class X64Emitter {
    private val out = ArrayList<Byte>(256)

    fun byte(v: Int) { out.add(v.toByte()) }
    fun bytes(vararg v: Int) { v.forEach { byte(it) } }
    fun imm32(v: Int) { for (i in 0 until 4) byte((v ushr (8 * i)) and 0xFF) }
    fun imm64(v: Long) { for (i in 0 until 8) byte(((v ushr (8 * i)) and 0xFF).toInt()) }
    fun toByteArray(): ByteArray = out.toByteArray()

    /** mov r64, imm64 */
    fun movRegImm64(reg: Int, value: Long) {
        bytes(0x48 or (if (reg >= 8) 1 else 0), 0xB8 + (reg and 7))
        imm64(value)
    }

    /** mov [base + disp32], reg64（base 不得为 rsp/r12） */
    fun movMemReg(base: Int, disp: Int, reg: Int) {
        byte(0x48 or (((reg ushr 3) and 1) shl 2) or ((base ushr 3) and 1))
        byte(0x89)
        byte(0x80 or ((reg and 7) shl 3) or (base and 7))
        imm32(disp)
    }

    /** mov reg64, [base + disp32]（base 不得为 rsp/r12） */
    fun movRegMem(reg: Int, base: Int, disp: Int) {
        byte(0x48 or (((reg ushr 3) and 1) shl 2) or ((base ushr 3) and 1))
        byte(0x8B)
        byte(0x80 or ((reg and 7) shl 3) or (base and 7))
        imm32(disp)
    }

    /** mov reg64, [rsp + disp32]（带 SIB） */
    fun movRegRspDisp(reg: Int, disp: Int) {
        byte(0x48 or (((reg ushr 3) and 1) shl 2))
        byte(0x8B)
        byte(0x80 or ((reg and 7) shl 3) or 4)
        byte(0x24)
        imm32(disp)
    }

    /** movsd [base + disp32], xmmN */
    fun movsdMemXmm(base: Int, disp: Int, xmm: Int) {
        val rex = (((xmm ushr 3) and 1) shl 2) or ((base ushr 3) and 1)
        if (rex != 0) byte(0x40 or rex)
        bytes(0xF2, 0x0F, 0x11)
        byte(0x80 or ((xmm and 7) shl 3) or (base and 7))
        imm32(disp)
    }

    /** movsd xmmN, [base + disp32] */
    fun movsdXmmMem(xmm: Int, base: Int, disp: Int) {
        val rex = (((xmm ushr 3) and 1) shl 2) or ((base ushr 3) and 1)
        if (rex != 0) byte(0x40 or rex)
        bytes(0xF2, 0x0F, 0x10)
        byte(0x80 or ((xmm and 7) shl 3) or (base and 7))
        imm32(disp)
    }

    /** mov dst64, src64（寄存器间传送） */
    fun movRegReg(dst: Int, src: Int) {
        byte(0x48 or (((src ushr 3) and 1) shl 2) or ((dst ushr 3) and 1))
        byte(0x89)
        byte(0xC0 or ((src and 7) shl 3) or (dst and 7))
    }

    fun push(reg: Int) { if (reg >= 8) byte(0x41); byte(0x50 + (reg and 7)) }
    fun pop(reg: Int) { if (reg >= 8) byte(0x41); byte(0x58 + (reg and 7)) }
    fun subRspImm8(imm: Int) { bytes(0x48, 0x83, 0xEC, imm) }
    fun addRspImm8(imm: Int) { bytes(0x48, 0x83, 0xC4, imm) }
    fun callReg(reg: Int) { if (reg >= 8) byte(0x41); bytes(0xFF, 0xD0 + (reg and 7)) }
    fun ret() { byte(0xC3) }
}

object X64StubGenerator : StubGenerator {
    private const val RAX = 0
    private const val RCX = 1
    private const val RDX = 2
    private const val RBX = 3
    private const val R10 = 10

    /** win x64 通用寄存器参数顺序 */
    private val GPR_ARGS = intArrayOf(RCX, RDX, 8, 9)

    override val patchLength: Int = 12

    /**
     * trampoline 尾部跳回用 `jmp qword ptr [rip+0]` + 8 字节绝对地址（14 字节）：
     * 不破坏任何寄存器。入口补丁可以用 `mov rax`（入口处 rax 无活跃值），
     * 但跳回点位于函数体中部，rax 可能承载拷贝区算出的返回值，必须保持不动。
     */
    override val jumpPatchLength: Int = 14

    override fun entryPatch(target: Long): ByteArray = X64Emitter().apply {
        movRegImm64(RAX, target)
        bytes(0xFF, 0xE0) // jmp rax
    }.toByteArray()

    override fun jumpPatch(target: Long): ByteArray = X64Emitter().apply {
        bytes(0xFF, 0x25, 0x00, 0x00, 0x00, 0x00) // jmp qword ptr [rip+0]
        imm64(target)
    }.toByteArray()

    override fun entryStub(
        cc: CallingConvention,
        ctxAddr: Long,
        hookId: Long,
        invokerAddr: Long,
    ): ByteArray {
        val e = X64Emitter()
        e.movRegImm64(RAX, ctxAddr) // rax = ctx
        GPR_ARGS.forEachIndexed { i, reg ->
            e.movMemReg(RAX, (cc.gprBase + i * 8L).toInt(), reg)
        }
        repeat(4) { i ->
            e.movsdMemXmm(RAX, (cc.fprBase + i * 8L).toInt(), i)
        }
        for (i in 0 until cc.maxStackSlots) {
            e.movRegRspDisp(R10, 8 + i * 8) // r10 = [rsp + 8 + i*8]
            e.movMemReg(RAX, (cc.stackBase + i * 8L).toInt(), R10)
        }
        e.movRegImm64(RCX, hookId) // rcx = hookId
        e.movRegReg(RDX, RAX)      // rdx = ctx
        e.subRspImm8(0x28)         // shadow space + 对齐
        e.movRegImm64(RAX, invokerAddr)
        e.callReg(RAX)
        e.addRspImm8(0x28)
        e.movRegImm64(RAX, ctxAddr)
        e.movsdXmmMem(0, RAX, cc.retFloatOffset.toInt()) // xmm0 = 浮点返回
        e.movRegMem(RAX, RAX, cc.retIntOffset.toInt())   // rax = 整型返回
        e.ret()
        return e.toByteArray()
    }

    override fun callThunk(cc: CallingConvention, target: Long): ByteArray {
        val e = X64Emitter()
        e.push(RBX)               // 保护 callee-saved
        e.movRegReg(RBX, RCX)     // rbx = ctx（rcx 入参）
        GPR_ARGS.forEachIndexed { i, reg ->
            e.movRegMem(reg, RBX, (cc.gprBase + i * 8L).toInt())
        }
        repeat(4) { i ->
            e.movsdXmmMem(i, RBX, (cc.fprBase + i * 8L).toInt())
        }
        e.subRspImm8(0x20)        // shadow space
        e.movRegImm64(RAX, target)
        e.callReg(RAX)
        e.addRspImm8(0x20)
        e.movsdMemXmm(RBX, cc.retFloatOffset.toInt(), 0)
        e.movMemReg(RBX, cc.retIntOffset.toInt(), RAX)
        e.pop(RBX)
        e.ret()
        return e.toByteArray()
    }

    override fun copyPrologue(target: Long, destAddr: Long, patchLength: Int): Copied {
        val raw = Memory.readBytes(target, patchLength + 16)
        val out = ByteArray(patchLength + 16)
        var copied = 0
        while (copied < patchLength) {
            val decoded = InstructionDecoder.decodeX64(raw, copied)
                ?: throw HookInstallException("unsupported instruction at 0x${(target + copied).toString(16)}")
            val instr = raw.copyOfRange(copied, copied + decoded.length)
            if (decoded.ripRelative) {
                val oldDisp = readIntLe(instr, decoded.dispOffset)
                val effective = target + copied + decoded.length + oldDisp
                val newDisp = effective - (destAddr + copied + decoded.length)
                if (newDisp < Int.MIN_VALUE || newDisp > Int.MAX_VALUE) {
                    throw HookInstallException("rip-relative overflow at 0x${(target + copied).toString(16)}")
                }
                writeIntLe(instr, decoded.dispOffset, newDisp.toInt())
            }
            instr.copyInto(out, copied)
            copied += decoded.length
        }
        return Copied(out.copyOf(copied), copied)
    }
}
