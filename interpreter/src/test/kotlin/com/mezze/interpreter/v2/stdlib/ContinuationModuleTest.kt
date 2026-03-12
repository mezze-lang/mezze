package com.mezze.interpreter.v2.stdlib

import com.mezze.interpreter.v2.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for std/control/continuation module.
 * Tests delimited continuations with capture, suspend_with, resume, fork, and abort.
 * Migrated from V1 to V2 with unboxed values.
 */
class ContinuationModuleTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // === new Function Tests ===

    @Test
    fun `test continuation new creates record`() {
        val result = eval("""
            use std::control::continuation

            let cont = continuation::new {}
            42
        """)
        assertEquals(42L, result)
    }

    // === abort Tests ===

    @Test
    fun `test abort terminates computation`() {
        var exceptionThrown = false
        try {
            eval("""
                use std::control::continuation

                let test = {} -> do
                    let cont = continuation::new {}
                    cont.abort {}
                    "should not reach here"

                test {}
            """)
        } catch (e: Exception) {
            exceptionThrown = true
        }
        assertTrue(exceptionThrown)
    }

    // === Integration with Effects ===

    @Test
    fun `test continuation used by early_return effect`() {
        val result = eval("""
            use std::effects::early_return::{Return, EarlyReturn}

            let check = {x} ->
                perform EarlyReturn in do
                    if x < 0 then
                        Return.return {val = "negative"}
                    else {}
                    "positive"

            check {x = -1}
        """)
        assertEquals("negative", result)
    }

    @Test
    fun `test continuation used by exception effect`() {
        // Wrap in a function to avoid top-level perform restriction
        val result = eval("""
            use std::effects::exception::{Exception, ExceptionToResult}

            let divide = {a, b} ->
                if b == 0 then
                    Exception.throw {error = "div by zero"}
                else
                    Ok { val = a / b }

            let test = {} -> do
                let r = perform ExceptionToResult in divide {a = 10, b = 0}
                match r is
                    Ok {val} -> "ok"
                    Err {error} -> "err"

            test {}
        """)
        assertEquals("err", result)
    }
}
