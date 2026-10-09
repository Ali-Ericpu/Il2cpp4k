package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 劫持线程调用导出入口的端到端实验。
 *
 * 背景：README「已知限制 1」记录了三种外部调用入口方式全部在「入口首条指令处 0xc0000005」崩溃，
 * 并归因于 Kotlin/Native 的 emutls 线程局部状态。但当时的入口地址是用 `GetExitCodeThread` 的
 * **32 位**退出码当模块基址算出来的 —— x64 上 DLL 基址（0x7FF8…）的高 32 位会被截断，
 * 入口地址因此是野指针，恰好表现出同样的症状。
 *
 * 本测试在正确地址计算下重做该实验：目标存活 ⟹ 原归因是误诊，`--call-entry` 可靠可用；
 * 目标仍在入口内崩溃 ⟹ emutls 归因成立，维持 README 的保守结论。两种结果都是有效证据。
 */
@OptIn(ExperimentalForeignApi::class)
class WinEntryCallE2ETest {
    @Test
    fun hijackEntryCallRunsAndKeepsTargetAlive() {
        val dll = requireArtifact(
            "example/build/bin/mingwX64/releaseShared/il2cpp4k_example.dll",
            ".\\gradlew.bat :example:linkReleaseSharedMingwX64",
        )
        val targetExe = requireArtifact(
            "injector/build/bin/mingwX64/il2cpp4k-inject-targetReleaseExecutable/il2cpp4k-inject-target.exe",
            ".\\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64",
        )

        val pid = Win32.launchProcess(targetExe, null, null)
        try {
            val exitCode = runCli(
                listOf(
                    "inject", "--lib", dll, "--pid", pid.toString(),
                    "--call-entry", "--entry", "il2cpp4k_init", "--timeout", "10000",
                ),
            )
            assertEquals(0, exitCode, "entry call must succeed once the entry address is computed correctly")
            assertTrue(isProcessAlive(pid), "target must survive the hijacked entry call")
        } finally {
            Win32.terminateProcess(pid)
            // 目标若在入口处崩溃，会被 WER 短暂滞留在进程表里；等它真正消失再返回，
            // 避免污染后续按进程名解析目标的测试（resolveTarget 会撞上重名残留）。
            var waited = 0
            while (isProcessAlive(pid) && waited < 15_000) {
                sleepMillis(100)
                waited += 100
            }
        }
    }
}
