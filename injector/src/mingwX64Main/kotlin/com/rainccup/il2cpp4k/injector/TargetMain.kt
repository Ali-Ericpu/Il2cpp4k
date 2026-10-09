@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.injector

import kotlinx.cinterop.*
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.sleep

/**
 * 测试辅助目标进程：空转等待，由测试结束时终止。
 *
 * 两种诊断用途：
 * - `--load-late <path>`：约 2 秒后 `LoadLibraryW` 该 DLL，用于验证注入器「启动后等待模块出现」的路径。
 * - 环境变量 `IL2CPP4K_SELFCALL` 与 `IL2CPP4K_SELFCALL_MODULE`（目标库文件名，例如
 *   `il2cpp4k_example.dll`）：检测到该模块被注入后，由本进程自己的线程调用其导出的 `il2cpp4k_init`，
 *   并把过程写入 `%TEMP%\il2cpp4k-selfcall.txt`。
 *   这是 Windows 上「入口由目标自身线程调用」这一推荐做法的可运行示例。
 */
fun targetMain(args: Array<String>) {
    val loadLate = loadLateArg(args)
    val selfCall = getenv("IL2CPP4K_SELFCALL") != null
    val moduleName = getenv("IL2CPP4K_SELFCALL_MODULE")?.toKString() ?: "il2cpp4k.dll"
    val marker = "C:\\Users\\15109\\AppData\\Local\\Temp\\il2cpp4k-selfcall.txt"
    var ticks = 0
    var lateLoaded = false
    var done = false
    while (true) {
        if (loadLate != null && !lateLoaded && ticks >= 2) {
            val wide = loadLate.toWideChars()
            wide.usePinned { pinned -> platform.windows.LoadLibraryW(pinned.addressOf(0)) }
            lateLoaded = true
        }
        if (selfCall && !done) {
            val module = platform.windows.GetModuleHandleA(moduleName)
            if (module != null) {
                val entry = platform.windows.GetProcAddress(module, "il2cpp4k_init")
                if (entry != null) {
                    writeText(marker, "before-call\n")
                    val fn = entry.reinterpret<CFunction<() -> Unit>>()
                    fn.invoke()
                    writeText(marker, "after-call-ok\n")
                    done = true
                }
            }
        }
        sleep(1u)
        ticks++
    }
}

/** 取 `--load-late <path>` 的值；未给出返回 null。 */
private fun loadLateArg(args: Array<String>): String? {
    val index = args.indexOf("--load-late")
    return if (index >= 0) args.getOrNull(index + 1) else null
}

private fun writeText(path: String, text: String) {
    val file = fopen(path, "a") ?: return
    val bytes = text.encodeToByteArray()
    bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1uL, bytes.size.toULong(), file) }
    fclose(file)
}
