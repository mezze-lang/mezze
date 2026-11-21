package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A suspension (an effect call that found no handler) is runtime plumbing that
 * a later `perform` forces. It must never become text or a program result.
 *
 * The shape that leaked it: a lazy `Iter` stage built inside `perform Ticker`
 * and pulled after that scope ended. The stage's callback then performed `Tick`
 * with no handler, and the suspension it returned was interpolated into output
 * as `<suspended <hash>>`. Two fixes, both pinned here:
 *
 *  1. The type checker refuses the shape — stage callbacks are pure, so an
 *     effect can only run on an arrow the checker tracks.
 *  2. If an unhandled effect ever reaches a value position anyway (the test
 *     harness runs a top-level expression with no entry-point validation, so it
 *     can), rendering it fails loudly (I0004) instead of printing the suspension.
 */
class SuspendedValueNeverObservableTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val head = """
        use std::collections::iter::{ from_seq, map, fold }

        effect Tick where { tick: {} -> Str <Tick> }
        impl Tick for Ticker where { tick = {} -> "tick" }
    """.trimIndent()

    @Test
    fun `a stream built inside a handler's scope cannot carry the effect out - the checker refuses it`() {
        val src = """
            $head
            do
              let src = perform Ticker in map { it = from_seq { xs = [1, 2] }, f = { x } -> Tick.tick {} }
              fold { it = src, init = "", f = { acc, x } -> "${'$'}(acc)${'$'}(x);" }
        """.trimIndent()
        val failure = runCatching { MezzeTestHelper.runAllowingDroppedEffects(src) }
        val message = failure.exceptionOrNull()?.message
            ?: throw AssertionError("expected a type error, got the value: ${failure.getOrNull()}")
        assertTrue(!message.contains("<suspended"), "a suspension leaked into the message: $message")
        assertTrue(message.contains("TypeCheckError") && message.contains("`map`"),
            "the effectful stage callback must be refused at the `map` call; got: $message")
    }

    @Test
    fun `an unhandled effect reaching a value position fails loudly instead of rendering a suspension`() {
        // No entry point, so nothing validates that Tick is handled: the op runs
        // unhandled and its suspension reaches a value position.
        val src = """
            $head
            "got ${'$'}(Tick.tick {})"
        """.trimIndent()
        val failure = runCatching { MezzeTestHelper.runAllowingDroppedEffects(src) }
        val message = failure.exceptionOrNull()?.message
            ?: throw AssertionError("expected a loud failure, got the value: ${failure.getOrNull()}")
        assertTrue(!message.contains("<suspended"), "a suspension leaked into the message: $message")
        // The whole top-level expression is suspended as one body, so the guard
        // that fires is the program's end rather than the interpolation; either
        // way it is loud and names the operation.
        assertTrue(
            message.contains("Effect operation `tick` ran with no handler in scope"),
            "unexpected failure: $message",
        )
    }
}
