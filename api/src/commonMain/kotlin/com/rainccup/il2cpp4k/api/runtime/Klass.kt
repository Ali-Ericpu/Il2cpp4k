package com.rainccup.il2cpp4k.api.runtime

import com.rainccup.il2cpp4k.api.Il2Cpp
import com.rainccup.il2cpp4k.api.Il2CppException
import com.rainccup.il2cpp4k.api.hook.valueOf
import com.rainccup.il2cpp4k.api.requireHandle
import com.rainccup.il2cpp4k.api.types.Il2CppValue

class Il2CppClass internal constructor(val address: Long) {
    val name: String get() = Il2Cpp.api.classGetName(address)

    fun method(name: String, paramCount: Int = -1): Il2CppMethod = Il2CppMethod(
        Il2Cpp.api.requireHandle(
            Il2Cpp.api.classGetMethodFromName(address, name, paramCount),
            "method $name",
        ),
    )

    fun field(name: String): Il2CppField =
        fields.firstOrNull { it.name == name } ?: throw Il2CppException("field not found: $name")

    val methods: List<Il2CppMethod> get() = Il2Cpp.api.classGetMethods(address).map { Il2CppMethod(it) }

    val fields: List<Il2CppField> get() = Il2Cpp.api.classGetFields(address).map { Il2CppField(it) }

    /**
     * 调用目标类型构造器创建对象：`il2cpp_object_new` 分配 + `.ctor` 调用（值包装版）。
     *
     * 类型安全版命名为 [create]（而非 `new` 重载）：`new()` 无参调用下 `vararg Il2CppValue`
     * 与 `vararg Any?` 会重载歧义。
     */
    fun new(vararg args: Il2CppValue): Il2CppObject {
        val handle = Il2Cpp.api.objectNew(address)
        if (handle == 0L) throw Il2CppException("il2cpp_object_new failed for $name")
        val instance = Il2CppObject(handle)
        method(".ctor", args.size).invoke(instance, *args)
        return instance
    }

    /** 类型安全版构造：参数自动装箱，如 `klass.create("hero", 100)`。 */
    inline fun create(vararg args: Any?): Il2CppObject = newValues(args.map { valueOf(it) })

    @PublishedApi
    internal fun newValues(args: List<Il2CppValue>): Il2CppObject = new(*args.toTypedArray())
}
