package org.plantalpha.il2cpp4k

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Il2cpp4k",
    ) {
        App()
    }
}