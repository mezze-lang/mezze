package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * `cont` — the runtime-bound name of continuations-and-swappable-async — as a
 * typechecker rule set, mirrored like the rest of the ability/effect spec:
 *
 *  - it is bound inside a performer op body and NOWHERE else (`scope`'s rule);
 *  - `cont.park { on, set }` is a `ParkOutcome`, so a `match` on it is checked
 *    for exhaustiveness like any other variant — a performer cannot forget
 *    `ParkCancelled`;
 *  - `cont.abort { val }` takes the op's declared return type.
 *
 * Every rule has its accepting mirror. Codes only, never message prose.
 */
class ContBindingSpecTest : AbilityEffectSpecBase() {

    private val wait = """
        use std::effects::async::{ ParkOn, WaitSet, ParkOutcome }
        effect Wait where { wait: {} -> Int <Wait>, }
    """

    @Test
    fun `cont outside a performer op body is refused`() {
        rejects(
            """
            pub let main = {} -> cont.park { on = OnPredicates { fs = [] }, set = WaitDefault }
            """,
            "V0702",
            "`cont` is runtime-bound in a performer op body and nowhere else",
        )
    }

    @Test
    fun `cont in an ability impl body is refused`() {
        rejects(
            """
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for Int where { sz = { it } -> cont.abort { val = 1 } }
            $MAIN
            """,
            "V0702",
            "an ability impl is not a performer; there is no perform to park or abort",
        )
    }

    @Test
    fun `a performer op body may park and must handle both outcomes`() {
        accepts(
            """
            $wait
            impl Wait for Parker where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> match cont.park { on = OnPredicates { fs = [] }, set = WaitDefault } is
                ParkReady { which } -> which
                ParkCancelled { reason } -> 0 - 1,
            }
            $MAIN
            """,
            "`cont.park` is a `ParkOutcome`; both constructors covered",
        )
    }

    @Test
    fun `a park whose match forgets ParkCancelled is refused`() {
        rejects(
            """
            $wait
            impl Wait for Parker where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> match cont.park { on = OnPredicates { fs = [] }, set = WaitDefault } is
                ParkReady { which } -> which,
            }
            $MAIN
            """,
            "V0601",
            "cancellation is a real outcome of every park; the typechecker makes a performer say what it does with it",
        )
    }

    @Test
    fun `a park's outcome is a ParkOutcome, not whatever the op returns`() {
        rejects(
            """
            $wait
            impl Wait for Parker where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> cont.park { on = OnPredicates { fs = [] }, set = WaitDefault },
            }
            $MAIN
            """,
            "T0201",
            "`wait` returns Int; a `ParkOutcome` handed back unexamined is a type error, not a silent coercion",
        )
    }

    @Test
    fun `a park request names its wait set and what it waits on`() {
        rejects(
            """
            $wait
            impl Wait for Parker where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> match cont.park { on = OnPredicates { fs = [] } } is
                ParkReady { which } -> which
                ParkCancelled { reason } -> 0 - 1,
            }
            $MAIN
            """,
            "V0704",
            "with `set` omitted the call is a PARTIAL APPLICATION — `{ set } -> ParkOutcome` — and a function " +
                "is not a variant to match constructors against. This claim type-checked clean before BUG 64 " +
                "(a performer op body dropped every solver failure that mentioned a type variable)",
        )
    }

    @Test
    fun `cont abort ends the perform with a value of the block's type`() {
        accepts(
            """
            $wait
            impl Wait for Aborter where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> cont.abort { val = 0 },
            }
            pub let run: {} -> Int <*> = {} -> perform Aborter in Wait.wait {} + 1
            $MAIN
            """,
            "same contract as the plain `abort`: the payload becomes the value of the whole perform",
        )
    }

    @Test
    fun `cont abort with a payload of the wrong type is refused at the perform`() {
        rejects(
            """
            $wait
            impl Wait for Aborter where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              wait = {} -> cont.abort { val = "zero" },
            }
            pub let run: {} -> Int <*> = {} -> perform Aborter in Wait.wait {} + 1
            $MAIN
            """,
            "T0201",
            "the block yields Int when nothing aborts and Str when it does — the plain `abort` mirror, `PerformAbortPayloadSpecTest`",
        )
    }
}
