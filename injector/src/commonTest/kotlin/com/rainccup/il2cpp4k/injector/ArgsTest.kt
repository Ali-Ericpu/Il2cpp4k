package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ArgsTest {
    @Test
    fun parsesListWithPatternAndVerbose() {
        val command = parseArgs(listOf("list", "--pattern", "game", "--verbose"))
        assertTrue(command is CliCommand.ListTargets)
        assertEquals("game", command.pattern)
        assertTrue(command.verbose)
    }

    @Test
    fun parsesInjectWithPidAndEntryCallIsOptIn() {
        val command = parseArgs(listOf("inject", "--lib", "libil2cpp4k.so", "--pid", "1234")) as CliCommand.Inject
        assertEquals("libil2cpp4k.so", command.libPath)
        assertEquals(1234, command.pid)
        assertEquals(DEFAULT_ENTRY, command.entry)
        assertEquals(DEFAULT_TIMEOUT_MS, command.timeoutMs)
        assertEquals(false, command.callEntry, "entry call must be opt-in")
    }

    @Test
    fun injectRequiresLib() {
        assertFailsWith<UsageException> { parseArgs(listOf("inject", "--pid", "1")) }
    }

    @Test
    fun injectRequiresExactlyOneSelector() {
        assertFailsWith<UsageException> { parseArgs(listOf("inject", "--lib", "x.so")) }
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.so", "--pid", "1", "--name", "g"))
        }
    }

    @Test
    fun respectsEntryTimeoutAndCallEntryFlags() {
        val command = parseArgs(
            listOf(
                "inject", "--lib", "x.so", "--name", "game", "--entry", "my_init",
                "--timeout", "500", "--call-entry",
            ),
        ) as CliCommand.Inject
        assertEquals("my_init", command.entry)
        assertEquals(500, command.timeoutMs)
        assertEquals(true, command.callEntry)
        assertEquals("game", command.name)
    }

    @Test
    fun rejectsBadInput() {
        assertFailsWith<UsageException> { parseArgs(emptyList()) }
        assertFailsWith<UsageException> { parseArgs(listOf("bogus")) }
        assertFailsWith<UsageException> { parseArgs(listOf("list", "--nope")) }
        assertFailsWith<UsageException> { parseArgs(listOf("inject", "--lib")) }
        assertFailsWith<UsageException> { parseArgs(listOf("inject", "--lib", "x.so", "--pid", "abc")) }
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.so", "--pid", "1", "--timeout", "x"))
        }
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.so", "--exe", "G.exe", "--wait-timeout", "x"))
        }
    }

    @Test
    fun parsesExeSelectorAndLaunchOptions() {
        val command = parseArgs(
            listOf(
                "inject", "--lib", "x.dll", "--exe", "C:\\game\\Game.exe",
                "--exe-args", "-windowed -screen-width 1280",
                "--wait-module", "GameAssembly.dll", "--wait-timeout", "30000",
            ),
        ) as CliCommand.Inject
        assertEquals("C:\\game\\Game.exe", command.exePath)
        assertEquals("-windowed -screen-width 1280", command.exeArgs)
        assertEquals("GameAssembly.dll", command.waitModule)
        assertEquals(30_000, command.waitTimeoutMs)
    }

    @Test
    fun launchOptionsDefaultForExePath() {
        val command = parseArgs(listOf("inject", "--lib", "x.dll", "--exe", "G.exe")) as CliCommand.Inject
        assertEquals(il2cppModuleName, command.waitModule)
        assertEquals(DEFAULT_WAIT_TIMEOUT_MS, command.waitTimeoutMs)
        assertEquals(null, command.exeArgs)
    }

    @Test
    fun exeIsExclusiveWithOtherSelectors() {
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--exe", "G.exe", "--pid", "1"))
        }
    }

    @Test
    fun launchOptionsRequireExe() {
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1", "--wait-timeout", "500"))
        }
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1", "--exe-args", "-windowed"))
        }
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1", "--wait-module", "m.dll"))
        }
    }

    @Test
    fun delayDefaultsToZeroAndParsesMilliseconds() {
        val plain = parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1")) as CliCommand.Inject
        assertEquals(0, plain.delayMs)
        assertEquals(DEFAULT_DELAY_MS, plain.delayMs)

        val delayed =
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1", "--delay", "1500")) as CliCommand.Inject
        assertEquals(1500, delayed.delayMs)
    }

    @Test
    fun delayIsNotLaunchOnlySoItWorksWithPid() {
        val command =
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "7", "--delay", "10")) as CliCommand.Inject
        assertEquals(7, command.pid)
        assertEquals(10, command.delayMs)
    }

    @Test
    fun delayRejectsNegativeAndNonNumeric() {
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1", "--delay", "-1"))
        }
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1", "--delay", "abc"))
        }
    }

    @Test
    fun spawnFlagParsesWithExe() {
        val command = parseArgs(
            listOf("inject", "--lib", "x.dll", "--exe", "G.exe", "--spawn", "--call-entry"),
        ) as CliCommand.Inject
        assertTrue(command.spawn, "--spawn must parse as a boolean flag")
    }

    @Test
    fun spawnDefaultsToFalse() {
        val command = parseArgs(listOf("inject", "--lib", "x.dll", "--exe", "G.exe")) as CliCommand.Inject
        assertEquals(false, command.spawn, "spawn mode must be opt-in")
    }

    @Test
    fun spawnRequiresExe() {
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--pid", "1", "--spawn"))
        }
        assertFailsWith<UsageException> {
            parseArgs(listOf("inject", "--lib", "x.dll", "--name", "Game.exe", "--spawn"))
        }
    }
}
