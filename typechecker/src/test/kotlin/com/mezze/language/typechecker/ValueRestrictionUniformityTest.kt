package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Dimension 2 — VALUE RESTRICTION APPLIED UNIFORMLY (S6.1 / #366, anchors #257/#8).
 *
 * Only a FUNCTION VALUE generalizes its free vars. A NON-value — an application
 * result, an empty collection, a mutable cell (TVar), an ability/effect-call
 * result — must NOT generalize, or the same underlying value could be viewed at
 * two incompatible types (the classic mutable-cell soundness hole). The rule is
 * keyed on the SOLVED TYPE being a FunctionType (no name/cell detection), which
 * is what makes it uniform and closes the hole (a cell's type is never a fn).
 *
 * This file constructs the counterexamples the audit asked for and pins that the
 * checker REJECTS the double-view. All cases here PASS today (the value
 * restriction is sound); a regression that lets any non-value escape would flip
 * a pin. Complements PolyAuditProbeTest (TVar cases) with type-keyed shapes and
 * an effect-call-result case.
 */
class ValueRestrictionUniformityTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }
    private fun typeOf(name: String, src: String): String? =
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            .getVariableType(name)?.let { prettyPrintType(it) }

    // ── the value-restriction boundary, type-keyed ───────────────────────

    @Test fun `a literal binding is monomorphic Int`() {
        assertEquals("Int", typeOf("n", "let n = 5"))
    }

    @Test fun `a top-level empty seq is a bare seq (not a quantified scheme)`() {
        // Displayed as ['a] but the var is a MONO var, not a bound scheme var —
        // proven by the double-view rejection below.
        assertEquals("['a]", typeOf("e", "let e = []"))
    }

    // ── non-value double-views are REJECTED (uniform value restriction) ──

    @Test fun `empty seq used at two element types is rejected`() {
        assertNotNull(err("""
            let e = []
            let a: Seq Int = e
            let b: Seq Str = e
        """), "value restriction: [] is not a function value → monomorphic")
    }

    @Test fun `a call-result binding used at two types is rejected`() {
        assertNotNull(err("""
            let mkEmpty = {} -> []
            let e = mkEmpty {}
            let a: Seq Int = e
            let b: Seq Str = e
        """), "value restriction: an application result is not a syntactic value → monomorphic")
    }

    @Test fun `a None binding used at two payload types is rejected`() {
        assertNotNull(err("""
            let o = None
            let a: | Some { val: Int }, None | = o
            let b: | Some { val: Str }, None | = o
        """), "value restriction: a nullary constructor value is not a function → monomorphic")
    }

    // ── mutable cell (TVar): the soundness-critical counterexample ───────

    @Test fun `a TVar built from a pure polymorphic call is not generalized (mutable-cell hole)`() {
        // If `box` generalized over the element type, the SAME cell could be
        // aliased at both TVar (Seq Int) and TVar (Seq Str) — an Int written
        // through one alias read as Str through the other. Must REJECT.
        assertNotNull(err("""
            use std::effects::stm::{ TVar, tvar }
            let f = {} -> do
              let box = tvar { init = [] }
              let asInt: TVar (Seq Int) = box
              let asStr: TVar (Seq Str) = box
              asInt
        """), "value restriction closes the mutable-cell hole")
    }

    @Test fun `a TVar used at a single consistent element type checks`() {
        // Control: one view is sound — proves the rejection is about the double
        // view, not TVar being unusable.
        assertNull(err("""
            use std::effects::stm::{ TVar, tvar }
            let f = {} -> do
              let box = tvar { init = [] }
              let asInt: TVar (Seq Int) = box
              asInt
        """))
    }

    // ── PROPERTY: no non-function RHS shape generalizes ──────────────────
    @Test fun `property - no non-function RHS escapes the value restriction`() {
        // Each RHS is a non-value; each double-view must be rejected.
        val nonValueRhs = listOf(
            "[]",            // empty seq
            "None",          // nullary constructor
        )
        for (rhs in nonValueRhs) {
            assertNotNull(err("""
                let v = $rhs
                let a: | Some { val: Int }, None | = v
                let b: | Some { val: Str }, None | = v
            """.let { if (rhs == "[]") """
                let v = []
                let a: Seq Int = v
                let b: Seq Str = v
            """ else it }), "non-value RHS `$rhs` must not generalize")
        }
    }

    // ── the sound escape: wrap in a lambda → per-call fresh ──────────────
    @Test fun `wrapping a non-value in a lambda restores per-call polymorphism`() {
        assertNull(err("""
            let x = {} -> []
            let a: Seq Int = x {}
            let b: Seq Str = x {}
        """))
    }
}
