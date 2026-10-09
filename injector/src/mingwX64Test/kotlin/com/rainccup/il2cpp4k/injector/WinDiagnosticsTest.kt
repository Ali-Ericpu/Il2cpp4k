package com.rainccup.il2cpp4k.injector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 诊断消息的构造逻辑：已知可操作的错误码/返回值必须给出准确的下一步判断。 */
class WinDiagnosticsTest {
    @Test
    fun elevationErrorsTellTheUserToElevate() {
        val required = launchFailureMessage("C:\\game.exe", 740u)
        assertTrue(required.contains("740"), "the raw code must stay visible: $required")
        assertTrue(required.contains("Administrator", ignoreCase = true), "740 is elevation: $required")

        val denied = launchFailureMessage("C:\\game.exe", 5u)
        assertTrue(denied.contains("Access denied", ignoreCase = true), "5 is access denied: $denied")
        assertTrue(denied.contains("Administrator", ignoreCase = true), "5 needs elevation here: $denied")
    }

    @Test
    fun otherErrorsKeepTheRawCodeAndAddNoHint() {
        val message = launchFailureMessage("C:\\game.exe", 267u)
        assertTrue(message.contains("267"), "unknown codes must still be reported: $message")
        assertFalse(message.contains("Administrator"), "must not guess a cause: $message")
    }

    @Test
    fun nullResultMeansTheLibraryCouldNotBeLoaded() {
        val message = loadFailureMessage(0L, "il2cpp4k_example.dll")
        assertTrue(message != null, "a null LoadLibraryW result is a failure")
        assertTrue(message!!.contains("null"), "it should name the null result: $message")
        assertTrue(message.contains("il2cpp4k_example.dll"), "it should name the library: $message")
    }

    @Test
    fun invalidHandleValueIsReportedAsRefusedOrIntercepted() {
        val message = loadFailureMessage(0xFFFFFFFFL, "il2cpp4k_example.dll")
        assertTrue(message != null, "LoadLibraryW never returns -1 on a real success")
        assertTrue(message!!.contains("0xffffffff"), "the observed value must stay visible: $message")
        assertTrue(
            message.contains("refused or intercepted"),
            "INVALID_HANDLE_VALUE points at the call, not at a missing module: $message",
        )
        assertFalse(message.contains("null"), "must not call -1 a null result: $message")
    }

    @Test
    fun onlyZeroAndInvalidHandleValueAreRejected() {
        // 关键回归护栏：**不做数值区间猜测**（曾经的错误）。0xC0000000–0xFFFFFFFF 在 64 位进程里
        // 完全是合法用户态地址，除 0xFFFFFFFF 本身外一律放行。
        assertNull(loadFailureMessage(0xEE4E0000L, "l.dll"), "a real handle seen in the smoke test")
        assertNull(loadFailureMessage(0x174B0000L, "l.dll"), "another real base")
        assertNull(loadFailureMessage(0x7FFBEE4E0000L, "l.dll"), "a high x64 base")
        assertNull(loadFailureMessage(0xC0000000L, "l.dll"), "legal in a 64-bit user space")
        assertNull(loadFailureMessage(0xEE4E0001L, "l.dll"), "one off from the sentinel is still allowed")
    }

    @Test
    fun absolutePathsAreReturnedAsIs() {
        val already = "C:\\Windows\\System32\\kernel32.dll"
        val resolved = absolutePathOf(already)
        assertEquals(already.lowercase(), resolved?.lowercase(), "an absolute path must come back unchanged")
    }

    @Test
    fun relativePathsBecomeDriveAbsolute() {
        // 相对路径必须在注入器这边解析成绝对路径：目标进程的 cwd 与注入器不同（用 --exe 时会不同）
        val resolved = absolutePathOf(".\\il2cpp4k_example.dll")
        assertTrue(resolved != null, "a relative path must resolve")
        assertTrue(
            Regex("^[A-Za-z]:\\\\").containsMatchIn(resolved!!),
            "must become drive-absolute: $resolved",
        )
        assertTrue(resolved.endsWith("il2cpp4k_example.dll"), "must keep the file name: $resolved")
        assertFalse(resolved.startsWith("."), "must not stay relative: $resolved")
        assertFalse(resolved.contains("/"), "separators must be normalised to backslashes: $resolved")
    }

    @Test
    fun bareFileNamesResolveAgainstTheCurrentDirectory() {
        val resolved = absolutePathOf("il2cpp4k_example.dll")
        assertTrue(resolved != null, "a bare file name must resolve")
        assertTrue(
            Regex("^[A-Za-z]:\\\\").containsMatchIn(resolved!!),
            "must become drive-absolute: $resolved",
        )
        assertTrue(resolved.endsWith("il2cpp4k_example.dll"), "must keep the file name: $resolved")
    }
}
