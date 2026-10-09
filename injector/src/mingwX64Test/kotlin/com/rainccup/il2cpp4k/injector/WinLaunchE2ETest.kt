package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.time.TimeSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class WinLaunchE2ETest {
    private val dll = requireArtifact(
        "injector/../api/build/bin/mingwX64/releaseShared/il2cpp4k.dll",
        ".\\gradlew.bat :api:linkReleaseSharedMingwX64",
    )

    private val targetExe = requireArtifact(
        "injector/build/bin/mingwX64/il2cpp4k-inject-targetReleaseExecutable/il2cpp4k-inject-target.exe",
        ".\\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64",
    )

    /** 启动 → 轮询等一个稍后才出现的模块 → 注入 → 校验。 */
    @Test
    fun launchesTargetWaitsForLateModuleThenInjects() {
        val pid = launchProcess(targetExe, "--load-late $dll", null)
        try {
            assertTrue(isProcessAlive(pid), "launched target $pid must be alive")
            assertTrue(
                !isModuleLoaded(pid, "il2cpp4k.dll"),
                "the module must not be loaded before --load-late fires",
            )

            awaitModuleLoaded(pid, "il2cpp4k.dll", timeoutMs = 30_000)
            assertTrue(isModuleLoaded(pid, "il2cpp4k.dll"), "target should have loaded the late module")

            val handle = injectAndCall(pid, dll, null, 10_000, verbose = true)
            assertTrue(handle != 0L, "LoadLibraryW should return a module handle")
            assertTrue(isModuleLoaded(pid, "il2cpp4k.dll"), "injected module must be visible in target")
            assertTrue(isProcessAlive(pid), "target must still be alive after injection")
        } finally {
            Win32.terminateProcess(pid)
        }
    }

    /** 等一个永不出现的模块 → 超时失败，且目标仍存活（注入器不清理游戏）。 */
    @Test
    fun timeoutLeavesTheTargetRunning() {
        val pid = launchProcess(targetExe, null, null)
        try {
            val failure = assertFailsWith<InjectFailure> {
                awaitModuleLoaded(pid, "definitely-absent-module.dll", timeoutMs = 1_000)
            }
            assertEquals(InjectError.TIMEOUT, failure.error)
            assertTrue(isProcessAlive(pid), "the injector must not terminate the game it started")
        } finally {
            Win32.terminateProcess(pid)
        }
    }

    /** 目标启动即退出 → 退出码 2（走完整 CLI，覆盖错误码映射）。 */
    @Test
    fun cliReportsTargetNotFoundWhenLaunchedProcessExits() {
        val shell = getenv("COMSPEC")?.toKString() ?: "C:\\Windows\\System32\\cmd.exe"
        val exitCode = runCli(
            listOf("inject", "--lib", dll, "--exe", shell, "--exe-args", "/c exit"),
        )
        assertEquals(InjectError.TARGET_NOT_FOUND.code, exitCode)
    }

    /**
     * `--delay` 必须真的把注入往后推：走完整 CLI，只断言耗时下界（延迟生效时必然 ≥ 延迟值，
     * 未生效时只有几十毫秒 ⟹ 不会 flaky）。
     *
     * 注意：**不要用 `build/test-results/` 下 TEST-*.xml 里 testcase 的 `time` 属性判定本测试耗时**
     * —— 实测它对本用例报告 0.003s，而真实 `elapsedNow()` 是 827ms。以测试内部实测为准。
     */
    @Test
    fun cliDelayPostponesInjection() {
        val pid = launchProcess(targetExe, null, null)
        try {
            val mark = TimeSource.Monotonic.markNow()
            val exitCode = runCli(
                listOf("inject", "--lib", dll, "--pid", pid.toString(), "--delay", "800"),
            )
            val elapsed = mark.elapsedNow().inWholeMilliseconds
            assertEquals(0, exitCode, "injection should succeed")
            assertTrue(elapsed >= 800, "--delay 800 must postpone injection, but the chain took ${elapsed}ms")
        } finally {
            Win32.terminateProcess(pid)
        }
    }
}
