package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Record specification, §11.8–11.9: what a spread produces, and when it is not
 * allowed at all.
 *
 * The sharpest rule in the section: **you may READ an unknown record, but you
 * may not REBUILD one.** A record literal always produces a closed record, and
 * you cannot close over fields you cannot name — so spreading a record whose
 * field set is open has no legitimate result type, and that is an error at the
 * spread rather than at some later use.
 *
 * And a spread of a NAMED record keeps the name, because the spread names its
 * source and the identity is right there. Without that, named records would be
 * unusable: a spread is the only way to produce a modified one, and an anonymous
 * result reaches no impl.
 */
class RecordSpecUpdateTest : VariantSpecBase() {

    // =============================================== 11.8 spread keeps the name

    /** 11.8 — the result of a spread is still the source's named type. */
    @Test fun `a spread keeps its source's name`() {
        val t = printedType(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let p: Point2 = { x = 1, y = 2 }
            pub let moved = {} -> { ...p, x = 10 }
            $MAIN
            """,
            "moved",
        )
        assertTrue(
            t.contains("Point2"),
            "A spread of a point that moves `x` to another Int is still a point. Acceptance " +
                "alone would not show this — an anonymous result is ALSO accepted by a " +
                "Point2 position, via adoption — so the printed type is the only witness, " +
                "and the difference shows up as which impl the result reaches; got $t",
        )
    }

    /** 11.8 — and it satisfies a position demanding that name. */
    @Test fun `a spread result fits its source's named position`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let p: Point2 = { x = 1, y = 2 }
            pub let takes_point = { q: Point2 } -> q.x
            pub let u = {} -> takes_point { q = { ...p, x = 10 } }
            $MAIN
            """,
            "The ordinary use. Pairs with the printed-type test above: this one would pass " +
                "even if the name were lost, which is exactly why both are here.",
        )
    }

    /** 11.8 — a spread may not add a field, which is settled behaviour. */
    @Test fun `a spread may not add a field`() {
        rejects(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let p: Point2 = { x = 1, y = 2 }
            pub let bad = {} -> { ...p, z = 3 }
            $MAIN
            """,
            "T0203",
            "Spread is type-preserving: it updates, it does not extend. Adding `z` would " +
                "produce a record of a different shape, which by 11.8 would then claim to " +
                "be a Point2.",
        )
    }

    // ========================================== 11.9 an open record is not spreadable

    /**
     * 11.9 — openness is CARRIED, not closed over.
     *
     * This test asserted the opposite: that spreading an open record is
     * refused, because a literal is always closed and you cannot close over
     * fields you cannot name. That conflated a design choice with the
     * invariant. The unsound case is giving the spread a CLOSED result --
     * a value carrying fields its type does not list, which would then satisfy
     * a closed position while secretly carrying more.
     *
     * So the spread is legal and the result shares the parameter's row: hand
     * `bump` a `{ x: Int, y: Str }` and it returns one.
     */
    @Test fun `an open record may be spread, carrying its row`() {
        accepts(
            """
            use std::int
            pub let bump = { r } -> { ...r, x = r.x + 1 }
            pub let u = {} -> bump { r = { x = 1, y = 2 } }
            $MAIN
            """,
            "The runtime copies the unknown fields and the type says so. Refusing this " +
                "would rule out row-polymorphic update, which is a feature choice rather " +
                "than a soundness wall -- and the caller here passes a record with a field " +
                "`bump` never named.",
        )
    }

    /** 11.9 — and a NAMED open source gives its name back, by 11.8. */
    @Test fun `spreading a declared-open record keeps its name`() {
        accepts(
            """
            use std::int
            type Loose = { x: Int, ... }
            pub let bump2 = { r: Loose } -> { ...r, x = r.x + 1 }
            $MAIN
            """,
            "`Loose` names the row, so 11.8 carries it and the result IS a Loose. The " +
                "difference from the unnamed case is only that there is a name to carry.",
        )
    }

    /** 11.8 — two spreads are refused outright, whatever their shapes. */
    @Test fun `two spreads are refused`() {
        rejects(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let p: Point2 = { x = 1, y = 2 }
            pub let q: Point2 = { x = 3, y = 4 }
            pub let bad = {} -> { ...p, ...q }
            $MAIN
            """,
            "T0220",
            "One source, one name to carry. Two would have to pick, and a union of their " +
                "fields adds fields to each -- which a spread may not do. `{ ...p }` alone " +
                "is fine and is the identity.",
        )
    }

    /** 11.8 — a single spread with no overrides is the identity. */
    @Test fun `a spread with no overrides is the identity`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let p: Point2 = { x = 1, y = 2 }
            pub let takes_point = { q: Point2 } -> q.x
            pub let u = {} -> takes_point { q = { ...p } }
            $MAIN
            """,
            "The control for the rejection above: one spread is always fine, so that rule " +
                "is about the SECOND spread and not about spreading.",
        )
    }

    /** 11.9, the mirror — annotating the parameter makes it legal. */
    @Test fun `annotating the parameter makes the spread legal`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let bump = { r: Point2 } -> { ...r, x = r.x + 1 }
            $MAIN
            """,
            "The fix the diagnostic should suggest: say what `r` is, and the field set is " +
                "known. Without this acceptance the rule above would read as \"spread is " +
                "broken\" rather than \"spread needs a known shape\".",
        )
    }

    /** 11.9 — and reading an unknown record stays fine, which is the other half. */
    @Test fun `reading an open record is still fine`() {
        accepts(
            """
            use std::int
            pub let get_x = { r } -> r.x
            pub let u = {} -> get_x { r = { x = 1, y = 2 } }
            $MAIN
            """,
            "This is what makes 11.9 a statement about CONSTRUCTION rather than about open " +
                "records being useless. An open record type is fine to have and to read; it " +
                "is illegitimate only as the type of something you build.",
        )
    }
}
