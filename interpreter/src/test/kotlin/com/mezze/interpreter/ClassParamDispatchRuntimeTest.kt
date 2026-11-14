package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * BUG 38 — class-param dispatch picked the wrong impl once both were reachable.
 *
 * Two impls of `Combine 'r for 'l having 'out` share the receiver `A` and
 * differ only in the class param. The typechecker resolved both correctly
 * (`ClassParamDispatchSpecTest` pins that: `with_b : {} -> Int`,
 * `with_a : {} -> Str`) while the runtime ran one body for both:
 *
 *     A+B expect 111 -> 111
 *     error[I0004]: class java.lang.Long cannot be cast to class java.lang.String
 *
 * Calling `with_b` alone worked, so it took BOTH impls being reachable.
 *
 * CAUSE: the disambiguator looked for an argument field named literally `r`.
 * Every numeric ability in the stdlib spells its co-operand that way, so
 * `datetime`'s `NumSub Duration for Instant` vs `NumSub Instant for Instant`
 * — the pair [[project_classparam_runtime_dispatch_tuple]] records as fixed —
 * kept working and nothing noticed. An ability naming the co-operand anything
 * else fell through to "first receiver match wins". Dispatch now asks which
 * candidate's OPERATION accepts the arguments supplied, reading the class param
 * off the impl's own signature.
 *
 * An interpreter test because the defect was invisible to the typechecker, and
 * only a VALUE distinguishes the impls — exactly like the datetime case.
 */
class ClassParamDispatchRuntimeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val src = """
use std::effects::log::{ println, Console }
use std::int

ability Combine 'r for 'l having 'out where {
  combine: { it: 'l, other: 'r } -> out
}

type A = { a: Int }
type B = { b: Int }

impl Combine B for A having 'out = Int where {
  combine: { it: A, other: B } -> Int = { it, other } -> 111
}
impl Combine A for A having 'out = Str where {
  combine: { it: A, other: A } -> Str = { it, other } -> "222"
}

pub let with_b: {} -> Int = {} -> do
  let x: A = { a = 1 }
  let y: B = { b = 2 }
  x.combine { other = y }

pub let with_a: {} -> Str = {} -> do
  let x: A = { a = 1 }
  let z: A = { a = 3 }
  x.combine { other = z }

let main = {} -> perform Console in do
  let p = with_b {}
  println { msg = "B=${'$'}(p.to_dbg {})" }
  let q = with_a {}
  println { msg = "A=${'$'}(q.to_dbg {})" }
main {}
    """.trimIndent()

    @Test
    fun `each impl is selected by its class param`() {
        val out = MezzeTestHelper.runWithOutput(src).output
        assertEquals(
            true,
            out.contains("B=111") && out.contains("A=\"222\""),
            "each call must reach the impl its CLASS PARAM selects — `A.combine{B}` " +
                "the Int one, `A.combine{A}` the Str one. Measured before the fix: " +
                "B=111 then a ClassCastException (Long -> String), i.e. the second " +
                "call reached the first impl:\n$out",
        )
    }

    /** BUG 39 — the class param in the RETURN, where no argument reaches it.
     *
     *      ability Into 'b for 'a where { into: { it: 'a } -> 'b }
     *
     *  The arg record is `{}`, so the signature match above has nothing to
     *  compare and the class-arg tuple is not in the arguments either. Dispatch
     *  fell to "first receiver match wins", and `Into Str for Int` is first:
     *
     *      let f: Float = (5).into {}
     *      -> error[I0004]: class java.lang.String cannot be cast to class java.lang.Double
     *
     *  ONE impl in the program was enough — unlike BUG 38, this needed no second
     *  call. `Int` has three impls (Str, Float, Frac), `Nat` three, `Char` and
     *  `Frac` two each, so every target but the first was unreachable. Dispatch
     *  now also asks which candidate's operation RETURNS what the call produces.
     *
     *  This is the stdlib half; the user-ability half is the test after it. */
    @Test
    fun `each stdlib Into target for Int arrives`() {
        val out = MezzeTestHelper.runWithOutput(
            """
            use std::effects::log::{ println, Console }
            use std::int
            use std::str
            use std::float
            use std::frac
            use std::abilities::convert::{ Into }

            let main = {} -> perform Console in do
              let s: Str = (5).into {}
              let f: Float = (5).into {}
              let q: Frac = (5).into {}
              println { msg = "S=${'$'}(s.to_dbg {})" }
              println { msg = "F=${'$'}(f.to_dbg {})" }
              println { msg = "Q=${'$'}(q.to_dbg {})" }
            main {}
            """.trimIndent(),
        ).output
        val tags = listOf("S", "F", "Q")
        assertEquals(
            listOf("S=\"5\"", "F=5.0", "Q=5//1"),
            out.lines().map { it.trim() }.filter { ln -> tags.any { ln.startsWith("$it=") } },
            "each `Into` target must reach its own impl. Before the fix the Float " +
                "and Frac lines each died casting the STRING \"5\":\n$out",
        )
    }

    /** The same shape in a user ability, with values that name the impl rather
     *  than types that merely differ — so a wrong answer is visible even when
     *  both impls happen to agree on representation. */
    @Test
    fun `a user ability's return-position class param selects its impl`() {
        val out = MezzeTestHelper.runWithOutput(
            """
            use std::effects::log::{ println, Console }
            use std::int
            use std::str

            ability Make 'b for 'a where { make: { it: 'a } -> 'b }
            type Src = { n: Int }
            impl Make Int for Src where {
              make: { it: Src } -> Int = { it } -> 111
            }
            impl Make Str for Src where {
              make: { it: Src } -> Str = { it } -> "222"
            }

            pub let as_int: {} -> Int = {} -> do
              let s: Src = { n = 1 }
              s.make {}
            pub let as_str: {} -> Str = {} -> do
              let s: Src = { n = 1 }
              s.make {}

            let main = {} -> perform Console in do
              println { msg = "I=${'$'}(as_int {}.to_dbg {})" }
              println { msg = "T=${'$'}(as_str {}.to_dbg {})" }
            main {}
            """.trimIndent(),
        ).output
        assertEquals(
            listOf("I=111", "T=\"222\""),
            out.lines().map { it.trim() }.filter { it.startsWith("I=") || it.startsWith("T=") },
            "the ANNOTATION is the only thing separating these two impls:\n$out",
        )
    }

    /** The STDLIB shape the disambiguator was written for, and the one the `r`
     *  name match happened to cover. `Instant - Duration` is an Instant and
     *  `Instant - Instant` is a Duration, so the receiver cannot choose and the
     *  result VALUE says which impl ran. This guards the other direction of the
     *  fix. Their params are UNANNOTATED (`sub = { it, r } -> …`), so they also
     *  exercise the class-arg tuple fallback rather than the signature match. */
    @Test
    fun `datetime's five class-param impls each run their own body`() {
        val out = MezzeTestHelper.runWithOutput(
            """
            use std::effects::log::{ println, Console }
            use std::datetime::{ Instant, Duration, instant_from_millis, duration_millis,
                                 instant_to_millis, duration_to_millis }
            use std::int

            let main = {} -> perform Console in do
              let t0: Instant = instant_from_millis { n = 1000 }
              let t1: Instant = instant_from_millis { n = 2500 }
              let d: Duration = duration_millis { n = 400 }
              let gap: Duration = t1 - t0
              let back: Instant = t1 - d
              let fwd: Instant = t0 + d
              let dsum: Duration = d + d
              let ddif: Duration = dsum - d
              println { msg = "I-I=${'$'}(duration_to_millis { d = gap }.to_dbg {})" }
              println { msg = "I-D=${'$'}(instant_to_millis { i = back }.to_dbg {})" }
              println { msg = "I+D=${'$'}(instant_to_millis { i = fwd }.to_dbg {})" }
              println { msg = "D+D=${'$'}(duration_to_millis { d = dsum }.to_dbg {})" }
              println { msg = "D-D=${'$'}(duration_to_millis { d = ddif }.to_dbg {})" }
            main {}
            """.trimIndent(),
        ).output
        val tags = listOf("I-I", "I-D", "I+D", "D+D", "D-D")
        assertEquals(
            listOf("I-I=1500", "I-D=2100", "I+D=1400", "D+D=800", "D-D=400"),
            out.lines().map { it.trim() }.filter { ln -> tags.any { ln.startsWith("$it=") } },
            "each of datetime's five class-param impls must run its own body:\n$out",
        )
    }
}
