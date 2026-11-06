package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Record specification, §11.1–11.2 and 11.11–11.12: where open and closed come
 * from, and what each admits.
 *
 * The model's other half. A record is a PRODUCT, so a position READS its fields
 * and a value must carry at least what is read — the mirror of the group rule,
 * and the same one question as §03: ask who receives.
 *
 * The division this file pins: **writing the fields is a claim about which
 * fields there are.** An annotation claims them, a destructuring parameter list
 * claims them, and `...` is the opt-in that says "and more". Inference claims
 * nothing — it knows only what the body read — so it produces an OPEN record and
 * the caller sees that in the signature.
 *
 * Like the variant file, every refusal here ships with the acceptance that
 * justifies it. "Extra field is refused" is weak on its own: an implementation
 * that compares field sets rigidly refuses extras AND refuses the open case, so
 * it passes every rejection in this file while being wrong. The `admits` tests
 * are what tell the two apart.
 */
class RecordSpecClosedTest : VariantSpecBase() {

    // ============================================ 11.1 annotation is a claim

    /** 11.1 — an annotated closed record refuses an extra field. */
    @Test fun `an annotated closed record refuses an extra field`() {
        rejects(
            """
            use std::int
            pub let f = { r: { x: Int } } -> r.x
            pub let u = {} -> f { r = { x = 1, y = 2 } }
            $MAIN
            """,
            "T0201",
            "Writing `{ x: Int }` claims the field set, so `y` contradicts it. The " +
                "alternative — width subtyping, on the grounds that a reader of `x` cannot " +
                "be hurt by a `y` — was rejected because it leaves no way to write " +
                "\"exactly these fields\" at all, and `...` already exists to say \"and more\".",
        )
    }

    /**
     * 11.1, the mirror — and the most load-bearing acceptance in this file.
     *
     * Without this, an implementation that simply demands equal field sets looks
     * healthy: it refuses the extra field above for the wrong reason, and refuses
     * this too. Only an acceptance here shows that closedness is what gates.
     */
    @Test fun `inference claims nothing, so an extra field is fine`() {
        accepts(
            """
            use std::int
            pub let g = { r } -> r.x
            pub let v = {} -> g { r = { x = 1, y = 2 } }
            $MAIN
            """,
            "`g` never claimed to know `r`'s fields — it only read `x` — so its parameter " +
                "is OPEN and the extra `y` is admitted. This is the same program as the " +
                "test above with the annotation removed, which is the whole point: the " +
                "annotation is the claim.",
        )
    }

    /** 11.1 — and the open annotation admits it explicitly. */
    @Test fun `an open annotation admits an extra field`() {
        accepts(
            """
            use std::int
            pub let f = { r: { x: Int, ... } } -> r.x
            pub let u = {} -> f { r = { x = 1, y = 2 } }
            $MAIN
            """,
            "`...` is the opt-in. Same call as the first test, accepted because the " +
                "signature now says \"and more\".",
        )
    }

    // ======================================== 11.2 open relaxes only the top

    /** 11.2 — a declared field is required whether the record is open or closed. */
    @Test fun `an open record still requires every declared field`() {
        rejects(
            """
            use std::int
            pub let f = { r: { a: Int, b: Int, ... } } -> r.a
            pub let u = {} -> f { r = { a = 1 } }
            $MAIN
            """,
            "T0201",
            "The `...` relaxes the UPPER bound, never the lower one. There are no " +
                "defaults, which is why the stdlib declares a smaller `StatusBody` rather " +
                "than letting a handler write part of a `Response`.",
        )
    }

    /** 11.2, the mirror — extras really are admitted by the same signature. */
    @Test fun `an open record admits extras beyond its declared fields`() {
        accepts(
            """
            use std::int
            pub let f = { r: { a: Int, b: Int, ... } } -> r.a
            pub let u = {} -> f { r = { a = 1, b = 2, c = 3 } }
            $MAIN
            """,
            "Both declared fields present, `c` extra. Pairs with the rejection above: " +
                "one signature, lower bound enforced, upper bound open.",
        )
    }

    // ============================== 11.11 a destructuring parameter is closed

    /**
     * 11.11 — the most consequential rule in the section by volume, since every
     * function in the language destructures a record in its parameter list.
     */
    @Test fun `a destructuring parameter list refuses an extra argument`() {
        rejects(
            """
            use std::int
            pub let add = { a, b } -> a + b
            pub let u = {} -> add { a = 1, b = 2, c = 3 }
            $MAIN
            """,
            "T0203",
            "Writing `a` and `b` claims the field set exactly as an annotation does. At a " +
                "CALL an extra field is almost always a typo or a leftover rather than an " +
                "intention, and this is the most common record in the language. The code is T0203_RECORD_EXTRA_FIELDS rather than the generic T0201 — the site has its own.",
        )
    }

    /** 11.11 — and `...` in a parameter list is the opt-in. */
    @Test fun `a destructuring parameter list may opt into extras`() {
        accepts(
            """
            use std::int
            pub let add2 = { a, b, ... } -> a + b
            pub let v = {} -> add2 { a = 1, b = 2, c = 3 }
            $MAIN
            """,
            "This unifies the two jobs `...` appeared to have: in `LetSyntax { value, ... }` " +
                "it means \"ignore the rest\", and ignoring the rest is precisely what " +
                "accepting extras IS on the reading side. One meaning, two sides.",
        )
    }

    // ================================= 11.12 a pattern mentions all, or says ...

    /** 11.12 — a record pattern that leaves a field unmentioned is refused. */
    @Test fun `a record pattern must mention every field`() {
        rejects(
            """
            use std::int
            pub let f = { r: { x: Int, y: Int } } -> match r is
              { x = 1 } -> 10
              { x = _, y = _ } -> 20
            $MAIN
            """,
            "V0602",
            "Follows from closedness rather than being its own decision, and the AST " +
                "already carries the distinction: `RecordPat` has an explicit `is_open` " +
                "flag, and the diagnostic is purpose-built: V0602_NON_EXHAUSTIVE_RECORD_PATTERN. The stdlib writes it this way throughout — `syntax.mz`'s walker " +
                "always says `...` where it ignores fields.",
        )
    }

    /** 11.12, the mirror — `...` makes the same pattern legal. */
    @Test fun `a record pattern may ignore the rest with dots`() {
        accepts(
            """
            use std::int
            pub let f = { r: { x: Int, y: Int } } -> match r is
              { x = 1, ... } -> 10
              { x = _, ... } -> 20
            $MAIN
            """,
            "Same two arms, with the ignoring made explicit. Pairs with the rejection " +
                "above — the rule is about saying so, not about which fields you read.",
        )
    }
}
