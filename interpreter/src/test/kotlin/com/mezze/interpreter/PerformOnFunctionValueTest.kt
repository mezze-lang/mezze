package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S-20: `perform H in <a function value>` binds the handler INTO that value.
 *
 * `perform Console in my_func` produces `my_func`, it does not call it. A Handle
 * lowers to push / body / pop, so the handler was popped before the returned
 * function was ever invoked:
 *
 *     let handled = perform Console in my_func   -- type: {} -> Str <*>
 *     handled {}                                 -- printed NOTHING, exit 0
 *
 * No diagnostic anywhere: `mez check` accepted it, `mez build` succeeded, the
 * program exited 0. The type system discharges the effect from the function's
 * type and 19 tests in ComprehensiveEffectHandlingTest pin that, so the VALUE has
 * to carry the handler for the type to be true.
 *
 * Every test here asserts stdout, because the defect was invisible to anything
 * that only looked at types — which is exactly why it survived.
 */
class PerformOnFunctionValueTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    /**
     * The shared prelude is prepended rather than interpolated: a multi-line
     * `${'$'}value` inside a `trimIndent()` block leaves the interpolated lines at
     * column 0 while the rest keep their indent, so trimIndent computes a common
     * prefix of 0 and the surrounding lines stay indented — which parses as
     * something else entirely.
     */
    private val prelude = """
        use std::effects::log

        let my_func: {} -> Str <Log> = {} -> do
          let u = log::println { msg = "RAN" }
          "Done"
    """.trimIndent()

    private fun outputOf(rest: String): String =
        MezzeTestHelper.runWithOutput(prelude + "\n" + rest.trimIndent()).output().trim()

    /** THE case. Silently printed nothing before the fix. */
    @Test
    fun `a handler bound into a function value fires when that function is called`() {
        assertEquals("RAN", outputOf("""
            let handled = perform Console in my_func
            handled {}
        """))
    }

    /** The control that always worked: the handler wraps the CALL. */
    @Test
    fun `a handler wrapping the call still fires`() {
        assertEquals("RAN", outputOf("""
            perform Console in my_func {}
        """))
    }

    /** The binding is not single-use — install/pop is re-entrant. */
    @Test
    fun `a bound function can be called more than once`() {
        assertEquals("RAN\nRAN", outputOf("""
            let run = {} -> do
              let handled = perform Console in my_func
              let a = handled {}
              let b = handled {}
              {}
            run {}
        """))
    }

    /**
     * The ORIGINAL function is untouched — binding produces a new value. This is
     * what ComprehensiveEffectHandlingTest's "perform on uncalled function does
     * not mutate original function" asks for, asserted on behaviour rather than
     * on the type.
     */
    @Test
    fun `binding does not mutate the original function`() {
        assertEquals("RAN\nRAN", outputOf("""
            let run = {} -> do
              let handled = perform Console in my_func
              let a = handled {}
              let b = perform Console in my_func {}
              {}
            run {}
        """))
    }

    /**
     * The mixed shape, and the reason the capture is ADDITIVE rather than a
     * replacement for the push/pop: the body BOTH performs the effect itself and
     * returns a function that performs it later. The push/pop covers the first,
     * the captured chain covers the second, and both must fire — in that order.
     */
    @Test
    fun `a body that performs AND returns a function gets both`() {
        assertEquals("BODY\nRAN", outputOf("""
            let handled = perform Console in do
              let u = log::println { msg = "BODY" }
              my_func
            handled {}
        """))
    }

    /**
     * A body producing a non-function is unaffected — the capture passes any
     * other value straight through, so wrapping it is safe wherever the flag is
     * set.
     */
    @Test
    fun `a non-function result is unchanged`() {
        assertEquals("RAN", outputOf("""
            let run = {} -> perform Console in my_func {}
            run {}
        """))
    }
}
