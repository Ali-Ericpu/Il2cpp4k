package com.rainccup.il2cpp4k.logging

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 捕获写入的记录，供断言使用。 */
private class CapturingWriter : LogWriter() {
    val records = mutableListOf<Triple<Severity, String, String>>()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        records.add(Triple(severity, tag, message))
    }
}

class LogTest {
    @Test
    fun passesRecordsToWriterWithSeverityAndTag() {
        val writer = CapturingWriter()
        Log.useWriter(writer)
        try {
            Log.level = Severity.Verbose
            Log.i { "hello" }
            Log.e { "boom" }
            Log.tag("Player").w { "low hp" }

            assertEquals(3, writer.records.size)
            assertEquals(Severity.Info, writer.records[0].first)
            assertEquals(DEFAULT_TAG, writer.records[0].second)
            assertEquals("hello", writer.records[0].third)
            assertEquals(Severity.Error, writer.records[1].first)
            assertEquals("Player", writer.records[2].second)
            assertEquals("low hp", writer.records[2].third)
        } finally {
            Log.level = Severity.Info
            Log.useDefaultWriter()
        }
    }

    @Test
    fun filtersByLevelWithoutEvaluatingMessage() {
        val writer = CapturingWriter()
        Log.useWriter(writer)
        try {
            Log.level = Severity.Error
            var evaluated = false
            Log.i {
                evaluated = true
                "expensive"
            }
            assertEquals(0, writer.records.size, "低于级别的记录不应写出")
            assertEquals(false, evaluated, "级别关闭时消息 lambda 不应被求值")

            Log.e { "kept" }
            assertEquals(1, writer.records.size)
        } finally {
            Log.level = Severity.Info
            Log.useDefaultWriter()
        }
    }

    @Test
    fun formatRecordIncludesLevelTagMessageAndThrowable() {
        val plain = formatRecord(Severity.Info, "T", "msg", null)
        assertEquals("[INFO][T] msg", plain)

        val withError = formatRecord(Severity.Error, "T", "msg", IllegalStateException("bad"))
        assertTrue(withError.startsWith("[ERROR][T] msg: IllegalStateException: bad"))
    }

    @Test
    fun defaultWriterIsAvailableOnThisPlatform() {
        // 平台默认 writer 必须可构造（Windows: stdout+OutputDebugString；Android: stdout+liblog 尽力）
        assertTrue(defaultLogWriter() != null)
    }
}
