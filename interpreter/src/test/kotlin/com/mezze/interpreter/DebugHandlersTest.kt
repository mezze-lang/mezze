package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals

/**
 * Tests for debug handlers with nested function calls and continuations.
 * Migrated from V1 to V2 with unboxed values.
 */
class DebugHandlersTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    @Test
    fun testNestedFunctionCalls() {
        // Test nested function calls with effect handlers
        // Direct continuation usage outside perform is an anti-pattern
        val source = """
            use std::effects::exception::{Exception, ExceptionToResult}

            let inner = { x } -> do
                # Inner function that may throw
                if x < 0 then Exception.throw {error = "negative"} else x * 2

            let outer = { x } -> do
                # Call inner, which may throw
                let result = inner { x = x }
                result + 10

            # Test nested calls through exception handler
            let test = {} -> do
                let result = perform ExceptionToResult in Ok {val = outer { x = 5 }}
                match result is
                    Ok {val} -> val
                    Err {error} -> 0 - 1  # Return -1 on error

            test {}
        """.trimIndent()

        val result = eval(source)
        println("Result: $result")
        println("Result class: ${result::class.simpleName}")
        // Should be 20 (5 * 2 + 10 = 20)
        assertEquals(20L, result)
    }
}
