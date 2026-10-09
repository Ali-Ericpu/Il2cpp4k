package com.rainccup.il2cpp4k.injector

/**
 * 解析「按名取符号」所需的两项信息：模块在目标进程中的基址，以及本地同名 ELF 文件路径。
 * 远程符号地址 = 基址 + `Elf.symbolOffset(本地ELF字节, 符号名)`。
 */
fun symbolSource(entries: List<MapEntry>, moduleName: String): Pair<Long, String>? {
    val base = moduleBase(entries, moduleName) ?: return null
    val path = entries
        .firstOrNull { it.path.isNotEmpty() && it.path.substringAfterLast('/') == moduleName }
        ?.path
        ?: return null
    return base to path
}
