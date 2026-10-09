@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.logging

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv

internal actual fun appendFileLine(path: String, line: String): Boolean {
    val file = fopen(path, "ab") ?: return false
    try {
        val bytes = "$line\n".encodeToByteArray()
        bytes.usePinned { pinned ->
            return fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file) == bytes.size.toULong()
        }
    } finally {
        fclose(file)
    }
}

actual fun defaultLogFilePath(): String {
    val dir = getenv("TMPDIR")?.toKString()?.takeIf { it.isNotEmpty() } ?: "/data/local/tmp"
    return if (dir.endsWith("/")) "${dir}il2cpp4k.log" else "$dir/il2cpp4k.log"
}
