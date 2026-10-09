package com.rainccup.il2cpp4k.api.runtime

import com.rainccup.il2cpp4k.api.Il2Cpp
import com.rainccup.il2cpp4k.api.requireHandle

class Il2CppImage internal constructor(val address: Long) {
    val name: String get() = Il2Cpp.api.imageGetName(address)

    fun classOf(name: String, namespace: String = ""): Il2CppClass = Il2CppClass(
        Il2Cpp.api.requireHandle(Il2Cpp.api.classFromName(address, namespace, name), "class $name"),
    )

    val classes: List<Il2CppClass>
        get() = (0 until Il2Cpp.api.imageClassCount(address))
            .map { Il2CppClass(Il2Cpp.api.imageGetClass(address, it)) }
}

class Il2CppDomain internal constructor(private val domain: Long) {
    val assemblies: List<Il2CppImage>
        get() = Il2Cpp.api.domainGetAssemblies(domain)
            .map { Il2CppImage(Il2Cpp.api.assemblyGetImage(it)) }
}
