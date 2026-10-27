package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A bare record literal dispatches an ability, with no annotation.
 *
 * These four pass WITHOUT the BUG 26 fix — measured, by removing both halves of
 * it and re-running. They are the plain shape: a constraint variable that the
 * parameter type mentions (`{ e: 'e } with Shape 'e`), which resolution has
 * always handled. They are kept as the floor the fix must not lower, not as its
 * regression test, and the header said otherwise before this was measured.
 *
 * The shapes that DID break are the ones whose constraint variable appears only
 * in the effect row, where mono's phantom check could not see it:
 * `ThrownTypeInConstraintTest` (minimal, user-defined ability) and
 * `HttpCatchingAnyTest`'s `a record thrown error dispatches to its alias's impl`
 * (the router, two constraints).
 *
 * What is load-bearing here is the LAST case: a record matching no alias must
 * still be refused. Adoption labels a literal with an alias's identity, and the
 * pass that descends into record FIELD types is how a record with a `Str` field
 * used to reach `impl Shape for Str` — so the refusal is the guard on both.
 */
class BareRecordDispatchTest {
    private fun run(src: String): Any = MezzeTestHelper.run(src.trimIndent())

    private val ability = """
        ability Shape for 'e where { shape: { it: 'e } -> Str }
        type Bad = { why: Str }
        impl Shape for Str where { shape = { it } -> "STR" }
        impl Shape for Bad where { shape = { it } -> "BAD" }
    """.trimIndent()

    /** Through a constrained function — the shape `catching_any` has. */
    @Test fun `a bare literal reaches the impl for its alias`() {
        assertEquals(
            "BAD",
            run(
                """
                $ability
                let describe: { e: 'e } with Shape 'e -> Str = { e } -> e.shape {}
                describe { e = { why = "x" } }
                """
            ).toString(),
        )
    }

    /** Annotated — always worked, kept so the fix cannot trade one for the other. */
    @Test fun `an annotated value still reaches it`() {
        assertEquals(
            "BAD",
            run(
                """
                $ability
                let describe: { e: 'e } with Shape 'e -> Str = { e } -> e.shape {}
                let b: Bad = { why = "x" }
                describe { e = b }
                """
            ).toString(),
        )
    }

    /** A primitive still reaches ITS impl, not the record one. */
    @Test fun `a Str still reaches the Str impl`() {
        assertEquals(
            "STR",
            run(
                """
                $ability
                let describe: { e: 'e } with Shape 'e -> Str = { e } -> e.shape {}
                describe { e = "plain" }
                """
            ).toString(),
        )
    }

    /**
     * A record whose shape matches NO alias is still refused, loudly.
     *
     * Adoption only labels a shape that names exactly one declared alias, so
     * this must not quietly find some other impl — in particular not by the
     * later pass that descends into record FIELD types, which is how a record
     * with a `Str` field used to reach `impl Shape for Str` and fail with
     * `Expected String for field 's'`.
     */
    @Test fun `a record matching no alias is refused`() {
        val outcome = runCatching {
            run(
                """
                $ability
                let describe: { e: 'e } with Shape 'e -> Str = { e } -> e.shape {}
                describe { e = { nothing_like_bad = 1 } }
                """
            )
        }
        val message = outcome.exceptionOrNull()?.message ?: ""
        assert(outcome.isFailure && !message.contains("STR")) {
            "a record matching no alias must be refused, not routed to another " +
                "impl; got ${outcome.getOrNull() ?: message}"
        }
    }
}
