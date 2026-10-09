package com.rainccup.il2cpp4k.injector

/** 候选目标进程；[il2cppModule] 为已加载的 il2cpp 库文件名，未加载为 null。 */
data class TargetProcess(val pid: Int, val name: String, val il2cppModule: String?)

/** 列出全部进程；[withIl2cppFlag] 为 true 时逐进程检查 il2cpp 模块（较慢）。 */
internal expect fun listAllProcesses(withIl2cppFlag: Boolean): List<TargetProcess>

/** 加载 [libPath] 进目标进程；[entry] 非空时再调用其导出符号。返回模块标识（HMODULE / dlopen handle）。 */
internal expect fun injectAndCall(
    pid: Int,
    libPath: String,
    entry: String?,
    timeoutMs: Int,
    verbose: Boolean,
): Long

/** 目标进程是否已加载指定模块（按模块文件名比对，忽略大小写）。 */
internal expect fun isModuleLoaded(pid: Int, moduleName: String): Boolean

/** `list` 命令语义：无 pattern 只列 Unity 候选（已加载 il2cpp 库）；有 pattern 按名字子串过滤。 */
internal fun listTargets(pattern: String?): List<TargetProcess> {
    val all = listAllProcesses(withIl2cppFlag = true)
    return if (pattern == null) {
        all.filter { it.il2cppModule != null }
    } else {
        all.filter { matchesPattern(it.name, pattern) }
    }
}

/** 按 pid / name / pattern 解析唯一目标；0 个或多命中抛 [InjectFailure]。 */
internal fun resolveTarget(pid: Int?, name: String?, pattern: String?): TargetProcess {
    val candidates = when {
        pid != null -> listAllProcesses(withIl2cppFlag = false).filter { it.pid == pid }
        name != null -> listAllProcesses(withIl2cppFlag = false).filter { matchesExactName(it.name, name) }
        pattern != null -> listAllProcesses(withIl2cppFlag = false).filter { matchesPattern(it.name, pattern) }
        else -> emptyList()
    }
    if (candidates.isEmpty()) {
        val what = pid?.let { "pid $it" } ?: name?.let { "name $it" } ?: "pattern $pattern"
        throw InjectFailure(InjectError.TARGET_NOT_FOUND, "no process matched $what")
    }
    if (candidates.size > 1) {
        val list = candidates.joinToString(", ") { "${it.pid}:${it.name}" }
        throw InjectFailure(InjectError.TARGET_NOT_FOUND, "multiple processes matched: $list (use --pid)")
    }
    return candidates.first()
}
