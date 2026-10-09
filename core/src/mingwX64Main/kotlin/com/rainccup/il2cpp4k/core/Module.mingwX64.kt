package com.rainccup.il2cpp4k.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import platform.windows.GetModuleHandleA
import platform.windows.GetProcAddress

@OptIn(ExperimentalForeignApi::class)
actual object Module {
    actual fun base(name: String?): Long = memScoped {
        val handle = if (name == null) GetModuleHandleA(null) else GetModuleHandleA(name.cstr.ptr)
        handle?.toLong() ?: 0L
    }

    actual fun symbol(module: String?, name: String): Long? = memScoped {
        val handle = if (module == null) GetModuleHandleA(null) else GetModuleHandleA(module.cstr.ptr)
        if (handle == null) return null
        val proc = GetProcAddress(handle, name.cstr.ptr) ?: return null
        proc.toLong()
    }
}
