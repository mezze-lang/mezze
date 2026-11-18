package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals

/**
 * The `Log` effect's JVM-logger handler (`JvmLogger` / `JvmLoggerWith`).
 *
 * The point of the handler is that a guest `Log.log` lands as a real
 * java.util.logging record under a chosen logger name — so it shares the host's
 * logging pipeline (the same one Helidon logs into) instead of going straight to
 * stdout. We prove that by attaching a capturing JUL handler to the logger the
 * program names and asserting the records arrive with the mapped levels.
 */
class JvmLoggerHandlerTest {

    private class Capture : Handler() {
        val records = mutableListOf<LogRecord>()
        override fun publish(record: LogRecord) { records.add(record) }
        override fun flush() {}
        override fun close() {}
    }

    @Test
    fun `Log via JvmLoggerWith emits JUL records at the mapped levels`() {
        val name = "mezze.test.jvmlog"
        val logger = Logger.getLogger(name)
        val cap = Capture()
        val savedLevel = logger.level
        val savedUseParent = logger.useParentHandlers
        logger.level = Level.ALL
        logger.useParentHandlers = false // keep the records off the test console
        logger.addHandler(cap)
        try {
            val r = MezzeTestHelper.run(
                """
                use std::effects::log::{ Log, JvmLoggerWith, info, warn, debug, print }
                let go: {} -> Str <*> = {} ->
                  perform JvmLoggerWith { name = "$name" } in do
                    info { msg = "hello-info" }
                    warn { msg = "hello-warn" }
                    debug { msg = "hello-debug" }
                    print { msg = "hello-print" }
                    "done"
                go {}
                """.trimIndent(),
            )
            assertEquals("done", r)
            assertEquals(4, cap.records.size, "every Log.log reached JUL")
            assertEquals("hello-info", cap.records[0].message)
            assertEquals(Level.INFO, cap.records[0].level)
            assertEquals("hello-warn", cap.records[1].message)
            assertEquals(Level.WARNING, cap.records[1].level)
            assertEquals("hello-debug", cap.records[2].message)
            assertEquals(Level.FINE, cap.records[2].level)
            // `print` is level Print, which has no severity of its own -> INFO.
            assertEquals("hello-print", cap.records[3].message)
            assertEquals(Level.INFO, cap.records[3].level)
        } finally {
            logger.removeHandler(cap)
            logger.level = savedLevel
            logger.useParentHandlers = savedUseParent
        }
    }
}
