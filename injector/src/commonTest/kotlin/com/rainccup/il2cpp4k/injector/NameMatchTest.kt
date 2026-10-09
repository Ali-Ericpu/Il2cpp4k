package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameMatchTest {
    @Test
    fun exactNameIgnoresCaseAndExeSuffix() {
        assertTrue(matchesExactName("Game.exe", "Game.exe"))
        assertTrue(matchesExactName("Game.exe", "game"))
        assertTrue(matchesExactName("GAME.EXE", "game"))
        assertTrue(matchesExactName("game", "Game.exe"))
        assertFalse(matchesExactName("game2.exe", "game"))
        assertFalse(matchesExactName("mygame.exe", "game"))
    }

    @Test
    fun patternMatchesSubstringIgnoringCase() {
        assertTrue(matchesPattern("MyGame.exe", "game"))
        assertTrue(matchesPattern("libil2cpp.so", "IL2CPP"))
        assertFalse(matchesPattern("other.exe", "game"))
    }
}
