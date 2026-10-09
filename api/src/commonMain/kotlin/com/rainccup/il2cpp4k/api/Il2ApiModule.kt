package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.core.Memory
import com.rainccup.il2cpp4k.core.Module
import com.rainccup.il2cpp4k.api.types.Il2CppKind
import kotlinx.cinterop.*

/**
 * 基于目标模块导出符号的真实 il2cpp API 实现。
 *
 * Windows 模块名为 `GameAssembly.dll`，Android 为 `libil2cpp.so`。
 * 任何必需符号缺失都会在 [create] 时抛 [Il2CppException]。
 */
@OptIn(ExperimentalForeignApi::class)
class Il2ApiModule private constructor(private val fn: Map<String, Long>) : Il2Api {

    private fun need(name: String): Long =
        fn[name] ?: throw Il2CppException("il2cpp symbol not found: $name")

    override fun domainGet(): Long {
        val f = need("il2cpp_domain_get").toCPointer<CFunction<() -> Long>>()!!
        return f.invoke()
    }

    override fun domainGetAssemblies(domain: Long): LongArray = memScoped {
        val size = alloc<ULongVar>()
        size.value = 0uL
        val f = need("il2cpp_domain_get_assemblies")
            .toCPointer<CFunction<(Long, CPointer<ULongVar>) -> Long>>()!!
        val arrayPtr = f.invoke(domain, size.ptr)
        if (arrayPtr == 0L) return LongArray(0)
        val count = size.value.toInt()
        LongArray(count) { i -> bytesToLong(Memory.readBytes(arrayPtr + i * 8L, 8)) }
    }

    override fun assemblyGetImage(assembly: Long): Long {
        val f = need("il2cpp_assembly_get_image").toCPointer<CFunction<(Long) -> Long>>()!!
        return f.invoke(assembly)
    }

    override fun imageGetName(image: Long): String = cString(pointerCall("il2cpp_image_get_name", image))

    override fun imageClassCount(image: Long): Int =
        pointerCall("il2cpp_image_get_class_count", image).toInt()

    override fun imageGetClass(image: Long, index: Int): Long {
        val f = need("il2cpp_image_get_class").toCPointer<CFunction<(Long, Long) -> Long>>()!!
        return f.invoke(image, index.toLong())
    }

    override fun classFromName(image: Long, namespace: String, name: String): Long = memScoped {
        val f = need("il2cpp_class_from_name")
            .toCPointer<CFunction<(Long, CPointer<ByteVar>?, CPointer<ByteVar>) -> Long>>()!!
        f.invoke(image, if (namespace.isEmpty()) null else namespace.cstr.ptr, name.cstr.ptr)
    }

    override fun classGetName(klass: Long): String = cString(pointerCall("il2cpp_class_get_name", klass))

    override fun classGetMethodFromName(klass: Long, name: String, paramCount: Int): Long = memScoped {
        val f = need("il2cpp_class_get_method_from_name")
            .toCPointer<CFunction<(Long, CPointer<ByteVar>, Int) -> Long>>()!!
        f.invoke(klass, name.cstr.ptr, paramCount)
    }

    /** il2cpp_class_get_methods / il2cpp_class_get_fields 共用 (klass, void** iter) 迭代器协议。 */
    override fun classGetMethods(klass: Long): LongArray = iterate(klass, "il2cpp_class_get_methods")

    override fun classGetFields(klass: Long): LongArray = iterate(klass, "il2cpp_class_get_fields")

    override fun methodGetName(method: Long): String = cString(pointerCall("il2cpp_method_get_name", method))

    override fun methodGetParamCount(method: Long): Int {
        val f = need("il2cpp_method_get_param_count").toCPointer<CFunction<(Long) -> Int>>()!!
        return f.invoke(method)
    }

    override fun methodGetFlags(method: Long): Int {
        val f = need("il2cpp_method_get_flags").toCPointer<CFunction<(Long, CPointer<UIntVar>?) -> Int>>()!!
        return f.invoke(method, null)
    }

    override fun methodGetReturnKind(method: Long): Il2CppKind {
        val getReturnType = need("il2cpp_method_get_return_type").toCPointer<CFunction<(Long) -> Long>>()!!
        val getType = need("il2cpp_type_get_type").toCPointer<CFunction<(Long) -> Int>>()!!
        val type = getReturnType.invoke(method)
        if (type == 0L) return Il2CppKind.VOID
        return when (getType.invoke(type)) {
            1 -> Il2CppKind.VOID
            2 -> Il2CppKind.BOOLEAN
            3, 4, 5, 6, 7, 8, 9, 16, 17 -> Il2CppKind.INT32 // I1/U1/I2/U2/I4/U4/CHAR/I
            10, 11 -> Il2CppKind.INT64 // I8/U8
            12 -> Il2CppKind.FLOAT // R4
            13 -> Il2CppKind.DOUBLE // R8
            14 -> Il2CppKind.STRING // STRING
            15, 22 -> Il2CppKind.POINTER // PTR / FNPTR
            else -> Il2CppKind.OBJECT
        }
    }

    override fun methodGetPointer(method: Long): Long = bytesToLong(Memory.readBytes(method, 8))

    override fun fieldGetName(field: Long): String = cString(pointerCall("il2cpp_field_get_name", field))

    override fun objectNew(klass: Long): Long {
        val f = need("il2cpp_object_new").toCPointer<CFunction<(Long) -> Long>>()!!
        return f.invoke(klass)
    }

    override fun stringNew(value: String): Long = memScoped {
        val f = need("il2cpp_string_new").toCPointer<CFunction<(CPointer<ByteVar>) -> Long>>()!!
        f.invoke(value.cstr.ptr)
    }

    override fun stringLength(str: Long): Int {
        val f = need("il2cpp_string_length").toCPointer<CFunction<(Long) -> Int>>()!!
        return f.invoke(str)
    }

    override fun stringChars(str: Long): String = cString(pointerCall("il2cpp_string_chars", str))

    private fun pointerCall(symbol: String, arg: Long): Long {
        val f = need(symbol).toCPointer<CFunction<(Long) -> Long>>()!!
        return f.invoke(arg)
    }

    private fun iterate(klass: Long, symbol: String): LongArray = memScoped {
        val f = need(symbol).toCPointer<CFunction<(Long, CPointer<COpaquePointerVar>) -> Long>>()!!
        val iter = alloc<COpaquePointerVar>()
        iter.value = null
        val out = ArrayList<Long>()
        while (true) {
            val item = f.invoke(klass, iter.ptr)
            if (item == 0L) break
            out.add(item)
        }
        out.toLongArray()
    }

    private fun cString(address: Long): String {
        if (address == 0L) return ""
        val bytes = ArrayList<Byte>(32)
        var i = 0L
        while (true) {
            val b = Memory.readBytes(address + i, 1)[0]
            if (b.toInt() == 0) break
            bytes.add(b)
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun bytesToLong(bytes: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = v or ((bytes[i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    companion object {
        private val REQUIRED = listOf(
            "il2cpp_domain_get",
            "il2cpp_domain_get_assemblies",
            "il2cpp_assembly_get_image",
            "il2cpp_image_get_name",
            "il2cpp_image_get_class_count",
            "il2cpp_image_get_class",
            "il2cpp_class_from_name",
            "il2cpp_class_get_name",
            "il2cpp_class_get_method_from_name",
            "il2cpp_class_get_methods",
            "il2cpp_class_get_fields",
            "il2cpp_method_get_name",
            "il2cpp_method_get_param_count",
            "il2cpp_method_get_flags",
            "il2cpp_method_get_return_type",
            "il2cpp_type_get_type",
            "il2cpp_field_get_name",
            "il2cpp_object_new",
            "il2cpp_string_new",
            "il2cpp_string_length",
            "il2cpp_string_chars",
        )

        fun create(moduleName: String): Il2ApiModule {
            if (Module.base(moduleName) == 0L) {
                throw Il2CppException("module not found: $moduleName")
            }
            val table = REQUIRED.associateWith { name ->
                Module.symbol(moduleName, name) ?: throw Il2CppException("il2cpp symbol not found: $name")
            }
            return Il2ApiModule(table)
        }
    }
}
