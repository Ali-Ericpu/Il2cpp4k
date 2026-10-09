package com.rainccup.il2cpp4k.core

/** 拷贝指令可能跨越补丁边界，最多多拷贝一条最长指令（x64 指令最长 15 字节）。 */
private const val MAX_INSTRUCTION_OVERSHOOT = 16

/**
 * 单次 inline hook 的安装/卸载：
 *  - [prepare] 构建 trampoline（拷贝原函数头部指令 + 重定位相对寻址 + 跳回续接点），
 *    之后 [trampoline] 可当作原函数直接调用；
 *  - [install] 用入口补丁把目标函数入口跳到 [stubAddr]；
 *  - [unhook] 恢复原始字节并释放 trampoline。
 */
class InlineHook internal constructor(
    val target: Long,
    private val cc: CallingConvention,
    private val stubGen: StubGenerator,
) {
    val patchLength: Int get() = stubGen.patchLength

    /** 可当作原函数调用的地址（prepare 之后有效）。 */
    var trampoline: Long = 0L
        private set

    var copiedPrologueLength: Int = 0
        private set

    private var originalBytes: ByteArray? = null
    private var trampolineSize: Long = 0L
    private var patched: Boolean = false

    fun prepare() {
        if (trampoline != 0L) return
        originalBytes = Memory.readBytes(target, patchLength)
        val size = (patchLength + MAX_INSTRUCTION_OVERSHOOT + stubGen.jumpPatchLength).toLong()
        val dest = Memory.alloc(size, exec = true)
        if (dest == 0L) throw HookInstallException("failed to allocate trampoline memory")
        trampolineSize = size
        try {
            val copied = stubGen.copyPrologue(target, dest, patchLength)
            copiedPrologueLength = copied.byteCount
            Memory.writeBytes(dest, copied.bytes)
            Memory.writeBytes(dest + copied.byteCount, stubGen.jumpPatch(target + copied.byteCount))
            Memory.protectExec(dest, size)
        } catch (e: Throwable) {
            Memory.free(dest)
            trampolineSize = 0L
            throw e
        }
        trampoline = dest
    }

    fun install(stubAddr: Long) {
        if (trampoline == 0L) throw HookInstallException("prepare() must be called before install()")
        if (patched) return
        Memory.protectRW(target, patchLength.toLong())
        Memory.writeBytes(target, stubGen.entryPatch(stubAddr))
        Memory.protectExec(target, patchLength.toLong())
        patched = true
    }

    fun unhook() {
        if (patched) {
            Memory.protectRW(target, patchLength.toLong())
            originalBytes?.let { Memory.writeBytes(target, it) }
            Memory.protectExec(target, patchLength.toLong())
            patched = false
        }
        if (trampoline != 0L) {
            Memory.free(trampoline)
            trampoline = 0L
            trampolineSize = 0L
        }
    }
}
