package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A scripted operation hands out its row's values in CALL order (SAMPLE-5).
 *
 * `mez sample` runs a function whose effects nothing handles by scripting them:
 * the row draws each operation's results up front and the performer hands them
 * back. One value per row made `let a = now {}; let b = now {}; b > a` impossible
 * to sample as true — the shape of almost every use of a clock.
 */
class ScriptedOperationTest {

    private fun program(body: String) = """
        use std::effects::sample::{ scripted }

        effect Clock where {
          now: {} -> Int <Clock>
        }

        impl Clock for Script { r_now: [Int], key: Int } where {
          now = {} -> scripted { values = r_now, key = key, op = 0 }
        }

        $body
    """.trimIndent()

    @Test
    fun `successive calls get successive values`() {
        val result = MezzeTestHelper.run(program("""
            let later = {} -> do
              let a = Clock.now {}
              let b = Clock.now {}
              b > a

            let main = {} -> perform Script { r_now = [1, 5], key = 11 } in later {}
            main {}
        """))
        assertEquals(true, result)
    }

    @Test
    fun `past the end the sequence repeats from the start`() {
        val result = MezzeTestHelper.run(program("""
            let three = {} -> do
              let a = Clock.now {}
              let b = Clock.now {}
              let c = Clock.now {}
              [a, b, c]

            let main = {} -> perform Script { r_now = [1, 5], key = 12 } in three {}
            main {}
        """))
        assertEquals("[1, 5, 1]", result.toString())
    }

    @Test
    fun `two rows never share a cursor`() {
        val result = MezzeTestHelper.run(program("""
            let first = {} -> Clock.now {}

            let main = {} -> [
              perform Script { r_now = [1, 5], key = 13 } in first {},
              perform Script { r_now = [1, 5], key = 14 } in first {},
            ]
            main {}
        """))
        assertEquals("[1, 1]", result.toString())
    }
}
