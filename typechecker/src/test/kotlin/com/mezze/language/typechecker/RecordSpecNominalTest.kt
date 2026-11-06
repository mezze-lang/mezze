package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Record specification, §11.6–11.7 and 11.10: nominality belongs to the
 * POSITION, not the value.
 *
 * Three cases, and the whole of §11.6 is in the table:
 *
 *   value          position                    verdict
 *   Point2         anonymous / open / inferred fits   — the name is just an alias
 *   bare literal   Point2                      fits   — adopts (11.3)
 *   Vec2           Point2                      ERROR  — two names, one shape
 *
 * So nominality bites only when BOTH sides carry a name and the names differ.
 * Everything else is structural, which is what keeps unannotated helpers usable.
 *
 * Why records differ from variants here, since the first draft had it backwards:
 * a variant alias names a set of TAGS and the tags carry identity (9.2), which
 * is why an alias can be transparent (6.1) without the name becoming decoration.
 * A record has nothing underneath to carry identity. If two names for one shape
 * interchanged, the name would mean nothing and 11.5 would have nothing to
 * protect.
 *
 * The `Vec2 -> Point2` case needs TWO MODULES, because 11.5 makes two
 * same-shaped names in one module illegal on their own. One of them has to
 * arrive as structure, through a function's signature.
 */
class RecordSpecNominalTest : VariantSpecBase() {

    // ========================= 11.6 a named value into a structural position

    /** 11.6 — a named record flows into an inferred position. */
    @Test fun `a named record fits an inferred position`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let get_x = { r } -> r.x
            pub let p: Point2 = { x = 1, y = 2 }
            pub let u = {} -> get_x { r = p }
            $MAIN
            """,
            "`get_x`'s parameter is structural and open — it only read `x`. If a named " +
                "record could not flow here, every unannotated helper would be unusable " +
                "with named records, which is the normal way to write one.",
        )
    }

    /** 11.6 — and into an explicitly open annotated position. */
    @Test fun `a named record fits an open annotated position`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let get_x = { r: { x: Int, ... } } -> r.x
            pub let p: Point2 = { x = 1, y = 2 }
            pub let u = {} -> get_x { r = p }
            $MAIN
            """,
            "Same as above with the openness written rather than inferred. The name is an " +
                "alias here: the position never asked for one.",
        )
    }

    /** 11.6 — and into an anonymous CLOSED position of the same shape. */
    @Test fun `a named record fits an anonymous closed position of its shape`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let takes_shape = { r: { x: Int, y: Int } } -> r.x
            pub let p: Point2 = { x = 1, y = 2 }
            pub let u = {} -> takes_shape { r = p }
            $MAIN
            """,
            "The position is closed but ANONYMOUS, so it demands a shape and not a name. " +
                "This is the case that shows nominality is a demand a position makes, not a " +
                "wall around the value.",
        )
    }

    // ======================== 11.7 two names for one shape do not interchange

    /**
     * 11.7 — the core refusal, and the one that makes the name mean something.
     *
     * Needs two modules: by 11.5, `Point2` and `Vec2` cannot both be named in one
     * module, so `Point2` arrives as structure through `takes_point`'s signature
     * while `Vec2` is declared locally.
     */
    @Test fun `a differently-named record of the same shape is refused`() {
        writeModule(
            "geometry",
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            pub let takes_point = { p: Point2 } -> p.x
            """,
        )
        rejectsMain(
            """
            use std::int
            use geometry::{ takes_point }
            type Vec2 = { x: Int, y: Int }
            pub let v: Vec2 = { x = 1, y = 2 }
            pub let u = {} -> takes_point { p = v }
            $MAIN
            """,
            "T0201",
            "A record type's NAME is its identity, so matching fields are not enough. The " +
                "diagnostic has to say the shapes match out loud, or the reader stares at " +
                "two types that print identically — and the fix cannot be \"annotate it\", " +
                "because by 11.5 importing Point2 while Vec2 is declared is itself an error. " +
                "The only legal program picks one name for the shape.",
        )
    }

    /**
     * 11.7, the mirror — and the test that makes the refusal mean something.
     *
     * Same two modules, same call, with the local name removed. If this were also
     * refused, the rule above would be measuring "cross-module records don't
     * work" rather than "two names don't interchange".
     */
    @Test fun `a bare literal reaches the same position fine`() {
        writeModule(
            "geometry",
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            pub let takes_point = { p: Point2 } -> p.x
            """,
        )
        acceptsMain(
            """
            use std::int
            use geometry::{ takes_point }
            pub let u = {} -> takes_point { p = { x = 1, y = 2 } }
            $MAIN
            """,
            "The literal adopts the name its position demands, even though `Point2` is not " +
                "in scope here (11.3, and B-16's impl-target route). Without this, calling " +
                "another module's function that takes a named record would require importing " +
                "that record's name.",
        )
    }

    // ================================================ 11.10 pass-through is id

    /** 11.10 — a pass-through keeps the name, by row instantiation. */
    @Test fun `a pass-through keeps the name`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let pass = { r } -> r
            pub let takes_point = { q: Point2 } -> q.x
            pub let p: Point2 = { x = 1, y = 2 }
            pub let u = {} -> takes_point { q = pass { r = p } }
            $MAIN
            """,
            "Nothing is constructed, so 11.9 does not apply: ordinary row polymorphism, " +
                "the row variable instantiated at the call with Point2's actual row. Which " +
                "sharpens 11.9 rather than weakening it — an open record type is fine to " +
                "HAVE and to READ, and illegitimate only as the type of something you " +
                "CONSTRUCT.",
        )
    }

    /** 11.10 — and the name is observable in the result, not just accepted. */
    @Test fun `a pass-through result prints the name`() {
        val t = printedType(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            pub let pass = { r } -> r
            pub let p: Point2 = { x = 1, y = 2 }
            pub let q = {} -> pass { r = p }
            $MAIN
            """,
            "q",
        )
        kotlin.test.assertTrue(
            t.contains("Point2"),
            "Acceptance alone is weak evidence here: an anonymous result would ALSO be " +
                "accepted by a Point2 position, via adoption. Only the printed type " +
                "distinguishes \"the name rode through\" from \"it was re-adopted at the " +
                "boundary\" — and the difference shows up as which impl it reaches; got $t",
        )
    }
}
