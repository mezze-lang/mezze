package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * C7: a handler-bound function may not escape a SCOPED `perform`.
 *
 * A scoped performer declares `initialize_scope`, and the matching
 * `finalize_scope` tears its `scope` down when the block exits. A function
 * returned out of that block carries the live handler chain — that is deliberate
 * (986bf38b3, so a returned closure can still reach its handlers) — so calling
 * it afterwards runs the performer's operations against a finalized scope.
 *
 * THE HARM IS MEASURED, not inferred. The entry recorded the ordering
 * (init / finalize / exited / op reads scope) but called the use-after-close
 * "UNPROVEN". With the scope holding a flag a finalizer marks closed:
 *
 *     initialize_scope = {} -> { live = bool_to_mut { b = True } },
 *     finalize_scope   = {} -> write_bool { b = scope.live, v = False },
 *     use_it           = {} -> read_bool { b = scope.live }
 *
 *     let f = perform Scoped in {} -> Res.use_it {}
 *     f {}   ->  "escaped op saw: CLOSED — used a finalized resource"
 *
 * `std/effects/http.mz` is this for real: `PooledHttpClient.finalize_scope`
 * calls `__native_http_client_close { client = scope.client }` while `request`
 * uses `scope.client`.
 *
 * DECIDED POST-SOLVE, which took two attempts worth recording. The obvious
 * place is `ExprPerformConstraints`, where `bodyFnRow` already marks a perform
 * whose result is a function — but at constraint-generation time a bare
 * lambda's type is still a VARIABLE, so that test is never true. (Proved by
 * making the guard unconditional: it then broke the stdlib build, so the site
 * is reached and the condition simply never held.) The resolved type is only
 * available after solving, which is where `TypeChecker.checkAbortPayloads`
 * already looks.
 *
 * `isScoped` could not ride on `PerformerTypeInfo` either: `addEffectPerformer`
 * returns early for a performer with no associated types and no parameters,
 * which is exactly the shape of a plain scoped handler, so that record does not
 * exist for the performers this is about. It travels on `ConstraintContext
 * .scopedPerformers`, built from every performer beside `performerAddedEffects`.
 */
class ScopedHandlerEscapeSpecTest : VariantSpecBase() {

    private val scopedCtr = """
        effect Ctr where { get: {} -> Int <Ctr> }
        impl Ctr for Scoped where {
          initialize_scope = {} -> { n = 7 },
          finalize_scope = {} -> {},
          get = {} -> scope.n
        }
    """.trimIndent()

    @Test fun `a function may not escape a scoped perform`() {
        rejects(
            """
            effect Ctr where { get: {} -> Int <Ctr> }
            impl Ctr for Scoped where {
              initialize_scope = {} -> { n = 7 },
              finalize_scope = {} -> {},
              get = {} -> scope.n
            }
            pub let escape: {} -> Int <*> = {} -> do
              let f = perform Scoped in {} -> Ctr.get {}
              f {}
            $MAIN
            """,
            "V0511",
            "C7. The returned function carries the handler chain, so `f {}` runs " +
                "`get` — reading `scope.n` — after `finalize_scope` has run. " +
                "Measured with an observable resource as a genuine use-after-close.",
        )
    }

    @Test fun `a function MAY escape an unscoped perform`() {
        accepts(
            """
            effect Ctr where { get: {} -> Int <Ctr> }
            impl Ctr for Plain where { get = {} -> 42 }
            pub let escape: {} -> Int <*> = {} -> do
              let f = perform Plain in {} -> Ctr.get {}
              f {}
            $MAIN
            """,
            "THE MIRROR, and it is load-bearing: binding the handler chain into a " +
                "returned closure is the deliberate behaviour of 986bf38b3. With no " +
                "`initialize_scope` there is no finalizer for it to outlive. " +
                "Measured at runtime as 42. If this ever goes red the rule has " +
                "stopped being about SCOPES and started being about closures.",
        )
    }

    @Test fun `a scoped perform returning a VALUE is fine`() {
        accepts(
            """
            $scopedCtr
            pub let ok: {} -> Int <*> = {} -> perform Scoped in Ctr.get {}
            $MAIN
            """,
            "The ordinary use of a scoped handler — the op runs INSIDE the block " +
                "and only its result leaves. Nothing here can outlive the scope, " +
                "and refusing it would make scoped effects unusable.",
        )
    }

    @Test fun `a scoped perform whose block yields a record is fine`() {
        accepts(
            """
            $scopedCtr
            pub let ok: {} -> { v: Int } <*> = {} -> perform Scoped in do
              let n = Ctr.get {}
              { v = n }
            $MAIN
            """,
            "Deliberately NOT refused. The rule is about a FUNCTION escaping, " +
                "because a function is what can still call the handler's ops later. " +
                "A record of already-computed values cannot. A record of FUNCTIONS " +
                "could, and is a known gap — see known-bugs C7.",
        )
    }
}
