package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import com.mezze.interpreter.v2.values.MezzeVariant
import com.mezze.interpreter.v2.values.MezzeRecord

/**
 * Integration tests for user-defined custom effects.
 *
 * These tests verify that the effect system works with user-defined effects,
 * not just the built-in Exception effect. This is critical because:
 * 1. Effects should be a pure userland construct
 * 2. The interpreter should NOT have hardcoded effect names
 * 3. Any effect using continuation-based handlers should work uniformly
 *
 * We define a custom Error effect that works identically to Exception,
 * proving that the interpreter handles all continuation-based effects
 * generically without special-casing effect names.
 */
class CustomEffectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================================================
    // Test 1: Custom Error Effect - Basic Success Case
    // ========================================================================

    @Test
    fun `test custom Error effect with ErrorToResult handler returns Ok on success`() {
        val source = """
            use std::control::continuation

            # Define custom Error effect (NOT using stdlib Exception)
            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            # Define handler that converts errors to Result
            impl Error for ErrorToResult having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = Err {error = err}}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Division by zero"} else Ok {val = a / b}

            let handle = {} -> perform ErrorToResult in safe_div {a = 10, b = 2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant, "Result should be MezzeVariant, got: ${result::class.simpleName}")
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(5L, payload.getField("val"))
    }

    // ========================================================================
    // Test 2: Custom Error Effect - Error Case Returns Err
    // ========================================================================

    @Test
    fun `test custom Error effect with ErrorToResult handler returns Err on error`() {
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            impl Error for ErrorToResult having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = Err {error = err}}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Division by zero"} else Ok {val = a / b}

            let handle = {} -> perform ErrorToResult in safe_div {a = 10, b = 0}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("Division by zero", payload.getField("error"))
    }

    // ========================================================================
    // Test 3: Custom Error Effect with Option Handler
    // ========================================================================

    @Test
    fun `test custom Error effect with ErrorToOption handler returns None on error`() {
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            # Handler that converts errors to Option (None on error)
            impl Error for ErrorToOption having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = None}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Division by zero"} else Some {val = a / b}

            let handle = {} -> perform ErrorToOption in safe_div {a = 10, b = 0}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("None", (result as MezzeVariant).constructorName)
    }

    // ========================================================================
    // Test 4: Custom Error Effect with Option Handler - Success Returns Some
    // ========================================================================

    @Test
    fun `test custom Error effect with ErrorToOption handler returns Some on success`() {
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            impl Error for ErrorToOption having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = None}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Division by zero"} else Some {val = a / b}

            let handle = {} -> perform ErrorToOption in safe_div {a = 10, b = 2}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Some", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(5L, payload.getField("val"))
    }

    // ========================================================================
    // Test 5: Custom Error Effect - Nested Expression Escapes Correctly
    // ========================================================================

    @Test
    fun `test custom Error effect in nested expression escapes correctly`() {
        // This tests that suspend_with properly escapes to the delimiter boundary
        // and doesn't get wrapped by intermediate expressions like Ok { val = ... }
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            impl Error for ErrorToResult having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = Err {error = err}}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Div by zero"} else a / b

            # Note: wrapping in Ok {val = ...} - the error should escape this
            let handle = {} -> perform ErrorToResult in Ok {val = safe_div {a = 4, b = 0}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        // Should be Err, NOT Ok { val = Err { ... } }
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("Div by zero", payload.getField("error"))
    }

    // ========================================================================
    // Test 6: Custom Error Effect - Success Path Through Nested Expression
    // ========================================================================

    @Test
    fun `test custom Error effect success path through nested expression`() {
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            impl Error for ErrorToResult having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = Err {error = err}}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Div by zero"} else a / b

            let handle = {} -> perform ErrorToResult in Ok {val = safe_div {a = 10, b = 2}}
            handle {}
        """.trimIndent()

        val result = eval(source)
        // Should be Ok { val = 5 }
        assertTrue(result is MezzeVariant)
        assertEquals("Ok", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals(5L, payload.getField("val"))
    }

    // ========================================================================
    // Test 7: Custom Error Effect - Multiple Perform Blocks
    // ========================================================================

    @Test
    fun `test custom Error effect with multiple perform blocks`() {
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            impl Error for ErrorToResult having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = Err {error = err}}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Div by zero"} else a / b

            let compute = {} -> do
                let r1 = perform ErrorToResult in Ok {val = safe_div {a = 4, b = 2}}
                let r2 = perform ErrorToResult in Ok {val = safe_div {a = 4, b = 0}}
                {first = r1, second = r2}
            compute {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val first = record.getField("first") as MezzeVariant
        assertEquals("Ok", first.constructorName)

        val second = record.getField("second") as MezzeVariant
        assertEquals("Err", second.constructorName)
    }

    // ========================================================================
    // Test 8: Custom Error Effect with Record Error Type
    // ========================================================================

    @Test
    fun `test custom Error effect with record error type`() {
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            impl Error for ErrorToResult having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = Err {error = err}}
            }

            type AppError = {code: Int, message: String}

            let validate = {x} ->
                if x < 0 then
                    Error.raise {err = {code = 400, message = "Value must be positive"}}
                else
                    Ok {val = x}

            let handle = {} -> perform ErrorToResult in validate {x = -5}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        val error = payload.getField("error") as MezzeRecord
        assertEquals(400L, error.getField("code"))
        assertEquals("Value must be positive", error.getField("message"))
    }

    // ========================================================================
    // Test 9: Custom Error Effect - Ignore Handler
    // ========================================================================

    @Test
    fun `test custom Error effect with ignore handler`() {
        val source = """
            use std::control::continuation

            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            # Handler that ignores errors and returns unit
            impl Error for IgnoreError having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = {}}
            }

            # Returns unit on both success and error paths
            let maybe_fail = {should_fail} ->
                if should_fail then Error.raise {err = "Ignored error"} else {}

            let handle = {} -> perform IgnoreError in maybe_fail {should_fail = True}
            handle {}
        """.trimIndent()

        val result = eval(source)
        // Should return unit {} (the suspended value from raise)
        assertTrue(result is MezzeRecord)
    }

    // ========================================================================
    // Test 10: Two Different Custom Effects in Same Program
    // ========================================================================

    @Test
    fun `test two different custom effects in same program`() {
        val source = """
            use std::control::continuation

            # Define Error effect
            effect Error having 'e where {
                raise: {err: 'e} -> 'a <Error>
            }

            impl Error for ErrorToResult having 'e = 'err where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                raise = {err} -> scope.cont.suspend_with {value = Err {error = err}}
            }

            # Define a completely different Abort effect
            effect Abort where {
                abort: {} -> 'a <Abort>
            }

            impl Abort for AbortToNone where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                abort = {} -> scope.cont.suspend_with {value = None}
            }

            let safe_div = {a, b} ->
                if b == 0 then Error.raise {err = "Div by zero"} else Ok {val = a / b}

            let maybe_abort = {should_abort} ->
                if should_abort then Abort.abort {} else Some {val = 42}

            let test_error = {} -> perform ErrorToResult in safe_div {a = 10, b = 0}
            let test_abort = {} -> perform AbortToNone in maybe_abort {should_abort = True}

            {error_result = test_error {}, abort_result = test_abort {}}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeRecord)
        val record = result as MezzeRecord

        val errorResult = record.getField("error_result") as MezzeVariant
        assertEquals("Err", errorResult.constructorName)

        val abortResult = record.getField("abort_result") as MezzeVariant
        assertEquals("None", abortResult.constructorName)
    }

    // ========================================================================
    // Test 11: Custom Early Return Effect
    // ========================================================================

    @Test
    fun `test custom EarlyReturn effect`() {
        val source = """
            use std::control::continuation

            # Define custom early return effect (NOT using stdlib Return)
            effect EarlyReturn having 'r where {
                return_early: {val: 'r} -> 'a <EarlyReturn>
            }

            impl EarlyReturn for EarlyReturnHandler having 'r = 'result where {
                initialize_scope = {} -> { cont = continuation::new {} },
                finalize_scope = {} -> scope.cont.reset {},
                return_early = {val} -> scope.cont.suspend_with {value = val}
            }

            let find_first_even = {xs} -> do
                # Would iterate and return early on first even number
                # For this test, just demonstrate the mechanism
                EarlyReturn.return_early {val = 4}
                0  # This is never reached

            let handle = {} -> perform EarlyReturnHandler in find_first_even {xs = [1, 2, 3, 4]}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertEquals(4L, result)
    }

    // ========================================================================
    // Test 12: Custom Effect with Multiple Operations
    // ========================================================================

    @Test
    fun `test custom effect with multiple operations`() {
        val source = """
            use std::control::continuation

            # Effect with two operations
            effect Logger where {
                warn: {msg: String} -> {} <Logger>,
                fail: {msg: String} -> 'a <Logger>
            }

            impl Logger for LoggerToResult where {
                initialize_scope = {} -> { cont = continuation::new {}, logs = [] },
                finalize_scope = {} -> scope.cont.reset {},
                warn = {msg} -> {},  # Just return unit, don't suspend
                fail = {msg} -> scope.cont.suspend_with {value = Err {error = msg}}
            }

            let process = {should_fail} ->
                if should_fail then
                    Logger.fail {msg = "Processing failed"}
                else
                    Ok {val = "Success"}

            let handle = {} -> perform LoggerToResult in process {should_fail = True}
            handle {}
        """.trimIndent()

        val result = eval(source)
        assertTrue(result is MezzeVariant)
        assertEquals("Err", (result as MezzeVariant).constructorName)
        val payload = result.payloadValue as MezzeRecord
        assertEquals("Processing failed", payload.getField("error"))
    }
}
