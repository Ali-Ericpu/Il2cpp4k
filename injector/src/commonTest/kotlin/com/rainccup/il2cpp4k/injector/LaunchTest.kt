package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaunchTest {
    @Test
    fun returnsAsSoonAsModuleAppears() {
        var probes = 0
        val waits = mutableListOf<Long>()
        awaitModuleLoaded(
            pid = 42,
            moduleName = "GameAssembly.dll",
            timeoutMs = 60_000,
            probe = { _, _ -> ++probes >= 3 },
            alive = { true },
            waitMillis = { waits.add(it) },
        )
        assertEquals(3, probes, "should stop probing once the module is seen")
        assertEquals(listOf(200L, 200L), waits, "two polls before the hit means two sleeps")
    }

    @Test
    fun returnsWithoutSleepingWhenAlreadyLoaded() {
        val waits = mutableListOf<Long>()
        awaitModuleLoaded(
            pid = 1,
            moduleName = "m.dll",
            timeoutMs = 1_000,
            probe = { _, _ -> true },
            alive = { true },
            waitMillis = { waits.add(it) },
        )
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun failsWhenTargetExitsBeforeModuleAppears() {
        val failure = assertFailsWith<InjectFailure> {
            awaitModuleLoaded(
                pid = 7,
                moduleName = "m.dll",
                timeoutMs = 1_000,
                probe = { _, _ -> false },
                alive = { false },
                waitMillis = {},
            )
        }
        assertEquals(InjectError.TARGET_NOT_FOUND, failure.error)
    }

    @Test
    fun failsOnTimeout() {
        val waits = mutableListOf<Long>()
        val failure = assertFailsWith<InjectFailure> {
            awaitModuleLoaded(
                pid = 7,
                moduleName = "m.dll",
                timeoutMs = 1_000,
                probe = { _, _ -> false },
                alive = { true },
                waitMillis = { waits.add(it) },
            )
        }
        assertEquals(InjectError.TIMEOUT, failure.error)
        assertEquals(5, waits.size, "1000 ms / 200 ms poll = 5 sleeps, never sleeping past the deadline")
    }

    @Test
    fun parentDirectoryOfHandlesWindowsAndPosixPaths() {
        assertEquals("C:\\games\\foo", parentDirectoryOf("C:\\games\\foo\\Game.exe"))
        assertEquals("C:\\", parentDirectoryOf("C:\\Game.exe"))
        assertEquals("/opt/game", parentDirectoryOf("/opt/game/game"))
        assertEquals(null, parentDirectoryOf("Game.exe"))
    }

    @Test
    fun verifyPassesWhenModuleIsListed() {
        assertNull(
            verifyInjection(
                pid = 7,
                moduleName = "il2cpp4k.dll",
                modules = listOf("Game.exe", "IL2CPP4K.DLL"),
                loadExitCode = 0xEE4E0000L,
            ),
            "module name matching must ignore case",
        )
    }

    @Test
    fun verifyDistinguishesBlockedEnumerationFromAbsentModule() {
        val blocked = verifyInjection(
            pid = 7,
            moduleName = "il2cpp4k.dll",
            modules = null,
            loadExitCode = 0xEE4E0000L,
        )
        assertTrue(
            blocked != null && blocked.contains("could not enumerate"),
            "a failed enumeration must not be reported as an absent module: $blocked",
        )
        assertTrue(blocked!!.contains("pid 7"), "the failing pid must be named: $blocked")

        val absent = verifyInjection(
            pid = 7,
            moduleName = "il2cpp4k.dll",
            modules = listOf("Game.exe", "ntdll.dll"),
            loadExitCode = 0xABCD0000L,
        )
        assertTrue(absent != null && absent.contains("not visible"), "expected an absence message: $absent")
        assertTrue(
            absent!!.contains("enumerated 2 modules"),
            "a successful enumeration should report what it saw: $absent",
        )
        assertFalse(absent.contains("could not enumerate"), "enumeration worked, so do not imply otherwise")
    }

    @Test
    fun verifyAlwaysReportsTheObservedLoadExitCode() {
        val blocked = verifyInjection(pid = 7, moduleName = "m.dll", modules = null, loadExitCode = 0xEE4E0000L)
        assertTrue(
            blocked != null && blocked.contains("0xee4e0000"),
            "the loader's own answer must stay visible even when enumeration fails: $blocked",
        )

        val absent = verifyInjection(pid = 7, moduleName = "m.dll", modules = listOf("ntdll.dll"), loadExitCode = 0L)
        assertTrue(
            absent != null && absent.contains("0x0"),
            "a zero exit code is exactly the interesting case and must not be hidden: $absent",
        )
    }

    @Test
    fun injectionDelaySleepsExactlyOnceWithTheRequestedValue() {
        val waits = mutableListOf<Long>()
        applyInjectionDelay(800, waitMillis = { waits.add(it) })
        assertEquals(listOf(800L), waits)
    }

    @Test
    fun injectionDelayIsFreeWhenZeroOrNegative() {
        val waits = mutableListOf<Long>()
        applyInjectionDelay(0, waitMillis = { waits.add(it) })
        applyInjectionDelay(-5, waitMillis = { waits.add(it) })
        assertEquals(emptyList<Long>(), waits, "no delay must never reach the sleeper")
    }

    @Test
    fun spawnSequenceWithCallEntryResumesThenWaitsDelaysAndCalls() {
        val calls = mutableListOf<String>()
        val command = parseArgs(
            listOf(
                "inject", "--lib", "x.dll", "--exe", "G.exe",
                "--spawn", "--call-entry", "--delay", "1600",
            ),
        ) as CliCommand.Inject
        spawnEntrySequence(
            pid = 9,
            command = command,
            resume = { calls.add("resume:$it") },
            await = { calls.add("await:$it") },
            delay = { calls.add("delay") },
            call = { calls.add("call:$it") },
        )
        // --delay 在 spawn 模式下延迟的是**入口调用**（注入发生在诞生时无法再延迟），
        // 对应 Frida spawn + setTimeout(1.6s) 的「注入早、动作晚」时间线。
        assertEquals(listOf("resume:9", "await:9", "delay", "call:9"), calls)
    }

    @Test
    fun spawnSequenceWithoutCallEntryOnlyResumes() {
        val calls = mutableListOf<String>()
        val command = parseArgs(
            listOf("inject", "--lib", "x.dll", "--exe", "G.exe", "--spawn"),
        ) as CliCommand.Inject
        spawnEntrySequence(
            pid = 9,
            command = command,
            resume = { calls.add("resume") },
            await = { calls.add("await") },
            delay = { calls.add("delay") },
            call = { calls.add("call") },
        )
        // 不调入口时不能等待/延迟/调用：--wait-module 的等待对入口调用才有意义，
        // 静默空转 60s 默认超时会让人以为注入卡住。
        assertEquals(listOf("resume"), calls)
    }
}
