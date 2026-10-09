package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.core.Memory
import com.rainccup.il2cpp4k.core.ParamTrack
import com.rainccup.il2cpp4k.api.runtime.Il2CppDomain
import com.rainccup.il2cpp4k.api.runtime.Il2CppImage
import com.rainccup.il2cpp4k.api.types.*
import kotlin.concurrent.Volatile

/**
 * Il2Cpp 门面：初始化、类型导航入口，以及类型描述符（同时是值构造器）。
 *
 * `Il2Cpp.Int` 等既是类型描述符（用于 hook 参数表），也可直接构造值：`Il2Cpp.Int(42)`。
 */
object Il2Cpp {
    @Volatile
    private var apiRef: Il2Api? = null

    val api: Il2Api
        get() = apiRef ?: throw Il2CppException("Il2Cpp.initialize() has not been called")

    fun initialize(api: Il2Api) {
        apiRef = api
    }

    /** 清除已注入的 API（重初始化或测试隔离用）。 */
    fun reset() {
        apiRef = null
    }

    fun domain(): Il2CppDomain = Il2CppDomain(api.domainGet())

    /** 按程序集名称（如 `Assembly-CSharp.dll`）定位 image。 */
    fun image(name: kotlin.String): Il2CppImage {
        val target = api.domainGetAssemblies(api.domainGet())
            .map { api.assemblyGetImage(it) }
            .firstOrNull { api.imageGetName(it) == name }
            ?: throw Il2CppException("image not found: $name")
        return Il2CppImage(target)
    }

    internal fun readPointer(address: kotlin.Long): kotlin.Long {
        if (address == 0L) return 0L
        var v = 0L
        val b = Memory.readBytes(address, 8)
        for (i in 0 until 8) v = v or ((b[i].toLong() and 0xFF) shl (8 * i))
        return v
    }

    object Int : Il2CppType {
        override val kind = Il2CppKind.INT32
        override val il2Name = "System.Int32"
        override val slotTrack = ParamTrack.GPR
        operator fun invoke(value: kotlin.Int): Il2CppValue = Int32Value(value)
    }

    object Long : Il2CppType {
        override val kind = Il2CppKind.INT64
        override val il2Name = "System.Int64"
        override val slotTrack = ParamTrack.GPR
        operator fun invoke(value: kotlin.Long): Il2CppValue = Int64Value(value)
    }

    object Boolean : Il2CppType {
        override val kind = Il2CppKind.BOOLEAN
        override val il2Name = "System.Boolean"
        override val slotTrack = ParamTrack.GPR
        operator fun invoke(value: kotlin.Boolean): Il2CppValue = BooleanValue(value)
    }

    object Float : Il2CppType {
        override val kind = Il2CppKind.FLOAT
        override val il2Name = "System.Single"
        override val slotTrack = ParamTrack.FPR
        operator fun invoke(value: kotlin.Float): Il2CppValue = FloatValue(value)
    }

    object Double : Il2CppType {
        override val kind = Il2CppKind.DOUBLE
        override val il2Name = "System.Double"
        override val slotTrack = ParamTrack.FPR
        operator fun invoke(value: kotlin.Double): Il2CppValue = DoubleValue(value)
    }

    object Void : Il2CppType {
        override val kind = Il2CppKind.VOID
        override val il2Name = "System.Void"
        override val slotTrack: ParamTrack? = null
    }

    object Pointer : Il2CppType {
        override val kind = Il2CppKind.POINTER
        override val il2Name = "System.IntPtr"
        override val slotTrack = ParamTrack.GPR
        operator fun invoke(address: kotlin.Long): Il2CppValue = PointerValue(address)
    }

    object String : Il2CppType {
        override val kind = Il2CppKind.STRING
        override val il2Name = "System.String"
        override val slotTrack = ParamTrack.GPR

        /** 调用 il2cpp_string_new 构造 il2cpp 字符串，返回包装其指针的值。 */
        operator fun invoke(value: kotlin.String): Il2CppValue = StringValue(api.stringNew(value))

        /** 从 il2cpp 字符串指针读取为 Kotlin 字符串。 */
        fun toKString(address: kotlin.Long): kotlin.String = api.stringChars(address)
    }

    object Object : Il2CppType {
        override val kind = Il2CppKind.OBJECT
        override val il2Name = "System.Object"
        override val slotTrack = ParamTrack.GPR
        operator fun invoke(address: kotlin.Long): Il2CppValue = ObjectValue(address)
    }
}
