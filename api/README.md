# il2cpp4k（com.rainccup.il2cpp4k）

Kotlin/Native 实现的 Il2Cpp hook 框架，支持 **Windows**（`mingwX64`）与 **Android**（`androidNativeArm64`）。
Hook 引擎完全自研（补丁、trampoline、x64/ARM64 机器码 stub），**零外部原生依赖**。

## 模块

| 模块 | 职责 |
|------|------|
| `core` | 平台无关内核：内存原语、调用约定槽位模型、x64 长度反汇编、机器码 stub 生成、inline hook + trampoline、hook 注册表、任意函数调用（FunctionCaller） |
| `api` | Il2Cpp 运行时 API：门面、类型系统、链式导航、对象构造、Method 调用、基础与类型安全 Hook API |

## 构建与注入

产物：`il2cpp4k.dll`（Windows）/ `libil2cpp4k.so`（Android）。

```powershell
.\gradlew.bat :api:linkReleaseSharedMingwX64          # build/bin/mingwX64/releaseShared/il2cpp4k.dll
.\gradlew.bat :api:linkReleaseSharedAndroidNativeArm64 # build/bin/androidNativeArm64/releaseShared/libil2cpp4k.so
```

用现有注入器把库注入目标进程，再调用导出入口 **`il2cpp4k_init`** 触发脚本（注入器不在框架范围内）。

**消费方只需要写自己的入口函数**——无需挂载、无时序要求；`il2cpp4kRun { }` 保证异常不越过 C 边界：

```kotlin
// 你自己的 mod 模块里（依赖 :api）
@OptIn(ExperimentalNativeApi::class)
@CName("il2cpp4k_init")   // 固定成外部可查找的符号；在本模块内声明，故不会被 DCE 丢弃
fun myHooks() = il2cpp4kRun {
    // Windows: "GameAssembly.dll"；Android: "libil2cpp.so"
    Il2Cpp.initialize(Il2ApiModule.create("GameAssembly.dll"))

    // 基础 Hook（内存偏移）：回调直接操作原始槽位
    Il2Cpp.hook(module = "GameAssembly.dll", offset = 0x1A2B3C) { ctx ->
        ctx.setArgLong(0, ctx.argLong(0) * 2)   // 未设置返回值 → 自动把当前槽位转发给原函数
    }

    // 类型安全 Hook：类型即签名，运行时自动解包参数
    Il2Cpp.image("Assembly-CSharp.dll")
        .classOf("Player")
        .method("TakeDamage", 2)
        .hook<Il2CppObject?, Int, Int> { self, damage ->
            if (damage > 1000) 0 else original(self, damage)
        }
}
```

> 完整可编译示例见 [`example/`](../example/README.md)（含构建、注入与入口触发说明）。

## 类型系统

`Il2Cpp.Int / Long / Float / Double / Boolean / Void / String / Object / Pointer`
既是类型描述符（用于 hook 参数表），也是值构造器：

```kotlin
Il2Cpp.Int(42)
Il2Cpp.Float(1.5f)
Il2Cpp.String("hello")   // 内部调用 il2cpp_string_new
Il2Cpp.Object(pointer)
```

## 链式导航、构造与调用

```kotlin
val klass  = Il2Cpp.image("Assembly-CSharp.dll").classOf("Player")   // image → class
val method = klass.method("TakeDamage", 2)                            // class → method
val field  = klass.field("hp")                                        // class → field
Il2Cpp.domain().assemblies / image.classes / klass.methods / klass.fields   // 枚举

val raw = method.invoke(instance, Il2Cpp.Int(50), Il2Cpp.Int(0))       // 值包装调用
val dmg = method.call<Int>(instance, 50, 0)                            // 类型安全调用

val obj = klass.new(Il2Cpp.String("hero"), Il2Cpp.Int(100))            // 值包装构造
val obj2 = klass.create("hero", 100)                                   // 类型安全构造（参数自动装箱）
```

> `create` 而非 `new` 重载：`new()` 无参调用下 `vararg Il2CppValue` 与 `vararg Any?` 会重载歧义。

## 调用约定说明（重要）

框架的槽位模型对准 **MSVC x64 ABI / AAPCS64**：通用寄存器参数按出现顺序占用 RCX/RDX/R8/R9（ARM64 为 X0–X7），
浮点参数按出现顺序占用 XMM0–XMM3（ARM64 为 V0–V7），溢出落入栈槽。这与 il2cpp 目标代码（MSVC/Clang 编译）一致。

注意：**Kotlin/Native 通过 `CFunction` 调用混合 int/float 签名时采用按位置分配寄存器**，与 MSVC 不同。
因此自测中的混签调用方使用手写机器码（见 `HookEngineE2ETest.makeMixedCaller`）。真实目标进程不受此影响。

## 限制（v1）

- 补丁不做线程挂起，存在极短竞态窗口；请在目标函数不被并发执行的时机安装 hook。
- x64 入口补丁 12 字节：要求目标函数头部至少 12 字节可解码指令；遇到未知 opcode 或不可重定位的 rip-relative 偏移会
  拒绝安装并抛 `HookInstallException`（目标字节保持不变）。
- ARM64 入口补丁 16 字节：拷贝区含 `adrp/adr/字面量加载/分支` 时同样拒绝。
- 结构体传参与返回、x86 Android、真机端到端验证不在本版本范围。
- 字符串内容读取假设 `il2cpp_string_chars` 返回 NUL 结尾的 UTF-8 `char*`。

## 日志

框架以 `api(project(":logging"))` 暴露日志 API，依赖本框架的 mod 模块可直接使用：

```kotlin
Log.i { "hook installed at 0x${addr.toString(16)}" }   // 惰性消息：级别关闭时零开销
Log.w(error) { "callback failed" }
val playerLog = Log.tag("Player")                      // 带 tag 的视图
Log.level = Severity.Debug                             // 默认 Info
```

平台默认输出：Windows 写 stdout + `OutputDebugStringA`；Android 写 stdout/stderr
（会被重定向进 logcat），可用时额外经 `liblog` 写入带 tag 的 logcat 记录。
详见 [`logging/README.md`](../logging/README.md)。框架自身也已接入：入口脚本异常记 `Log.e`，
hook 回调异常经 `HookEngine.onHookError` 记 `Log.w`。

## 测试

```powershell
.\gradlew.bat :core:mingwX64Test     # 29 用例
.\gradlew.bat :api:mingwX64Test     # 23 用例
```

引擎测试包含**同进程自 hook 端到端闭环**：真实补丁 + 机器码 stub + 参数编组 + 原函数转发/调用，
以及 trampoline 的 rip-relative 重定位验证。ARM64 侧通过已知编码断言 + `objdump` 反汇编交叉核对，未做真机执行。
