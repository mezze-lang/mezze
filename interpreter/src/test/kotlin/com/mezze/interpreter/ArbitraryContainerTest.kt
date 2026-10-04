package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Generating a type whose components are CONTAINERS.
 *
 * SEQUENCES WORK — `[Int]`, `[Str]`, `[[Int]]` and `[Row]` all generate. That took
 * two fixes: `impl X for ['a]` was registered under `Seq_a`, keyed by its own type
 * PARAMETER's spelling (`typeNameOf`), and a receiver-less ability op was emitted
 * with no dictionary at all, because the instance-head binding read its concrete
 * type off a RECEIVER FIELD that a no-argument operation does not have. It now binds
 * against the type the call PRODUCES, which plays exactly the receiver's role.
 *
 * `Option` and `Result` do NOT, and the reason is no longer the dictionary — it is
 * S-4 (#161). Their impls would work; they cannot be WRITTEN. The coherence rule
 * refuses a hand-written impl for anything structurally derivable, a variant is, and
 * the thing that would derive it never runs because `std::option`/`std::result`
 * cannot import `Arbitrary` (the prelude is built from them). So the type gets
 * neither instance. `['a]` escapes only because it has no declaration, so the rule
 * does not apply to it at all.
 *
 * The blocked cases assert the REASON, not merely that something threw. The first
 * version accepted anything mentioning `Arbitrary`, so when the runtime diagnostic
 * improved it could not tell an improvement from a regression — it failed, which is
 * how the wording got locked.
 */
class ArbitraryContainerTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, SeededSample }
        use std::abilities::arbitrary::{ Arbitrary }
        use std::collections::seq
        use std::option::{ get_or }
        use std::result::{ is_ok }
    """.trimIndent()

    /**
     * Blocked, and blocked for the REASON S-3 names.
     *
     * Matching the message rather than merely "it threw" is the point: the first
     * version of this accepted anything mentioning `Arbitrary`, and when the
     * diagnostic improved it could not tell an improvement from a regression. The
     * two accepted shapes are the two places the gap surfaces — the typechecker
     * declining to pick an impl, and the runtime reaching a dictionary slot nobody
     * filled.
     */
    private fun stillBlocked(label: String, body: String) {
        val e = runCatching { MezzeTestHelper.run("$imports\n\n$body") }.exceptionOrNull()
        val message = e?.message ?: ""
        val noDictionary = "no dictionary was supplied for ability operation `arbitrary`" in message
        val noImpl = "takes no receiver" in message && "chooses the implementation" in message
        assertTrue(
            noDictionary || noImpl,
            "$label now works, or fails for a NEW reason — revisit S-3 (#160). Got: $message",
        )
    }

    /** Draws the component and returns something; the value itself is the RNG's. */
    private fun generates(label: String, body: String) {
        val v = runCatching { MezzeTestHelper.run("$imports\n\n$body") }
        assertTrue(v.isSuccess, "$label failed to generate: ${v.exceptionOrNull()?.message}")
    }

    @Test
    fun `a seq component generates`() {
        generates(
            "a [Int] field",
            """
            type Tags = { xs: [Int] }
            perform SeededSample { seed = 3 } in do
              let t: Tags = Arbitrary.arbitrary {}
              seq::size { xs = t.xs }
            """.trimIndent(),
        )
    }

    @Test
    fun `a seq of Str generates`() {
        generates(
            "a [Str] field",
            """
            type Tags = { xs: [Str] }
            perform SeededSample { seed = 9 } in do
              let t: Tags = Arbitrary.arbitrary {}
              seq::size { xs = t.xs }
            """.trimIndent(),
        )
    }

    /** NESTED — each level peels one container, so the inner dict must be right. */
    @Test
    fun `a nested seq generates`() {
        generates(
            "a [[Int]] field",
            """
            type N = { xss: [[Int]] }
            perform SeededSample { seed = 3 } in do
              let n: N = Arbitrary.arbitrary {}
              seq::size { xs = n.xss }
            """.trimIndent(),
        )
    }

    /** A seq of RECORDS — the element's own derived instance has to be reached. */
    @Test
    fun `a seq of records generates`() {
        generates(
            "a [Row] field",
            """
            type Row = { a: Int, b: Str }
            type Table = { rows: [Row] }
            perform SeededSample { seed = 4 } in do
              let t: Table = Arbitrary.arbitrary {}
              seq::size { xs = t.rows }
            """.trimIndent(),
        )
    }

    @Test
    fun `an Option component generates`() {
        generates(
            "an Option Int field",
            """
            type Maybe = { o: Option Int }
            perform SeededSample { seed = 3 } in do
              let m: Maybe = Arbitrary.arbitrary {}
              get_or { opt = m.o, def = 0 - 1 }
            """.trimIndent(),
        )
    }

    @Test
    fun `a Result component generates`() {
        generates(
            "a Result Int Str field",
            """
            type Outcome = { r: Result Int Str }
            perform SeededSample { seed = 3 } in do
              let o: Outcome = Arbitrary.arbitrary {}
              is_ok { result = o.r }
            """.trimIndent(),
        )
    }

    /**
     * What DOES work, kept here as the contrast: a record of records of primitives
     * needs no dictionary, because every component resolves to a concrete instance
     * at the call site. That is the line S-3 moves.
     */
    @Test
    fun `a record of records of primitives generates`() {
        val v = MezzeTestHelper.asLong(MezzeTestHelper.run("""
            $imports

            type Inner = { n: Int, s: Str }
            type Outer = { a: Inner, b: Inner, flag: Bool }

            perform SeededSample { seed = 3 } in do
              let o: Outer = Arbitrary.arbitrary {}
              o.a.n
        """.trimIndent()))
        assertTrue(v != null, "a nested record of primitives failed to generate")
    }
}
