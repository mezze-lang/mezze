package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * `resume` and `abort` may only be the last thing an op body does, and are
 * never values.
 *
 * The rule follows from what they are at runtime rather than from typing:
 * `resume { val = X }` is the IDENTITY FUNCTION (it transfers no control) and
 * `abort { val = X }` THROWS. Mezze has tail-resumptive handlers plus an escape
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
 *       resume { val = 1 }
 *       resume { val = 2 }
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
                resume { val = 1 }
                resume { val = 2 }
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
                resume { val = 1 }
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
                abort { val = 0 }
                resume { val = 1 }
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
              a = {} -> resume { val = 1 } + 1
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
                let k = resume
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
              a = {} -> apply_it { f = abort }
            }
            $MAIN
            """,
            "V0503",
            "Same confusion through a parameter rather than a `let`.",
        )
    }

    // ───────────────────────────────── the spellings that are honest

    @Test fun `a lone tail resume is accepted`() {
        accepts(
            """
            $eff
            impl A for Fine where {
              a = {} -> resume { val = 1 }
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
              throw = { error } -> abort { val = Err { error } }
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
                  X -> abort { val = 1 }
                  Y -> abort { val = 2 }
                  Z -> abort { val = 3 }
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
                resume { val = more }
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
              a = {} -> if True then resume { val = 1 } else resume { val = 2 }
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
}
