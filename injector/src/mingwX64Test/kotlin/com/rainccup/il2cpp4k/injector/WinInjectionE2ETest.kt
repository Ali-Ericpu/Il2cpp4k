package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class WinInjectionE2ETest {
    @Test
    fun injectsFrameworkLibraryCallsEntryAndVerifies() {
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
            val discovered = listTargets("il2cpp4k-inject-target")
            assertTrue(discovered.any { it.pid == pid }, "target $pid should be discoverable by pattern")

            val resolved = resolveTarget(pid, null, null)
            assertEquals(pid, resolved.pid)
            assertEquals(pid, resolveTarget(null, null, "il2cpp4k-inject-target").pid)

            // 只注入不调入口（该用例聚焦加载与校验）；入口调用路径见 WinEntryCallE2ETest——
            // 2026-10 修复「入口地址截断 + 合成栈」两个叠加 bug 后，劫持调用已实测可用。
            val moduleHandle = injectAndCall(pid, dll, null, 10_000, verbose = true)
            assertTrue(moduleHandle != 0L, "LoadLibraryW should return a module handle")

            assertTrue(isModuleLoaded(pid, "il2cpp4k.dll"), "injected module must be visible in target")
            assertTrue(
                listAllProcesses(withIl2cppFlag = false).any { it.pid == pid },
                "target process must still be alive after injection",
            )
        } finally {
            Win32.terminateProcess(pid)
        }
    }

    @Test
    fun resolveTargetFailsForUnknownName() {
        val failure = try {
            resolveTarget(null, "definitely-not-running-xyz.exe", null)
            null
        } catch (e: InjectFailure) {
            e
        }
        assertEquals(InjectError.TARGET_NOT_FOUND, failure?.error)
    }

    @Test
    fun injectReportsMissingLibrary() {
        val failure = try {
            injectAndCall(0, "no-such-library.dll", null, 1_000, verbose = false)
            null
        } catch (e: InjectFailure) {
            e
        }
        assertEquals(InjectError.LIB_NOT_FOUND, failure?.error)
    }

    /**
     * 相对 `--lib` 必须由**注入器**解析成绝对路径。
     *
     * 我们把这个字符串原样写进目标再调 `LoadLibraryW`，而相对路径在目标里是按**目标的 cwd** 解析的
     * —— 本测试刻意让目标的工作目录（它自己的目录）与注入器的不同，因此任何"原样交给目标"的实现
     * 都会在目标里找不到该文件而失败。`--exe` 启动路径天然就是这种情况（cwd 被设成 exe 所在目录）。
     */
    @Test
    fun relativeLibPathIsResolvedByTheInjectorNotByTheTarget() {
        val dll = requireArtifact(
            "injector/../api/build/bin/mingwX64/releaseShared/il2cpp4k.dll",
            ".\\gradlew.bat :api:linkReleaseSharedMingwX64",
        )
        val targetExe = requireArtifact(
            "injector/build/bin/mingwX64/il2cpp4k-inject-targetReleaseExecutable/il2cpp4k-inject-target.exe",
            ".\\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64",
        )
        // 目录要从**已解析的绝对路径**上取：requireArtifact 返回的候选串用的是正斜杠，
        // 直接按 '\\' 切会找不到分隔符而返回整串（把文件当成目录 ⟹ CreateProcessW 报 267）。
        val absoluteTarget = absolutePathOf(targetExe) ?: error("cannot resolve $targetExe")
        val targetDir = absoluteTarget.substringBeforeLast('\\')
        assertTrue(targetDir.isNotEmpty(), "the target must live in a directory")

        val pid = Win32.launchProcess(absoluteTarget, null, targetDir)
        try {
            val exitCode = runCli(listOf("inject", "--lib", dll, "--pid", pid.toString()))
            assertEquals(
                0,
                exitCode,
                "a relative --lib must be resolved by the injector, not against the target's working directory",
            )
            assertTrue(isModuleLoaded(pid, "il2cpp4k.dll"), "the module must be visible in the target")
        } finally {
            Win32.terminateProcess(pid)
        }
    }
}
