package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Record specification, §11.17–11.20: abilities and impls over records.
 *
 * Dispatch is nominal (11.6), so an impl target has to be a NAMED record and a
 * value has to BE that name to reach it. Those two together are what make
 * adoption (11.3) load-bearing rather than cosmetic: it is the only bridge from
 * an anonymous literal to a nominal impl, and the stdlib rides it every time a
 * handler answers `{ status = 404, body = "not found" }`.
 *
 * The coherence rule: impl targets for one ability are pairwise DISJOINT. One
 * target being an INSTANCE of another refuses the pair — no specialization at
 * any level, even with the right types. That extends the existing strict
 * coherence from a fully generic `'a` to a generic record's instantiations, and
 * it is nearly free because the instance test is the one 11.15 already needs.
 */
class RecordSpecImplTest : VariantSpecBase() {

    // ======================================= 11.17 the target must be named

    /** 11.17 — an impl may not target an anonymous record shape. */
    @Test fun `an impl may not target an anonymous record`() {
        rejects(
            """
            use std::int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for { x: Int, y: Int } where { norm = { it } -> it.x + it.y }
            $MAIN
            """,
            "V0701",
            "An impl has to be attributable to a declared type. Anonymous records remain " +
                "ordinary values — constructed, read, passed, returned — they simply cannot " +
                "be an impl TARGET, which is exactly what makes adoption the only bridge to " +
                "a nominal impl.",
        )
    }

    /** 11.17, the mirror — the same impl on a named record is fine. */
    @Test fun `an impl may target a named record`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Point2 where { norm = { it } -> it.x + it.y }
            $MAIN
            """,
            "The control. Same ability, same body, a name instead of a shape — so the " +
                "rejection above is about anonymity and not about the impl being malformed.",
        )
    }

    // ============================================== 11.19 adoption bridges

    /** 11.19 — a bare literal reaches a nominal impl by adopting its target. */
    @Test fun `a bare literal reaches an impl by adoption`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Point2 where { norm = { it } -> it.x + it.y }
            pub let n = {} -> { x = 1, y = 2 }.norm {}
            $MAIN
            """,
            "The stdlib's own pattern: a handler writes a bare record and reaches " +
                "`into_response` without importing `StatusBody`. B-16 is this fix — a bare " +
                "record adopts its record-alias impl target during op resolution.",
        )
    }

    /**
     * 11.19 — and an unadopted literal reaches nothing.
     *
     * The failure mode worth pinning: this is not a malformed program, it is a
     * value that matches no name and therefore finds no impl. The diagnostic
     * ought to say which record it ALMOST matched.
     */
    @Test fun `an extra field leaves the literal with no impl`() {
        rejects(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Point2 where { norm = { it } -> it.x + it.y }
            pub let n = {} -> { x = 1, y = 2, z = 3 }.norm {}
            $MAIN
            """,
            "V0402",
            "One extra field and exactness (11.3) refuses the match, so the literal stays " +
                "anonymous and dispatch finds nothing. Pairs with the test above: the only " +
                "difference between reaching the impl and reaching nothing is whether the " +
                "field set is exact. The code is V0402_ABILITY_NOT_SATISFIED — the ability exists, this value just does not satisfy it.",
        )
    }

    // ===================================== 11.18 targets are pairwise disjoint

    /** 11.18 — a blanket impl and a concrete one for the same ability. */
    @Test fun `a blanket impl forbids a concrete one`() {
        rejects(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for 'a where { norm = { it } -> 0 }
            impl Norm for Point2 where { norm = { it } -> it.x + it.y }
            $MAIN
            """,
            "V0407",
            "The existing strict-coherence rule: `'a` covers Point2, so a value matches " +
                "both and choosing the narrower one is the ranking \"no specialization\" " +
                "forbids. A blanket impl MONOPOLIZES its ability for the whole dependency " +
                "tree. The code is V0407_OVERLAPPING_INSTANCE, which is purpose-built.",
        )
    }

    /**
     * 11.18 — and the extension: a generic record's instantiation.
     *
     * 11.16 made the type argument visible, which makes `Box Int` a distinct
     * type — but not a DISJOINT one. Every `Box Int` is a `Box 'a`.
     */
    @Test fun `a generic record impl forbids an instantiated one`() {
        rejects(
            """
            use std::int
            type Box 'a = { v: 'a }
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Box 'a where { norm = { it } -> 0 }
            impl Norm for Box Int where { norm = { it } -> 1 }
            $MAIN
            """,
            "V0701",
            "One target being an INSTANCE of the other refuses the pair, even with the " +
                "right types. The instance test is the same one 11.15 needs for the claim " +
                "rule, so this is nearly free to enforce.\n\nRED ON PURPOSE. This IS refused today, but with V0705_TYPE_ARITY_MISMATCH — an arity mismatch is not an overlap diagnostic. It says `impl Norm for Box Int` is not EXPRESSIBLE: the checker trips on the instantiated target before coherence is ever consulted, so the rule holds by accident. Asserting V0705 here would pin the accident. The fix is to make an instantiated target parse and then let coherence refuse it.",
        )
    }

    /** 11.18, the mirror — genuinely disjoint targets coexist. */
    @Test fun `disjoint named targets coexist`() {
        writeModule(
            "shapes",
            """
            use std::int
            pub type Vec2 = { x: Int, y: Int }
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Vec2 where { norm = { it } -> it.x + it.y }
            """,
        )
        acceptsMain(
            """
            use std::int
            use shapes::{ Norm }
            type Named = { label: Int, weight: Int }
            impl Norm for Named where { norm = { it } -> it.label + it.weight }
            $MAIN
            """,
            "Two impls for one ability over records that share no shape. Without this the " +
                "rejections above would read as \"an ability admits one impl\", which is " +
                "not the rule — disjointness is.",
        )
    }
}
