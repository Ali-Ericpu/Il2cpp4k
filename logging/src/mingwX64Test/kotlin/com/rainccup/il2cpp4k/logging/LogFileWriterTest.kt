@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.rainccup.il2cpp4k.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.remove
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 文件输出 writer 的端到端验证（真实文件系统）。
 *
 * 动机（2026-10 真机实验）：注入到游戏里的脚本日志在 stdout（无控制台）与
 * OutputDebugStringA（无捕获器在场即丢弃）两条默认通道上都不可见；文件 writer
 * 让日志**事后可读**，且 open-append-close 每行落盘保证目标崩溃前的日志不丢。
 */
class LogFileWriterTest {
    private lateinit var path: String

    @BeforeTest
    fun setUp() {
        path = defaultLogFilePath() + ".writer-test"
        removeFile(path)
    }

    @AfterTest
    fun tearDown() {
        removeFile(path)
    }

    @Test
    fun fileWriterAppendsFormattedLinesToTheFile() {
        Log.useWriter(LogFileWriter(path))
        try {
            Log.level = Severity.Verbose
            Log.i { "file-writer-probe" }
            Log.e(IllegalStateException("boom")) { "file-writer-error" }
        } finally {
            Log.level = Severity.Info
            Log.useDefaultWriter()
        }

        val content = readTextFileOrNull(path)
        assertNotNull(content, "the log file must exist after logging")
        assertTrue(
            content.contains("[INFO][il2cpp4k] file-writer-probe\n"),
            "the info record must be appended in formatRecord form: $content",
        )
        assertTrue(
            content.contains("[ERROR][il2cpp4k] file-writer-error: IllegalStateException: boom\n"),
            "the error record must include the throwable suffix: $content",
        )
    }

    @Test
    fun useFileWritesToTheFileWhileKeepingPlatformOutput() {
        Log.useFile(path)
        try {
            Log.i { "use-file-probe" }
        } finally {
            Log.useDefaultWriter()
        }

        val content = readTextFileOrNull(path)
        assertNotNull(content, "useFile must produce the log file")
        assertTrue(content.contains("[INFO][il2cpp4k] use-file-probe\n"), "got: $content")
    }

    @Test
    fun compositeWriterDeliversEveryRecordToAllWriters() {
        val capture = CapturingWriter()
        val file = LogFileWriter(path)
        Log.useWriter(CompositeLogWriter(capture, file))
        try {
            Log.i { "composite-probe" }
        } finally {
            Log.useDefaultWriter()
        }

        assertEquals(1, capture.records.size, "the composite must deliver to the captured writer")
        assertEquals("composite-probe", capture.records[0].third)

        val content = readTextFileOrNull(path)
        assertNotNull(content)
        assertTrue(
            content.contains("[INFO][il2cpp4k] composite-probe\n"),
            "the composite must deliver to the file writer too",
        )
    }

    @Test
    fun defaultLogFilePathIsUsableByAppendFileLine() {
        val p = defaultLogFilePath()
        assertTrue(p.isNotEmpty(), "a platform default path must exist")
        // 默认路径必须真的可写（%TEMP% 对目标进程用户可写），否则 useFile() 开箱即坏
        assertTrue(appendFileLine(p + ".probe", "probe"), "the default directory must be writable")
        removeFile(p + ".probe")
    }

    private class CapturingWriter : LogWriter() {
        val records = mutableListOf<Triple<Severity, String, String>>()
        override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
            records.add(Triple(severity, tag, message))
        }
    }
}

private fun removeFile(path: String) {
    remove(path)
}

private fun readTextFileOrNull(path: String): String? {
    val file = fopen(path, "rb") ?: return null
    try {
        // mingw(LLP64) 的 long 为 32 位,ftell 返回 Int
        if (fseek(file, 0, SEEK_END) != 0) return null
        val size = ftell(file)
        if (size < 0) return null
        if (fseek(file, 0, SEEK_SET) != 0) return null
        if (size == 0) return ""
        val out = ByteArray(size)
        out.usePinned { pinned ->
            fread(pinned.addressOf(0), 1uL, size.toULong(), file)
        }
        return out.decodeToString()
    } finally {
        fclose(file)
    }
}
