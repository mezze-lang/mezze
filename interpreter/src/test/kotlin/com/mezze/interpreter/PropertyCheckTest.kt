package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `std::property::check` — and above all its shrinking (SHRINK-1).
 *
 * The counterexample a random draw finds is whatever the draw happened to be; the
 * one worth reporting is the simplest input that still fails. Shrinking works on
 * the run's draws, not on the type, so these use types with no shrinker at all.
 */
class PropertyCheckTest {

    private val head = """
        use std::property::{ check, check_from, Passed, Failed }
        use std::effects::sample::{  }
        use std::abilities::arbitrary::{ Arbitrary }

        let ints: {} -> Int <Sample> = {} -> Arbitrary.arbitrary {}
        let lists: {} -> [Int] <Sample> = {} -> Arbitrary.arbitrary {}
    """.trimIndent()

    private fun run(body: String): String = MezzeTestHelper.run("$head\n\n${body.trimIndent()}").toString()

    @Test
    fun `a property that holds passes every run`() {
        val out = run("""
            let main = {} -> match check { gen = ints, holds = { x } -> x == x, runs = 50, seed = 3 } is
                Passed { runs } -> "passed $(runs.to_str {})"
                Failed { input, original, shrinks } -> "failed"
            main {}
        """)
        assertEquals("passed 50", out)
    }

    @Test
    fun `an Int counterexample shrinks to the boundary`() {
        // Fails for every x >= 10 drawn as a RANDOM value; the simplest is 10 exactly.
        // (Int's maximum is excluded: met in the special-values table, it shrinks to
        // itself — see the special-values test below.)
        val out = run("""
            let main = {} -> match check { gen = ints, holds = { x } -> x < 10 || x == 9223372036854775807, runs = 200, seed = 1 } is
                Passed { runs } -> "passed"
                Failed { input, original, shrinks } -> input.to_str {}
            main {}
        """)
        assertEquals("10", out)
    }

    @Test
    fun `a list counterexample shrinks to the smallest list that fails`() {
        // Fails once the list has 3 or more elements: [0, 0, 0] is simplest.
        val out = run("""
            let main = {} -> match check { gen = lists, holds = { x } -> x.size {} < 3, runs = 200, seed = 5 } is
                Passed { runs } -> "passed"
                Failed { input, original, shrinks } -> input.to_dbg {}
            main {}
        """)
        assertEquals("[0, 0, 0]", out)
    }

    @Test
    fun `the original failing input is kept beside the shrunk one`() {
        val out = run("""
            let main = {} -> match check { gen = ints, holds = { x } -> x < 10, runs = 200, seed = 1 } is
                Passed { runs } -> "passed"
                Failed { input, original, shrinks } -> "$(original.to_str {}) $(shrinks.to_str {})"
            main {}
        """)
        val (original, shrinks) = out.split(" ").map { it.toLong() }
        assertTrue(original >= 10, "the original is a real failure: $original")
        assertTrue(shrinks > 0 || original == 10L, "it took steps to get to 10: $out")
    }

    @Test
    fun `a string counterexample shrinks to the empty string when that fails`() {
        val out = run("""
            let strs: {} -> Str <Sample> = {} -> Arbitrary.arbitrary {}
            let main = {} -> match check { gen = strs, holds = { x } -> x.size {} > 100, runs = 20, seed = 4 } is
                Passed { runs } -> "passed"
                Failed { input, original, shrinks } -> input.to_dbg {}
            main {}
        """)
        assertEquals("\"\"", out)
    }

    @Test
    fun `a failure first found among the special values shrinks to the simplest one that fails`() {
        // Only Int's maximum fails, and it is reachable only from the table of
        // special values — the shrunk input is that value, found by draws alone.
        val out = run("""
            let main = {} -> match check { gen = ints, holds = { x } -> x < 9223372036854775807, runs = 300, seed = 2 } is
                Passed { runs } -> "passed"
                Failed { input, original, shrinks } -> input.to_str {}
            main {}
        """)
        assertEquals("9223372036854775807", out)
    }

    @Test
    fun `pinned inputs are checked first and a failure among them is reported as given`() {
        // Fails only at 7 — a value no run of 50 seeded draws is likely to hit,
        // and exactly the kind a branch plan pins.
        val out = run("""
            let main = {} -> match check_from { first = [0, 7, 100], gen = ints, holds = { x } -> x != 7, runs = 50, seed = 3 } is
                Passed { runs } -> "passed $(runs.to_str {})"
                Failed { input, original, shrinks } -> "failed at $(input.to_str {}) after $(shrinks.to_str {}) shrinks"
            main {}
        """)
        assertEquals("failed at 7 after 0 shrinks", out)
    }

    @Test
    fun `when every pinned input holds the drawn runs follow and the count includes both`() {
        val out = run("""
            let main = {} -> match check_from { first = [1, 2], gen = ints, holds = { x } -> x == x, runs = 50, seed = 3 } is
                Passed { runs } -> "passed $(runs.to_str {})"
                Failed { input, original, shrinks } -> "failed"
            main {}
        """)
        assertEquals("passed 52", out)
    }
}
