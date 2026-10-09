package com.rainccup.il2cpp4k.api.hook

import com.rainccup.il2cpp4k.core.*
import com.rainccup.il2cpp4k.api.Il2Cpp
import com.rainccup.il2cpp4k.api.Il2CppException
import com.rainccup.il2cpp4k.api.runtime.Il2CppMethod
import com.rainccup.il2cpp4k.api.types.il2cppTypeOf

/** 已安装的 hook。[unhook] 恢复原始字节并释放相关资源。 */
class Hook internal constructor(
    private val engine: EngineHook,
    private val site: CallSite?,
) {
    val address: Long get() = engine.target

    fun unhook() {
        site?.close()
        engine.unhook()
    }
}

class HookScope0<R> @PublishedApi internal constructor(private val call: (List<Any?>) -> Any?) {
    fun original(): R = call(emptyList()) as R
}

class HookScope1<A, R> @PublishedApi internal constructor(private val call: (List<Any?>) -> Any?) {
    fun original(a: A): R = call(listOf(a)) as R
}

class HookScope2<A, B, R> @PublishedApi internal constructor(private val call: (List<Any?>) -> Any?) {
    fun original(a: A, b: B): R = call(listOf(a, b)) as R
}

class HookScope3<A, B, C, R> @PublishedApi internal constructor(private val call: (List<Any?>) -> Any?) {
    fun original(a: A, b: B, c: C): R = call(listOf(a, b, c)) as R
}

class HookScope4<A, B, C, D, R> @PublishedApi internal constructor(private val call: (List<Any?>) -> Any?) {
    fun original(a: A, b: B, c: C, d: D): R = call(listOf(a, b, c, d)) as R
}

class HookScope5<A, B, C, D, E, R> @PublishedApi internal constructor(private val call: (List<Any?>) -> Any?) {
    fun original(a: A, b: B, c: C, d: D, e: E): R = call(listOf(a, b, c, d, e)) as R
}

class HookScope6<A, B, C, D, E, F, R> @PublishedApi internal constructor(private val call: (List<Any?>) -> Any?) {
    fun original(a: A, b: B, c: C, d: D, e: E, f: F): R = call(listOf(a, b, c, d, e, f)) as R
}

/** 类型安全 hook（arity 0）。 */
inline fun <reified R> hook(target: Long, noinline body: HookScope0<R>.() -> R): Hook =
    installTypedHook(target, emptyList(), il2cppTypeOf<R>()) { _, original ->
        HookScope0<R>(original).body()
    }

/** 类型安全 hook（arity 1）。 */
inline fun <reified A, reified R> hook(target: Long, noinline body: HookScope1<A, R>.(A) -> R): Hook =
    installTypedHook(target, listOf(il2cppTypeOf<A>()), il2cppTypeOf<R>()) { args, original ->
        HookScope1<A, R>(original).body(args[0] as A)
    }

inline fun <reified A, reified B, reified R> hook(
    target: Long,
    noinline body: HookScope2<A, B, R>.(A, B) -> R,
): Hook = installTypedHook(
    target,
    listOf(il2cppTypeOf<A>(), il2cppTypeOf<B>()),
    il2cppTypeOf<R>(),
) { args, original ->
    HookScope2<A, B, R>(original).body(args[0] as A, args[1] as B)
}

inline fun <reified A, reified B, reified C, reified R> hook(
    target: Long,
    noinline body: HookScope3<A, B, C, R>.(A, B, C) -> R,
): Hook = installTypedHook(
    target,
    listOf(il2cppTypeOf<A>(), il2cppTypeOf<B>(), il2cppTypeOf<C>()),
    il2cppTypeOf<R>(),
) { args, original ->
    HookScope3<A, B, C, R>(original).body(args[0] as A, args[1] as B, args[2] as C)
}

inline fun <reified A, reified B, reified C, reified D, reified R> hook(
    target: Long,
    noinline body: HookScope4<A, B, C, D, R>.(A, B, C, D) -> R,
): Hook = installTypedHook(
    target,
    listOf(il2cppTypeOf<A>(), il2cppTypeOf<B>(), il2cppTypeOf<C>(), il2cppTypeOf<D>()),
    il2cppTypeOf<R>(),
) { args, original ->
    HookScope4<A, B, C, D, R>(original).body(args[0] as A, args[1] as B, args[2] as C, args[3] as D)
}

inline fun <reified A, reified B, reified C, reified D, reified E, reified R> hook(
    target: Long,
    noinline body: HookScope5<A, B, C, D, E, R>.(A, B, C, D, E) -> R,
): Hook = installTypedHook(
    target,
    listOf(
        il2cppTypeOf<A>(), il2cppTypeOf<B>(), il2cppTypeOf<C>(),
        il2cppTypeOf<D>(), il2cppTypeOf<E>(),
    ),
    il2cppTypeOf<R>(),
) { args, original ->
    HookScope5<A, B, C, D, E, R>(original)
        .body(args[0] as A, args[1] as B, args[2] as C, args[3] as D, args[4] as E)
}

inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified R> hook(
    target: Long,
    noinline body: HookScope6<A, B, C, D, E, F, R>.(A, B, C, D, E, F) -> R,
): Hook = installTypedHook(
    target,
    listOf(
        il2cppTypeOf<A>(), il2cppTypeOf<B>(), il2cppTypeOf<C>(),
        il2cppTypeOf<D>(), il2cppTypeOf<E>(), il2cppTypeOf<F>(),
    ),
    il2cppTypeOf<R>(),
) { args, original ->
    HookScope6<A, B, C, D, E, F, R>(original)
        .body(args[0] as A, args[1] as B, args[2] as C, args[3] as D, args[4] as E, args[5] as F)
}

// 以 Il2CppMethod 为目标的重载

inline fun <reified R> Il2CppMethod.hook(noinline body: HookScope0<R>.() -> R): Hook =
    hook(pointer, body)

inline fun <reified A, reified R> Il2CppMethod.hook(noinline body: HookScope1<A, R>.(A) -> R): Hook =
    hook(pointer, body)

inline fun <reified A, reified B, reified R> Il2CppMethod.hook(
    noinline body: HookScope2<A, B, R>.(A, B) -> R,
): Hook = hook(pointer, body)

inline fun <reified A, reified B, reified C, reified R> Il2CppMethod.hook(
    noinline body: HookScope3<A, B, C, R>.(A, B, C) -> R,
): Hook = hook(pointer, body)

inline fun <reified A, reified B, reified C, reified D, reified R> Il2CppMethod.hook(
    noinline body: HookScope4<A, B, C, D, R>.(A, B, C, D) -> R,
): Hook = hook(pointer, body)

inline fun <reified A, reified B, reified C, reified D, reified E, reified R> Il2CppMethod.hook(
    noinline body: HookScope5<A, B, C, D, E, R>.(A, B, C, D, E) -> R,
): Hook = hook(pointer, body)

inline fun <reified A, reified B, reified C, reified D, reified E, reified F, reified R> Il2CppMethod.hook(
    noinline body: HookScope6<A, B, C, D, E, F, R>.(A, B, C, D, E, F) -> R,
): Hook = hook(pointer, body)

/** 基础 Hook API：按「模块 + 偏移」定位，回调直接操作原始槽位。 */
fun hook(module: String, offset: Long, callback: (RawHookContext) -> Unit): Hook {
    val base = Module.base(module)
    if (base == 0L) throw Il2CppException("module not found: $module")
    return Hook(
        HookEngine.hookByAddress(base + offset, platformCallingConvention, platformStubGenerator, callback),
        null,
    )
}
