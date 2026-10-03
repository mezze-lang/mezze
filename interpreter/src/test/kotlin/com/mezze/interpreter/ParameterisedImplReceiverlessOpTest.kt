package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * S-10 — calling a PARAMETERISED impl of a RECEIVER-LESS operation.
 *
 * Everything here uses a locally declared ability, so nothing depends on the
 * sampler, the stdlib or an effect:
 *
 *     ability Make for 'a where { make: {} -> 'a }
 *
 * `make` takes no argument, so the type it PRODUCES is the only thing that can
 * choose an impl — the shape of `arbitrary`, `decode`, `zero`, `default`.
 *
 * ## What was broken
 *
 * `impl Make for Wrap 'a`, `Box 'k 'v`, or `['a]` — any parameterised impl of a
 * receiver-less operation — could not be called from a hand-written call site, in
 * any shape: a `let` annotation, a function's declared return, one type variable or
 * two. Every one failed with "nothing here determines that type", which was wrong
 * about its own situation: the annotation determines it exactly.
 *
 * ## Three wrong diagnoses, each ruled out by a test here
 *
 *  - "a two-variable head poisons the impl table." Adding
 *    `impl Arbitrary for Map 'k 'v` to the stdlib broke every `Arbitrary` call in
 *    the program including `Int`, and bisection supported it. False: arity is
 *    innocent, a one-variable impl fails identically, and a parameterised impl's
 *    mere PRESENCE leaves `Int` working (asserted below). The stdlib case looked
 *    global only because a failure inside `std::abilities::arbitrary` takes every
 *    consumer of that module with it.
 *  - "a direct annotation does not pin the impl." False: pinning by a function's
 *    declared return fails the same way, so position was not the variable. What
 *    separated the working `let q: Frac = …` from the failing `let xs: [Int] = …` is
 *    that `Frac`'s impl is CONCRETE and needs no element dictionary.
 *  - "the impl op's `with` clause is dropped, so the dict-method prescan sees
 *    nothing." False, and this one took instrumenting to kill: the prescan sees the
 *    constraints AND the body scan raises the right keys
 *    (`make@k`, `make@v`). The generic impl operation gets its dictionary parameters
 *    correctly. An earlier reading of `constraints=[]` was a MONOMORPHISED copy
 *    misattributed to the generic one.
 *
 * ## What it actually was
 *
 * `Monomorphizer.rewriteCalls` rewrote a constraint-driven call only when the
 * function position was a `TypedFieldAccess` — `receiver.method`. A receiver-less
 * operation has no receiver to be a field access OF; the emitter builds it as a
 * `TypedVar` bound to the ability operation, so it fell through unrewritten into a
 * specialization that by design carries no dictionary parameters. The reference then
 * asked for a slot in a function deliberately compiled without one.
 *
 * So the outer call resolved correctly and injected its evidence, and the body it
 * entered could not reach it. The fix selects the impl on the type the call PRODUCES
 * — which plays the receiver's role, as it already does in `buildStaticAbilityOpCall`
 * and in the compiler's instance-head binding — and is concrete in a specialization.
 *
 * ## Why it mattered beyond this test
 *
 * This is why every container test in the repo reached containers through a record
 * FIELD: a derived record instance injects the element dictionary as a literal at
 * the call site, so the sequence impl was entered with evidence already in hand.
 * That was a workaround for this bug, not a style choice. It is also why
 * `mez sample` could not generate a `Set Str` argument — it emits
 * `let x: Set Str = Arbitrary.arbitrary {}` — and why `SampleGen` carried a
 * hand-written arm per container type.
 */
class ParameterisedImplReceiverlessOpTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val ability = """
        ability Make for 'a where {
          make: {} -> 'a
        }

        impl Make for Int where { make = {} -> 7 }
        impl Make for Str where { make = {} -> "x" }
    """.trimIndent()

    private val boxImpl = """
        type Box 'k 'v = { first: 'k, second: 'v }

        impl Make for Box 'k 'v where {
          make: {} with Make 'k, Make 'v -> Box 'k 'v = {} ->
            { first = Make.make {}, second = Make.make {} }
        }
    """.trimIndent()

    private fun long(source: String): Long? =
        MezzeTestHelper.asLong(MezzeTestHelper.run(source))

    @Test
    fun `a CONCRETE impl of a receiver-less op resolves`() {
        assertEquals(7L, long("$ability\n\ndo\n  let n: Int = Make.make {}\n  n"))
    }

    /**
     * Kept from when this was filed as table poisoning: a parameterised impl's
     * PRESENCE must not change what a concrete one means.
     */
    @Test
    fun `a parameterised impl's presence does not disturb a concrete one`() {
        assertEquals(
            7L,
            long("$ability\n\n$boxImpl\n\ndo\n  let n: Int = Make.make {}\n  n"),
            "declaring `impl Make for Box 'k 'v` changed what `Make.make {}` means at Int",
        )
    }

    /** ONE type variable, reached through its own element impl. */
    @Test
    fun `a one-variable parameterised impl is directly callable`() {
        assertEquals(
            7L,
            long(
                """
                $ability

                type Wrap 'a = { only: 'a }

                impl Make for Wrap 'a where {
                  make: {} with Make 'a -> Wrap 'a = {} -> { only = Make.make {} }
                }

                do
                  let w: Wrap Int = Make.make {}
                  w.only
                """.trimIndent(),
            ),
            "`Wrap Int`'s element did not come from `impl Make for Int`",
        )
    }

    /**
     * A BUILTIN sequence, which is the stdlib's own `impl Arbitrary for ['a]` shape.
     * Asserts the ELEMENT, not just the length — a sequence of the wrong element
     * type would still have size 1.
     */
    @Test
    fun `a builtin sequence impl is directly callable`() {
        assertEquals(
            7L,
            long(
                """
                use std::collections::seq
                use std::option::{ get_or }

                $ability

                impl Make for ['a] where {
                  make: {} with Make 'a -> ['a] = {} -> [Make.make {}]
                }

                do
                  let xs: [Int] = Make.make {}
                  get_or { opt = seq::get { xs = xs, i = 0 }, def = 0 - 1 }
                """.trimIndent(),
            ),
            "`[Int]`'s element did not come from `impl Make for Int`",
        )
    }

    @Test
    fun `a two-variable parameterised impl resolves its first variable`() {
        assertEquals(
            7L,
            long("$ability\n\n$boxImpl\n\ndo\n  let b: Box Int Str = Make.make {}\n  b.first"),
        )
    }

    /**
     * THE CROSSING CHECK, and the reason the two-variable case is worth having: `'k`
     * and `'v` must get their OWN evidence. With one shared slot per operation name,
     * whichever filled it would answer for both and `second` would come back `7`.
     */
    @Test
    fun `the two variables do not share one dictionary`() {
        assertEquals(
            "x",
            MezzeTestHelper.run(
                "$ability\n\n$boxImpl\n\ndo\n  let b: Box Int Str = Make.make {}\n  b.second",
            ).toString(),
            "`Box Int Str`'s second field did not come from `impl Make for Str` — the two " +
                "variables shared a dictionary",
        )
    }

    /**
     * S-11 PROBE: both draws inside a LAMBDA, so two per-variable dictionaries must
     * be CAPTURED by the closure rather than read from the enclosing operation's
     * parameters.
     *
     * This is the shape `impl Arbitrary for Map 'k 'v` has and `Box 'k 'v` above does
     * not — the map draws its pair inside `list_of { g = {} -> … }`. ONE variable
     * inside a lambda is fine (`impl Arbitrary for ['a]` is exactly that and works),
     * so if this fails the combination is what matters, not either half.
     */
    @Test
    fun `two variables drawn inside a lambda`() {
        assertEquals(
            7L,
            long(
                """
                $ability

                type Box 'k 'v = { first: 'k, second: 'v }

                let apply: { f: {} -> 'a } -> 'a = { f } -> f {}

                impl Make for Box 'k 'v where {
                  make: {} with Make 'k, Make 'v -> Box 'k 'v = {} ->
                    apply { f = {} -> { first = Make.make {}, second = Make.make {} } }
                }

                do
                  let b: Box Int Str = Make.make {}
                  b.first
                """.trimIndent(),
            ),
            "two dictionaries were not captured into the closure",
        )
    }

    /** Pinned by a declared RETURN rather than a `let`, the other annotation position. */
    @Test
    fun `pinning by a function's return type works`() {
        assertEquals(
            7L,
            long(
                """
                $ability

                $boxImpl

                let mk: {} -> Box Int Str = {} -> Make.make {}

                do
                  let b = mk {}
                  b.first
                """.trimIndent(),
            ),
        )
    }
}
