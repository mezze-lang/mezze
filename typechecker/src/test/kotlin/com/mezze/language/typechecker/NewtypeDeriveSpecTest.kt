package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * What a `newtype` derives, and what it deliberately does not.
 *
 * A newtype inherits NOTHING SEMANTIC. It is a distinct type from what it
 * wraps and is erased to its payload at runtime, so a derived comparison would
 * silently be the WRAPPED type's — the author says what the wrapper means.
 *
 * Exactly two are derived, and both for the same reason: neither claims
 * anything about equality or ordering.
 *
 *   - `Inspect` — rendering. Total for every shape.
 *   - `Hash` — a structural hash. Total for every shape too, and SAFE only
 *     because `Eq` is not derived: a hash is meaningless unless it agrees with
 *     an equality, and the author has not supplied one yet.
 *
 * MEASURED against a record, which is the contrast that makes the rule a
 * decision rather than an accident: a record derives `Eq` as well, because a
 * record IS its fields and there is nothing for an author to decide.
 *
 * And the error carries the whole requirement, because `Eq` and `Hash` are one
 * decision: writing only `Eq` is separately refused, so an author told to
 * "implement Eq" would come back for a second round trip. `Eq`'s own
 * declaration now says so, through the `#{ missing: … }` advice the ability
 * author is meant to supply.
 */
class NewtypeDeriveSpecTest : VariantSpecBase() {

    // ===================================== derived

    /** `Inspect` — rendering, and it claims nothing. */
    @Test fun `a newtype derives Inspect`() {
        accepts(
            """
            use std::int
            newtype Millis = Int
            pub let show = { m: Millis } -> m.to_dbg {}
            $MAIN
            """,
            "Total for every shape, so deriving it can never typecheck something that " +
                "then fails — and it says nothing about equality.",
        )
    }

    /** `Hash` — a structural hash, safe precisely because `Eq` is not derived. */
    @Test fun `a newtype derives Hash`() {
        accepts(
            """
            use std::int
            newtype Millis = Int
            pub let k = { m: Millis } -> m.hash {}
            $MAIN
            """,
            "Safe only BECAUSE `Eq` is withheld: a hash that disagreed with an " +
                "author's equality would lose entries from a container, and there is no " +
                "author equality yet to disagree with.",
        )
    }

    // ===================================== not derived

    /** `Eq` — the author decides what the wrapper means. */
    @Test fun `a newtype does not derive Eq`() {
        rejects(
            """
            use std::int
            newtype Millis = Int
            pub let same = { a: Millis, b: Millis } -> a == b
            $MAIN
            """,
            "V0402",
            "A newtype is erased to its payload, so a derived equality would silently " +
                "be `Int`'s. Deriving it is what would defeat the point of having a " +
                "distinct type at all.",
        )
    }

    /** `Ord` — likewise, and composites never derive it anyway. */
    @Test fun `a newtype does not derive Ord`() {
        rejects(
            """
            use std::int
            newtype Millis = Int
            pub let before = { a: Millis, b: Millis } -> a < b
            $MAIN
            """,
            "V0402",
            "Wrapping an `Int` does not make a thing ordered — whether `Millis` has an " +
                "order, and which, is the author's to say.",
        )
    }

    /** Arithmetic — the case that tempts people to reach back for a plain Int. */
    @Test fun `a newtype does not derive arithmetic`() {
        rejects(
            """
            use std::int
            newtype Millis = Int
            pub let total = { a: Millis, b: Millis } -> a + b
            $MAIN
            """,
            "V0402",
            "Adding two `Millis` is meaningful; adding a `Millis` to a `Celsius` is " +
                "not, and only the author knows which they built. The friction here is " +
                "the feature.",
        )
    }

    /** Nor the payload's inherent methods. */
    @Test fun `a newtype does not inherit the payload's methods`() {
        rejects(
            """
            use std::int
            use std::str
            newtype Millis = Int
            pub let s = { m: Millis } -> m.to_str {}
            $MAIN
            """,
            "T0201",
            "`to_str` exists for `Int` and not for `Millis` — opacity is the whole " +
                "construct, and it applies to inherent methods as much as to abilities.",
        )
    }

    // ===================================== the contrast, and the error

    /** A record DOES derive `Eq`, which is what makes the above a choice. */
    @Test fun `a record derives Eq, unlike a newtype`() {
        accepts(
            """
            use std::int
            type P = { x: Int }
            pub let same = { a: P, b: P } -> a == b
            $MAIN
            """,
            "A record IS its fields, so there is nothing to decide and structural " +
                "equality is the only answer. A newtype asserts the opposite — that it " +
                "is NOT its payload.",
        )
    }

    /**
     * The error names the WHOLE requirement, not half of it.
     *
     * A `Map` key needs `Eq`; writing only `Eq` is then separately refused
     * because `Eq` and `Hash` are one decision. Told only "implement Eq", an
     * author fixes it and is immediately refused again.
     */
    @Test fun `the missing-Eq error names Hash too`() {
        val r = check(
            """
            use std::int
            newtype Millis = Int
            pub let same = { a: Millis, b: Millis } -> a == b
            $MAIN
            """.trimIndent()
        )
        val msg = (r.exceptionOrNull() as? TypeCheckError)?.message ?: ""
        assert(msg.contains("Hash")) {
            "the advice must mention Hash, or the author hits a second refusal after " +
                "following it; got:\n$msg"
        }
        assert(msg.contains("ONE decision") || msg.contains("one decision")) {
            "and say WHY the two go together; got:\n$msg"
        }
    }

    /** Writing only `Eq` really is refused, which is why the advice must pre-empt it. */
    @Test fun `writing Eq without Hash is refused`() {
        rejects(
            """
            use std::int
            use std::abilities::compare::{ Eq }
            newtype Millis = Int
            impl Eq for Millis where {
              eq = { it, r } -> match it is
                Millis.(a) -> match r is
                  Millis.(b) -> a == b,
              neq = { it, r } -> False
            }
            $MAIN
            """,
            "V0401",
            "The second round trip, pinned — so the test above is guarding against a " +
                "real sequence and not a hypothetical one.",
        )
    }
}
