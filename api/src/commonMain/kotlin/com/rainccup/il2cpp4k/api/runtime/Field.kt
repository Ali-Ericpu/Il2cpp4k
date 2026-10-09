package com.rainccup.il2cpp4k.api.runtime

import com.rainccup.il2cpp4k.api.Il2Cpp

class Il2CppField internal constructor(val address: Long) {
    val name: String get() = Il2Cpp.api.fieldGetName(address)
}
