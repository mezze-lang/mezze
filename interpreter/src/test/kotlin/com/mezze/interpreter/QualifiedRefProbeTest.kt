package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Can a module reference a stdlib value by FULL PATH with no `use`?
 *
 * If yes, a compiler-generated body can name anything it needs and the derived
 * Arbitrary impl stops being limited to what the prelude propagates.
 */
class QualifiedRefProbeTest {
    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun show(label: String, body: String) {
        System.err.println("=== $label ===")
        try {
            System.err.println("   OK -> " + MezzeTestHelper.run(body))
        } catch (e: Exception) {
            System.err.println("   FAILED -> " + (e.message ?: "").lines().first().take(170))
        }
    }

    /** A plain stdlib function, fully qualified, with NO import at all. */
    @Test fun `Q1 fully qualified stdlib function, no use`() {
        show("Q1  std::int::abs { n = 0 - 5 }", "std::int::abs { n = 0 - 5 }")
    }

    /** An EFFECT operation by full path. */
    @Test fun `Q2 fully qualified effect operation`() {
        show("Q2  std::effects::sample::choose via with_seed", """
            std::effects::sample::with_seed { seed = 3, call = {} ->
              std::effects::sample::choose { bound = 100 } }
        """.trimIndent())
    }

    /** The seq generator by full path — what a derived body would emit. */
    @Test fun `Q3 fully qualified list_of`() {
        show("Q3  std::effects::sample::list_of", """
            std::effects::sample::with_seed { seed = 3, call = {} -> do
              let xs: [Int] = std::effects::sample::list_of {
                g = {} -> std::abilities::arbitrary::Arbitrary.arbitrary {} }
              std::collections::seq::size { xs = xs }
            }
        """.trimIndent())
    }
}
