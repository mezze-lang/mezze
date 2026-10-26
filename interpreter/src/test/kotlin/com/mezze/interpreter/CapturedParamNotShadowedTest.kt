package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A captured parameter is the parameter, not a top-level binding of that name.
 *
 * `TypedASTCompiler`'s `BindingRef.Local` fall-through looked in the current
 * scope, the outer scopes, the params and the locals — and then searched the
 * declaration index BY NAME. A function-valued parameter that a NESTED lambda
 * captured was not found by any of the four, so the name search ran and the
 * top-level declaration won. The `BindingRef.Param` branch twenty lines below
 * has always consulted the captures first; this branch did not.
 *
 * It took a while to see because every obvious variation is correct: the same
 * collision with the parameter used directly in a body, the same collision on
 * an `Int`, a named argument instead of an inline lambda, a free function
 * instead of a method, the global declared after the call. It needed the
 * nesting AND a function-valued name. In the stdlib it bit hardest —
 * `http_router`'s `route` reads its `handler` inside `run = { req } -> … }` and
 * `otherwise` reads its `fallback` inside `{ req } -> … }`, so a user writing
 * the obvious `let handler` or `let fallback` had their own function called in
 * place of the one they passed. Those two are covered where they live, in
 * HttpThrowingFallbackTest; these are the language-level cases.
 */
class CapturedParamNotShadowedTest {
    /** The four-line reproduction. 111 is the global, 222 the argument. */
    @Test fun `a nested lambda captures the parameter, not the global of that name`() {
        assertEquals(
            222L,
            MezzeTestHelper.run(
                """
                let f: { n: Int } -> Int = { n } -> 111
                let mk: { f: { n: Int } -> Int } -> { } -> Int = { f } -> { } -> f { n = 1 }
                let g = mk { f = { n } -> 222 }
                g { }
                """.trimIndent()
            ),
        )
    }

    /** Two levels of nesting, so it is not an off-by-one in one frame. */
    @Test fun `the same through two levels of nesting`() {
        assertEquals(
            222L,
            MezzeTestHelper.run(
                """
                let f: { n: Int } -> Int = { n } -> 111
                let mk: { f: { n: Int } -> Int } -> { } -> { } -> Int =
                  { f } -> { } -> { } -> f { n = 1 }
                let g = mk { f = { n } -> 222 }
                g { } { }
                """.trimIndent()
            ),
        )
    }

    /**
     * A genuine sibling reference must still resolve. This is what the by-name
     * search was there for — a top-level function calling another from inside a
     * nested lambda — and the capture check must not take it away.
     */
    @Test fun `a top-level sibling is still reachable from a nested lambda`() {
        assertEquals(
            333L,
            MezzeTestHelper.run(
                """
                let helper: { n: Int } -> Int = { n } -> 333
                let mk: { k: Int } -> { } -> Int = { k } -> { } -> helper { n = k }
                let g = mk { k = 1 }
                g { }
                """.trimIndent()
            ),
        )
    }

    /** And the shadowed name still reads the global where there is no parameter. */
    @Test fun `the global is used when nothing captures that name`() {
        assertEquals(
            111L,
            MezzeTestHelper.run(
                """
                let f: { n: Int } -> Int = { n } -> 111
                let mk: { k: Int } -> { } -> Int = { k } -> { } -> f { n = k }
                let g = mk { k = 1 }
                g { }
                """.trimIndent()
            ),
        )
    }

    /** The cases that were already correct, kept so a fix cannot regress them. */
    @Test fun `a directly used parameter is unaffected`() {
        assertEquals(
            222L,
            MezzeTestHelper.run(
                """
                let f: { n: Int } -> Int = { n } -> 111
                let use_it: { f: { n: Int } -> Int } -> Int = { f } -> f { n = 1 }
                use_it { f = { n } -> 222 }
                """.trimIndent()
            ),
        )
    }

    @Test fun `a captured Int parameter is unaffected`() {
        assertEquals(
            222L,
            MezzeTestHelper.run(
                """
                let k: Int = 111
                let mk: { k: Int } -> { } -> Int = { k } -> { } -> k
                let g = mk { k = 222 }
                g { }
                """.trimIndent()
            ),
        )
    }
}
