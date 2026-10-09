# il2cpp4k 注入器（com.rainccup.il2cpp4k.injector）

把 `il2cpp4k.dll` / `libil2cpp4k.so` 注入目标 Unity 进程，并（可选）调用导出入口 `il2cpp4k_init`。

## 允许的用途

仅限**本机与自有设备**的调试、研究与模组开发；需要管理员（Windows）或 root（Android）权限。

## 构建

```powershell
.\gradlew.bat :injector:linkReleaseMingwX64                        # injector/build/bin/mingwX64/releaseExecutable/
.\gradlew.bat :injector:linkIl2cpp4k-inject-targetReleaseExecutableMingwX64   # 测试用空转目标进程
.\gradlew.bat :injector:linkReleaseExecutableAndroidNativeArm64    # 产物为 .kexe，push 到设备后重命名
```

产物：`il2cpp4k-inject.exe`（Windows）、`il2cpp4k-inject.kexe`（Android arm64）、
`il2cpp4k-inject-target.exe`（仅测试用的空转目标进程）。

## Windows

```powershell
# 列出加载了 GameAssembly.dll 的进程（Unity 候选）
.\il2cpp4k-inject.exe list

# 按进程名子串查找任意进程（含非 Unity 进程）
.\il2cpp4k-inject.exe list --pattern notepad

# 注入（默认只注入；入口由目标自身代码调用，见「已知限制」）
.\il2cpp4k-inject.exe inject --lib C:\path\il2cpp4k.dll --name Game.exe

# 由注入器劫持目标线程调用入口（见「已知限制」）
.\il2cpp4k-inject.exe inject --lib C:\path\il2cpp4k.dll --pid 1234 --call-entry --entry il2cpp4k_init

# spawn 模式（frida-parity 时间线）：挂起启动 → 诞生时注入 → resume → 等 il2cpp 就绪
# → 延迟 3s → 调入口。模块在主线程跑第一条指令前就位，反作弊用户态组件初始化时
# 看到的是"与生俱来"的模块，而非运行中途的加载事件。
.\il2cpp4k-inject.exe inject --lib C:\path\il2cpp4k_example.dll ^
    --exe "C:\game\Game.exe" --spawn --call-entry --delay 3000

# 模块已驻留时重新注入：自动跳过加载（连 LoadLibraryW 都不发，不触发目标侧加载监控），
# 直接进入入口调用 —— 可反复调入口而无需重新加载（真机实证：运行中途的新加载会被
# 反作弊拒绝，但已驻留模块安然无恙，见「已知限制」5）。
.\il2cpp4k-inject.exe inject --lib C:\path\il2cpp4k_example.dll --pid 1234 --call-entry
```

## Android（需 root）

```powershell
.\tools\inject-android.ps1 -Action list
.\tools\inject-android.ps1 -Action inject -Lib .\api\build\bin\androidNativeArm64\releaseShared\libil2cpp4k.so -Target com.example.game
```

或手动：

```bash
adb push injector/build/bin/androidNativeArm64/releaseExecutable/il2cpp4k-inject.kexe /data/local/tmp/il2cpp4k-inject
adb push libil2cpp4k.so /data/local/tmp/
adb shell chmod 755 /data/local/tmp/il2cpp4k-inject
adb shell su -c "/data/local/tmp/il2cpp4k-inject list"
adb shell su -c "/data/local/tmp/il2cpp4k-inject inject --lib /data/local/tmp/libil2cpp4k.so --name com.example.game"
```

## 退出码

| 码 | 含义 |
|----|------|
| 0 | 成功 |
| 1 | 用法错误 |
| 2 | 目标未找到或不唯一 |
| 3 | 打开目标失败（含权限不足：Windows 需管理员 / Android 需 root） |
| 4 | 库文件不存在或不可读 |
| 5 | 注入失败 |
| 6 | 符号未找到 |
| 7 | 远程调用超时 |
| 8 | 注入后校验失败 |

## 平台实现要点

- **Windows**：`Toolhelp32` 快照发现进程/模块/线程 → `OpenProcess` + `VirtualAllocEx` + `WriteProcessMemory`
  + `CreateRemoteThread(LoadLibraryW)` 加载库 → 校验模块出现在目标模块列表。
  （平台库未提供 tlhelp32.h，故经 `GetProcAddress(kernel32, …)` 运行时绑定符号并按固定 x64 偏移读结构体。）
- **spawn 模式（--spawn，需 --exe）**：`CreateProcessW(CREATE_SUSPENDED)` 启动 → 诞生时注入
  （实测：新远程线程会代为完成 ntdll 进程初始化再执行 `LoadLibraryW`，主线程未跑任何指令）→
  `ResumeThread` 主线程 →（`--call-entry` 时）等 `--wait-module` → `--delay` → 劫持调入口。
  `--delay` 在 spawn 模式下延迟的是**入口调用**（对应 frida spawn + setTimeout 的时序）。
- **Android**：`ptrace` 附加目标线程，借其栈下方空间写 `brk #0` 陷阱做远程调用；
  经 `linker64` 的 `__loader_dlopen`/`__loader_dlsym`（回退 `libdl.so`）加载库与取符号；
  远程内存读写优先 `/proc/<pid>/mem`，回退 `PTRACE_POKEDATA`/`PEEKDATA`；
  任何失败路径都恢复寄存器并 detach。spawn 模式是 Windows 专属（Android 注入没有
  「诞生时」的挂起点）。

## 已知限制（重要）

1. **Windows 外部调入口的历史状态**。
   `--call-entry` 的劫持调用已端到端实测可用（见 `WinEntryCallE2ETest`：入口执行、
   异常被 `il2cpp4kRun` 捕获、目标存活）。外部裸 Win32 线程直接调入口仍然不可行
   （Kotlin/Native 运行时依赖由进程 CRT 建立的线程状态），劫持目标已有线程没有此问题。
   最稳妥的路径仍是目标自身代码调用（SELFCALL，测试辅助进程
   `IL2CPP4K_SELFCALL=1` + `IL2CPP4K_SELFCALL_MODULE=<库文件名>` 可复现）；
   `--call-entry` 是实测可用的注入器侧替代。
2. 仅 Android arm64、必须 root；未在无 root 或非 arm64 设备上验证，真机端到端属交付后验证项。
3. Windows 入口调用路径与测试辅助进程仅支持 ASCII 库路径。
4. 每次 `--call-entry` 会在目标内留下约 72 字节 stub（不释放，避免恢复失败时线程执行已释放内存）。
5. **对已运行目标的「新加载」可能被反作弊拒绝。**但**诞生时注入的模块
   会持续驻留**且不触发惩罚。因此注入器在加载前先查目标模块表：已驻留 → 整个跳过
   `LoadLibraryW`（不发出被监控的调用），直接进入可选的入口调用阶段
   （见 `WinResidentModuleE2ETest`）。

## 测试

```powershell
.\gradlew.bat :injector:mingwX64Test    # 69 用例
```

覆盖：CLI 解析（含 `--spawn`）、`/proc/maps` 解析、最小 ELF64 动态符号解析、名称匹配、PE 导出解析、
Windows 进程/模块发现（真实进程列表）、**端到端注入**（启动目标进程 → 发现 → 注入 `il2cpp4k.dll`
→ 校验模块可见且目标存活 → 结束进程）、**端到端入口调用**（`WinEntryCallE2ETest`：劫持调入口、
异常被捕获、目标存活）、**端到端 spawn**（`WinSpawnE2ETest`：挂起启动 → 主线程未运行即注入
→ resume 存活 → resume 后调入口存活）。
