package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * BUG 64: a performer op body reported a solver failure only when it was
 * "settled" — a T0201 with no type variable and no effectful arrow on either
 * side — and dropped every other failure as "pinned at the use site". The same
 * code in an ability impl body was refused all along. Found while writing the
 * `cont.park` spec: `cont.park { on = … }` with `set` omitted type-checked.
 *
 * Each rejected shape has its accepting mirror in the same position, so the fix
 * is shown to report the error and not the position.
 */
class PerformerBodySolveErrorsSpecTest : AbilityEffectSpecBase() {

    private val op = """
        effect Wait where { wait: {} -> Int <Wait>, }
        let mk = {} -> { f = { val } -> val }
    """

    private fun performer(body: String) = """
        $op
        impl Wait for P where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          wait = {} -> $body,
        }
        $MAIN
    """

    @Test
    fun `a call with the wrong field name is refused in an op body`() {
        rejects(
            performer("""do
            let c = mk {}
            let x: Int = c.f { nope = 1 }
            x"""),
            "T0201",
            "`{ nope }` is not `{ val }` under any instantiation of `'v`; an ability impl body already said so",
        )
    }

    @Test
    fun `the same call with the right field name is accepted`() {
        accepts(
            performer("""do
            let c = mk {}
            let x: Int = c.f { val = 1 }
            x"""),
            "mirror",
        )
    }

    @Test
    fun `a partially applied effectful function is not a variant`() {
        rejects(
            """
            type Out = | Ready { which: Int }, Gone, |
            effect E where { e: {} -> Int <E>, }
            effect Wait where { wait: {} -> Int <Wait>, }
            let f = { a, b } -> Ready { which = a + b + E.e {} }
            impl Wait for P where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> match f { a = 1 } is
                Ready { which } -> which
                Gone -> 0,
            }
            $MAIN
            """,
            "V0704",
            "`f { a = 1 }` is `{ b } -> Out <E>`; the arrow's row was the excuse for dropping this",
        )
    }

    @Test
    fun `the fully applied call is matched fine`() {
        accepts(
            """
            type Out = | Ready { which: Int }, Gone, |
            effect E where { e: {} -> Int <E>, }
            effect Wait where { wait: {} -> Int <Wait>, }
            let f = { a, b } -> Ready { which = a + b + E.e {} }
            impl Wait for P where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> match f { a = 1, b = 2 } is
                Ready { which } -> which
                Gone -> 0,
            }
            $MAIN
            """,
            "mirror",
        )
    }

    @Test
    fun `an open ability obligation is still the use site's to satisfy`() {
        accepts(
            """
            effect Show having 'v where { show: { v: 'v } -> Str <Show>, }
            impl Show for Plain having 'v = Int where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              show = { v } -> (v + 1).to_str {},
            }
            $MAIN
            """,
            "BUG 54's rule survives: `NumAdd Int Int` is settled here and satisfied; the exception is for OPEN arguments only",
        )
    }
}
