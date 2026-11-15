package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Record specification, §11.8–11.9: what a spread produces, and when it is not
 * allowed at all.
 *
 * The sharpest rule in the section: **you may READ an unknown record, but you
 * may not REBUILD one.** A record literal always produces a closed record, and
 * you cannot close over fields you cannot name — so spreading a source whose
 * field set is open has no legitimate result type (V0714), and that is an error
 * at the spread rather than at some later use. "Open" here means DECLARED open:
 * a source that inference has simply not got to yet is pinned by its call site
 * and spreads to an exact, closed result.
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

    // ========================================== 11.9 an open row is not carried

    /**
     * 11.9 — A ROW IS NOT CARRIED FORWARD (V0714).
     *
     * This section has now said three different things, so it is worth being
     * exact about which one is the rule and why the other two were wrong.
     *
     *  1. "an open record is not spreadable" — the heading, and too strong: it
     *     refuses a spread whose source a caller DOES pin.
     *  2. "openness is CARRIED, not closed over" — the body below this comment
     *     used to say that, and it is the feature the author has ruled out. The
     *     result wore the source's row, and a row on a RESULT is a type that
     *     answers for fields nobody wrote: measured, `(bump { r = { x = 1, y = 2
     *     } }).zzz` type-checked and read `'a`.
     *  3. The rule: a spread's result is the fields it can NAME, and the row
     *     does not travel. What decides legality is whether the source's fields
     *     are knowable at all.
     *
     * A DECLARED-open source (`type Loose = { x: Int, ... }`) is refused: its
     * extra fields have no names anywhere, so no honest result type exists.
     * A source that is merely unresolved WHERE THE SPREAD IS WRITTEN is fine --
     * the call site pins it, which is ordinary bidirectional inference and not
     * openness at all.
     */
    @Test fun `a spread of an inferred source closes to the fields it can name`() {
        val t = printedType(
            """
            use std::int
            pub let bump = { r } -> { ...r, x = r.x + 1 }
            $MAIN
            """,
            "bump",
        )
        assertTrue(
            !t.contains("..."),
            "`bump`'s parameter and result must both be CLOSED: it names `x` and nothing " +
                "else, so `{ x: 'a }` is the whole truth about it. This read " +
                "`{ x: 'a, ... }` before — the row was pinned open so a wider caller " +
                "would be accepted, and the same row then reached the RESULT, where it " +
                "answered for any field at all; got $t",
        )
    }

    /** 11.9 — and the loop shape, which is what makes the rule liveable. */
    @Test fun `a spread of a parameter the call site pins is exact`() {
        val t = printedType(
            """
            use std::int
            let go = { st } -> if st.i <= 0 then st.acc else go { st = { ...st, i = st.i - 1 } }
            pub let run = {} -> go { st = { i = 4, acc = 0 } }
            $MAIN
            """,
            "go",
        )
        assertTrue(
            !t.contains("...") && t.contains("i:") && t.contains("acc:"),
            "THE CASE THE RULE HAS TO KEEP. `st` is unresolved where `...st` is written " +
                "and fully determined by the call below it, so the spread is legal and " +
                "`go` reads `{ st: { i: Int, acc: 'a } }` — closed, both fields, no row. " +
                "An earlier version of V0714 refused this for being unresolved AT THE " +
                "SPREAD, which is not the same question; got $t",
        )
    }

    /** 11.9 — a DECLARED-open source is the shape with no honest answer. */
    @Test fun `spreading a declared-open record is refused`() {
        rejects(
            """
            use std::int
            type Loose = { x: Int, ... }
            pub let bump2 = { r: Loose } -> { ...r, x = r.x + 1 }
            $MAIN
            """,
            "V0714",
            "`Loose`'s other fields are named NOWHERE, so neither answer is honest: " +
                "carrying the row puts a row on a result (case 2 above), and closing it " +
                "drops fields the value still has -- which is what this DID, inferring " +
                "`{ x: Int }` and silently discarding the rest. This case asserted " +
                "acceptance, on the reasoning that `Loose` \"names the row, so 11.8 " +
                "carries it\" -- the name is not the problem, the carrying is.",
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
