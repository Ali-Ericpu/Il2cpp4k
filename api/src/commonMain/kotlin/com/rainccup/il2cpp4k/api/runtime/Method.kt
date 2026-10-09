package com.rainccup.il2cpp4k.api.runtime

import com.rainccup.il2cpp4k.api.Il2Cpp
import com.rainccup.il2cpp4k.api.METHOD_ATTRIBUTE_STATIC
import com.rainccup.il2cpp4k.api.hook.invokeRaw
import com.rainccup.il2cpp4k.api.hook.unboxResult
import com.rainccup.il2cpp4k.api.hook.valueOf
import com.rainccup.il2cpp4k.api.types.Il2CppKind
import com.rainccup.il2cpp4k.api.types.Il2CppType
import com.rainccup.il2cpp4k.api.types.Il2CppValue
import com.rainccup.il2cpp4k.api.types.il2cppTypeOf

class Il2CppMethod internal constructor(val address: Long) {
    val name: String get() = Il2Cpp.api.methodGetName(address)

    val paramCount: Int get() = Il2Cpp.api.methodGetParamCount(address)

    val isStatic: Boolean
        get() = (Il2Cpp.api.methodGetFlags(address) and METHOD_ATTRIBUTE_STATIC) != 0

    /** MethodInfo->methodPointer（首字段，全版本稳定）：可直接调用的函数指针。 */
    val pointer: Long get() = Il2Cpp.api.methodGetPointer(address)

    /**
     * 值包装调用。返回类型由 il2cpp 方法元数据推断。
     * 静态方法传 `null` 作为 [instance]；void 返回 `null`。
     */
    fun invoke(instance: Il2CppObject?, vararg args: Il2CppValue): Il2CppValue? = invokeRaw(
        pointer,
        args.toList(),
        hasInstance = !isStatic,
        instance = instance?.address ?: 0L,
        returnType = returnKindToType(Il2Cpp.api.methodGetReturnKind(address)),
    )

    /**
     * 类型安全调用：参数自动装箱，返回值按 [R] 解包。
     * 静态方法传 `null` 作为 [instance]。
     */
    inline fun <reified R> call(instance: Il2CppObject?, vararg args: Any?): R {
        val expected = il2cppTypeOf<R>()
        return unboxResult(
            invokeRaw(
                pointer,
                args.map { valueOf(it) },
                hasInstance = !isStatic,
                instance = instance?.address ?: 0L,
                returnType = expected,
            ),
            expected,
        ) as R
    }
}

internal fun returnKindToType(kind: Il2CppKind): Il2CppType = when (kind) {
    Il2CppKind.VOID -> Il2Cpp.Void
    Il2CppKind.BOOLEAN -> Il2Cpp.Boolean
    Il2CppKind.INT32 -> Il2Cpp.Int
    Il2CppKind.INT64 -> Il2Cpp.Long
    Il2CppKind.FLOAT -> Il2Cpp.Float
    Il2CppKind.DOUBLE -> Il2Cpp.Double
    Il2CppKind.POINTER -> Il2Cpp.Pointer
    Il2CppKind.STRING -> Il2Cpp.String
    Il2CppKind.OBJECT -> Il2Cpp.Object
}
