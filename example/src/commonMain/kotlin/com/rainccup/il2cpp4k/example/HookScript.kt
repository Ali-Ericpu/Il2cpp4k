package com.rainccup.il2cpp4k.example

import co.touchlab.kermit.Severity
import com.rainccup.il2cpp4k.api.Il2ApiModule
import com.rainccup.il2cpp4k.api.Il2Cpp
import com.rainccup.il2cpp4k.api.hook.hook
import com.rainccup.il2cpp4k.api.il2cpp4kRun
import com.rainccup.il2cpp4k.api.runtime.Il2CppObject
import com.rainccup.il2cpp4k.api.types.Il2CppValue
import com.rainccup.il2cpp4k.core.HookEngine
import com.rainccup.il2cpp4k.logging.Log
import kotlin.experimental.ExperimentalNativeApi

/** 目标程序集（Unity 托管程序集）与 il2cpp 运行时模块名。 */
private const val GAME_ASSEMBLY = "Assembly-CSharp.dll"
private const val IL2CPP_MODULE = "GameAssembly.dll" // Android 上改为 "libil2cpp.so"

/** 验证脚本的 image 匹配关键字（子串匹配）；换成你目标游戏自己的程序集名。 */
private const val TARGET_IMAGE_KEYWORD = "Assembly-CSharp"

/**
 * 本示例的所有 hook 逻辑都写在这里。
 *
 * 类名/方法签名来自示例游戏，实际使用换成自己目标里的 `image / class / method / field` 与偏移即可。
 */
private fun demoScript() {
    // ── 0) 初始化：定位 il2cpp 运行时并解析其 C API 导出符号 ────────────────
    Log.level = Severity.Debug
    Il2Cpp.initialize(Il2ApiModule.create(IL2CPP_MODULE))
    Log.i { "script started (module=$IL2CPP_MODULE, image=$GAME_ASSEMBLY)" }

    // 回调内异常会被框架吞掉（不允许跨 C 边界抛出），这里接到日志
    HookEngine.onHookError = { error ->
        Log.w(error) { "hook callback failed" }
    }

    // ── 1) 类型系统：类型描述符同时是值构造器 ──────────────────────────────
    val values: List<Il2CppValue> = listOf(
        Il2Cpp.Int(42),
        Il2Cpp.Long(1L shl 40),
        Il2Cpp.Float(1.5f),
        Il2Cpp.Double(2.5),
        Il2Cpp.Boolean(true),
        Il2Cpp.String("hero"), // 内部调用 il2cpp_string_new
        Il2Cpp.Pointer(0x1000L),
        Il2Cpp.Object(0x2000L),
    )
    Log.d { "value descriptors: ${values.map { it.type.il2Name }}" }

    // ── 2) 链式导航 → 调用 → 构造 ────────────────────────────────────────
    val player = Il2Cpp.image(GAME_ASSEMBLY).classOf("Player")
    val takeDamage = player.method("TakeDamage", paramCount = 2)
    val getHp = player.method("get_hp", paramCount = 0)
    val hpField = player.field("hp")
    Log.d {
        "navigate: $GAME_ASSEMBLY → Player → " +
                "${takeDamage.name}/${takeDamage.paramCount} (static=${takeDamage.isStatic}), field=${hpField.name}"
    }

    // 调用目标类型构造器创建对象（.ctor(string, int)）
    val spawned = player.create("hero", 100)
    // 类型安全调用：自动装箱参数、按 R 解包返回值
    val hp: Int = getHp.call<Int>(spawned)
    // 值包装调用：类型信息显式可见
    val rawResult = takeDamage.invoke(spawned, Il2Cpp.Int(10), Il2Cpp.Int(0))
    Log.i { "created=${spawned.address} hp=$hp invoke=${rawResult?.type?.il2Name}" }

    // ── 3) 类型安全 Hook：类型即签名，运行时自动解包参数 ────────────────────
    val damageHook = takeDamage.hook<Il2CppObject?, Int, Int> { self, damage ->
        if (damage >= 1000) {
            Log.w { "blocked $damage damage on ${self?.address}" }
            damage / 2 // 末表达式即 hook 的返回值（自动装箱）
        } else {
            original(self, damage) // 显式调用原函数，参数类型受检
        }
    }

    // ── 4) 基础 Hook：模块 + 偏移，直接读写原始槽位 ─────────────────────────
    val offsetHook = hook(module = IL2CPP_MODULE, offset = 0x1A2B3C) { ctx ->
        // 没设置返回值、也没调 callOriginal → 框架把当前槽位值（已修改）转发给原函数
        ctx.setArgLong(0, ctx.argLong(0) * 2)
    }

    Log.i {
        "hooks installed: typed=0x${damageHook.address.toString(16)} " +
                "offset=0x${offsetHook.address.toString(16)}"
    }
    // 需要时卸载：damageHook.unhook(); offsetHook.unhook()
}

/**
 * 自适应验证脚本：先 dump 域内全部 image 名，再挑匹配 [TARGET_IMAGE_KEYWORD] 的 image
 * 枚举其全部类。（硬编码 image 名在真机上暴露为占位名 —— il2cpp 的 image 名通常带
 * `.dll` 后缀，先枚举再匹配才稳。）
 */
fun main() {
    Log.level = Severity.Info
    // 双写:平台输出 + %TEMP%\il2cpp4k.log。真机实验教训:注入目标无控制台、
    // OutputDebugStringA 无捕获器在场即丢弃 —— 文件通道让日志事后可读(见 LogFileWriter)。
    Log.useFile()
    Il2Cpp.initialize(Il2ApiModule.create(IL2CPP_MODULE))

    val images = Il2Cpp.domain().assemblies
    Log.i { "domain: ${images.size} images" }
    images.forEach { Log.i { "image: ${it.name}" } }

    val target = images.firstOrNull { it.name.contains(TARGET_IMAGE_KEYWORD) }
    if (target == null) {
        Log.w { "no image matching \"$TARGET_IMAGE_KEYWORD\"; adjust TARGET_IMAGE_KEYWORD in HookScript.kt" }
        return
    }

    val classes = target.classes
    Log.i { "image ${target.name}: ${classes.size} classes" }
    classes.forEach { Log.i { "class: ${it.name}" } }
}

/**
 * 脚本入口 —— **这就是消费方需要写的全部内容**。
 *
 * - `@CName("il2cpp4k_init")` 把函数固定成外部可查找的符号（注入器默认 `--entry il2cpp4k_init`，
 *   或由目标自身代码调用）。声明在本模块内，因此不会被 DCE 丢弃，并自动出现在导出表里。
 * - 不需要任何挂载步骤，也没有"必须在入口被调用前赋值"的时序约束。
 * - `il2cpp4kRun { }` 保证异常绝不越过 C 边界。
 */
@OptIn(ExperimentalNativeApi::class)
@CName("il2cpp4k_init")
fun myHooks() = il2cpp4kRun { main() }
