package com.rainccup.il2cpp4k.injector

import co.touchlab.kermit.Severity
import com.rainccup.il2cpp4k.logging.Log
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(runCli(args.toList()))
}

/** 返回进程退出码。 */
fun runCli(args: List<String>): Int {
    val command = try {
        parseArgs(args)
    } catch (e: UsageException) {
        Log.e { "error: ${e.message}" }
        printUsage()
        return InjectError.USAGE.code
    }
    return when (command) {
        is CliCommand.ListTargets -> {
            val targets = try {
                listTargets(command.pattern)
            } catch (e: InjectFailure) {
                Log.e { "error: ${e.message}" }
                return e.error.code
            }
            if (targets.isEmpty()) {
                Log.e { "no target process found" }
                return InjectError.TARGET_NOT_FOUND.code
            }
            targets.forEach { Log.e { "${it.pid}\t${it.name}\t${it.il2cppModule ?: "-"}" } }
            0
        }

        is CliCommand.Inject -> runInject(command)
    }
}

internal fun printUsage() {
    Log.i {

        "usage:\n" +
                "  il2cpp4k-inject list [--pattern <substr>] [--verbose]\n" +
                "  il2cpp4k-inject inject --lib <path>\n" +
                "                        (--pid <n> | --name <s> | --pattern <s> | --exe <path>)\n" +
                "                        [--exe-args <string>] [--wait-module <name>] [--wait-timeout <ms>]\n" +
                "                        [--delay <ms>] [--entry <symbol>] [--call-entry] [--timeout <ms>]\n" +
                "                        [--spawn] [--verbose]\n" +
                "\n" +
                "--spawn (需 --exe)：挂起启动目标，主线程未运行时即注入（诞生时注入）。\n" +
                "  配合 --call-entry 时：resume → 等 --wait-module 就绪 → --delay → 调入口；\n" +
                "  --delay 在 spawn 模式下延迟的是入口调用（对应 frida spawn + setTimeout 的时间线）。"

    }
}

/** 完整注入流程：解析目标（必要时先启动）→ 注入（可选调入口）→ 校验模块可见。 */
internal fun runInject(command: CliCommand.Inject): Int = try {
    // 必须在启动之前设置：否则启动与等待阶段的 Log.d 不会输出
    if (command.verbose) Log.level = Severity.Debug

    // --spawn：挂起启动 → 诞生时注入 → resume →（可选）等模块+延迟后调入口。
    // 时间线与 attach 式完全不同，走独立流程。
    if (command.spawn) return runSpawnInject(command)

    val pid = if (command.exePath != null) {
        launchAndAwaitIl2cpp(command)
    } else {
        val target = resolveTarget(command.pid, command.name, command.pattern)
        Log.d { "target pid=${target.pid} name=${target.name}" }
        target.pid
    }

    // --delay：紧贴注入之前的本地延迟，与 --timeout / --wait-timeout 的超时语义无关
    if (command.delayMs > 0) {
        Log.i { "delaying ${command.delayMs} ms before injection (--delay)" }
    }
    applyInjectionDelay(command.delayMs)

    val entry = if (command.callEntry) command.entry else null
    val handle = injectAndCall(pid, command.libPath, entry, command.timeoutMs, command.verbose)

    val moduleName = command.libPath.substringAfterLast('/').substringAfterLast('\\')
    // 用 moduleNames 而非 isModuleLoaded：需要把「枚举失败」与「模块确实不在」分开报；
    // 同时把加载方自己的回答（远程 LoadLibraryW 退出码）带上，便于定位"说成功却没落地"
    verifyInjection(pid, moduleName, moduleNames(pid), handle)?.let { reason ->
        throw InjectFailure(InjectError.VERIFY_FAILED, reason)
    }

    Log.i { "injected $moduleName into pid $pid (module handle 0x${handle.toString(16)})" }
    if (entry != null) {
        Log.i { "entry $entry called" }
    } else {
        Log.i {
            "entry not called: 可让目标自身代码调用 ${command.entry}，或加 --call-entry 由注入器" +
                    "劫持目标线程调用（实测可用；入口地址与栈已修复，见 WinEntryCallE2ETest）"
        }
    }
    0
} catch (e: InjectFailure) {
    Log.e { "error: ${e.message}" }
    e.error.code
}

/**
 * spawn 模式注入（`--spawn`，需 `--exe`）：复现 frida spawn 的时间线。
 *
 * CREATE_SUSPENDED 启动 → **诞生时**注入（主线程未跑任何指令）→ resume →
 * （`--call-entry` 时）等 `--wait-module` 就绪 → `--delay` → 劫持调入口。
 *
 * 失败策略分界：resume **之前**失败（启动/加载）→ 尽力 resume 让目标作为普通进程
 * 活下去（注入器不接管生命周期，也不留一个永远挂起的僵尸进程），原始失败照常上抛；
 * resume **之后**失败（等待/入口/校验）→ 目标已在正常运行，留给使用者处置。
 */
private fun runSpawnInject(command: CliCommand.Inject): Int {
    val exe = command.exePath ?: throw InjectFailure(InjectError.USAGE, "--spawn requires --exe")
    val pid = launchProcessSuspended(exe, command.exeArgs, parentDirectoryOf(exe))
    Log.i { "launched $exe suspended (pid $pid); injecting at process birth (--spawn)" }

    val handle = try {
        injectAndCall(pid, command.libPath, null, command.timeoutMs, command.verbose)
    } catch (e: InjectFailure) {
        runCatching { resumeProcess(pid) }
            .onFailure { Log.w { "resume after a failed load also failed: ${it.message}" } }
        throw e
    }

    spawnEntrySequence(pid, command)

    val moduleName = command.libPath.substringAfterLast('/').substringAfterLast('\\')
    verifyInjection(pid, moduleName, moduleNames(pid), handle)?.let { reason ->
        throw InjectFailure(InjectError.VERIFY_FAILED, reason)
    }

    Log.i { "spawn-injected $moduleName into pid $pid: target was born with the module" }
    if (command.callEntry) {
        Log.i { "entry ${command.entry} called after resume" }
    } else {
        Log.i { "entry not called: 请让目标自身代码调用 ${command.entry}（或下次加 --call-entry）" }
    }
    return 0
}

/**
 * 启动 `--exe` 指定的可执行文件，等到 `--wait-module` 出现后返回其 pid。
 *
 * 失败即抛 [InjectFailure]（exe 无法启动 → OPEN_FAILED；目标提前退出 → TARGET_NOT_FOUND；
 * 等待超时 → TIMEOUT）。**失败时不终止已启动的游戏**：注入器不接管游戏生命周期。
 */
private fun launchAndAwaitIl2cpp(command: CliCommand.Inject): Int {
    val exe = command.exePath ?: throw InjectFailure(InjectError.USAGE, "missing --exe")
    val pid = launchProcess(exe, command.exeArgs, parentDirectoryOf(exe))
    Log.i {
        "launched $exe (pid $pid); waiting up to ${command.waitTimeoutMs} ms for ${command.waitModule}"
    }
    awaitModuleLoaded(pid, command.waitModule, command.waitTimeoutMs)
    Log.d { "${command.waitModule} present in pid $pid" }
    return pid
}
