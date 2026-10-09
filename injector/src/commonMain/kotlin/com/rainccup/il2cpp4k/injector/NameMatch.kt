package com.rainccup.il2cpp4k.injector

/** `--name`：忽略大小写的精确匹配，允许一侧带/不带 `.exe` 后缀。 */
fun matchesExactName(processName: String, wanted: String): Boolean =
    processName.equals(wanted, ignoreCase = true) ||
        stripExe(processName).equals(stripExe(wanted), ignoreCase = true)

/** `--pattern`：忽略大小写的子串匹配。 */
fun matchesPattern(processName: String, pattern: String): Boolean =
    processName.contains(pattern, ignoreCase = true)

private fun stripExe(name: String): String =
    if (name.length > 4 && name.regionMatches(name.length - 4, ".exe", 0, 4, ignoreCase = true)) {
        name.dropLast(4)
    } else {
        name
    }
