package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.api.types.Il2CppKind

/**
 * 伪造 il2cpp 运行时：句柄为递增计数器，名称/成员关系保存在 Kotlin 表里。
 *
 * [registerMethod] 的 pointer 可指向真实可执行机器码，从而让调用链能被端到端执行。
 * 字符串用 Kotlin String 直接保存，`stringLength`/`stringChars` 自洽。
 */
class FakeIl2Api : Il2Api {
    private var counter = 0x1000L

    private fun next(): Long = counter++

    private val domain = next()
    private val assembly = next()
    private var image: Long = 0L

    private val imageNames = mutableMapOf<Long, String>()
    private val klassNames = mutableMapOf<Long, String>()
    private val classNamesByKey = mutableMapOf<Pair<Long, String>, Long>()
    private val imageClasses = mutableMapOf<Long, MutableList<Long>>()
    private val methodNames = mutableMapOf<Long, String>()
    private val methodParamCounts = mutableMapOf<Long, Int>()
    private val methodFlags = mutableMapOf<Long, Int>()
    private val methodPointers = mutableMapOf<Long, Long>()
    private val methodReturnKinds = mutableMapOf<Long, Il2CppKind>()
    private val classMethods = mutableMapOf<Long, MutableList<Long>>()
    private val classFields = mutableMapOf<Long, MutableList<Long>>()
    private val fieldNames = mutableMapOf<Long, String>()
    private val strings = mutableMapOf<Long, String>()
    private val allocatedObjects = mutableListOf<Long>()

    fun registerImage(name: String): Long = next().also {
        image = it
        imageNames[it] = name
    }

    fun registerClass(image: Long, name: String, namespace: String = ""): Long = next().also {
        klassNames[it] = name
        classNamesByKey[image to "$namespace::$name"] = it
        imageClasses.getOrPut(image) { mutableListOf() }.add(it)
    }

    fun registerMethod(
        klass: Long,
        name: String,
        paramCount: Int,
        pointer: Long,
        isStatic: Boolean = false,
        returnKind: Il2CppKind = Il2CppKind.INT32,
    ): Long = next().also {
        methodNames[it] = name
        methodParamCounts[it] = paramCount
        methodFlags[it] = if (isStatic) METHOD_ATTRIBUTE_STATIC else 0
        methodPointers[it] = pointer
        methodReturnKinds[it] = returnKind
        classMethods.getOrPut(klass) { mutableListOf() }.add(it)
    }

    fun registerField(klass: Long, name: String): Long = next().also {
        fieldNames[it] = name
        classFields.getOrPut(klass) { mutableListOf() }.add(it)
    }

    /** 已创建对象的句柄（供 [objectNew] 断言）。 */
    val objects: List<Long> get() = allocatedObjects

    override fun domainGet(): Long = domain

    override fun domainGetAssemblies(domain: Long): LongArray = longArrayOf(assembly)

    override fun assemblyGetImage(assembly: Long): Long = image

    override fun imageGetName(image: Long): String = imageNames[image] ?: ""

    override fun imageClassCount(image: Long): Int = imageClasses[image]?.size ?: 0

    override fun imageGetClass(image: Long, index: Int): Long = imageClasses[image]!![index]

    override fun classFromName(image: Long, namespace: String, name: String): Long =
        classNamesByKey[image to "$namespace::$name"] ?: 0L

    override fun classGetName(klass: Long): String = klassNames[klass] ?: ""

    override fun classGetMethodFromName(klass: Long, name: String, paramCount: Int): Long =
        classMethods[klass]?.firstOrNull {
            methodNames[it] == name && (paramCount < 0 || methodParamCounts[it] == paramCount)
        } ?: 0L

    override fun classGetMethods(klass: Long): LongArray = classMethods[klass]?.toLongArray() ?: LongArray(0)

    override fun classGetFields(klass: Long): LongArray = classFields[klass]?.toLongArray() ?: LongArray(0)

    override fun methodGetName(method: Long): String = methodNames[method] ?: ""

    override fun methodGetParamCount(method: Long): Int = methodParamCounts[method] ?: 0

    override fun methodGetFlags(method: Long): Int = methodFlags[method] ?: 0

    override fun methodGetReturnKind(method: Long): Il2CppKind =
        methodReturnKinds[method] ?: Il2CppKind.VOID

    override fun methodGetPointer(method: Long): Long = methodPointers[method] ?: 0L

    override fun fieldGetName(field: Long): String = fieldNames[field] ?: ""

    override fun objectNew(klass: Long): Long = next().also { allocatedObjects.add(it) }

    override fun stringNew(value: String): Long = next().also { strings[it] = value }

    override fun stringLength(str: Long): Int = strings[str]?.length ?: 0

    override fun stringChars(str: Long): String = strings[str] ?: ""
}
