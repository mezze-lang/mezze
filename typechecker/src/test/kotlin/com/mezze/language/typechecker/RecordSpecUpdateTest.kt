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
     * 11.9 — the rule, and the one I would point a reader at first.
     *
     * `r` is open: all that is known is that it has an Int `x`. A literal must be
     * closed, and the unknown fields cannot be named, so there is no result type
     * to give this expression.
     */
    @Test fun `an open record may not be spread`() {
        rejects(
            """
            use std::int
            pub let bump = { r } -> { ...r, x = r.x + 1 }
            $MAIN
            """,
            "T0201",
            "You may READ an unknown record, but you may not REBUILD one. Reported at the " +
                "spread, not at some later use, and the message has to name the cause: the " +
                "parameter's type is open, so the result's fields are not known.\n\n" +
                "Row-polymorphic update is the road not taken here. The cost is that a " +
                "spreading helper must annotate its parameter, and so works for one named " +
                "type rather than any record with an `x`.",
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
