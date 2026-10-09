@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import platform.posix.fclose
import platform.posix.fopen

/** 在若干候选工作目录下查找构建产物。 */
internal fun findArtifact(relative: String): String? =
    listOf("", "../", "../../", "../../../")
        .flatMap { prefix -> listOf(prefix + relative, prefix + relative.removePrefix("injector/")) }
        .firstOrNull { fileExists(it) }

internal fun fileExists(path: String): Boolean {
    val file = fopen(path, "rb") ?: return false
    fclose(file)
    return true
}

/** 找不到产物时以「如何构建」的错误信息失败。 */
internal fun requireArtifact(relative: String, howToBuild: String): String =
    findArtifact(relative) ?: error("missing $relative — run: $howToBuild")
