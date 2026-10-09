package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapsTest {
    private val maps = listOf(
        "12c00000-12c40000 rw-p 00000000 00:00 0",
        "7a1c000000-7a1c021000 r-xp 00000000 fd:00 1234 /system/lib64/libc.so",
        "7a1c200000-7a1c202000 r--p 00000000 fd:00 1234 /system/lib64/libc.so",
        "7a2d000000-7a2d004000 r-xp 00000000 fd:00 5678 /data/app/com.game/lib/arm64/libil2cpp.so",
        "7a2d100000-7a2d102000 r--p 00000000 fd:00 5678 /data/app/com.game/lib/arm64/libil2cpp.so",
        "7b00000000-7b00001000 r-xp 00000000 fd:00 9999 /apex/com.android.runtime/bin/linker64",
        "7c00000000-7c00001000 r-xp 00000000 fd:00 4321 /data/local/tmp/libil2cpp4k.so (deleted)",
    ).joinToString("\n")

    @Test
    fun parsesAllMappingsIncludingAnonymous() {
        val entries = parseMaps(maps)
        assertEquals(7, entries.size)
        assertEquals("", entries[0].path)
        assertEquals("r-xp", entries[1].permissions)
        assertEquals(0x7a1c000000L, entries[1].start)
        assertEquals(0x7a1c021000L, entries[1].end)
    }

    @Test
    fun moduleBaseIsLowestMapping() {
        val entries = parseMaps(maps)
        assertEquals(0x7a1c000000L, moduleBase(entries, "libc.so"))
        assertEquals(0x7a2d000000L, moduleBase(entries, "libil2cpp.so"))
        assertEquals(0x7b00000000L, moduleBase(entries, "linker64"))
        assertEquals(null, moduleBase(entries, "liblog.so"))
    }

    @Test
    fun detectsLoadedModulesIncludingDeletedSuffix() {
        val entries = parseMaps(maps)
        assertTrue(isModuleLoaded(entries, "libil2cpp.so"))
        assertTrue(isModuleLoaded(entries, "libil2cpp4k.so"))
        assertFalse(isModuleLoaded(entries, "libtarget.so"))
    }
}
