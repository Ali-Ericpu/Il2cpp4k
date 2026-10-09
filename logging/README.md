# il2cpp4k logging（com.rainccup.il2cpp4k.logging）

框架与调用者共用的日志门面，基于 [Kermit](https://github.com/touchlab/Kermit) 2.2.0
（KMP 日志事实标准；已确认发布 `mingwX64` 与 `androidNativeArm64` 变体）。

## API

```kotlin
import com.rainccup.il2cpp4k.logging.Log
import co.touchlab.kermit.Severity

Log.i { "hook installed at 0x${addr.toString(16)}" }   // 惰性消息：级别关闭时零开销
Log.d { "args: $a, $b" }
Log.w(error) { "callOriginal failed" }                 // 带异常
Log.e(error) { "il2cpp symbol missing: $name" }

val playerLog = Log.tag("Player")                      // 带 tag 的视图
playerLog.i { "TakeDamage blocked" }

Log.level = Severity.Debug                             // 阈值（默认 Info）
Log.useWriter(myWriter)                                // 换输出（LogWriter）
Log.useDefaultWriter()                                 // 恢复平台默认
```

级别：`Verbose / Debug / Info / Warn / Error`（`Assert` 由 Kermit 提供）。

## 平台默认输出

| 平台 | 输出 |
|------|------|
| Windows（`mingwX64`） | `stdout` + `OutputDebugStringA`（DebugView 可看；未附加调试器时为空操作） |
| Android（`androidNativeArm64`） | `stdout`/`stderr`（应用标准输出会被 Android 重定向进 logcat，以 `System.out` 出现）；若能从 `liblog` 取得 `__android_log_write`，额外写一条带真实 tag 的 logcat 记录 |

记录格式统一为 `[LEVEL][tag] message[: ExceptionType: message]`（见 `formatRecord`，可直接复用）。

## 在框架内使用

`api` 以 `api(project(":logging"))` 暴露本模块，因此依赖框架的 mod 模块无需额外声明即可用 `Log.*`。
框架自身也已接入：入口脚本异常记 `Log.e`、hook 回调异常经 `HookEngine.onHookError` 记 `Log.w`。

注入器（`injector`）的 `--verbose` 会把日志阈值调到 `Debug`；其 `list` 等**对外数据输出仍走 `println`**，
不受日志格式影响。
