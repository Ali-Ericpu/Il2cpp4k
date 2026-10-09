package com.rainccup.il2cpp4k.injector

/**
 * arm64 `struct user_pt_regs` 字段布局（`PTRACE_GETREGSET`/`SETREGSET` 的 `NT_PRSTATUS` 缓冲）：
 * `regs[31]`(x0..x30) + sp + pc + pstate，共 34 个 u64。
 */
object Regs {
    const val X0 = 0
    const val X7 = 7
    const val LR = 30
    const val SP = 31
    const val PC = 32
    const val PSTATE = 33
    const val COUNT = 34
    const val SIZE_BYTES = COUNT * 8

    fun encode(values: LongArray): ByteArray {
        require(values.size == COUNT) { "expected $COUNT registers, got ${values.size}" }
        val out = ByteArray(SIZE_BYTES)
        for (i in 0 until COUNT) {
            var value = values[i]
            for (b in 0 until 8) {
                out[i * 8 + b] = (value and 0xFF).toByte()
                value = value ushr 8
            }
        }
        return out
    }

    fun decode(bytes: ByteArray): LongArray {
        require(bytes.size >= SIZE_BYTES) { "expected at least $SIZE_BYTES bytes, got ${bytes.size}" }
        val out = LongArray(COUNT)
        for (i in 0 until COUNT) {
            var value = 0L
            for (b in 0 until 8) {
                value = value or ((bytes[i * 8 + b].toLong() and 0xFF) shl (8 * b))
            }
            out[i] = value
        }
        return out
    }
}
