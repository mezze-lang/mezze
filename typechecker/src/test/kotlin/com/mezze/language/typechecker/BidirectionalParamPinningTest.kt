package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Dimension 5 — BIDIRECTIONAL FLOW (S6.1 / #366, task #56).
 *
 * An untyped lambda param / match-arm binder is PINNED from the expected
 * (contextual) type where it is known, and stays polymorphic where it is not.
 * BidirectionalTest already pins the ACCEPT behaviour; this file pins the
 * inferred TYPE so a regression that stops pushing the expected type (leaving
 * the param a leaked 'a) or wrongly concretizes a genuinely-poly param flips a
 * pin.
 */
class BidirectionalParamPinningTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun typeOf(name: String, src: String): String? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)?.let { prettyPrintType(it) }
    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── the untyped param is PINNED from the concrete element type ───────

    @Test fun `untyped lambda param is pinned from a concrete list element type`() {
        // `map { xs = [1,2,3], f = { x } -> x }`: `x` has NO annotation; it is
        // pinned to Int from the element type of the concrete `[Int]`.
        assertEquals("{} -> [Int]", typeOf("g", """
            use std::collections::seq::{ map }
            let g = {} -> map { xs = [1, 2, 3], f = { x } -> x }
        """))
    }

    // ── the untyped param stays POLYMORPHIC where the element type is ────

    @Test fun `untyped lambda param stays polymorphic where the element type is a var`() {
        // `ys : ['b]` is polymorphic; the untyped `x` must remain `'a`, not
        // leak or wrongly concretize.
        assertEquals("{ ys: ['a] } -> ['a]", typeOf("h", """
            use std::collections::seq::{ map }
            let h = { ys: ['b] } -> map { xs = ys, f = { x } -> x }
        """))
    }

    // ── the pinned param body type-checks against the concrete pin ───────

    @Test fun `a pinned param supports operations valid for the pinned type`() {
        // `x` pinned to Int from [Int]; `x + 1` is valid Int arithmetic.
        assertNull(err("""
            use std::collections::seq::{ map }
            let g = {} -> map { xs = [1, 2, 3], f = { x } -> x + 1 }
            g
        """))
    }

    @Test fun `a pinned param rejects operations invalid for the pinned type`() {
        // `x` pinned to Str; `x + 1` (numeric) must be rejected — proves the pin
        // is a real constraint, not a discarded hint.
        assertNotNull(err("""
            use std::collections::seq::{ map }
            let g = {} -> map { xs = ["a", "b"], f = { x } -> x + 1 }
            g
        """), "a param pinned to Str must reject numeric use")
    }

    // ── match-arm binder pinned bidirectionally, per-call fresh ──────────

    @Test fun `match-arm binder is polymorphic and instantiates fresh per call`() {
        assertNull(err("""
            let f = { o: | Some { val: 'a }, None |, dflt: 'a } -> match o is
              Some { val = v } -> v
              None -> dflt
            let n: Int = f { o = Some { val = 1 }, dflt = 0 }
            let s: Str = f { o = Some { val = "a" }, dflt = "b" }
            n
        """))
    }
}
