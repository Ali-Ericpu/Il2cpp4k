# il2cpp4k 示例（com.rainccup.il2cpp4k.example）

一份可直接编译成可注入库的 hook 脚本示例，覆盖框架的四项能力。**把它当作自己的 mod 项目的起点**：
保留构建配置与入口部分，把 `demoScript()` 里的类名/方法/偏移换成自己目标里的即可。

## 展示的内容

| 位置 | 能力 |
|------|------|
| `demoScript()` 第 1 段 | **类型系统**：`Il2Cpp.Int/Long/Float/Double/Boolean/String/Pointer/Object` 既是类型描述符也是值构造器 |
| 第 2 段 | **链式导航 + 调用 + 构造**：`image → classOf → method/field`，`create(...)` 调目标构造器，`call<R>(...)` 类型安全调用，`invoke(...)` 值包装调用 |
| 第 3 段 | **类型安全 Hook**：`method.hook<A, B, R> { a, b -> ... }`，运行时自动解包参数，`original(...)` 透传原函数 |
| 第 4 段 | **基础 Hook（模块 + 偏移）**：`Il2Cpp.hook(module, offset) { ctx -> ... }`，直接读写原始槽位 |
| `exampleEntry()` / `installDemoScript` | **导出入口与脚本挂载**（见下） |

## 构建

```powershell
# Windows
.\gradlew.bat :example:linkReleaseSharedMingwX64
#   → example/build/bin/mingwX64/releaseShared/il2cpp4k_example.dll

# Android（arm64）
.\gradlew.bat :example:linkReleaseSharedAndroidNativeArm64
#   → example/build/bin/androidNativeArm64/releaseShared/libil2cpp4k_example.so
```

自检（确认入口符号确实导出）：

```powershell
& "$env:USERPROFILE\.konan\dependencies\msys2-mingw-w64-x86_64-2\x86_64-w64-mingw32\bin\objdump.exe" `
  -x example\build\bin\mingwX64\releaseShared\il2cpp4k_example.dll | Select-String il2cpp4k_init
```

## 注入与触发入口

```powershell
# Windows：列出 Unity 候选进程并注入（默认只注入，不调入口）
.\injector\build\bin\mingwX64\releaseExecutable\il2cpp4k-inject.exe list
.\injector\build\bin\mingwX64\releaseExecutable\il2cpp4k-inject.exe inject `
    --lib .\example\build\bin\mingwX64\releaseShared\il2cpp4k_example.dll --name Game.exe
```

```powershell
# Android（需 root）
.\tools\inject-android.ps1 -Action inject `
    -Lib .\example\build\bin\androidNativeArm64\releaseShared\libil2cpp4k_example.so -Target com.example.game
```

**Windows 上入口由目标自身代码调用**（推荐做法，可运行示例见 `injector/src/mingwX64Main/.../TargetMain.kt`
的 `IL2CPP4K_SELFCALL=1` 路径）：

```powershell
$env:IL2CPP4K_SELFCALL = '1'
$env:IL2CPP4K_SELFCALL_MODULE = 'il2cpp4k_example.dll'   # 本示例的库名
Start-Process .\injector\build\bin\mingwX64\il2cpp4k-inject-targetReleaseExecutable\il2cpp4k-inject-target.exe
# 注入 il2cpp4k_example.dll 后，该进程会自行调用 myHooks()（= il2cpp4k_init）
```

> 原因：从外部（`CreateRemoteThread`、库自身 `pthread_create`、劫持已有线程）调用该库的 Kotlin 入口，
> 都会在入口首条指令处让目标 `0xc0000005` 崩溃——Kotlin/Native(mingw) 的运行时初始化需要调用线程
> 由进程自身 CRT 创建并进入其代码流。`il2cpp4k-inject … --call-entry` 可显式尝试，风险自担。

## 为什么只需要一个函数

消费方**只写自己的入口函数**（`HookScript.kt` 末尾的 `myHooks()`），其余全部由框架约定兜住：

- `@CName("il2cpp4k_init")` 把函数固定成外部可查找的符号，注入器默认调用的就是它。
  该声明位于**消费方自己的模块**里，因此不会被 Kotlin/Native 的 DCE 丢弃，并会自动出现在导出表中
  （本项目已用 objdump 核对 `.def` 与导出表）。
  若不带 `@CName`，就没有可被外部找到的符号——这是唯一必须写的一行。
- **不需要挂载**：脚本就是入口正文，没有"必须在入口被调用前赋值"的时序约束。
- `il2cpp4kRun { }` 捕获一切异常，保证不越过 C 边界（跨边界抛出是未定义行为）。

自检入口是否导出：

```powershell
& "$env:USERPROFILE\.konan\dependencies\msys2-mingw-w64-x86_64-2\x86_64-w64-mingw32\bin\objdump.exe" `
  -x example\build\bin\mingwX64\releaseShared\il2cpp4k_example.dll | Select-String il2cpp4k_init
```

## 换成自己的目标

- `GAME_ASSEMBLY` / `IL2CPP_MODULE`：程序集名；Android 上 `IL2CPP_MODULE` 改为 `libil2cpp.so`
- `Player` / `TakeDamage` / `get_hp` / `hp` / `0x1A2B3C`：替换为目标的真实类名、方法名、字段名与偏移
- 需要卸载时调用 `hook.unhook()`
