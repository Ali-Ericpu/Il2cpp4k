package com.rainccup.il2cpp4k.api.types

import com.rainccup.il2cpp4k.api.Il2Cpp
import com.rainccup.il2cpp4k.api.Il2CppException
import com.rainccup.il2cpp4k.core.ParamTrack
import com.rainccup.il2cpp4k.api.runtime.Il2CppObject

enum class Il2CppKind { VOID, BOOLEAN, INT32, INT64, FLOAT, DOUBLE, POINTER, STRING, OBJECT }

interface Il2CppType {
    val kind: Il2CppKind
    val il2Name: String

    /** null 表示无槽位（void）；FPR 为浮点轨，其余为通用轨。 */
    val slotTrack: ParamTrack?
}

sealed interface Il2CppValue {
    val type: Il2CppType
}

class Int32Value(val value: Int) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.Int
}

class Int64Value(val value: Long) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.Long
}

class BooleanValue(val value: Boolean) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.Boolean
}

class FloatValue(val value: Float) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.Float
}

class DoubleValue(val value: Double) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.Double
}

class PointerValue(val address: Long) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.Pointer
}

class StringValue(val address: Long) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.String
}

class ObjectValue(val address: Long) : Il2CppValue {
    override val type: Il2CppType get() = Il2Cpp.Object
}

inline fun <reified T> il2cppTypeOf(): Il2CppType = when (T::class) {
    Int::class -> Il2Cpp.Int
    Long::class -> Il2Cpp.Long
    Float::class -> Il2Cpp.Float
    Double::class -> Il2Cpp.Double
    Boolean::class -> Il2Cpp.Boolean
    String::class -> Il2Cpp.String
    Unit::class -> Il2Cpp.Void
    Il2CppObject::class -> Il2Cpp.Object
    else -> throw Il2CppException("unsupported parameter type: ${T::class.simpleName}")
}
