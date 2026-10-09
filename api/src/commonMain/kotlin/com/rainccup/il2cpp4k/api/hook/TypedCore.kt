package com.rainccup.il2cpp4k.api.hook

import com.rainccup.il2cpp4k.api.Il2Cpp
import com.rainccup.il2cpp4k.api.Il2CppException
import com.rainccup.il2cpp4k.core.*
import com.rainccup.il2cpp4k.api.runtime.Il2CppObject
import com.rainccup.il2cpp4k.api.types.*

/** 把一个 Kotlin 值按类型装箱为 [Il2CppValue]（供 `call`/`new` 的类型安全重载使用）。 */
@PublishedApi
internal fun valueOf(value: Any?): Il2CppValue = when (value) {
    is Int -> Il2Cpp.Int(value)
    is Long -> Il2Cpp.Long(value)
    is Float -> Il2Cpp.Float(value)
    is Double -> Il2Cpp.Double(value)
    is Boolean -> Il2Cpp.Boolean(value)
    is String -> Il2Cpp.String(value)
    is Il2CppObject -> Il2Cpp.Object(value.address)
    null -> Il2Cpp.Pointer(0L)
    else -> throw Il2CppException("cannot box value of type ${value::class.simpleName}")
}

internal fun writeValueToSite(site: CallSite, slot: ArgSlot, value: Il2CppValue) {
    val offset = site.cc.offsetOf(slot)
    when (value) {
        is Int32Value -> site.writeLong(offset, value.value.toLong())
        is Int64Value -> site.writeLong(offset, value.value)
        is BooleanValue -> site.writeLong(offset, if (value.value) 1L else 0L)
        is FloatValue -> site.writeFloatAt(offset, value.value)
        is DoubleValue -> site.writeDoubleAt(offset, value.value)
        is PointerValue -> site.writeLong(offset, value.address)
        is StringValue -> site.writeLong(offset, value.address)
        is ObjectValue -> site.writeLong(offset, value.address)
    }
}

internal fun unpack(ctx: RawHookContext, cc: CallingConvention, slots: List<ArgSlot>, types: List<Il2CppType>): List<Any?> =
    types.mapIndexed { i, type -> readSlotValue(ctx, cc, slots[i], type) }

private fun readSlotValue(ctx: RawHookContext, cc: CallingConvention, slot: ArgSlot, type: Il2CppType): Any? =
    when (type.kind) {
        Il2CppKind.VOID -> Unit
        Il2CppKind.BOOLEAN -> readSlotLong(ctx, slot) != 0L
        Il2CppKind.INT32 -> readSlotLong(ctx, slot).toInt()
        Il2CppKind.INT64 -> readSlotLong(ctx, slot)
        Il2CppKind.POINTER -> readSlotLong(ctx, slot)
        Il2CppKind.FLOAT -> readSlotFloat(ctx, slot)
        Il2CppKind.DOUBLE -> readSlotDouble(ctx, slot)
        Il2CppKind.STRING -> readSlotLong(ctx, slot).let { if (it == 0L) null else Il2Cpp.String.toKString(it) }
        Il2CppKind.OBJECT -> readSlotLong(ctx, slot).let { if (it == 0L) null else Il2CppObject(it) }
    }

private fun readSlotLong(ctx: RawHookContext, slot: ArgSlot): Long =
    if (slot.isStack) ctx.argLongStack(slot.index) else ctx.argLong(slot.index)

private fun readSlotFloat(ctx: RawHookContext, slot: ArgSlot): Float =
    if (slot.isStack) Float.fromBits(ctx.argLongStack(slot.index).toInt()) else ctx.argFloat(slot.index)

private fun readSlotDouble(ctx: RawHookContext, slot: ArgSlot): Double =
    if (slot.isStack) Double.fromBits(ctx.argLongStack(slot.index)) else ctx.argDouble(slot.index)

/** 把 hook 回调的返回值按 [type] 写回返回槽。 */
internal fun writeReturn(ctx: RawHookContext, type: Il2CppType, result: Any?) {
    when (type.kind) {
        Il2CppKind.VOID -> ctx.resultLong(0)
        Il2CppKind.BOOLEAN -> ctx.resultLong(if (result == true) 1L else 0L)
        Il2CppKind.INT32 -> ctx.resultLong((result as? Int ?: 0).toLong())
        Il2CppKind.INT64, Il2CppKind.POINTER -> ctx.resultLong(result as? Long ?: 0L)
        Il2CppKind.FLOAT -> ctx.resultFloat(result as? Float ?: 0f)
        Il2CppKind.DOUBLE -> ctx.resultDouble(result as? Double ?: 0.0)
        Il2CppKind.STRING -> ctx.resultLong((valueOf(result) as StringValue).address)
        Il2CppKind.OBJECT -> ctx.resultLong((result as? Il2CppObject)?.address ?: 0L)
    }
}

internal fun readReturnValue(site: CallSite, type: Il2CppType): Il2CppValue? = when (type.kind) {
    Il2CppKind.VOID -> null
    Il2CppKind.BOOLEAN -> Il2Cpp.Boolean(site.readLong(site.cc.retIntOffset) != 0L)
    Il2CppKind.INT32 -> Il2Cpp.Int(site.readLong(site.cc.retIntOffset).toInt())
    Il2CppKind.INT64 -> Il2Cpp.Long(site.readLong(site.cc.retIntOffset))
    Il2CppKind.POINTER -> Il2Cpp.Pointer(site.readLong(site.cc.retIntOffset))
    Il2CppKind.FLOAT -> Il2Cpp.Float(site.readFloatAt(site.cc.retFloatOffset))
    Il2CppKind.DOUBLE -> Il2Cpp.Double(site.readDoubleAt(site.cc.retFloatOffset))
    Il2CppKind.STRING -> StringValue(site.readLong(site.cc.retIntOffset))
    Il2CppKind.OBJECT -> Il2Cpp.Object(site.readLong(site.cc.retIntOffset))
}

@PublishedApi
internal fun unboxResult(value: Il2CppValue?, expected: Il2CppType): Any? = when (expected.kind) {
    Il2CppKind.VOID -> Unit
    Il2CppKind.BOOLEAN -> (value as? BooleanValue)?.value ?: false
    Il2CppKind.INT32 -> (value as? Int32Value)?.value ?: 0
    Il2CppKind.INT64, Il2CppKind.POINTER ->
        (value as? Int64Value)?.value ?: (value as? PointerValue)?.address ?: 0L
    Il2CppKind.FLOAT -> (value as? FloatValue)?.value ?: 0f
    Il2CppKind.DOUBLE -> (value as? DoubleValue)?.value ?: 0.0
    Il2CppKind.STRING -> (value as? StringValue)?.address?.let { Il2Cpp.String.toKString(it) }
    Il2CppKind.OBJECT -> (value as? ObjectValue)?.address?.let { Il2CppObject(it) }
}

/**
 * 调用任意函数指针：静态函数 [hasInstance] 传 false；实例方法第一位槽传 [instance]。
 * 返回值按 [returnType] 解包。
 */
@PublishedApi
internal fun invokeRaw(
    target: Long,
    values: List<Il2CppValue>,
    hasInstance: Boolean,
    instance: Long,
    returnType: Il2CppType,
): Il2CppValue? {
    val cc = platformCallingConvention
    val tracks = buildList {
        if (hasInstance) add(ParamTrack.GPR)
        values.forEach { add(it.type.slotTrack ?: throw Il2CppException("void argument is not allowed")) }
    }
    val slots = cc.assignSlots(tracks)
    val site = FunctionCaller.create(target, cc, platformStubGenerator)
    try {
        var index = 0
        if (hasInstance) {
            site.writeLong(cc.offsetOf(slots[index]), instance)
            index++
        }
        values.forEach { value ->
            writeValueToSite(site, slots[index], value)
            index++
        }
        site.invoke()
        return readReturnValue(site, returnType)
    } finally {
        site.close()
    }
}

/**
 * 安装类型安全 hook。`body` 收到解包后的参数列表与 `original(args)` 回调；
 * body 的返回值按 [returnType] 写回返回槽（即 hook 的返回）。
 */
@PublishedApi
internal fun installTypedHook(
    target: Long,
    paramTypes: List<Il2CppType>,
    returnType: Il2CppType,
    body: (List<Any?>, (List<Any?>) -> Any?) -> Any?,
): Hook {
    val cc = platformCallingConvention
    val tracks = paramTypes.map { it.slotTrack ?: throw Il2CppException("void parameter is not allowed") }
    val slots = cc.assignSlots(tracks)

    var engineHook: EngineHook? = null
    var originalSite: CallSite? = null

    val originalCall: (List<Any?>) -> Any? = { args ->
        val site = originalSite ?: throw Il2CppException("hook is not ready")
        if (args.size != paramTypes.size) {
            throw Il2CppException("original() expects ${paramTypes.size} arguments, got ${args.size}")
        }
        args.forEachIndexed { i, arg -> writeValueToSite(site, slots[i], valueOf(arg)) }
        site.invoke()
        unboxResult(readReturnValue(site, returnType), returnType)
    }

    engineHook = HookEngine.hookByAddress(target, cc, platformStubGenerator) { ctx ->
        writeReturn(ctx, returnType, body(unpack(ctx, cc, slots, paramTypes), originalCall))
    }
    originalSite = FunctionCaller.create(engineHook.trampoline, cc, platformStubGenerator)
    return Hook(engineHook, originalSite)
}
