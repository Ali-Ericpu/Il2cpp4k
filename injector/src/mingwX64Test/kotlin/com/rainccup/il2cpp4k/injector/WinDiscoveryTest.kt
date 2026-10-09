package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.ExperimentalForeignApi
import platform.windows.GetCurrentProcessId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class WinDiscoveryTest {
    private fun selfName(pid: Int): String =
        Win32.listProcesses().first { it.first == pid }.second

    @Test
    fun findsOwnProcessByPattern() {
        val pid = GetCurrentProcessId().toInt()
        val found = listTargets(selfName(pid))
        assertTrue(found.any { it.pid == pid }, "should find the test process itself by name pattern")
    }

    @Test
    fun il2cppFlagIsNullForNonUnityProcess() {
        val pid = GetCurrentProcessId().toInt()
        val self = listTargets(selfName(pid)).first { it.pid == pid }
        assertEquals(null, self.il2cppModule)
    }

    @Test
    fun isModuleLoadedIsFalseForGameAssembly() {
        val pid = GetCurrentProcessId().toInt()
        assertTrue(!isModuleLoaded(pid, il2cppModuleName))
    }

    @Test
    fun moduleListContainsExePathOfSelf() {
        val pid = GetCurrentProcessId().toInt()
        val modules = Win32.listModules(pid)
        assertTrue(
            modules.any {
                it.name.endsWith(".exe", ignoreCase = true) || it.name.endsWith(".kexe", ignoreCase = true)
            },
        )
    }
}
