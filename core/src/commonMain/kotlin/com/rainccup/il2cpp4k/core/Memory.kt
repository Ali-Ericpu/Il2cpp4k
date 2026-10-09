package com.rainccup.il2cpp4k.core

expect object Memory {
    /** 分配内存并返回地址；失败返回 0。[exec] 为 true 时分配可执行内存。 */
    fun alloc(size: Long, exec: Boolean): Long

    fun free(addr: Long)

    fun readBytes(addr: Long, size: Int): ByteArray

    fun writeBytes(addr: Long, bytes: ByteArray)

    fun protectExec(addr: Long, size: Long)

    fun protectRW(addr: Long, size: Long)
}
