package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Record specification, §11.3–11.4, 11.14 and 11.16: when a bare literal takes
 * on a named record type, and what the result prints as.
 *
 * The record mirror of variant adoption (1.1), and load-bearing for the same
 * reason: an impl target is nominal, so a literal has to BE its named type to
 * reach one. The stdlib depends on exactly this — a handler writes
 * `{ status = 404, body = "not found" }` and it must become a `StatusBody` to
 * find `impl IntoResponse for StatusBody`.
 *
 * The rule is EXACTNESS: same field names and same field types, no extra and no
 * fewer. That is what makes it decidable with no ranking and no deferral —
 * `{ status, body }` matches `StatusBody` exactly and `Response` only as a
 * subset, and if a subset counted the literal would be ambiguous between them.
 *
 * Adoption is observed through the PRINTED TYPE, because that is the only place
 * it shows: an adopted and an unadopted literal both typecheck, and differ in
 * which name they carry and therefore which impl they reach.
 */
class RecordSpecAdoptionTest : VariantSpecBase() {

    // ======================================================= 11.3 exactness

    /** 11.3 — an exact match on fields and types adopts the name. */
    @Test fun `a literal adopts a named record it matches exactly`() {
        val t = printedType(
            """
            use std::str
            type StatusBody = { status: Int, body: Str }
            pub let h = {} -> { status = 404, body = "not found" }
            $MAIN
            """,
            "h",
        )
        assertTrue(
            t.contains("StatusBody"),
            "The stdlib's own pattern: a handler writes a bare `{ status, body }` and it " +
                "has to become a StatusBody to reach `impl IntoResponse for StatusBody`. " +
                "Without adoption the literal reaches no impl at all; got $t",
        )
    }

    /** 11.3 — an extra field matches nothing exactly, so nothing is adopted. */
    @Test fun `an extra field means no name is adopted`() {
        val t = printedType(
            """
            use std::str
            type StatusBody = { status: Int, body: Str }
            pub let h = {} -> { status = 404, body = "x", note = "dbg" }
            $MAIN
            """,
            "h",
        )
        assertTrue(
            !t.contains("StatusBody"),
            "`no extra` is half of exactness. This literal is a perfectly good anonymous " +
                "closed record — it simply reaches no impl, which is what the author should " +
                "see; got $t",
        )
    }

    /** 11.3 — and a missing field likewise, which is why `StatusBody` exists. */
    @Test fun `a missing field means no name is adopted`() {
        val t = printedType(
            """
            use std::int
            type StatusBody = { status: Int, body: Str }
            pub let h = {} -> { status = 404 }
            $MAIN
            """,
            "h",
        )
        assertTrue(
            !t.contains("StatusBody"),
            "`no fewer` is the other half, and there are no defaults — which is precisely " +
                "why the stdlib declares a SMALLER `StatusBody` beside `Response` rather " +
                "than letting a handler write part of a Response; got $t",
        )
    }

    /** 11.3 — the field TYPES have to match too, not just the names. */
    @Test fun `matching field names with wrong types adopts nothing`() {
        val t = printedType(
            """
            use std::int
            type StatusBody = { status: Int, body: Str }
            pub let h = {} -> { status = 404, body = 7 }
            $MAIN
            """,
            "h",
        )
        assertTrue(
            !t.contains("StatusBody"),
            "Exactness is over fields AND types. `body = 7` has the right field names and " +
                "the wrong type, so it is not that record; got $t",
        )
    }

    /**
     * 11.3 — the case exactness exists for: a subset must NOT count.
     *
     * `{ status, body }` is a subset of `Response`'s five fields. If subsets
     * counted, this literal would match both names and adoption would need a
     * ranking rule — which is the thing exactness removes.
     */
    @Test fun `a subset of a larger record does not adopt it`() {
        val t = printedType(
            """
            use std::str
            type Response = { status: Int, body: Str, url: Str }
            pub let h = {} -> { status = 404, body = "x" }
            $MAIN
            """,
            "h",
        )
        assertTrue(
            !t.contains("Response"),
            "If a subset adopted, `{ status, body }` would match `Response` too and there " +
                "would be two candidates to rank. Exactness is what keeps \"at most one " +
                "candidate\" true; got $t",
        )
    }

    // ================================================ 11.4 open never adopts

    /** 11.4 — an open named record is not an adoption candidate. */
    @Test fun `an open named record is never adopted`() {
        val t = printedType(
            """
            use std::int
            type Loose = { x: Int, ... }
            pub let v = {} -> { x = 1 }
            $MAIN
            """,
            "v",
        )
        assertTrue(
            !t.contains("Loose"),
            "Falls out of exactness rather than being decided: a `...` absorbs anything, " +
                "so there is nothing for \"exact\" to mean against it. Only an annotation " +
                "produces a Loose; got $t",
        )
    }

    /** 11.4, the mirror — an annotation does produce it. */
    @Test fun `an annotation produces the open named record`() {
        accepts(
            """
            use std::int
            type Loose = { x: Int, ... }
            pub let w: Loose = { x = 1 }
            $MAIN
            """,
            "The escape 11.4 names. Pairs with the test above: not adopted, but writable.",
        )
    }

    // ============================= 11.14 / 11.16 generics and what prints

    /** 11.14 — adoption instantiates a generic record rather than demanding a ground match. */
    @Test fun `a generic record is adopted by instantiating it`() {
        val t = printedType(
            """
            use std::int
            type Box 'a = { v: 'a }
            pub let b = {} -> { v = 1 }
            $MAIN
            """,
            "b",
        )
        assertTrue(
            t.contains("Box"),
            "Adoption unifies the literal against the DECLARED shape and instantiates. The " +
                "alternative makes no generic record reachable by any impl, since a " +
                "literal's fields are always concrete; got $t",
        )
    }

    /**
     * 11.16 — and the instantiation is VISIBLE.
     *
     * This reverses a deliberate implementation choice: records erased their type
     * arguments on purpose. Erasure is no longer available, because 11.6 makes the
     * name the identity and 11.15 makes a name claim its shape up to
     * instantiation — both need the arguments present to compare at all.
     */
    @Test fun `a generic record prints its type argument`() {
        val t = printedType(
            """
            use std::int
            type Box 'a = { v: 'a }
            pub let b = {} -> { v = 1 }
            $MAIN
            """,
            "b",
        )
        assertTrue(
            t.contains("Box Int"),
            "`Box Int`, not a bare `Box` and not `{ v: Int }` — the same answer as 9.1 for " +
                "variants, for the same reason: a type prints as the name a reader wrote. " +
                "Erased, Box Int and Box Str either interchange wrongly or the shape has to " +
                "be re-derived at every comparison; got $t",
        )
    }
}
