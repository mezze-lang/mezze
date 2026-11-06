package com.mezze.interpreter

import kotlin.test.assertEquals
import org.junit.jupiter.api.Test

/**
 * `|` scopes a guard, AT RUNTIME.
 *
 * The typechecker accepting a per-alternative guard proves nothing about what
 * runs. Each alternative is tried in order and matches only when its pattern
 * matches AND its own guard holds, falling through to the next alternative and
 * then to the next arm.
 *
 * Emit expands such an arm into one typed case per alternative, so what these
 * exercise is that the expansion preserves order, bindings and fall-through.
 */
class AltGuardRuntimeTest {

    private fun run(src: String): Any? = MezzeTestHelper.run(src.trimIndent())

    private val decl = """
        use std::int
        use std::collections::list

        type C = | Red { n: Int }, Pink { n: Int } |

        pub let pick = { c: C } -> match c is
          Red { n } if n > 0 | Pink { n } if n < 5 -> n
          _ -> 0 - 1
    """

    /** Each guard gates only the alternative it sits beside. */
    @Test fun `each guard gates its own alternative`() {
        assertEquals(
            7L,
            run("$decl\npick { c = Red { n = 7 } }"),
            "Red with 7 > 0 takes the FIRST alternative.",
        )
        assertEquals(
            -1L,
            run("$decl\npick { c = Red { n = 0 - 2 } }"),
            "Red with -2 fails its own guard; Pink's PATTERN does not match a Red, " +
                "so the arm fails entirely and the fallback runs. If one guard gated " +
                "both alternatives this would be the same — the next case is what " +
                "separates them.",
        )
        assertEquals(
            3L,
            run("$decl\npick { c = Pink { n = 3 } }"),
            "Pink with 3 < 5 takes the SECOND alternative, under the SECOND guard. " +
                "Pink with 3 would FAIL the first alternative's `n > 0`… which it also " +
                "satisfies, so the discriminating case is the next one.",
        )
        assertEquals(
            -1L,
            run("$decl\npick { c = Pink { n = 9 } }"),
            "THE DISCRIMINATING CASE: 9 fails Pink's own `n < 5`, so the arm does not " +
                "match. Under an arm-level guard reading, `n > 0` would have been the " +
                "test and 9 would have matched, returning 9.",
        )
    }

    /** An unguarded alternative beside a guarded one matches unconditionally. */
    @Test fun `an unguarded alternative is not gated by a sibling's guard`() {
        val src = """
            use std::int

            type D = | Red, Pink |

            pub let pick = { c: D, n: Int } -> match c is
              Red | Pink if n > 0 -> 1
              Pink -> 2
        """
        assertEquals(
            1L,
            run("$src\npick { c = Red, n = 0 - 5 }"),
            "THE POINT OF SCOPING: `Red` carries no guard, so a negative `n` cannot " +
                "stop it. Under the old arm-level reading the guard gated `Red` too and " +
                "this fell through.",
        )
        assertEquals(1L, run("$src\npick { c = Pink, n = 4 }"), "Pink's guard holds.")
        assertEquals(
            2L,
            run("$src\npick { c = Pink, n = 0 - 5 }"),
            "Pink's guard fails, so the arm does not match and the NEXT ARM takes it.",
        )
    }

    /** Order is preserved across the expansion. */
    @Test fun `alternatives are tried in source order`() {
        val src = """
            use std::int

            type E = | Red, Pink, Blue |

            pub let pick = { c: E, n: Int } -> match c is
              Red if n > 0 | Pink | Blue if n < 5 -> 1
              _ -> 2
        """
        assertEquals(1L, run("$src\npick { c = Red, n = 3 }"), "first alternative, guard holds")
        assertEquals(2L, run("$src\npick { c = Red, n = 0 }"), "first alternative's guard fails")
        assertEquals(1L, run("$src\npick { c = Pink, n = 0 }"), "middle alternative is unguarded")
        assertEquals(1L, run("$src\npick { c = Blue, n = 3 }"), "third alternative, guard holds")
        assertEquals(2L, run("$src\npick { c = Blue, n = 9 }"), "third alternative's guard fails")
    }
}
