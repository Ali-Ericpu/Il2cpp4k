package com.rainccup.il2cpp4k.api

import com.rainccup.il2cpp4k.core.HookEngine
import com.rainccup.il2cpp4k.logging.Log

/**
 * 入口正文安全包装：捕获一切异常，绝不让异常越过 C 边界（跨边界抛出是未定义行为）。
 *
 * 消费方的脚本写成自己的入口函数即可，无需任何挂载或时序考虑：
 *
 * ```kotlin
 * @OptIn(ExperimentalNativeApi::class)
 * @CName("il2cpp4k_init")
 * fun myHooks() = il2cpp4kRun {
 *     Il2Cpp.initialize(Il2ApiModule.create("GameAssembly.dll"))
 *     Log.i { "script started" }
 *     Il2Cpp.image("Assembly-CSharp.dll").classOf("Player").method("TakeDamage", 2)
 *         .hook<Il2CppObject?, Int, Int> { self, damage -> original(self, damage) }
 * }
 * ```
 *
 * `@CName` 的作用是给函数固定一个能被外部（注入器 / 目标自身代码）找到的符号名；
 * 该声明在消费方自己的模块里，因此不会被 Kotlin/Native 的 DCE 丢弃，并会自动出现在导出表中。
 */
fun il2cpp4kRun(body: () -> Unit) {
    // 默认把 hook 回调里的异常接到日志；调用方若已自设处理器则尊重之
    if (HookEngine.onHookError == null) {
        HookEngine.onHookError = { error -> Log.w(error) { "hook callback failed" } }
    }
    try {
        body()
    } catch (error: Throwable) {
        val handler = il2cpp4kOnError
        if (handler != null) {
            handler(error)
        } else {
            Log.e(error) { "hook script failed" }
        }
    }
}

/** 自定义入口异常处理；不设置时记一条 error 日志。 */
var il2cpp4kOnError: ((Throwable) -> Unit)? = null
