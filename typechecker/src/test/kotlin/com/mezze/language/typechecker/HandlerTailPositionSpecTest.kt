package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * `resume` and `abort` may only be the last thing an op body does, and are
 * never values.
 *
 * The rule follows from what they are at runtime rather than from typing:
 * `cont.resume { val = X }` is the IDENTITY FUNCTION (it transfers no control) and
 * `cont.abort { val = X }` THROWS. Mezze has tail-resumptive handlers plus an escape
 * continuation — not one-shot continuations. There is no `k` to store, defer or
 * call twice.
 *
 * Typing cannot catch this, and that is the interesting part: `resume`'s type is
 * `{ val: opReturn } -> performReturn`, which is exactly a delimited
 * continuation's type. The type system is already general enough for the
 * feature the runtime does not implement — so every spelling that assumes
 * continuations checks clean and means something else. MEASURED:
 *
 *     a = {} -> do
 *       cont.resume { val = 1 }
 *       cont.resume { val = 2 }
 *
 * printed `body ran, v = 2` — body ran ONCE, with the SECOND value. The first
 * call computed 1 as a discarded statement. In Eff/Koka/OCaml 5 that same
 * handler is multi-shot and the body runs twice.
 *
 * Why TAIL POSITION and not "at most once": several stdlib and test bodies have
 * more than one `abort`, one per `match` arm, and each arm is its own tail. That
 * was established by surveying all 9 explicit stdlib uses and every test use
 * BEFORE writing the rule — all of them already comply, so nothing valid moves.
 */
class HandlerTailPositionSpecTest : VariantSpecBase() {

    private val eff = "effect A where { a: {} -> Int <A> }"

    // ───────────────────────────────── the spellings that lie

    @Test fun `a second resume replaces the first rather than resuming twice`() {
        rejects(
            """
            $eff
            impl A for Twice where {
              a = {} -> do
                cont.resume { val = 1 }
                cont.resume { val = 2 }
            }
            $MAIN
            """,
            "V0503",
            "Reads as multi-shot. Measured: the body runs ONCE with 2 — the first " +
                "`resume` computed 1 and discarded it. Nothing about this is " +
                "expressible, so it should not be writable.",
        )
    }

    @Test fun `work after resume runs before the body resumes, not after`() {
        rejects(
            """
            $eff
            impl A for ThenMore where {
              a = {} -> do
                cont.resume { val = 1 }
                2
            }
            $MAIN
            """,
            "V0503",
            "Reads as \"resume, then do the rest once the body is done\". `resume` " +
                "transfers no control, so the rest happens IMMEDIATELY and the body " +
                "has not run yet — then `2` silently becomes the operation's real " +
                "result. The ordering the author wrote is not the ordering they get.\n" +
                "Deliberately type-correct (both branches Int) so this measures the " +
                "POSITION rule and not a return-type mismatch: an earlier draft used " +
                "a unit-returning statement and was refused as T0201, which would " +
                "have made the rule look present when it was not.",
        )
    }

    @Test fun `anything after abort is unreachable`() {
        rejects(
            """
            $eff
            impl A for AbortThen where {
              a = {} -> do
                cont.abort { val = 0 }
                cont.resume { val = 1 }
            }
            $MAIN
            """,
            "V0503",
            "`abort` throws, so the `resume` is dead code. Accepted today, which " +
                "makes a contradiction look like a choice.",
        )
    }

    @Test fun `resume as an operand is not a tail call`() {
        rejects(
            """
            $eff
            impl A for Operand where {
              a = {} -> cont.resume { val = 1 } + 1
            }
            $MAIN
            """,
            "V0503",
            "`+ 1` reads as applying to the resumed computation's answer. It " +
                "applies to the identity function's return instead — the op's own " +
                "result — so the arithmetic lands in a different place than written.",
        )
    }

    @Test fun `resume may not be stored as a value`() {
        rejects(
            """
            $eff
            impl A for Store where {
              a = {} -> do
                let k = cont.resume
                k { val = 1 }
            }
            $MAIN
            """,
            "V0503",
            "THE CENTRAL CONFUSION. Binding `resume` is the one thing that would " +
                "make it a continuation — capture it, hand it elsewhere, call it " +
                "later. It is the identity function, so `k` is just a function that " +
                "returns its argument, and calling it later resumes nothing.",
        )
    }

    @Test fun `abort may not be passed to a function`() {
        rejects(
            """
            $eff
            pub let apply_it: { f: { val: Int } -> Int } -> Int = { f } -> f { val = 7 }
            impl A for Passed where {
              a = {} -> apply_it { f = cont.abort }
            }
            $MAIN
            """,
            "V0503",
            "Same confusion through a parameter rather than a `let`.",
        )
    }

    @Test fun `cont itself may not be stored`() {
        rejects(
            """
            $eff
            impl A for Keep where {
              a = {} -> do
                let k = cont
                k.resume { val = 1 }
            }
            $MAIN
            """,
            "V0503",
            "The runtime holds the continuation. Binding `cont` is the capture the " +
                "design forbids, spelled one level up from `let k = cont.resume`.",
        )
    }

    @Test fun `bare resume is not a handler binding`() {
        rejects(
            """
            $eff
            impl A for Old where {
              a = {} -> resume { val = 1 }
            }
            $MAIN
            """,
            "V0702",
            "ONE mechanism, one spelling: the handles live on `cont`. A bare `resume` " +
                "is an ordinary (here undefined) name, so a missed migration is a compile " +
                "error rather than a silent second mechanism.",
        )
    }

    // ───────────────────────────────── the spellings that are honest

    @Test fun `a lone tail resume is accepted`() {
        accepts(
            """
            $eff
            impl A for Fine where {
              a = {} -> cont.resume { val = 1 }
            }
            $MAIN
            """,
            "`std/effects/noop.mz` and `comptime.mz` are spelled exactly this way.",
        )
    }

    @Test fun `a lone tail abort is accepted`() {
        accepts(
            """
            use std::result
            effect Ex having 'error where { throw: { error: 'error } -> 'a <Ex> }
            impl Ex for ToResult having 'error = 'e where {
              throw = { error } -> cont.abort { val = Err { error } }
            }
            $MAIN
            """,
            "`std/effects/exception.mz` verbatim. All four of its performers and " +
                "`early_return.mz` are this shape.",
        )
    }

    @Test fun `one abort per match arm is accepted`() {
        accepts(
            """
            type E = | X, Y, Z |
            effect Ex having 'error where { throw: { error: 'error } -> 'a <Ex> }
            impl Ex for PerArm having 'error = E where {
              throw = { error } ->
                match error is
                  X -> cont.abort { val = 1 }
                  Y -> cont.abort { val = 2 }
                  Z -> cont.abort { val = 3 }
            }
            $MAIN
            """,
            "THE MIRROR CASE, and the reason the rule is TAIL POSITION rather than " +
                "\"at most once\". Each arm is its own tail. Several " +
                "ExceptionTypeInferenceTest bodies are exactly this, so an " +
                "occurrence-counting rule would have broken real tests.",
        )
    }

    @Test fun `resume after computing a value in a do block is accepted`() {
        accepts(
            """
            $eff
            impl A for Computed where {
              a = {} -> do
                let base = 40
                let more = base + 2
                cont.resume { val = more }
            }
            $MAIN
            """,
            "Work BEFORE the tail call is ordinary and correct — it happens before " +
                "the operation returns, which is what it looks like. Only work " +
                "AFTER is the lie.",
        )
    }

    @Test fun `a resume in an if arm is accepted`() {
        accepts(
            """
            $eff
            impl A for Branch where {
              a = {} -> if True then cont.resume { val = 1 } else cont.resume { val = 2 }
            }
            $MAIN
            """,
            "Both arms are tails of the body. Only one of them runs.",
        )
    }

    @Test fun `a body using neither name is untouched`() {
        accepts(
            """
            effect Txn where {
              read: {} -> Int <Txn>,
              atomically: { f: {} -> 'a <Txn> } -> 'a <Txn>
            }
            impl Txn for SeqTxn where {
              read = {} -> 1,
              atomically = { f } -> f {}
            }
            $MAIN
            """,
            "`std/effects/stm.mz`'s shape. A bare value IS an implicit resume, and " +
                "the rule must say nothing about it — that is the overwhelming " +
                "majority of the stdlib's 957 ops.",
        )
    }

    @Test fun `an effect op named abort is not the binding`() {
        accepts(
            """
            effect Abortable where { abort: {} -> Int <Abortable> }
            impl Abortable for Stopper where { abort = {} -> 0 }
            pub let use_it: {} -> Int <Abortable> = {} -> do
              let v = Abortable.abort {}
              v + 1
            $MAIN
            """,
            "A user effect may name an op `abort`; `Abortable.abort {}` is a " +
                "QUALIFIED op call, not the handler binding. CustomEffectTest has " +
                "one of these, so the check keys on an unqualified identifier only.",
        )
    }

    // ───────────────────────────────── the scope hooks bind neither name

    @Test fun `abort in initialize_scope is refused`() {
        rejects(
            """
            $eff
            impl A for AbortInit where {
              initialize_scope = {} -> cont.abort { val = 11 },
              finalize_scope = {} -> {},
              a = {} -> cont.resume { val = 7 }
            }
            $MAIN
            """,
            "V0503",
            "THE WORST OF THE FOUR. `invokeInitializeScope` runs BEFORE " +
                "`pushHandlerAndEnterHandle`, so an abort escaping the hook unwinds " +
                "into a catch that pops a handler never pushed. Measured: " +
                "`ArrayIndexOutOfBoundsException: Index -1 out of bounds for length " +
                "16` — `16` is EffectState's INITIAL_CAPACITY and `-1` its depth, so " +
                "the per-thread handler chain is left CORRUPTED, which is worse than " +
                "a clean crash.",
        )
    }

    @Test fun `abort in finalize_scope is refused`() {
        rejects(
            """
            $eff
            impl A for AbortFin where {
              initialize_scope = {} -> { n = 1 },
              finalize_scope = {} -> cont.abort { val = 22 },
              a = {} -> cont.resume { val = 7 }
            }
            $MAIN
            """,
            "V0503",
            "Measured: SILENTLY SWALLOWED. The block produced 7 and still yielded " +
                "7 despite `cont.abort { val = 22 }` — the quietest possible way for a " +
                "written intention to do nothing.",
        )
    }

    @Test fun `resume in initialize_scope is refused`() {
        rejects(
            """
            $eff
            impl A for ResumeInit where {
              initialize_scope = {} -> cont.resume { val = { n = 1 } },
              finalize_scope = {} -> {},
              a = {} -> cont.resume { val = 7 }
            }
            $MAIN
            """,
            "V0503",
            "`resume` is the identity function, so this \"worked\" by accident — it " +
                "returned its argument and the scope was created. Meaningless noise " +
                "that reads as control flow.",
        )
    }

    @Test fun `resume in finalize_scope is refused`() {
        rejects(
            """
            $eff
            impl A for ResumeFin where {
              initialize_scope = {} -> { n = 1 },
              finalize_scope = {} -> cont.resume { val = {} },
              a = {} -> cont.resume { val = 7 }
            }
            $MAIN
            """,
            "V0503",
            "Same as above on the way out. A hook is not an operation: it runs " +
                "AROUND the block rather than in response to a performed one, so " +
                "there is no operation call to hand a value back to.",
        )
    }

    @Test fun `hooks that return their value directly are accepted`() {
        accepts(
            """
            $eff
            impl A for Scoped where {
              initialize_scope = {} -> { n = 1 },
              finalize_scope = {} -> scope,
              a = {} -> cont.resume { val = scope.n }
            }
            pub let use_it: {} -> Int <A> = {} -> A.a {}
            pub let run = {} -> perform Scoped in use_it {}
            $MAIN
            """,
            "THE MIRROR CASE, and `std/effects/workflow.mz`'s exact shape including " +
                "`finalize_scope = {} -> scope`. A hook returns its value directly, " +
                "and the OP body may still use `resume` normally — the rule is about " +
                "the hooks, not about scoped performers.",
        )
    }
}
