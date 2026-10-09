package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 「模块已驻留 → 跳过加载」机制的端到端验证。
 *
 * 动机来自受保护目标的真机实验（2026-10）：对运行中的游戏第二次 `LoadLibraryW` 被反作弊
 * 主动拒绝（返回 INVALID_HANDLE_VALUE），但诞生时注入的模块始终驻留。因此注入器在
 * 加载前应先查目标模块表 —— **已驻留就连加载调用都不发**（不去触发监控），直接进入
 * 可选的入口调用阶段。
 *
 * 测试目标进程没有反作弊，无法复现「拒绝」分支（加载器对同名模块本就短路），该分支
 * 的最终验证是真机；这里钉死的是机制层：驻留查找的正确性，以及「二次注入 + 调入口」
 * 的结果回归。
 */
@OptIn(ExperimentalForeignApi::class)
class WinResidentModuleE2ETest {
    @Test
    fun residentModuleBaseFindsInjectedModuleAndMissesAbsentOnes() {
        val dll = requireArtifact(
            "injector/../api/build/bin/mingwX64/releaseShared/il2cpp4k.dll",
            ".\\gradlew.bat :api:linkReleaseSharedMingwX64",
        )
        val targetExe = requireArtifact(
            "injector/build/bin/mingwX64/il2cpp4k-inject-targetReleaseExecutable/il2cpp4k-inject-target.exe",
            ".\\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64",
        )

        val pid = Win32.launchProcess(targetExe, null, null)
        try {
            // 注入前：模块不存在 → 必须返回 null（这是「跳过加载」决定的另一半）
            assertNull(
                WinInjector.residentModuleBase(pid, dll),
                "an absent module must not be reported as resident",
            )

            val handle = injectAndCall(pid, dll, null, 10_000, verbose = false)
            assertTrue(handle != 0L, "the first load must succeed")

            val base = WinInjector.residentModuleBase(pid, dll)
            assertTrue(base != null && base != 0L, "the injected module must be found as resident")
            // 与 Toolhelp 快照对账：找到的基址必须与模块表中的条目一致
            val listed = Win32.listModules(pid).firstOrNull { it.name.equals("il2cpp4k.dll", ignoreCase = true) }
            assertEquals(listed?.base, base, "residentModuleBase must agree with the module snapshot")

            assertNull(
                WinInjector.residentModuleBase(pid, "C:\\definitely\\not\\loaded.dll"),
                "an unknown module name must not be reported as resident",
            )
        } finally {
            Win32.terminateProcess(pid)
            var waited = 0
            while (isProcessAlive(pid) && waited < 15_000) {
                sleepMillis(100)
                waited += 100
            }
        }
    }

    @Test
    fun reinjectOnResidentModuleCallsEntryAndKeepsTargetAlive() {
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
            // 第一次：只加载，不调入口（模拟 spawn 模式的「诞生时注入」）
            val first = runCli(listOf("inject", "--lib", dll, "--pid", pid.toString(), "--timeout", "10000"))
            assertEquals(0, first, "the initial load must succeed")
            assertTrue(isModuleLoaded(pid, "il2cpp4k_example.dll"), "the module must be resident")

            // 第二次：模块已驻留 → 跳过加载，直接劫持调入口（对应真机上 AC 拒载的场景）
            val second = runCli(
                listOf(
                    "inject", "--lib", dll, "--pid", pid.toString(),
                    "--call-entry", "--entry", "il2cpp4k_init", "--timeout", "30000",
                ),
            )
            assertEquals(0, second, "re-inject on a resident module must skip the load and call the entry")
            assertTrue(isProcessAlive(pid), "target must survive the entry call on the resident module")
            assertTrue(isModuleLoaded(pid, "il2cpp4k_example.dll"), "the module must remain resident")
        } finally {
            Win32.terminateProcess(pid)
            var waited = 0
            while (isProcessAlive(pid) && waited < 15_000) {
                sleepMillis(100)
                waited += 100
            }
        }
    }
}
