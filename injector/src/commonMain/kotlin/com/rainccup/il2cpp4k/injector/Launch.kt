package com.rainccup.il2cpp4k.injector

/** 等待目标模块出现时两次探测之间的间隔。 */
internal const val POLL_INTERVAL_MS = 200L

/** 启动 [exePath] 并返回其 pid；[args] 原样追加到命令行，[workingDir] 为初始工作目录。 */
internal expect fun launchProcess(exePath: String, args: String?, workingDir: String?): Int

/**
 * 启动 [exePath] 并**保持主线程挂起**（CREATE_SUSPENDED）：主线程尚未执行任何一条指令。
 *
 * spawn 模式的注入窗口由此而来 —— 模块在目标「诞生」时映射，反作弊用户态组件
 * 初始化时看到的是一个已存在的模块，而不是运行中途突然出现的加载事件。
 */
internal expect fun launchProcessSuspended(exePath: String, args: String?, workingDir: String?): Int

/** 恢复 [pid] 的主线程（配合 [launchProcessSuspended]）。 */
internal expect fun resumeProcess(pid: Int)

/** 在已注入 [libPath] 的目标内单独调用导出入口（劫持目标线程；与 `--call-entry` 同机制）。 */
internal expect fun callExportedEntry(pid: Int, libPath: String, entry: String, timeoutMs: Int, verbose: Boolean)

/** 进程是否仍在运行。 */
internal expect fun isProcessAlive(pid: Int): Boolean

/** 阻塞 [ms] 毫秒。 */
internal expect fun sleepMillis(ms: Long)

/** 目标 il2cpp 运行时模块名（Windows: GameAssembly.dll；Android: libil2cpp.so）。 */
internal expect val il2cppModuleName: String

/**
 * 目标当前已加载的模块名列表；**无法枚举时返回 null**。
 *
 * 存在的意义是把「枚举不到」与「模块确实不在」分开 —— 活进程至少有自身 exe 与 ntdll，
 * 因此返回空列表只可能是枚举失败（进程已退出、或受保护/反作弊拦截快照）。
 */
internal expect fun moduleNames(pid: Int): List<String>?

/** [path] 的父目录；没有目录分隔符（或分隔符在位置 0）时返回 null，交由 CreateProcess 继承当前目录。 */
internal fun parentDirectoryOf(path: String): String? {
    val index = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    if (index <= 0) return null
    val parent = path.substring(0, index)
    return if (parent.endsWith(":")) "$parent\\" else parent
}

/**
 * 轮询直到 [moduleName] 出现在 [pid] 的模块列表中。
 *
 * - 命中即返回
 * - 目标进程提前退出 → [InjectError.TARGET_NOT_FOUND]
 * - 超过 [timeoutMs] → [InjectError.TIMEOUT]
 *
 * 探测、存活、休眠三个副作用均可注入，因此边界行为可在 commonTest 中确定性验证。
 * 永远不会休眠到超过 [timeoutMs] 的时刻。
 */
internal fun awaitModuleLoaded(
    pid: Int,
    moduleName: String,
    timeoutMs: Int,
    pollIntervalMs: Long = POLL_INTERVAL_MS,
    probe: (Int, String) -> Boolean = ::isModuleLoaded,
    alive: (Int) -> Boolean = ::isProcessAlive,
    waitMillis: (Long) -> Unit = ::sleepMillis,
) {
    var waited = 0L
    while (true) {
        if (probe(pid, moduleName)) return
        if (!alive(pid)) {
            throw InjectFailure(
                InjectError.TARGET_NOT_FOUND,
                "target pid $pid exited before $moduleName was loaded",
            )
        }
        if (waited + pollIntervalMs > timeoutMs) {
            throw InjectFailure(
                InjectError.TIMEOUT,
                "$moduleName did not appear in pid $pid within $timeoutMs ms",
            )
        }
        waitMillis(pollIntervalMs)
        waited += pollIntervalMs
    }
}

/**
 * 注入后校验：通过返回 null，否则返回**能说明白原因**的失败消息。
 *
 * [modules] 为 null 表示无法枚举目标模块列表 —— 此时绝不能报「模块不在」：真实原因通常是
 * 目标已退出、或受保护（内核反作弊）导致快照失败，排查方向与「模块没加载」完全不同。
 * 成功枚举时会把枚举到的模块数一并报出。
 *
 * [loadExitCode] 是远程 `LoadLibraryW` 线程的退出码，两个失败分支都会带上它：
 * 「加载调用说成功、模块列表里却没有」这个组合本身就指向"加载被回滚"或"返回了非真的句柄"，
 * 不把它给出来就无法定位。注意它是 `GetExitCodeThread` 的 **32 位**返回值，x64 句柄的高 32 位
 * 会被截断，所以只当作"加载方自己的回答"展示，**不要**当成可用句柄。
 */
internal fun verifyInjection(
    pid: Int,
    moduleName: String,
    modules: List<String>?,
    loadExitCode: Long,
): String? = when {
    modules == null ->
        "cannot confirm injection: could not enumerate modules of pid $pid " +
            "(the target may have exited, be protected, or have refused the load; " +
            "remote LoadLibraryW exit code 0x${loadExitCode.toString(16)})"

    modules.none { it.equals(moduleName, ignoreCase = true) } ->
        "module $moduleName not visible in pid $pid (enumerated ${modules.size} modules; " +
            "remote LoadLibraryW exit code 0x${loadExitCode.toString(16)})"

    else -> null
}

/**
 * 注入前的本地延迟（`--delay`）：在目标解析/启动与所有 `--wait-*` 完成之后、真正注入之前休眠。
 *
 * `delayMs <= 0` 时零开销、完全不调用 [waitMillis]。休眠副作用可注入，因此"是否休眠、休眠多久"
 * 可在 commonTest 中确定性验证（不必真的等）。
 *
 * 与 `--timeout` / `--wait-timeout` 无关：那两者分别是远程调用与模块等待的超时，本项只是本地休眠。
 */
internal fun applyInjectionDelay(delayMs: Int, waitMillis: (Long) -> Unit = ::sleepMillis) {
    if (delayMs > 0) waitMillis(delayMs.toLong())
}

/**
 * spawn 模式的注入后动作序列：**resume →（`--call-entry` 时）等模块就绪 → 延迟 → 调入口**。
 *
 * - 不带 `--call-entry`：只 resume。此时等待/延迟都没有意义 —— 入口留给目标自身调用
 *   （SELFCALL 模式），静默空转 60s 的默认 `--wait-timeout` 只会让人误以为注入卡住。
 * - 带 `--call-entry`：`--wait-module`（默认 il2cpp 模块）的语义从「等注入」变为「等入口可调」；
 *   `--delay` 延迟的是**入口调用** —— 注入已经发生在诞生时，这是对 Frida
 *   spawn + `setTimeout(3s)` 「注入早、动作晚」时间线的对等参数。
 *
 * 所有副作用可注入，因此调用次序可在 commonTest 中确定性验证。
 */
internal fun spawnEntrySequence(
    pid: Int,
    command: CliCommand.Inject,
    resume: (Int) -> Unit = ::resumeProcess,
    await: (Int) -> Unit = { awaitModuleLoaded(it, command.waitModule, command.waitTimeoutMs) },
    delay: () -> Unit = { applyInjectionDelay(command.delayMs) },
    call: (Int) -> Unit = {
        callExportedEntry(it, command.libPath, command.entry, command.timeoutMs, command.verbose)
    },
) {
    resume(pid)
    if (command.callEntry) {
        await(pid)
        delay()
        call(pid)
    }
}
