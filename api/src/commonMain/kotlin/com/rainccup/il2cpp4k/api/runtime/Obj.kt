package com.rainccup.il2cpp4k.api.runtime

import com.rainccup.il2cpp4k.api.Il2Cpp

/** il2cpp 对象指针包装。 */
class Il2CppObject(val address: Long) {
    /** 对象首字段为 Il2CppClass*（全版本稳定约定）。 */
    val klassPointer: Long get() = Il2Cpp.readPointer(address)
}
