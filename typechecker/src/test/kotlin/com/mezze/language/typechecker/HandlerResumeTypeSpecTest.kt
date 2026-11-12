package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A handler that RESUMES must hand back the type the throw site asked for.
 *
 * The two ways a performer op body can end are different operations, measured:
 *
 * | body                          | `f` continues? | block yields |
 * |-------------------------------|----------------|--------------|
 * | `op = {..} -> 7`              | yes, with 7    | `f`'s result |
 * | `op = {..} -> abort {val = 7}`| no             | 7            |
 *
 * So a bare value RESUMES: it is handed back at the throw site and becomes the
 * value of the `Effect.op {}` expression inside the performing function. That
 * makes the body's type a claim about the op's DECLARED RETURN at that site.
 *
 * When the op declares `-> 'a`, the site picks `'a`, not the performer — the
 * same situation `AnnotationValidator.validatePolymorphismPreserved` already
 * rejects for an ordinary function ("declares type variable 'a but the
 * implementation forces it to concrete type"). A performer op is just a
 * function, so pinning `'a` in the body is the same lie.
 *
 * Measured consequence of not checking it, with `throw: {error:'error} -> 'a`
 * and `throw = {error} -> error`:
 *
 *     divide : { a: Int, b: Int } -> Int <Ex having 'error = Str>
 *     Check passed
 *     r (declared Int) = Division by zero
 *     java.lang.RuntimeException: Cannot multiply String and Long
 *
 * A clean `mez check`, then a JVM type confusion — the `Int`-declared binding
 * holds a `String`. Nothing in the program is spelled wrong; the rule is
 * missing.
 *
 * The RULE IS ABOUT PINNING, NOT ABOUT RESUMING. `resumes with a value whose
 * type IS the op's variable` below is the mirror case that must stay accepted,
 * and it is what keeps this from being read as "handlers must abort".
 */
class HandlerResumeTypeSpecTest : VariantSpecBase() {

    private val exEffect = """
        effect Ex having 'error where {
          throw: { error: 'error } -> 'a <Ex>
        }
    """.trimIndent()

    // ───────────────────────────────── the lie

    @Test fun `resuming with a concrete type pins the op's own variable`() {
        rejects(
            """
            $exEffect
            impl Ex for StringHandler having 'error = Str where {
              throw = { error } -> error
            }
            pub let divide: { a: Int, b: Int } -> Int <Ex> = { a, b } ->
              if b == 0 then
                Ex.throw { error = "Division by zero" }
              else
                a / b
            pub let run = {} -> perform StringHandler in divide { a = 10, b = 0 }
            $MAIN
            """,
            "T0210",
            "`throw` is declared `-> 'a`, so the THROW SITE fixes 'a — here to Int, " +
                "from the sibling arm `a / b`. A handler resuming with `error` (Str) " +
                "hands a Str back to a site typed Int, and `divide` — declared " +
                "`-> Int` — returns a Str. Measured: the program checks clean and " +
                "then dies with `Cannot multiply String and Long`.",
        )
    }

    @Test fun `resuming with a concrete type pins it even with no throw site in the module`() {
        rejects(
            """
            $exEffect
            impl Ex for StringHandler having 'error = Str where {
              throw = { error } -> error
            }
            $MAIN
            """,
            "T0210",
            "The performer is wrong on its own terms — `-> 'a` must work for EVERY " +
                "site, so pinning it to Str is a lie whether or not this module " +
                "happens to contain a site. Checking it where it is written is also " +
                "what gives the error a location the author can act on.",
        )
    }

    @Test fun `resuming with a concrete type that is not the error type`() {
        rejects(
            """
            $exEffect
            impl Ex for SevenHandler having 'error = Str where {
              throw = { error } -> 7
            }
            $MAIN
            """,
            "T0210",
            "Nothing about Str is the problem — `7` pins 'a to Int just as `error` " +
                "pinned it to Str. The rule is about pinning a variable the site " +
                "owns, not about which type was chosen.",
        )
    }

    // ───────────────────────────────── the honest spellings

    @Test fun `aborting with a value is accepted`() {
        accepts(
            """
            use std::result
            $exEffect
            impl Ex for ExToResult having 'error = 'e where {
              throw = { error } -> abort { val = Err { error } }
            }
            $MAIN
            """,
            "`abort` abandons the body, so the value becomes the PERFORM BLOCK's " +
                "result and says nothing about 'a — which stays free, as the " +
                "declaration promises. This is the stdlib idiom in " +
                "`std/effects/exception.mz`, and the rule must not disturb it.",
        )
    }

    @Test fun `resumes with a value whose type IS the op's variable`() {
        accepts(
            """
            effect Fallback where {
              recover: { fallback: 'a } -> 'a <Fallback>
            }
            impl Fallback for UseFallback where {
              recover = { fallback } -> fallback
            }
            pub let read_or: { d: Int } -> Int <Fallback> = { d } ->
              Fallback.recover { fallback = d }
            pub let run = {} -> perform UseFallback in read_or { d = 0 }
            $MAIN
            """,
            "THE MIRROR CASE. This handler resumes, and must be accepted: the body " +
                "has type 'a, so it works for every site. A rule that refused this " +
                "would be \"handlers must abort\", which is not the rule and would " +
                "break resuming handlers for good.",
        )
    }

    @Test fun `resuming with a concrete type the op declares concretely`() {
        accepts(
            """
            effect Clock where { now: {} -> Int <Clock> }
            impl Clock for FixedClock where {
              now = {} -> 42
            }
            pub let elapsed: {} -> Int <Clock> = {} -> Clock.now {}
            pub let run = {} -> perform FixedClock in elapsed {}
            $MAIN
            """,
            "`now` declares `-> Int`, so Int is the performer's to choose and 42 is " +
                "exactly right. The check must look at the op's OWN quantified " +
                "variables, not at every return type.",
        )
    }

    @Test fun `an associated type bound by having is not the op's to quantify`() {
        accepts(
            """
            effect Store having 'val where {
              load: {} -> 'val <Store>
            }
            impl Store for IntStore having 'val = Int where {
              load = {} -> 99
            }
            pub let get: {} -> Int <Store> = {} -> Store.load {}
            pub let run = {} -> perform IntStore in get {}
            $MAIN
            """,
            "`'val` is fixed by the IMPL's `having 'val = Int`, so by the time the " +
                "body is checked the declared return IS Int — the performer chose " +
                "it. Only a variable left free after the `having` substitution " +
                "belongs to the site.",
        )
    }

    // ───────────────────────────────── the root cause keeps priority

    @Test fun `an omitted having binding reports the missing binding, not the body`() {
        rejects(
            """
            effect State having 'state where {
              get: {} -> 'state <State>
            }
            impl State for Counter {} where {
              get = {} -> 42
            }
            $MAIN
            """,
            "V0413",
            "`'state` is unsubstituted here only BECAUSE the binding is missing, so " +
                "`42` looks like it pins a site-chosen variable. It does not — an " +
                "associated type is the PERFORMER's to choose, and the real mistake " +
                "is the omitted `having 'state = …`. This check must stay behind " +
                "that one, or the author gets an answer to a question they did not " +
                "ask while the one they did ask is buried.",
        )
    }

    // ───────────────────────────────── the honest type must be writeable

    @Test fun `the resuming handler can be written for a site-chosen type`() {
        accepts(
            """
            $exEffect
            impl Ex for ExAbort having 'error = Str where {
              throw = { error } -> abort { val = 0 }
            }
            pub let divide: { a: Int, b: Int } -> Int <Ex> = { a, b } ->
              if b == 0 then
                Ex.throw { error = "Division by zero" }
              else
                a / b
            pub let run = {} -> perform ExAbort in divide { a = 10, b = 0 }
            $MAIN
            """,
            "Refusing the lie is only half a rule: the author needs a spelling that " +
                "WORKS. `abort { val = 0 }` is it — the block yields 0 and `divide` " +
                "never returns a non-Int. Both laundering bugs this suite has found " +
                "had no correct spelling at all, which is what made them severe.",
        )
    }
}
