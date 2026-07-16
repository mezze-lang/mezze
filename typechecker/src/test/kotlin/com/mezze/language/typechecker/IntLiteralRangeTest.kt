package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** #82: an integer literal beyond the 64-bit range must produce a clean parse
 *  error, not a raw NumberFormatException. */
class IntLiteralRangeTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setup() { parser = MezzeParser() }

    /** Returns the parse-error message, or null if it parses. */
    private fun parseError(src: String): String? = try {
        parser.parseParsedModule(src, "n.mz")
        null
    } catch (e: ParseException) {
        e.message
    }

    @Test
    fun `int literal one past Long MAX is a clean parse error`() {
        val msg = parseError("let x = 9223372036854775808\nx")
        assertNotNull(msg, "expected a parse error")
        assertTrue(msg!!.contains("out of range"), "expected a range message, got: $msg")
        assertFalse(msg.contains("NumberFormatException"), "should not leak the Java exception: $msg")
    }

    @Test
    fun `nat literal out of range is a clean parse error`() {
        val msg = parseError("let x = 99999999999999999999n\nx")
        assertNotNull(msg)
        assertTrue(msg!!.contains("out of range"), "got: $msg")
    }

    @Test
    fun `Long MAX itself parses`() {
        assertNull(parseError("let x = 9223372036854775807\nx"))
    }

    @Test
    fun `ordinary int parses`() {
        assertNull(parseError("let x = 42\nx"))
    }
}
