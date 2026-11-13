package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * What a handler ABORTS with must be what the `perform` block produces, and a
 * performer's parameters must be supplied where it is installed.
 *
 * `abort { val = v }` abandons the body and makes `v` the value of the whole
 * `perform` block — but the block ALSO finishes normally when nothing throws.
 * One expression, one type, so the two have to agree. The machinery for that
 * already existed: `ContextBuilder.extractSuspensionType` walks a performer's op
 * bodies for an `abort`, and `ExprPerformConstraints` unifies the result with the
 * block's type ("perform body must return the same type that handler X
 * produces").
 *
 * It was defeated by its own `else` branch. `inferParsedPayloadType` typed
 * variant constructs, record literals and identifiers; everything else became a
 * FRESH VARIABLE — which does not mean "unknown", it means "unifies with
 * anything", so the comparison succeeded vacuously. Measured, with
 * `throw = { error } -> abort { val = "ABORTED-STRING" }` and a block declared
 * `Int`:
 *
 *     pub run : {} -> Int <*>
 *     Check passed
 *     run (declared Int) = ABORTED-STRING
 *     java.lang.RuntimeException: Cannot multiply String and Long
 *
 * That is the same shape as the resume hole in HandlerResumeTypeSpecTest: a
 * check that runs, and succeeds by BINDING a variable instead of comparing two
 * types. Worth stating plainly, because it has now appeared three times in this
 * area — a fresh variable standing in for "I could not work it out" is
 * indistinguishable from "any type is fine", and only the second is what the
 * solver hears.
 *
 * `a computed abort payload still goes unchecked` below is a BREAK, asserted as
 * one so it is recorded rather than believed fixed.
 */
class PerformAbortPayloadSpecTest : VariantSpecBase() {

    private val exEffect = """
        effect Ex having 'error where {
          throw: { error: 'error } -> 'a <Ex>
        }
    """.trimIndent()

    private fun divide(ret: String) = """
        pub let divide: { a: Int, b: Int } -> $ret <Ex> = { a, b } ->
          if b == 0 then
            Ex.throw { error = "div0" }
          else
            a / b
    """.trimIndent()

    // ───────────────────────────────── abort payload vs block type

    @Test fun `aborting with a literal of the wrong type is refused`() {
        rejects(
            """
            $exEffect
            impl Ex for AbortStr having 'error = Str where {
              throw = { error } -> abort { val = "ABORTED-STRING" }
            }
            ${divide("Int")}
            pub let run: {} -> Int <*> = {} -> perform AbortStr in divide { a = 10, b = 0 }
            $MAIN
            """,
            "T0201",
            "The block yields Int when nothing throws and Str when it does — one " +
                "expression cannot have both types. Measured as accepted before " +
                "the literal cases were added to `inferParsedPayloadType`, and the " +
                "Int-declared binding then held a Str at runtime.",
        )
    }

    @Test fun `aborting with a literal of the right type is accepted`() {
        accepts(
            """
            $exEffect
            impl Ex for AbortZero having 'error = Str where {
              throw = { error } -> abort { val = 0 }
            }
            ${divide("Int")}
            pub let run: {} -> Int <*> = {} -> perform AbortZero in divide { a = 10, b = 0 }
            $MAIN
            """,
            "THE MIRROR CASE. Int on both paths, so the block is Int. A rule that " +
                "refused this would be refusing `abort` itself.",
        )
    }

    @Test fun `the block may be declared as the aborted type instead`() {
        accepts(
            """
            $exEffect
            impl Ex for AbortStr having 'error = Str where {
              throw = { error } -> abort { val = "recovered" }
            }
            pub let describe: { b: Int } -> Str <Ex> = { b } ->
              if b == 0 then
                Ex.throw { error = "div0" }
              else
                "ok"
            pub let run: {} -> Str <*> = {} -> perform AbortStr in describe { b = 0 }
            $MAIN
            """,
            "Refusing the mismatch is only half a rule — the author needs a " +
                "spelling that works. A performing function whose NORMAL path is " +
                "also Str makes both paths agree, and is the other way to fix the " +
                "case above. (It needs its own body: `divide`'s `else` is `a / b`, " +
                "so that one cannot be declared `-> Str` — which is the rule doing " +
                "its job, not a second bug.)",
        )
    }

    @Test fun `the stdlib Result channel still works`() {
        accepts(
            """
            use std::result
            $exEffect
            impl Ex for ExToResult having 'error = 'e where {
              throw = { error } -> abort { val = Err { error } }
            }
            pub let risky: { b: Int } -> Int <Ex> = { b } ->
              if b == 0 then Ex.throw { error = "div0" } else b
            pub let run = {} -> perform ExToResult in do
              let v = risky { b = 0 }
              Ok { val = v }
            $MAIN
            """,
            "A variant payload was always typed by this walker, which is why " +
                "`std/effects/exception.mz` worked and the hole stayed hidden. The " +
                "normal path yields `Ok`, the abort path `Err`, both Result — this " +
                "is the shape the agreement rule is FOR, and it must keep passing.",
        )
    }

    @Test fun `a computed abort payload is checked`() {
        rejects(
            """
            $exEffect
            pub let make_str: {} -> Str = {} -> "computed"
            impl Ex for AbortComputed having 'error = Str where {
              throw = { error } -> abort { val = make_str {} }
            }
            ${divide("Int")}
            pub let run: {} -> Int <*> = {} -> perform AbortComputed in divide { a = 10, b = 0 }
            $MAIN
            """,
            "T0201",
            "FIXED, and by the route this comment predicted. The payload is a " +
                "CALL, which `extractSuspensionType`'s walker cannot type — it " +
                "runs at context-build time over parsed, un-typechecked " +
                "expressions and falls back to a fresh variable that unifies with " +
                "anything. Adding cases there would never have finished the job. " +
                "`ImplValidator` now KEEPS the `abort` payload variable its op-body " +
                "solve already unified with the real type, and " +
                "`TypeChecker.checkAbortPayloads` resolves it post-solve against " +
                "each perform site's block type — post-solve because that solve " +
                "runs during constraint generation and a site may precede the impl " +
                "in the file. Conservative: reported only when both sides resolve " +
                "CONCRETE and differ.",
        )
    }

    // ───────────────────────────────── performer parameters

    @Test fun `omitting a performer's parameters is refused`() {
        rejects(
            """
            effect Cwd where { path: {} -> Str <Cwd> }
            impl Cwd for MockCwd { dir: Str } where {
              path = {} -> dir
            }
            pub let p: {} -> Str <Cwd> = {} -> Cwd.path {}
            pub let run = {} -> perform MockCwd in p {}
            $MAIN
            """,
            "T0201",
            "A wrong type and an extra field were both already caught; omitting " +
                "the record skipped the check entirely, because the gate asked " +
                "whether the author wrote parameters rather than whether the " +
                "performer needs them. Not a type confusion — the handler's " +
                "identity includes its parameters, so nothing is installed and the " +
                "program dies with `Handler not found for: <128 hex chars>`.",
        )
    }

    @Test fun `supplying a performer's parameters is accepted`() {
        accepts(
            """
            effect Cwd where { path: {} -> Str <Cwd> }
            impl Cwd for MockCwd { dir: Str } where {
              path = {} -> dir
            }
            pub let p: {} -> Str <Cwd> = {} -> Cwd.path {}
            pub let run = {} -> perform MockCwd { dir = "/tmp" } in p {}
            $MAIN
            """,
            "Parameters are read directly BY NAME in an op body — not through " +
                "`scope`, which is a separate mechanism created by " +
                "`initialize_scope`.",
        )
    }

    // ───────────────────────────────── an op body MAY perform its own effect

    @Test fun `an op body may perform the effect it implements`() {
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
            pub let total: {} -> Int <Txn> = {} -> Txn.atomically { f = {} -> Txn.read {} }
            pub let run = {} -> perform SeqTxn in total {}
            $MAIN
            """,
            "`std/effects/stm.mz`'s shape, and the reason V0504 is about the op's " +
                "ANNOTATION and not its body. `atomically = { f } -> f {}` performs " +
                "Txn because `f` IS the transaction body, whose `read` is meant to " +
                "reach this same handler.\n" +
                "Extending V0504 to the body was tried and is wrong: 7 tests fell " +
                "over, two named \"effect handler call inside same type impl should " +
                "succeed\", and SequentialSTM is the proof. The case that motivated " +
                "it — `a = {} -> A.a {}` on a one-op effect — is an ordinary " +
                "infinite recursion, no more a type error than `let f = {} -> f {}`.",
        )
    }

    @Test fun `declaring your own effect in your own op row is still refused`() {
        rejects(
            """
            effect A where { a: {} -> Int <A> }
            impl A for SelfA where {
              a: {} -> Int <A> = {} -> 1
            }
            $MAIN
            """,
            "V0504",
            "The ANNOTATION case V0504 is actually for, kept beside the accepted " +
                "body case above so the boundary between them is written down " +
                "rather than rediscovered.",
        )
    }

    @Test fun `an op body performing a DIFFERENT effect is accepted when declared`() {
        accepts(
            """
            effect Tick where { tick: {} -> Int <Tick> }
            effect A where { a: {} -> Int <A> }
            impl Tick for Ticker where { tick = {} -> 1 }
            impl A for UsesTick where {
              a = {} -> Tick.tick {}
            }
            pub let use_a: {} -> Int <A> = {} -> A.a {}
            pub let run: {} -> Int <Tick, *> = {} -> perform UsesTick in use_a {}
            $MAIN
            """,
            "THE MIRROR CASE. A performer that performs something ELSE is fine and " +
                "common — the row just has to say so, which `<Tick, *>` does. The " +
                "subtraction the new check sits in front of is what computes that, " +
                "so the check must not disturb it.",
        )
    }

    @Test fun `a parameter-less performer needs no record`() {
        accepts(
            """
            effect Cwd where { path: {} -> Str <Cwd> }
            impl Cwd for FixedCwd where {
              path = {} -> "/fixed"
            }
            pub let p: {} -> Str <Cwd> = {} -> Cwd.path {}
            pub let run = {} -> perform FixedCwd in p {}
            $MAIN
            """,
            "`performerFields` is null when no parameters are declared, so the new " +
                "check must not fire here — this is the overwhelming majority of " +
                "performers, the whole stdlib included.",
        )
    }
}
