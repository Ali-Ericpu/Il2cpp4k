package com.rainccup.il2cpp4k.injector

class MapEntry(val start: Long, val end: Long, val permissions: String, val path: String)

private val MAPS_SEPARATOR = Regex("\\s+")

fun parseMaps(text: String): List<MapEntry> =
    text.lineSequence().mapNotNull { parseMapLine(it) }.toList()

/** 一行格式：`start-end perms offset dev inode path`；path 可含空格，缺失表示匿名映射。 */
private fun parseMapLine(line: String): MapEntry? {
    val parts = line.trim().split(MAPS_SEPARATOR, limit = 6)
    if (parts.size < 5) return null
    val range = parts[0].split('-')
    if (range.size != 2) return null
    val start = range[0].toLongOrNull(16) ?: return null
    val end = range[1].toLongOrNull(16) ?: return null
    val path = if (parts.size >= 6) parts[5].removeSuffix(" (deleted)") else ""
    return MapEntry(start, end, parts[1], path)
}

private fun matchesModule(entry: MapEntry, moduleName: String): Boolean =
    entry.path.isNotEmpty() && entry.path.substringAfterLast('/') == moduleName

/** 模块在进程中的基址：同名映射的最低 start。 */
fun moduleBase(entries: List<MapEntry>, moduleName: String): Long? =
    entries.filter { matchesModule(it, moduleName) }.minOfOrNull { it.start }

fun isModuleLoaded(entries: List<MapEntry>, moduleName: String): Boolean =
    entries.any { matchesModule(it, moduleName) }
