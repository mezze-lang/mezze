package com.mezze.language.cli

import com.mezze.language.cli.commands.ExplainCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/** Smoke checks that `mez explain <CODE>` finds and prints embedded docs. */
class ExplainCommandTest {

    private fun captureStdout(block: () -> Unit): String {
        val original = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buf.toString()
    }

    @Test
    fun `prints T0201 doc`() {
        val out = captureStdout { ExplainCommand().execute(listOf("T0201")) }
        assertTrue(out.contains("Type mismatch"), "expected T0201 header, got:\n$out")
        assertTrue(out.contains("How to fix"))
    }

    @Test
    fun `is case-insensitive`() {
        val out = captureStdout { ExplainCommand().execute(listOf("t0201")) }
        assertTrue(out.contains("Type mismatch"))
    }

    @Test
    fun `each shipped doc renders`() {
        val codes = listOf("T0201", "T0202", "T0203", "T0205", "T0210",
                           "V0501", "V0601", "V0701", "V0702",
                           "M0801", "M0803", "P0001")
        for (code in codes) {
            val out = captureStdout { ExplainCommand().execute(listOf(code)) }
            assertFalse(out.isBlank(), "explain $code produced no output")
            assertTrue(out.contains("#"), "explain $code: missing markdown header")
        }
    }
}
