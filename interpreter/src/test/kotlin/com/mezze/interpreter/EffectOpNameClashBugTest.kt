package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression tests for #246: when two distinct effects in scope both declare
 * an op with the same simple name (e.g. both `Args.get` and `Cwd.get`), the
 * resolver was picking the wrong impl for one of the call sites.
 *
 * The user-visible symptom was silent value corruption: `Cwd.get {}` returned
 * `Args.get {}`'s value (or vice versa) at runtime, no error reported.
 *
 * The fix tags each handler chain entry with the content hash of the effect
 * it implements, and the dispatcher filters by (effect_hash, op_name) before
 * accepting a field match.
 */
class EffectOpNameClashBugTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    /**
     * Baseline: two effects with distinct op names work correctly.
     * Should always pass — proves the rest of the test infrastructure works.
     */
    @Test
    fun `distinct op names — baseline works`() {
        val result = eval(
            """
            effect E1 where {
              read_a: {} -> Str <E1>
            }
            effect E2 where {
              read_b: {} -> Int <E2>
            }
            impl E1 for H1 where {
              read_a = {} -> "from_E1"
            }
            impl E2 for H2 where {
              read_b = {} -> 42
            }

            perform H1 in
            perform H2 in do
              let a = E1.read_a {}
              let b = E2.read_b {}
              # If anything went wrong, comparison would fail.
              a == "from_E1" && b == 42
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    /**
     * Two effects, same op simple-name, called in the same do-block.
     * Reproduces the original bug from #245's split work where Args.get
     * and Cwd.get clashed.
     */
    @Test
    fun `same op simple name across two effects — both should be reachable`() {
        val result = eval(
            """
            effect E1 where {
              shared: {} -> Str <E1>
            }
            effect E2 where {
              shared: {} -> Int <E2>
            }
            impl E1 for H1 where {
              shared = {} -> "from_E1"
            }
            impl E2 for H2 where {
              shared = {} -> 99
            }

            perform H1 in
            perform H2 in do
              let a = E1.shared {}
              let b = E2.shared {}
              # If the resolver picks the wrong impl for either site, this
              # will produce wrong types or wrong values.
              a == "from_E1" && b == 99
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    /**
     * Same as above but with handler order flipped — neither order should
     * matter if resolution is by (ability, op) not by handler-scope-order.
     */
    @Test
    fun `same op simple name — flipped handler order`() {
        val result = eval(
            """
            effect E1 where {
              shared: {} -> Str <E1>
            }
            effect E2 where {
              shared: {} -> Int <E2>
            }
            impl E1 for H1 where {
              shared = {} -> "from_E1"
            }
            impl E2 for H2 where {
              shared = {} -> 99
            }

            perform H2 in
            perform H1 in do
              let a = E1.shared {}
              let b = E2.shared {}
              a == "from_E1" && b == 99
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    /**
     * Three effects, same op simple-name across all of them, all called.
     * Stress test: makes sure the bug isn't "just a 2-way collision" but
     * arbitrary breadth.
     */
    @Test
    fun `three effects with same op simple name`() {
        val result = eval(
            """
            effect E1 where {
              fetch: {} -> Str <E1>
            }
            effect E2 where {
              fetch: {} -> Int <E2>
            }
            effect E3 where {
              fetch: {} -> Bool <E3>
            }
            impl E1 for H1 where {
              fetch = {} -> "alpha"
            }
            impl E2 for H2 where {
              fetch = {} -> 7
            }
            impl E3 for H3 where {
              fetch = {} -> True
            }

            perform H1 in
            perform H2 in
            perform H3 in do
              let a = E1.fetch {}
              let b = E2.fetch {}
              let c = E3.fetch {}
              a == "alpha" && b == 7 && c == True
            """.trimIndent()
        )
        assertEquals(true, result)
    }

    /**
     * Same op name with same return type — distinguishes "type-driven
     * resolution accidentally works because the types differ" from "by-name
     * lookup actually does the right thing." If the resolver disambiguates
     * by op return type, this test passes despite the bug being present.
     * If it disambiguates by (ability, op) keys, this also passes.
     * If it disambiguates by string name only and types let it slide, this
     * test will fail with value corruption.
     */
    @Test
    fun `same op simple name and same return type`() {
        val result = eval(
            """
            effect E1 where {
              read: {} -> Str <E1>
            }
            effect E2 where {
              read: {} -> Str <E2>
            }
            impl E1 for H1 where {
              read = {} -> "value_from_E1"
            }
            impl E2 for H2 where {
              read = {} -> "value_from_E2"
            }

            perform H1 in
            perform H2 in do
              let a = E1.read {}
              let b = E2.read {}
              a == "value_from_E1" && b == "value_from_E2"
            """.trimIndent()
        )
        assertEquals(true, result)
    }
}
