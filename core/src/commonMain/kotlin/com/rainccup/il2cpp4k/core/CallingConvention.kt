package com.rainccup.il2cpp4k.core

enum class ParamTrack { GPR, FPR }

/** 参数槽位：track 决定寄存器轨道；isStack 为 true 时 index 表示栈槽序号。 */
data class ArgSlot(val track: ParamTrack, val index: Int, val isStack: Boolean)

/**
 * 上下文缓冲区布局（所有字段 8 字节对齐）：
 *   gprBase        : gprArgCount * 8
 *   fprBase        : fprArgCount * 8
 *   stackBase      : maxStackSlots * 8
 *   retIntOffset   : 8（返回值整型槽）
 *   retFloatOffset : 8（返回值浮点槽）
 *   ctxSize
 */
class CallingConvention(
    val name: String,
    val gprArgCount: Int,
    val fprArgCount: Int,
    val maxStackSlots: Int = 8,
) {
    val gprBase: Long = 0L
    val fprBase: Long = gprArgCount * 8L
    val stackBase: Long = fprBase + fprArgCount * 8L
    val retIntOffset: Long = stackBase + maxStackSlots * 8L
    val retFloatOffset: Long = retIntOffset + 8L
    val ctxSize: Long = retFloatOffset + 8L

    fun offsetOf(slot: ArgSlot): Long = when {
        slot.isStack -> stackBase + slot.index * 8L
        slot.track == ParamTrack.GPR -> gprBase + slot.index * 8L
        else -> fprBase + slot.index * 8L
    }

    /** 按声明顺序分配槽位：同轨道寄存器用尽后落入栈槽（栈槽按声明顺序编号）。 */
    fun assignSlots(tracks: List<ParamTrack>): List<ArgSlot> {
        var gpr = 0
        var fpr = 0
        var stack = 0
        return tracks.map { track ->
            when {
                track == ParamTrack.GPR && gpr < gprArgCount -> ArgSlot(track, gpr++, false)
                track == ParamTrack.FPR && fpr < fprArgCount -> ArgSlot(track, fpr++, false)
                else -> ArgSlot(track, stack++, true)
            }
        }
    }
}

val WINDOWS_X64: CallingConvention = CallingConvention("win-x64", gprArgCount = 4, fprArgCount = 4)
val ANDROID_ARM64: CallingConvention = CallingConvention("aarch64", gprArgCount = 8, fprArgCount = 8)

expect val platformCallingConvention: CallingConvention
