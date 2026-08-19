package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * HOLE A regression: a nested CONSTRUCTOR or LITERAL sub-pattern inside a record
 * field or variant payload must be TESTED at runtime, not just extracted.
 *
 * A flat `PatternIR.Record`/`PatternIR.Variant` only binds its fields; it never
 * tested a nested tag/literal. So `match r is { c = Red } -> …` selected its arm
 * on the record's SHAPE alone — every value hit the FIRST arm regardless of the
 * nested constructor. `classify { r = { c = Blue } }` returned 1 (the `Red` arm)
 * instead of 3. Same for a nested variant in a variant payload
 * (`Wrap { inner = A {n} }`) and a nested literal (`{ n = 0 }`).
 *
 * The fix synthesizes a boolean test for the nested sub-patterns and folds it
 * into the arm's guard; a false guard falls through to the next arm. See
 * TypedASTCompiler.buildNestedTests.
 */
class NestedPatternDispatchTest {

    // ── record scrutinee with a nested variant field ─────────────────────

    private val classifyColor = """
        type Color = | Red, Green, Blue |
        let classify: { r: { c: Color } } -> Int = { r } -> match r is
          { c = Red } -> 1
          { c = Green } -> 2
          { c = Blue } -> 3
    """.trimIndent()

    @Test fun `record nested-variant field selects the matching arm - Red`() {
        assertEquals(1L, MezzeTestHelper.run("$classifyColor\nclassify { r = { c = Red } }"))
    }

    @Test fun `record nested-variant field selects the matching arm - Green`() {
        assertEquals(2L, MezzeTestHelper.run("$classifyColor\nclassify { r = { c = Green } }"))
    }

    @Test fun `record nested-variant field selects the matching arm - Blue`() {
        // The bug: this returned 1 (first arm) instead of 3.
        assertEquals(3L, MezzeTestHelper.run("$classifyColor\nclassify { r = { c = Blue } }"))
    }

    // ── two nested-variant fields (product) select correctly ─────────────

    @Test fun `two nested-variant fields dispatch on the full pair`() {
        val src = """
            type Color = | Red, Green |
            let f: { r: { a: Color, b: Color } } -> Int = { r } -> match r is
              { a = Red, b = Red } -> 1
              { a = Red, b = Green } -> 2
              { a = Green, b = Red } -> 3
              { a = Green, b = Green } -> 4
        """.trimIndent()
        assertEquals(1L, MezzeTestHelper.run("$src\nf { r = { a = Red, b = Red } }"))
        assertEquals(2L, MezzeTestHelper.run("$src\nf { r = { a = Red, b = Green } }"))
        assertEquals(3L, MezzeTestHelper.run("$src\nf { r = { a = Green, b = Red } }"))
        assertEquals(4L, MezzeTestHelper.run("$src\nf { r = { a = Green, b = Green } }"))
    }

    // ── nested variant inside a variant payload ──────────────────────────

    private val unwrap = """
        type Inner = | A { n: Int }, B { s: Str } |
        type Wrap = | Wrap { inner: Inner } |
        let f: { w: Wrap } -> Str = { w } -> match w is
          Wrap { inner = A { n } } -> "got-A"
          Wrap { inner = B { s } } -> "got-B"
    """.trimIndent()

    @Test fun `variant-payload nested variant selects A`() {
        assertEquals("got-A", MezzeTestHelper.run("$unwrap\nf { w = Wrap { inner = A { n = 7 } } }"))
    }

    @Test fun `variant-payload nested variant selects B`() {
        // The bug: this returned "got-A".
        assertEquals("got-B", MezzeTestHelper.run("$unwrap\nf { w = Wrap { inner = B { s = \"x\" } } }"))
    }

    @Test fun `nested-variant payload binding is still available in the body`() {
        // The synthesized tag test must not clobber the payload binding `n`.
        val src = """
            type Inner = | A { n: Int }, B { s: Str } |
            type Wrap = | Wrap { inner: Inner } |
            let f: { w: Wrap } -> Int = { w } -> match w is
              Wrap { inner = A { n } } -> n
              Wrap { inner = B { s } } -> 0
            f { w = Wrap { inner = A { n = 42 } } }
        """.trimIndent()
        assertEquals(42L, MezzeTestHelper.run(src))
    }

    // ── nested literal in a record field ─────────────────────────────────

    private val classifyN = """
        let g: { r: { n: Int } } -> Str = { r } -> match r is
          { n = 0 } -> "zero"
          { n = 1 } -> "one"
          _ -> "other"
    """.trimIndent()

    @Test fun `record nested-literal field selects the matching arm`() {
        assertEquals("zero", MezzeTestHelper.run("$classifyN\ng { r = { n = 0 } }"))
        // The bug: this returned "zero" (first arm).
        assertEquals("one", MezzeTestHelper.run("$classifyN\ng { r = { n = 1 } }"))
        assertEquals("other", MezzeTestHelper.run("$classifyN\ng { r = { n = 9 } }"))
    }

    // ── a nested test combined with a user guard ─────────────────────────

    @Test fun `a nested variant test composes with a user guard`() {
        // The `Red` arm only applies when the guard holds; otherwise fall through.
        val src = """
            type Color = | Red, Green |
            let f: { r: { c: Color, n: Int } } -> Int = { r } -> match r is
              { c = Red, n } if n > 10 -> 1
              { c = Red, n } -> 2
              { c = Green, n } -> 3
        """.trimIndent()
        assertEquals(1L, MezzeTestHelper.run("$src\nf { r = { c = Red, n = 20 } }"))
        assertEquals(2L, MezzeTestHelper.run("$src\nf { r = { c = Red, n = 5 } }"))
        assertEquals(3L, MezzeTestHelper.run("$src\nf { r = { c = Green, n = 20 } }"))
    }

    // ── regressions: existing binding behavior must be preserved ─────────

    @Test fun `nested-record destructuring still binds and selects`() {
        val src = """
            type P = | P { pt: { x: Int, y: Int } } |
            let h: { p: P } -> Int = { p } -> match p is
              P { pt = { x = px, y = py } } -> px + py
            h { p = P { pt = { x = 3, y = 4 } } }
        """.trimIndent()
        assertEquals(7L, MezzeTestHelper.run(src))
    }

    @Test fun `an irrefutable field binding is not turned into a test`() {
        // `{ c }` binds the whole field; the second arm is a catch-all and must
        // still be reachable (no spurious test added).
        val src = """
            type Color = | Red, Green, Blue |
            let f: { r: { c: Color } } -> Int = { r } -> match r is
              { c = Red } -> 1
              { c } -> 2
            f { r = { c = Blue } }
        """.trimIndent()
        assertEquals(2L, MezzeTestHelper.run(src))
    }
}
