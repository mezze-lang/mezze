package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Record specification, §11.19: what a record derives, and what it does not.
 *
 * A record is a PRODUCT, so the question "does it derive X" reduces to "do all
 * its fields", and that is how the rule is written: a record satisfies an
 * ability structurally when every field does. Nothing is keyed on the record's
 * NAME here, which is the one place the name does NOT carry identity -- an
 * anonymous literal derives exactly what the same fields derive under a name.
 *
 * Two shapes get nothing, for different reasons:
 *
 *   - `Ord` for ANY composite, because the runtime comparison nodes specialise
 *     long/double/String/Frac and THROW on anything else. Deriving it would
 *     typecheck a program that crashes at `a < b`, which is worse than
 *     refusing it.
 *   - A `newtype`, which derives only `Inspect` and `Hash` -- it is a distinct
 *     type from what it wraps, and is erased to its payload at runtime, so a
 *     derived comparison would silently be the WRAPPED type's.
 *
 * `Hash` and `Inspect` are universal: both are total for every shape, so
 * neither can typecheck something that then fails.
 */
class RecordSpecDeriveTest : VariantSpecBase() {

    // ================================= 11.19 Eq is structural, field by field

    /** 11.19 -- a closed named record compares with `==`. */
    @Test fun `a named record derives Eq from its fields`() {
        accepts(
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            pub let same: { a: Point2, b: Point2 } -> Bool = { a, b } -> a == b
            $MAIN
            """,
            "Every field is an Int and Int has Eq, so the product does. Nothing was " +
                "written to say so -- the rule asks the FIELDS, which is what makes a " +
                "record's equality mean the same thing wherever it is declared.",
        )
    }

    /**
     * 11.19 -- and an ANONYMOUS record compares too.
     *
     * The one place a record's name does not matter. Eq is decided on the TYPE's
     * fields, so an unnamed `{ x: Int, y: Int }` parameter has it on exactly the
     * same terms as `Point2`. If this needed a name, every structural position
     * (11.1's inferred parameter) would silently lose equality.
     */
    @Test fun `an anonymous record derives Eq from its fields`() {
        accepts(
            """
            use std::int
            pub let same: { a: { x: Int, y: Int }, b: { x: Int, y: Int } } -> Bool =
              { a, b } -> a == b
            $MAIN
            """,
            "Structural, so an anonymous record is not second-class for equality.",
        )
    }

    /** 11.19 -- a field WITHOUT Eq takes the record's Eq with it. */
    @Test fun `a record with a function field does not derive Eq`() {
        rejects(
            """
            use std::int
            pub type Handler = { id: Int, run: { n: Int } -> Int }
            pub let same: { a: Handler, b: Handler } -> Bool = { a, b } -> a == b
            $MAIN
            """,
            "V0402",
            "A function has no equality, so the product that contains one has none " +
                "either. The alternative -- comparing the fields that CAN be compared -- " +
                "would make `==` mean something different per record, and silently: two " +
                "handlers with different behaviour would read as equal.",
        )
    }

    /**
     * 11.19 -- `Ord` is NOT derived for a record, and this is deliberate.
     *
     * Unlike Eq, whose comparison node has an `Objects.equals` fallback that
     * reaches a record, the runtime `Direct{LessThan,...}Node` only specialise
     * long/double/String/Frac. A derived Ord would typecheck a program that
     * throws at `a < b` -- so the refusal is the honest answer, and the author
     * writes the impl when it wants one.
     */
    @Test fun `a record does not derive Ord`() {
        rejects(
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            pub let before: { a: Point2, b: Point2 } -> Bool = { a, b } -> a < b
            $MAIN
            """,
            "V0402",
            "There is no field order to pick anyway: `{ x, y }` could sort by x then y " +
                "or by y then x, and a derived answer would be a guess that compiles.",
        )
    }

    /** 11.19 -- `Hash` IS derived, so a record is a usable key with no boilerplate. */
    @Test fun `a record derives Hash`() {
        accepts(
            """
            use std::int
            pub type Point2 = { x: Int, y: Int }
            pub let key: { p: Point2 } -> Int = { p } -> p.hash {}
            $MAIN
            """,
            "A structural hash is TOTAL -- it is already what every hash container " +
                "computes for its keys -- so unlike Ord there is no shape it cannot " +
                "answer for, and a record is a usable `Map` key with no boilerplate. " +
                "Safe only because Eq is structural too: the derived hash agrees with " +
                "the derived equality by construction.",
        )
    }

    // ================================= 11.19 openness and derivation

    /**
     * 11.19 -- an OPEN record does not derive Eq.
     *
     * The tail is the whole reason. Two values of `{ x: Int, ... }` may carry
     * different extra fields, and a comparison has only two options: ignore the
     * tail, which makes `==` lie about values that genuinely differ, or compare
     * it, which is not a function of the declared type at all. Refusing is the
     * only answer that does not depend on what a caller happened to pass.
     *
     * This is 11.9's rule reaching derivation: openness is CARRIED, so what is
     * unknown stays unknown rather than being closed over.
     */
    @Test fun `an open record does not derive Eq`() {
        rejects(
            """
            use std::int
            pub type Loose = { x: Int, ... }
            pub let same: { a: Loose, b: Loose } -> Bool = { a, b } -> a == b
            $MAIN
            """,
            "V0402",
            "PROBE as much as a rule: the derivability test asks RecordType.fields " +
                "without consulting rowVar, while the impl GENERATOR refuses an open " +
                "record outright. If those two disagree the constraint is satisfied with " +
                "no impl to satisfy it, which surfaces as a missing dictionary rather " +
                "than as this rule.",
        )
    }

    /**
     * 11.19, KNOWN GAP -- an ANONYMOUS open record still derives Eq, and should
     * not. Recorded, not asserted as correct.
     *
     * MEASURED. By the time derivability is asked, an open record's row is
     * already gone:
     *
     *     named      fields=[x] row=null name=Loose  -> open=true
     *     anonymous  fields=[x] row=null name=null   -> open=false
     *
     * So the named case above is caught by the DECLARATION lookup, not by the
     * row -- and an anonymous shape has no declaration, so nothing is left to
     * ask. The openness is live earlier (field resolution rejects `r.y` on the
     * same type) and lost in between.
     *
     * The cause is a documented SEAM, not an oversight:
     * `closeGeneratedRowVars` closes record and variant tails at every boundary
     * that produces a STORED or DISPLAYED type, and
     * `ConstraintGenerator.addAbilityConstraintsToFunctionType` -- the
     * ability-constraint args -- is explicitly one of them, so signatures and
     * hover show `{ a: T }` rather than `{ a: T, ... }`. Derivability then reads
     * a type that has been through a display-oriented close. Fixing it means
     * asking the ability question of the PRE-close type, which is a change to
     * that seam rather than to this rule.
     *
     * The gap is narrow in practice: an open record is written to be shared, so
     * it is nearly always declared with a name, and the named case is closed.
     */
    @Test fun `an anonymous open record derives Eq -- KNOWN GAP`() {
        accepts(
            """
            use std::int
            pub let same: { a: { x: Int, ... }, b: { x: Int, ... } } -> Bool =
              { a, b } -> a == b
            $MAIN
            """,
            "Records what the compiler DOES, so the gap cannot be forgotten and the " +
                "day the seam is fixed this test fails and says so. It should be a " +
                "refusal for the same reason as the named case: the hidden tail is not " +
                "observable at the type, so an equality that reads it answers from " +
                "information the type does not expose.",
        )
    }

    /** 11.19 -- an open record still derives the TOTAL abilities. */
    @Test fun `an open record still derives Inspect`() {
        accepts(
            """
            use std::int
            pub type Loose = { x: Int, ... }
            pub let show: { r: Loose } -> Str = { r } -> r.to_dbg {}
            $MAIN
            """,
            "Inspect is total for every shape, extra fields included -- it renders what " +
                "is there rather than deciding anything about it. So openness costs a " +
                "record its Eq and nothing else.",
        )
    }
}
