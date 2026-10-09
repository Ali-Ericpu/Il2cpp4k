package com.rainccup.il2cpp4k.injector

sealed interface CliCommand {
    data class ListTargets(val pattern: String?, val verbose: Boolean) : CliCommand

    data class Inject(
        val libPath: String,
        val pid: Int?,
        val name: String?,
        val pattern: String?,
        val exePath: String?,
        val exeArgs: String?,
        val waitModule: String,
        val waitTimeoutMs: Int,
        val delayMs: Int,
        val entry: String,
        val callEntry: Boolean,
        /** `--spawn`：挂起启动目标、诞生时注入（需 `--exe`；`--delay` 语义变为延迟入口调用）。 */
        val spawn: Boolean,
        val timeoutMs: Int,
        val verbose: Boolean,
    ) : CliCommand
}

const val DEFAULT_ENTRY = "il2cpp4k_init"
const val DEFAULT_TIMEOUT_MS = 10_000
const val DEFAULT_WAIT_TIMEOUT_MS = 60_000
const val DEFAULT_DELAY_MS = 0

private val BOOLEAN_FLAGS = setOf("--call-entry", "--verbose", "--spawn")

/** 只在 `--exe` 路径下生效的选项；给出但缺 `--exe` 时是用法错误，避免静默忽略。 */
private val LAUNCH_ONLY_FLAGS = listOf("--exe-args", "--wait-module", "--wait-timeout", "--spawn")

private val ALLOWED_FLAGS = setOf(
    "--lib", "--pid", "--name", "--pattern", "--exe", "--exe-args", "--wait-module", "--wait-timeout",
    "--delay", "--entry", "--timeout", "--call-entry", "--spawn", "--verbose",
)

fun parseArgs(args: List<String>): CliCommand {
    if (args.isEmpty()) throw UsageException("missing command: expected 'list' or 'inject'")
    val flags = parseFlags(args.drop(1))
    return when (val command = args[0]) {
        "list" -> CliCommand.ListTargets(
            pattern = flags["--pattern"],
            verbose = flags.containsKey("--verbose"),
        )
        "inject" -> parseInject(flags)
        else -> throw UsageException("unknown command: $command")
    }
}

private fun parseInject(flags: Map<String, String>): CliCommand.Inject {
    val lib = flags["--lib"] ?: throw UsageException("inject requires --lib <path>")
    val pid = flags["--pid"]?.let {
        it.toIntOrNull() ?: throw UsageException("--pid must be an integer: $it")
    }
    val exePath = flags["--exe"]
    val selectors = listOfNotNull(pid, flags["--name"], flags["--pattern"], exePath)
    if (selectors.size != 1) {
        throw UsageException("inject requires exactly one of --pid / --name / --pattern / --exe")
    }
    val launchOnly = LAUNCH_ONLY_FLAGS.filter { flags.containsKey(it) }
    if (exePath == null && launchOnly.isNotEmpty()) {
        throw UsageException("${launchOnly.joinToString(", ")} require --exe")
    }
    return CliCommand.Inject(
        libPath = lib,
        pid = pid,
        name = flags["--name"],
        pattern = flags["--pattern"],
        exePath = exePath,
        exeArgs = flags["--exe-args"],
        waitModule = flags["--wait-module"] ?: il2cppModuleName,
        waitTimeoutMs = flags["--wait-timeout"]?.let {
            it.toIntOrNull() ?: throw UsageException("--wait-timeout must be an integer: $it")
        } ?: DEFAULT_WAIT_TIMEOUT_MS,
        // --delay 不属于启动专属选项：四条目标选择器都要能用（"进程已在跑，过会儿再注入"）
        delayMs = flags["--delay"]?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw UsageException("--delay must be a non-negative integer: $raw")
        } ?: DEFAULT_DELAY_MS,
        entry = flags["--entry"] ?: DEFAULT_ENTRY,
        callEntry = flags.containsKey("--call-entry"),
        spawn = flags.containsKey("--spawn"),
        timeoutMs = flags["--timeout"]?.let {
            it.toIntOrNull() ?: throw UsageException("--timeout must be an integer: $it")
        } ?: DEFAULT_TIMEOUT_MS,
        verbose = flags.containsKey("--verbose"),
    )
}

/** 解析 `--key value` 与布尔 `--key`；未知选项报用法错误。 */
private fun parseFlags(args: List<String>): Map<String, String> {
    val out = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        val flag = args[i]
        if (flag !in ALLOWED_FLAGS) throw UsageException("unknown option: $flag")
        if (flag in BOOLEAN_FLAGS) {
            out[flag] = ""
            i++
            continue
        }
        val value = args.getOrNull(i + 1) ?: throw UsageException("option $flag requires a value")
        out[flag] = value
        i += 2
    }
    return out
}
