package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.FixHint
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #218: a `match` whose SCRUTINEE is a record with variant/Bool fields must be
 * exhaustiveness-checked. `match r is { c = Red } -> …` on `r: { c: Color }`
 * (Color = Red|Green|Blue) used to type-check and then hit no arm at runtime for
 * `{ c = Green }`, while the equivalent top-level `match c is Red -> …` was
 * correctly rejected. The dispatcher only expanded VariantType/primitive/seq
 * scrutinees; a RecordType fell through to Success.
 *
 * The rule (conservative, sound — never accepts a non-exhaustive record match):
 *  - a catch-all arm (`_`/var/fully-irrefutable record) makes it total;
 *  - otherwise, the ONE finite (closed-variant or Bool) field must be fully
 *    covered and every opaque field bound irrefutably in every arm;
 *  - with ≥2 finite fields (the unsound product case) a catch-all is required.
 * Bool is the `True | False` variant, so a `{ b: Bool }` field needs both.
 */
class RecordScrutineeExhaustivenessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    private fun caught(src: String): TypeCheckError? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e }

    // ── rejected: genuinely non-exhaustive record matches ────────────────

    @Test fun `record with a single variant field missing a constructor is rejected`() {
        val m = err(
            """
            type Color = | Red, Green, Blue |
            let f: { r: { c: Color } } -> Int = { r } -> match r is
              { c = Red } -> 1
              { c = Green } -> 2
            """
        )
        assertNotNull(m, "missing `{ c = Blue }` must be rejected")
        assertTrue(m!!.contains("Blue"), "the error should name the missing Blue case; got: $m")
    }

    @Test fun `record with a Bool field matched on only True is rejected`() {
        val m = err(
            """
            let f: { r: { b: Bool } } -> Int = { r } -> match r is
              { b = True } -> 1
            """
        )
        assertNotNull(m, "a Bool field covered only by True must be rejected (missing False)")
        assertTrue(m!!.contains("False"), "the error should name the missing False case; got: $m")
    }

    @Test fun `record with an Int field and no catch-all is rejected`() {
        assertNotNull(
            err(
                """
                let f: { r: { n: Int } } -> Int = { r } -> match r is
                  { n = 0 } -> 1
                """
            ),
            "an Int field pinned to a literal with no catch-all is non-exhaustive",
        )
    }

    @Test fun `two variant fields covering only one combination is rejected, naming the rest`() {
        // Product coverage: `{a=Red,b=Red}` covers 1 of the 4 (2×2) combinations.
        val m = err(
            """
            type Color = | Red, Green |
            let f: { r: { a: Color, b: Color } } -> Int = { r } -> match r is
              { a = Red, b = Red } -> 1
            """
        )
        assertNotNull(m, "a match covering 1 of 4 field combinations is not exhaustive")
        assertTrue(m!!.contains("{ a = Green, b = Green }"),
            "should name an uncovered combination; got: $m")
    }

    @Test fun `two variant fields covering the full product is accepted`() {
        // All 2×2 combinations present ⇒ exhaustive WITHOUT a catch-all.
        assertNull(
            err(
                """
                type Color = | Red, Green |
                let f: { r: { a: Color, b: Color } } -> Int = { r } -> match r is
                  { a = Red, b = Red } -> 1
                  { a = Red, b = Green } -> 2
                  { a = Green, b = Red } -> 3
                  { a = Green, b = Green } -> 4
                """
            )
        )
    }

    @Test fun `two variant fields covered via a wildcard on one axis is accepted`() {
        // `{a=Red, b=_}` + `{a=Green, b=_}` covers every b for each a ⇒ total.
        assertNull(
            err(
                """
                type Color = | Red, Green |
                let f: { r: { a: Color, b: Color } } -> Int = { r } -> match r is
                  { a = Red, b = _ } -> 1
                  { a = Green, b = _ } -> 2
                """
            )
        )
    }

    @Test fun `a Bool field crossed with a variant field is checked as a product`() {
        assertNull(
            err(
                """
                type Color = | Red, Green |
                let f: { r: { flag: Bool, c: Color } } -> Int = { r } -> match r is
                  { flag = True, c = Red } -> 1
                  { flag = True, c = Green } -> 2
                  { flag = False, c = Red } -> 3
                  { flag = False, c = Green } -> 4
                """
            )
        )
    }

    // ── accepted: total record matches (must not regress) ────────────────

    @Test fun `record with a single variant field covering every constructor is accepted`() {
        assertNull(
            err(
                """
                type Color = | Red, Green, Blue |
                let f: { r: { c: Color } } -> Int = { r } -> match r is
                  { c = Red } -> 1
                  { c = Green } -> 2
                  { c = Blue } -> 3
                """
            )
        )
    }

    @Test fun `record with a Bool field covering both True and False is accepted`() {
        assertNull(
            err(
                """
                let f: { r: { b: Bool } } -> Int = { r } -> match r is
                  { b = True } -> 1
                  { b = False } -> 0
                """
            )
        )
    }

    @Test fun `record match with a wildcard field pattern is accepted`() {
        assertNull(
            err(
                """
                type Color = | Red, Green, Blue |
                let f: { r: { c: Color } } -> Int = { r } -> match r is
                  { c = Red } -> 1
                  { c = _ } -> 2
                """
            )
        )
    }

    @Test fun `record match with an irrefutable binding arm is accepted`() {
        // `{ c }` binds the whole field irrefutably — a catch-all.
        assertNull(
            err(
                """
                type Color = | Red, Green, Blue |
                let f: { r: { c: Color } } -> Int = { r } -> match r is
                  { c = Red } -> 1
                  { c } -> 2
                """
            )
        )
    }

    @Test fun `record with an Int field and a catch-all is accepted`() {
        assertNull(
            err(
                """
                let f: { r: { n: Int } } -> Int = { r } -> match r is
                  { n = 0 } -> 1
                  _ -> 2
                """
            )
        )
    }

    @Test fun `record with two variant fields plus a catch-all is accepted`() {
        assertNull(
            err(
                """
                type Color = | Red, Green |
                let f: { r: { a: Color, b: Color } } -> Int = { r } -> match r is
                  { a = Red, b = Red } -> 1
                  _ -> 2
                """
            )
        )
    }

    @Test fun `a guarded field arm does not count toward coverage`() {
        // The only False-covering arm is guarded → still non-exhaustive.
        assertNotNull(
            err(
                """
                let f: { r: { b: Bool } } -> Int = { r } -> match r is
                  { b = True } -> 1
                  { b = False } if False -> 0
                """
            ),
            "a guarded arm can fail at runtime, so it can't be relied on for coverage",
        )
    }

    // ── normalization: advice-only errors offer no "fill arms" fix ───────

    @Test fun `an advice-only record error offers no fill-match-arms fix`() {
        // A non-enumerable field (Int) has no finite missing set to insert, so this
        // is guidance ("add a `_`") — a NonExhaustiveRecordMatch whose detail lives
        // outside missingConstructors and offers no FillMatchArms quick-fix (it can
        // never leak a bogus arm). Contrast the product case above, which DOES name
        // concrete combinations and keeps the fill-arms fix.
        val e = caught(
            """
            let f: { r: { n: Int } } -> Int = { r } -> match r is
              { n = 0 } -> 1
            """
        )
        assertNotNull(e)
        assertTrue(
            e!!.fixHints.none { it is FixHint.FillMatchArms },
            "a match with no enumerable missing cases must not offer a fill-arms fix",
        )
    }
}
