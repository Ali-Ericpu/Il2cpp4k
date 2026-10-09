package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * spawn 模式端到端实验（frida-parity 时间线）。
 *
 * `--spawn`：CREATE_SUSPENDED 启动目标 → **主线程未执行任何指令时**注入 → resume。
 * 目标「生来就带着」我们的模块，反作弊用户态组件初始化时看到的是一个已存在的模块，
 * 而不是一个运行中途突然出现的未知加载事件 —— 这是对 Frida spawn 行为的对等复现。
 *
 * 需要实证的问题（本测试就是答案）：
 * 1. 对从未运行过的挂起进程，CreateRemoteThread(LoadLibraryW) 是否成功
 *    （新远程线程会代为完成 ntdll 进程初始化，再执行起始地址）。
 * 2. resume 之后目标能否带着注入模块正常运行。
 */
@OptIn(ExperimentalForeignApi::class)
class WinSpawnE2ETest {
    @Test
    fun moduleIsPresentWhileMainThreadHasNotRun() {
        val dll = requireArtifact(
            "injector/../api/build/bin/mingwX64/releaseShared/il2cpp4k.dll",
            ".\\gradlew.bat :api:linkReleaseSharedMingwX64",
        )
        val targetExe = requireArtifact(
            "injector/build/bin/mingwX64/il2cpp4k-inject-targetReleaseExecutable/il2cpp4k-inject-target.exe",
            ".\\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64",
        )

        val pid = launchProcessSuspended(targetExe, null, null)
        try {
            val handle = injectAndCall(pid, dll, null, 10_000, verbose = false)
            assertTrue(handle != 0L, "LoadLibraryW must succeed in a suspended-spawned process")
            assertTrue(
                isModuleLoaded(pid, "il2cpp4k.dll"),
                "the module must be mapped while the main thread has not run a single instruction",
            )
            resumeProcess(pid)
            assertTrue(isProcessAlive(pid), "target must run normally after resume")
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
    fun spawnWithCallEntryRunsEntryAfterResumeAndKeepsTargetAlive() {
        val dll = requireArtifact(
            "example/build/bin/mingwX64/releaseShared/il2cpp4k_example.dll",
            ".\\gradlew.bat :example:linkReleaseSharedMingwX64",
        )
        val targetExe = requireArtifact(
            "injector/build/bin/mingwX64/il2cpp4k-inject-targetReleaseExecutable/il2cpp4k-inject-target.exe",
            ".\\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64",
        )

        val pid = launchProcessSuspended(targetExe, null, null)
        try {
            val handle = injectAndCall(pid, dll, null, 10_000, verbose = false)
            assertTrue(handle != 0L, "load at birth must succeed")
            resumeProcess(pid)
            // 等目标 exe 模块可见（进程初始化完成的标志），再由注入器劫持调入口。
            // 顺序与 spawnEntrySequence 一致：resume → await → delay → call。
            awaitModuleLoaded(pid, "il2cpp4k-inject-target.exe", 30_000)
            callExportedEntry(pid, dll, "il2cpp4k_init", 10_000, verbose = false)
            assertTrue(isProcessAlive(pid), "target must survive the spawn-mode entry call")
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
    fun neverStartedSpawnedProcessCanBeTerminated() {
        val targetExe = requireArtifact(
            "injector/build/bin/mingwX64/il2cpp4k-inject-targetReleaseExecutable/il2cpp4k-inject-target.exe",
            ".\\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64",
        )
        val pid = launchProcessSuspended(targetExe, null, null)
        Win32.terminateProcess(pid)
        // TerminateProcess 是异步的：挂起态进程也要等终止完成才从进程表消失。
        var waited = 0
        while (isProcessAlive(pid) && waited < 15_000) {
            sleepMillis(100)
            waited += 100
        }
        assertEquals(false, isProcessAlive(pid), "a never-started spawned process must be terminable")
    }
}
