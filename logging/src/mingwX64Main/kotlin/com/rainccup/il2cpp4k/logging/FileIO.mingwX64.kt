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
    // "ab"：二进制追加，不做换行翻译，行尾保持 LF（与 ODS/DBWin 约定一致）
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
    // TEMP 偶带结尾反斜杠（如 "C:\...\Temp\"），拼接时归一
    val dir = getenv("TEMP")?.toKString()
        ?: getenv("TMP")?.toKString()
        ?: "."
    return if (dir.endsWith("\\") || dir.endsWith("/")) "${dir}il2cpp4k.log" else "$dir\\il2cpp4k.log"
}
