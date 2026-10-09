package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.*
import platform.posix.*

/** RTLD_NOLOAD：仅复用已加载模块，避免重复加载。 */
private const val RTLD_NOLOAD = 0x4

@OptIn(ExperimentalForeignApi::class)
actual object Module {
    actual fun base(name: String?): Long {
        val target = name ?: return 0L
        val file = fopen("/proc/self/maps", "r") ?: return 0L
        val line = ByteArray(512)
        var lowest = Long.MAX_VALUE
        while (true) {
            val read = fgets(line.refTo(0), line.size, file) ?: break
            val text = read.toKString()
            if (!text.contains(target)) continue
            val value = text.substringBefore('-').trim().toLongOrNull(16) ?: continue
            if (value in 1 until lowest) lowest = value
        }
        fclose(file)
        return if (lowest == Long.MAX_VALUE) 0L else lowest
    }

    actual fun symbol(module: String?, name: String): Long? {
        val handle = memScoped {
            if (module == null) {
                dlopen(null, RTLD_NOW)
            } else {
                dlopen(module.cstr.ptr, RTLD_NOW or RTLD_NOLOAD)
            }
        } ?: return null
        val sym = memScoped { dlsym(handle, name.cstr.ptr) } ?: return null
        return sym.toLong()
    }
}
