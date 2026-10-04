package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A hand-written `impl Arbitrary` for a shape the compiler DERIVES is rejected.
 *
 * This file began as a feasibility probe: the shapes a generated body would have,
 * written by hand, to find out whether the design could be expressed before the
 * generator was built. It found what it was for — see `StaticAbilityOpPinningTest`
 * and S-1b — and those exact sources are now illegal, because deriving `Arbitrary`
 * like `Eq` brings `Eq`'s coherence rule with it: one instance per type, and a
 * derived one is an instance.
 *
 * Kept as an assertion rather than deleted, because it is the CONSEQUENCE of the
 * derive-it-everywhere decision and the place to notice it. A record or variant
 * cannot carry a custom generator; a type that needs one (an `Age` that must be
 * 0..120, an `Email` that must parse) says so by being a newtype, which does not
 * derive and must opt in.
 */
class ArbitraryHandWrittenShapeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, SeededSample }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    private fun rejects(label: String, source: String) {
        val e = runCatching { MezzeTestHelper.run("$imports\n\n$source") }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(
            "Duplicate ability impl" in message && "derived by the compiler" in message,
            "$label: expected a coherence refusal naming the derived instance, got: $message",
        )
    }

    @Test
    fun `a hand-written impl for a RECORD is a duplicate`() {
        rejects(
            "record",
            """
            type Point = { x: Int, y: Int }

            impl Arbitrary for Point where {
              arbitrary = {} -> { x = Arbitrary.arbitrary {}, y = Arbitrary.arbitrary {} }
            }

            perform SeededSample { seed = 4 } in do
              let p: Point = Arbitrary.arbitrary {}
              p.x
            """.trimIndent(),
        )
    }

    @Test
    fun `a hand-written impl for a VARIANT is a duplicate`() {
        rejects(
            "variant",
            """
            type Wrap = | Wrapped { val: Int } |

            impl Arbitrary for Wrap where {
              arbitrary = {} -> Wrapped { val = Arbitrary.arbitrary {} }
            }

            perform SeededSample { seed = 6 } in do
              let w: Wrap = Arbitrary.arbitrary {}
              match w is
                Wrapped { val } -> val
            """.trimIndent(),
        )
    }

    /**
     * A NEWTYPE is the escape hatch, and the one place a custom generator lives.
     * It does not derive, so its hand-written impl is the ONLY instance and is
     * accepted — which is what makes "wrap it in a newtype" a real answer rather
     * than advice with nothing behind it.
     */
    @Test
    fun `a newtype accepts a hand-written impl and uses it`() {
        val v = MezzeTestHelper.asLong(MezzeTestHelper.run("""
            $imports

            newtype Age = Int

            impl Arbitrary for Age where {
              arbitrary = {} -> Age.(42)
            }

            # A newtype is fully erased at runtime, so the value that comes back IS
            # the wrapped Int — no unwrap needed to check which generator ran.
            perform SeededSample { seed = 1 } in do
              let a: Age = Arbitrary.arbitrary {}
              a
        """.trimIndent()))
        assertTrue(v == 42L, "the newtype's own generator was not used: $v")
    }
}
