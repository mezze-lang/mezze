package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Generating a type whose components are CONTAINERS — the boundary, asserted.
 *
 * None of it works yet, and the three cases below say so rather than being left
 * out. A gap nothing tests is a gap nobody finds again: `S-2` shipped claiming
 * "records, variants and containers" because the derivability RULE answered yes for
 * a container, and nothing checked that an instance existed to go with the answer.
 *
 * The single blocker is S-3 (#160): a parameterised container's impl carries a
 * `with Arbitrary 'a` precondition, so its call site has to pass a dictionary for
 * the element's instance — and for a receiver-LESS operation that path falls
 * through to the receiver-taking convention, looking for an `it` field a
 * no-argument operation does not have.
 *
 * That hits every container, by two different routes:
 *
 *  - `[Int]` — the impl is HAND-WRITTEN in `std::abilities::arbitrary` (a sequence
 *    has no declaration to derive from) and carries the precondition, so it reaches
 *    a dictionary slot nobody filled and fails at RUNTIME.
 *  - `Option Int` / `Result Int Str` — these are real variant declarations and
 *    WOULD derive, except their home modules cannot import `Arbitrary` (the
 *    prelude is built from them), so no instance is generated and it fails in the
 *    TYPECHECKER. A hand-written one is refused by the coherence rule, which asks
 *    whether an instance COULD be derived rather than whether one exists — and
 *    would hit the same dictionary gap anyway, being equally generic.
 *
 * ## What is already proven to work, and so what the fix looks like
 *
 * `InlineContainerProbeTest` measured the alternative: generating a container
 * INLINE at a concrete element type needs no dictionary and works today —
 * `list_of { g = {} -> Arbitrary.arbitrary {} }` pinned to `[Int]`, and a record
 * built with such a field. A recursive variant bounded by `size`/`resize` works
 * too. So neither feature is blocked on generation; both are blocked on what a
 * GENERATED body is allowed to name, which is abilities only — the prelude
 * deliberately propagates no effects or bindings, and a fully-qualified path does
 * not resolve without an import either.
 */
class ArbitraryContainerTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, with_seed }
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

    @Test
    fun `a seq component is blocked`() {
        stillBlocked(
            "a [Int] field",
            """
            type Tags = { xs: [Int] }
            with_seed { seed = 3, call = {} -> do
              let t: Tags = Arbitrary.arbitrary {}
              seq::size { xs = t.xs }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `an Option component is blocked`() {
        stillBlocked(
            "an Option Int field",
            """
            type Maybe = { o: Option Int }
            with_seed { seed = 3, call = {} -> do
              let m: Maybe = Arbitrary.arbitrary {}
              get_or { opt = m.o, def = 0 - 1 }
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `a Result component is blocked`() {
        stillBlocked(
            "a Result Int Str field",
            """
            type Outcome = { r: Result Int Str }
            with_seed { seed = 3, call = {} -> do
              let o: Outcome = Arbitrary.arbitrary {}
              is_ok { result = o.r }
            }
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

            with_seed { seed = 3, call = {} -> do
              let o: Outer = Arbitrary.arbitrary {}
              o.a.n
            }
        """.trimIndent()))
        assertTrue(v != null, "a nested record of primitives failed to generate")
    }
}
