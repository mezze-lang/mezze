package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Record specification, §11.5 and 11.15: one shape, one named record, per
 * module — and a name claims its shape UP TO INSTANTIATION.
 *
 * The record counterpart of 8.3, and it does the same job: it makes ambiguity
 * UNREACHABLE. With at most one matching name in scope, adoption (11.3) always
 * has at most one candidate, so it needs no ranking and no deferral rule. The
 * declaration rule REPLACES an ambiguity rule rather than adding to one.
 *
 * Its cost is larger than 8.3's, and that is worth stating plainly. A tag name
 * is chosen vocabulary — `Timeout` appearing in three stdlib groups was a
 * decision someone made three times. A field set is structural coincidence, and
 * `{ x: Int, y: Int }` is just two ints: a geometry library and a physics
 * library will collide without either author doing anything questionable. The
 * escape is 8.4's — import one name and reach the other through a function's
 * signature, since structure travels through a signature while a claim comes
 * from importing the name.
 *
 * 11.15 is what keeps the guarantee true through generics. `{ v: 'a }` and
 * `{ v: Int }` are not the SAME shape, so 11.5 alone would admit both — and then
 * `{ v = 1 }` matches two names exactly, `Wrap` directly and `Box` instantiated.
 */
class RecordSpecClaimTest : VariantSpecBase() {

    // ================================= 11.5 one shape, one name, per module

    /** 11.5 — two same-shaped records declared in one module. */
    @Test fun `two same-shaped records may not be declared together`() {
        rejects(
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            pub type Vec2   = { x: Int, y: Int }
            $MAIN
            """,
            "V0701",
            "Refused at the second declaration, before anything of that shape is written — " +
                "the clash exists whether or not it ever is. Without this, a bare " +
                "`{ x = 1, y = 2 }` matches two names exactly and adoption would need a " +
                "ranking rule, which exactness exists to avoid.",
        )
    }

    /** 11.5, the mirror — different shapes coexist freely. */
    @Test fun `records of different shapes coexist`() {
        accepts(
            """
            use std::int
            use std::str
            pub type Point2 = { x: Int, y: Int }
            pub type Named  = { x: Int, label: Str }
            pub type Deep   = { x: Int, y: Int, z: Int }
            $MAIN
            """,
            "The rule is about one SHAPE, not about one record per module. `Named` differs " +
                "by a field name and `Deep` by a field count, so neither competes with " +
                "Point2 for a literal.",
        )
    }

    /** 11.5, the mirror — and one name alone is obviously fine. */
    @Test fun `one name for a shape is fine`() {
        accepts(
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            pub let p: Point2 = { x = 1, y = 2 }
            $MAIN
            """,
            "The control. If this failed, the rejection above would be measuring something " +
                "other than the clash.",
        )
    }

    /** 11.5 — identically by import, however the name arrived. */
    @Test fun `importing two same-shaped records is refused`() {
        writeModule(
            "geometry",
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            """,
        )
        writeModule(
            "physics",
            """
            use std::int
            pub type Vec2 = { x: Int, y: Int }
            """,
        )
        rejectsMain(
            """
            use geometry::{ Point2 }
            use physics::{ Vec2 }
            $MAIN
            """,
            "V0701",
            "However the name arrived. Note the program never writes a record of that " +
                "shape — the clash is in holding both names, exactly as it is for two " +
                "groups claiming one tag (8.3).",
        )
    }

    /** 11.5 — the escape 8.4 names: reach the other through a signature. */
    @Test fun `one name imported and one reached through a signature is fine`() {
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
            pub type Vec2 = { x: Int, y: Int }
            pub let v: Vec2 = { x = 1, y = 2 }
            $MAIN
            """,
            "The escape, and the reason the rule is livable: a type inside an imported " +
                "function's signature travels as STRUCTURE, while a claim comes from " +
                "importing the NAME. Declaring Vec2 here is legal because no Point2 name is " +
                "in scope to clash with.",
        )
    }

    // ============================ 11.15 claims extend up to instantiation

    /** 11.15 — two generic records of one shape. */
    @Test fun `two same-shaped generic records may not be declared together`() {
        rejects(
            """
            pub type Box  'a = { v: 'a }
            pub type Cell 'a = { v: 'a }
            $MAIN
            """,
            "V0701",
            "The Point2/Vec2 case with a type parameter. Nothing about genericity changes " +
                "the clash.",
        )
    }

    /**
     * 11.15 — and the subtler one: a monomorphic record whose shape is an
     * INSTANCE of a generic one.
     */
    @Test fun `a monomorphic instance of a generic shape is refused`() {
        rejects(
            """
            use std::int
            pub type Box 'a = { v: 'a }
            pub type Wrap    = { v: Int }
            $MAIN
            """,
            "V0701",
            "`{ v: 'a }` and `{ v: Int }` are not the SAME shape, so 11.5 alone admits " +
                "both — and then `{ v = 1 }` matches Wrap directly AND Box instantiated, so " +
                "the ambiguity 11.5 exists to make unreachable comes back through the " +
                "generic door. Two names clash when either shape is an INSTANCE of the " +
                "other.\n\nThis is the longest reach in the section: declaring " +
                "`type Wrapper 'a = { value: 'a }` forbids any `{ value: T }` record in the " +
                "module, for every T. The rejected alternative was to let the monomorphic " +
                "one win on specificity — which is a ranking.",
        )
    }

    /**
     * PROBE: does the shape claim catch the EMPTY record?
     *
     * `recordShapeKey` builds its key from the field list, so `{}` keys on the
     * empty string and two unit types would collide. The validator already
     * special-cases empty records elsewhere, as ability unit-implementers.
     */
    @Test fun `two empty record types`() {
        accepts(
            """
            pub type UnitA = {}
            pub type UnitB = {}
            $MAIN
            """,
            "PROBE -- if this is refused, 11.5 gives a module ONE unit type, which " +
                "reads like an accident rather than a decision.",
        )
    }

    /** 11.15, the mirror -- a shape that is NOT an instance coexists. */
    @Test fun `a shape that is not an instance coexists with a generic record`() {
        accepts(
            """
            use std::int
            use std::str
            pub type Box 'a = { v: 'a }
            pub type Pair  = { v: Int, w: Int }
            pub type Tag   = { name: Str }
            $MAIN
            """,
            "`Pair` has an extra field and `Tag` a different field name, so neither is an " +
                "instance of `{ v: 'a }`. Without this the rule would read as \"a generic " +
                "record forbids everything\", which is not what instantiation means.",
        )
    }
}
