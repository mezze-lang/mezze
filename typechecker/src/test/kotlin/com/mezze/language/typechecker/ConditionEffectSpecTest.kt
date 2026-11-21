package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * The TYPE rules behind `std::effects::condition` — why the API has the shape
 * it has, rather than what it does at runtime (that is ConditionEffectTest).
 *
 * `signal: { ctx: 'ctx, fallback: 'a } -> 'a` takes the recovery value FROM THE
 * CALL SITE, and that is not a stylistic choice — it is forced. The operation
 * returns `'a`, which the site picks, so a handler that invented a recovery
 * value of its own would be pinning a variable it does not own, which is the
 * soundness bug fixed in 21014fc86 (clean `mez check`, then
 * `Cannot multiply String and Long`). `a handler may not invent a recovery
 * value` below is that rule showing up as an API constraint.
 *
 * The three performers are LATE-BOUND DISCHARGE: one signalling body, and the
 * caller decides at the `perform` whether a condition means "carry on", "Err"
 * or "None". Exceptions make the THROW site choose; a `Result` return type bakes
 * the choice in permanently. Each discharge imposes its own requirement on the
 * block, and the tests below pin all three.
 */
class ConditionEffectSpecTest : VariantSpecBase() {

    private val useCond =
        "use std::effects::condition::{ Condition, signal, UseFallback, FirstErr, FirstNone }"

    // ───────────────────────────────── the shape that makes it work

    @Test fun `one body may signal at several fallback types`() {
        accepts(
            """
            $useCond
            pub let parse: { ok: Bool } -> { name: Str, age: Int } <Condition> = { ok } -> do
              let name = if ok then "alice" else signal { ctx = "bad name", fallback = "?" }
              let age = if ok then 30 else signal { ctx = "bad age", fallback = 0 }
              { name = name, age = age }
            pub let run = {} -> perform UseFallback in parse { ok = False }
            $MAIN
            """,
            "`'a` is chosen PER SITE, so one body signals at Str and at Int. That " +
                "is the whole reason `fallback` is a parameter: it makes the " +
                "operation polymorphic in the recovery value without the handler " +
                "having to produce one.",
        )
    }

    @Test fun `a handler may not invent a recovery value`() {
        rejects(
            """
            use std::effects::condition::{ Condition, signal }
            impl Condition for Invents having 'ctx = Str where {
              signal = { ctx, fallback } -> cont.resume { val = "made up" }
            }
            $MAIN
            """,
            "T0210",
            "THE RULE THAT FORCES THE API. `signal` returns `'a`, chosen by the " +
                "site, so resuming with a Str pins a variable the handler does not " +
                "own — exactly the hole fixed in 21014fc86. A handler's only honest " +
                "options are the site's `fallback` or giving up, which is why " +
                "`fallback` has to be in the parameters.",
        )
    }

    @Test fun `a handler resuming with the site's fallback is accepted`() {
        accepts(
            """
            use std::effects::condition::{ Condition, signal }
            impl Condition for Mine having 'ctx = Str where {
              signal = { ctx, fallback } -> cont.resume { val = fallback }
            }
            pub let total: {} -> Int <Condition> = {} -> signal { ctx = "a", fallback = 1 }
            pub let run = {} -> perform Mine in total {}
            $MAIN
            """,
            "The mirror of the case above, and `UseFallback`'s own body. The " +
                "fallback HAS type `'a`, so it works for every site.",
        )
    }

    // ───────────────────────────────── late-bound discharge

    @Test fun `UseFallback leaves the block's own type alone`() {
        accepts(
            """
            $useCond
            pub let n: {} -> Int <Condition> = {} -> signal { ctx = "x", fallback = 7 }
            pub let run: {} -> Int <*> = {} -> perform UseFallback in n {}
            $MAIN
            """,
            "Resuming never ends the block, so the block's type is just the body's " +
                "— `Int` in, `Int` out. No wrapping imposed on the caller.",
        )
    }

    @Test fun `FirstErr requires the block to be a Result`() {
        accepts(
            """
            use std::result
            $useCond
            pub let n: {} -> Int <Condition> = {} -> signal { ctx = "x", fallback = 7 }
            pub let run = {} -> perform FirstErr in do
              let v = n {}
              Ok { val = v }
            $MAIN
            """,
            "Aborting with `Err` makes the block a Result, so the body's normal " +
                "tail must produce one too. That is the agreement rule from " +
                "PerformAbortPayloadSpecTest, and it is the same shape " +
                "`ExceptionToResult` has always had.",
        )
    }

    @Test fun `FirstErr on a bare non-Result block is refused`() {
        rejects(
            """
            $useCond
            pub let n: {} -> Int <Condition> = {} -> signal { ctx = "x", fallback = 7 }
            pub let run: {} -> Int <*> = {} -> perform FirstErr in n {}
            $MAIN
            """,
            "T0201",
            "The block completes normally as Int and aborts as a Result — one " +
                "expression cannot be both. The caller has to opt into the Result " +
                "channel, which is what makes the discharge a CHOICE rather than " +
                "something imposed on every call site.",
        )
    }

    @Test fun `FirstNone requires the block to be an Option`() {
        accepts(
            """
            use std::option
            $useCond
            pub let n: {} -> Int <Condition> = {} -> signal { ctx = "x", fallback = 7 }
            pub let run = {} -> perform FirstNone in do
              let v = n {}
              Some { val = v }
            $MAIN
            """,
            "The third discharge of the SAME signalling body. `n` committed to " +
                "nothing: its signature says `<Condition>` and the caller picked.",
        )
    }

    // ───────────────────────────────── the row still tells the truth

    @Test fun `a signalling function may not be declared pure`() {
        rejects(
            """
            use std::effects::condition::{ Condition, signal }
            pub let n: {} -> Int = {} -> signal { ctx = "x", fallback = 7 }
            $MAIN
            """,
            "T0210",
            "A condition is an effect like any other — the row has to say so. " +
                "Resumability changes nothing about honesty, and the launder net " +
                "applies here as everywhere.",
        )
    }

    @Test fun `handling a condition is marked with the star`() {
        accepts(
            """
            $useCond
            pub let n: {} -> Int <Condition> = {} -> signal { ctx = "x", fallback = 7 }
            pub let run: {} -> Int <*> = {} -> perform UseFallback in n {}
            $MAIN
            """,
            "`<*>` is required where a function handles what it performs, and the " +
                "condition effect is no exception to that either.",
        )
    }

    // ───────────────────────────────── the tail rule applies to these handlers too

    @Test fun `a condition handler may not resume twice`() {
        rejects(
            """
            use std::effects::condition::{ Condition, signal }
            impl Condition for Twice having 'ctx = Str where {
              signal = { ctx, fallback } -> do
                cont.resume { val = fallback }
                cont.resume { val = fallback }
            }
            $MAIN
            """,
            "V0503",
            "A resumable condition is still not a continuation. The second call " +
                "replaces the first value rather than running the body again — " +
                "worth pinning HERE in particular, because a resuming handler is " +
                "exactly where someone will reach for multi-shot.",
        )
    }
}
