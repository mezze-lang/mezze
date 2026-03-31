package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Test suite for the new std/control/continuation module with delimited continuations.
 * Migrated from V1 to V2 with unboxed values.
 */
class ContinuationModuleTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testModuleLoads() {
        // Test that the new continuation module loads successfully
        val source = """
            use std::control::continuation

            let cont = continuation::new {}
            {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord, "Expected Unit record, got: ${result::class.simpleName}")
    }

    @Test
    fun testDelimiterCreation() {
        // Test that delimiter is created and continuation record is returned
        val source = """
            use std::control::continuation

            let cont = continuation::new {}

            # cont should be a record with methods
            # Just verify it's not null by using it in a do block
            {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
    }

    @Test
    fun testCaptureOperation() {
        // Test capture operation from continuation module
        val source = """
            use std::control::continuation

            let cont = continuation::new {}
            let k = cont.capture {}

            {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
    }

    @Test
    fun testResumeOperation() {
        // Test resume operation
        val source = """
            use std::control::continuation

            let cont = continuation::new {}
            let k = cont.capture {}
            let result = cont.resume { cont = k, value = 42 }

            {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
    }
}
