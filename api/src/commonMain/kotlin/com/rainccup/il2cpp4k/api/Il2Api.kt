package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.api.types.Il2CppKind

/**
 * il2cpp C API 绑定。所有句柄均为原始指针的 Long 表示。
 *
 * 做成接口的原因：真实实现通过目标模块的导出符号解析，而测试可以在无 Unity 进程的
 * 机器上注入伪造实现（见 mingwX64Test 的 FakeIl2Api）。
 */
interface Il2Api {
    fun domainGet(): Long
    fun domainGetAssemblies(domain: Long): LongArray
    fun assemblyGetImage(assembly: Long): Long
    fun imageGetName(image: Long): String
    fun imageClassCount(image: Long): Int
    fun imageGetClass(image: Long, index: Int): Long
    fun classFromName(image: Long, namespace: String, name: String): Long
    fun classGetName(klass: Long): String
    fun classGetMethodFromName(klass: Long, name: String, paramCount: Int): Long
    fun classGetMethods(klass: Long): LongArray
    fun classGetFields(klass: Long): LongArray
    fun methodGetName(method: Long): String
    fun methodGetParamCount(method: Long): Int
    fun methodGetFlags(method: Long): Int

    /** 返回类型分类（经 il2cpp_method_get_return_type + il2cpp_type_get_type 映射）。 */
    fun methodGetReturnKind(method: Long): Il2CppKind

    /** MethodInfo->methodPointer（首字段，全版本稳定）。 */
    fun methodGetPointer(method: Long): Long

    fun fieldGetName(field: Long): String
    fun objectNew(klass: Long): Long
    fun stringNew(value: String): Long
    fun stringLength(str: Long): Int

    /** il2cpp 字符串内容（UTF-8）读取。 */
    fun stringChars(str: Long): String
}

internal const val METHOD_ATTRIBUTE_STATIC = 0x0010

internal fun Il2Api.requireHandle(handle: Long, what: String): Long =
    if (handle == 0L) throw Il2CppException("$what not found") else handle
