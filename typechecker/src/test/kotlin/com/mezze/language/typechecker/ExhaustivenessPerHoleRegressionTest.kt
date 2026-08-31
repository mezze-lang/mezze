package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * S6.3 (#368): one regression test per exhaustiveness hole that was patched
 * one-off THIS Stage-6 session, asserting the EXACT verdict (rejected-as-non-
 * exhaustive vs accepted) — not merely "compiles". These pin the behaviour of
 * the single coverage algorithm in [MatchValidator.validateParsedExhaustiveness]
 * so a future refactor cannot silently re-open a hole.
 *
 * Holes covered here (the ones lacking a ticket-tagged verdict test before this
 * ticket): #210 (Bool literals treated as variant constructors — both True and
 * False, or a catch-all), #259 (nested-payload cartesian diagonal), #270
 * (partially-overlapping or-pattern arm stays reachable). #218 / #221 / #332
 * already have dedicated files (RecordScrutineeExhaustivenessTest,
 * NestedPayloadPrimitiveExhaustivenessTest, NonParamScrutineeExhaustivenessTest);
 * a pointer test here asserts their verdict too so the whole hole-set reads in
 * one place.
 */
class ExhaustivenessPerHoleRegressionTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    /** Returns the error message if the module is rejected, else null. */
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── #210: Bool literals ARE variant constructors ─────────────────────
    // A Bool match is exhaustive iff both True and False (or a catch-all) are
    // covered. The literal True/False patterns feed the SAME variant-coverage
    // path as `Red`/`Blue` (Bool folds to VariantType{True,False}). Note a
    // top-level Bool scrutinee emits a TypedIf, so this exercises the Bool
    // literals inside a record field where a TypedMatch is emitted.

    @Test fun `#210 record Bool field covered by only True literal is rejected naming False`() {
        val m = err(
            """
            let f: { r: { b: Bool } } -> Int = { r } -> match r is
              { b = True } -> 1
            """
        )
        assertNotNull(m, "Bool field missing the False literal must be rejected")
        assertTrue(m!!.contains("False"), "error must name the missing False case; got: $m")
    }

    @Test fun `#210 record Bool field covering True and False is accepted`() {
        assertNull(
            err(
                """
                let f: { r: { b: Bool } } -> Int = { r } -> match r is
                  { b = True } -> 1
                  { b = False } -> 2
                """
            ),
            "both Bool literals present ⇒ exhaustive"
        )
    }

    // ── #259: nested-payload cartesian product (diagonal-only is a hole) ──
    // `Wrap { a: Bool, b: Bool }` matched only on the diagonal True/True and
    // False/False leaves True/False and False/True uncovered. The nested path
    // reuses the SAME product routine as top-level records.

    @Test fun `#259 nested two-Bool payload diagonal-only is rejected`() {
        val m = err(
            """
            type W = | Wrap { a: Bool, b: Bool } |
            let f: { w: W } -> Int = { w } -> match w is
              Wrap { a = True, b = True } -> 1
              Wrap { a = False, b = False } -> 2
            """
        )
        assertNotNull(m, "diagonal-only over two Bool fields misses the off-diagonal combos")
    }

    @Test fun `#259 nested two-Bool payload full product is accepted`() {
        assertNull(
            err(
                """
                type W = | Wrap { a: Bool, b: Bool } |
                let f: { w: W } -> Int = { w } -> match w is
                  Wrap { a = True, b = True } -> 1
                  Wrap { a = True, b = False } -> 2
                  Wrap { a = False, b = True } -> 3
                  Wrap { a = False, b = False } -> 4
                """
            ),
            "the full 2x2 product is exhaustive"
        )
    }

    // ── #270: partially-overlapping or-pattern arm stays reachable ───────
    // `G | B` after `R | G` re-covers G but introduces the fresh alternative B,
    // so the arm is NOT unreachable. Reporting per-alternative wrongly flagged
    // it. The whole match (R|G then G|B) covers R,G,B ⇒ exhaustive & accepted.

    @Test fun `#270 partially overlapping or-arm is not flagged unreachable`() {
        assertNull(
            err(
                """
                type Color = | Red, Green, Blue |
                let f: { c: Color } -> Int = { c } -> match c is
                  Red | Green -> 1
                  Green | Blue -> 2
                """
            ),
            "the second arm adds Blue, so it is reachable; and R,G,B are all covered"
        )
    }

    @Test fun `#270 fully overlapping or-arm is flagged unreachable`() {
        val m = err(
            """
            type Color = | Red, Green, Blue |
            let f: { c: Color } -> Int = { c } -> match c is
              Red | Green | Blue -> 1
              Red | Green -> 2
            """
        )
        assertNotNull(m, "the second arm's alternatives are all already covered ⇒ unreachable")
    }

    // ── #218 pointer: record-scrutinee variant field ─────────────────────
    @Test fun `#218 record scrutinee variant field missing a constructor is rejected`() {
        val m = err(
            """
            type Color = | Red, Green, Blue |
            let f: { r: { c: Color } } -> Int = { r } -> match r is
              { c = Red } -> 1
              { c = Green } -> 2
            """
        )
        assertNotNull(m, "missing { c = Blue }")
        assertTrue(m!!.contains("Blue"), "error names Blue; got: $m")
    }

    // ── #221 pointer: nested primitive payload needs a catch-all ─────────
    @Test fun `#221 nested Int-literal payload without a catch-all is rejected`() {
        assertNotNull(
            err(
                """
                type W = | Wrap { n: Int } |
                let f: { w: W } -> Int = { w } -> match w is
                  Wrap { n = 0 } -> 1
                  Wrap { n = 1 } -> 2
                """
            ),
            "an Int payload field cannot be exhausted by listing values"
        )
    }

    // ── #332 pointer: non-param scrutinee via decided node ───────────────
    @Test fun `#332 non-param scrutinee variant match missing a constructor is rejected`() {
        val m = err(
            """
            type Color = | Red, Green, Blue |
            let mk: {} -> Color = {} -> Red
            let f: {} -> Int = {} -> match mk {} is
              Red -> 1
              Green -> 2
            """
        )
        assertNotNull(m, "a non-parameter scrutinee (call result) is still exhaustiveness-checked")
        assertTrue(m!!.contains("Blue"), "error names Blue; got: $m")
    }
}
