package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolSourceTest {
    private val entries = parseMaps(
        listOf(
            "7a2d000000-7a2d004000 r-xp 00000000 fd:00 5678 /data/app/com.game/lib/arm64/libil2cpp.so",
            "7b00000000-7b00001000 r-xp 00000000 fd:00 9999 /apex/com.android.runtime/bin/linker64",
            "7b01000000-7b01002000 r--p 00000000 fd:00 9999 /apex/com.android.runtime/bin/linker64",
            "7b10000000-7b10010000 r-xp 00000000 fd:00 4321 /system/lib64/libdl.so",
        ).joinToString("\n"),
    )

    @Test
    fun resolvesBaseAndLocalPath() {
        assertEquals(0x7b00000000L to "/apex/com.android.runtime/bin/linker64", symbolSource(entries, "linker64"))
        assertEquals(0x7b10000000L to "/system/lib64/libdl.so", symbolSource(entries, "libdl.so"))
    }

    @Test
    fun returnsNullForMissingModule() {
        assertEquals(null, symbolSource(entries, "libmissing.so"))
    }
}
