package org.plantalpha.il2cpp4k

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform