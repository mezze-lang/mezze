package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Phase A of continuations-and-swappable-async, the typing half: the five
 * promoted ops are REQUIRED of every `Async` performer. The runtime half
 * (interception actually happening) is `AsyncOpsPromotedTest` in the interpreter.
 */
class AsyncOpsPromotedSpecTest : AbilityEffectSpecBase() {

    @Test
    fun `a performer omitting the promoted ops is refused, naming them`() {
        rejects(
            """
            use std::effects::async::{ Async, promise_of }
            impl Async for Partial where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              spawn = { f } -> promise_of { val = f {} },
            }
            $MAIN
            """,
            "V0404",
            "spawn alone no longer makes a performer: await, cancel, yield_now, park, park_until are ops",
        )
    }

    @Test
    fun `a performer supplying all six ops is accepted`() {
        accepts(
            """
            use std::effects::async::{ Async, promise_of, inspect, AsyncError, CancelOutcome }
            impl Async for SyncAsync where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              spawn = { f } -> promise_of { val = f {} },
              await = { p } -> match inspect { p = p } is
                PromiseCompleted { val } -> Ok { val = val }
                PromiseFailed { err } -> Err { error = err }
                PromisePending -> Err { error = AsyncFailed { msg = "no" } },
              cancel = { p, reason } -> CancelAlreadyCompleted,
              yield_now = {} -> {},
              park = { predicate } -> {},
              park_until = { predicate, ms } -> True,
            }
            $MAIN
            """,
            "the mirror: a complete userland performer, no natives",
        )
    }
}
