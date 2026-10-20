package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A lambda inside a handler's operation, reading the HANDLER's parameter.
 *
 * The operation bodies were built with no scope for the handler's parameters,
 * so a lambda inside one did not see `data` as coming from an enclosing level:
 * it was never recorded as a capture, and the compiler read a local slot that
 * did not exist — "Frame slot kind Object expected, but got Illegal". It hid as
 * long as the lambda also captured an operation parameter.
 */
class PerformerParamCaptureTest {

    private val head = """
        use std::collections::seq
        effect Ask where { ask: { n: Int } -> [Int] <Ask> }
    """.trimIndent()

    @Test
    fun `a lambda in an op that captures only the handler's parameter`() = assertEquals(
        "[1, 2]",
        MezzeTestHelper.run("""
            $head
            impl Ask for WithData { data: [Int] } where {
              ask = { n } -> do
                let found = seq::filter { xs = [1, 2, 3], p = { x } -> seq::size { xs = data } > x }
                found
            }
            perform WithData { data = [1, 2, 3] } in Ask.ask { n = 1 }
        """.trimIndent()).toString(),
    )

    @Test
    fun `a lambda in an op that captures both kinds`() = assertEquals(
        "[3]",
        MezzeTestHelper.run("""
            $head
            impl Ask for WithData { data: [Int] } where {
              ask = { n } -> seq::filter { xs = data, p = { x } -> x > n + seq::size { xs = data } - 2 }
            }
            perform WithData { data = [1, 2, 3] } in Ask.ask { n = 1 }
        """.trimIndent()).toString(),
    )
}
