package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Dimension 1 — GENERALIZATION AT EVERY BINDER IS THE SAME RULE (S6.1 / #366).
 *
 * The inference core generalizes a binding IFF its solved type is a FUNCTION
 * VALUE (a syntactic lambda or a curried partial application) — the uniform
 * `isLambdaBinding || solvedType is FunctionType` rule in
 * ConstraintGenerator.buildBindingForContext. This file pins that the SAME rule
 * fires at every binder — top-level `let`, do-block `let`, and (through function
 * values) lambda-param / match-arm binders — by asserting the exact INFERRED
 * TYPE. A regression that over-generalizes (leaks poly where it should be mono)
 * or under-generalizes (fails to make a legit-poly binding poly) flips a pin.
 *
 * Sibling files: LetGeneralizationSoundnessTest (reject/accept behaviour),
 * ValueRestrictionUniformityTest (the value-restriction boundary),
 * ForwardRefRecursionTest (D4), BidirectionalParamPinningTest (D5).
 */
class GeneralizationUniformityTest {
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

    // ── a top-level lambda generalizes (type-keyed) ──────────────────────
    @Test fun `top-level identity lambda is polymorphic`() {
        assertEquals("{ x: 'a } -> 'a", typeOf("id", "let id = { x } -> x"))
    }

    // ── a do-block lambda binding generalizes the SAME way ───────────────
    @Test fun `do-block lambda binding is polymorphic (same rule as top-level)`() {
        // f returns the inner generalized identity; the do-block binder uses the
        // same quantify-free-vars rule as the top level.
        assertEquals("{} -> { x: 'a } -> 'a", typeOf("f", """
            let f = {} -> do
              let g = { x } -> x
              g
        """))
    }

    // ── a curried partial application is a function value → generalizes ───
    @Test fun `a partial application stays a polymorphic function value`() {
        // `add { x = 5 }` produces `{ y: 'a } -> Int` — a FUNCTION, so it
        // generalizes even though it is NOT a syntactic lambda. This is the
        // "solvedType is FunctionType" arm of the uniform rule.
        assertEquals("{ y: 'a } -> Int", typeOf("add5", """
            let add = { x, y } -> x
            let add5 = add { x = 5 }
        """))
    }

    // ── a match-arm binder result generalizes through a top-level fn ─────
    @Test fun `match-arm binder result generalizes through the enclosing function value`() {
        // The `v` bound by the Some arm and the `dflt` param flow to a single
        // result var, generalized because the enclosing binding is a lambda.
        assertEquals("{ o: | Some { val: 'a }, None |, dflt: 'a } -> 'a", typeOf("f", """
            let f = { o: | Some { val: 'a }, None |, dflt: 'a } -> match o is
              Some { val = v } -> v
              None -> dflt
        """))
    }

    // ── the escape hatch: a lambda returning [] is per-call polymorphic ──
    @Test fun `a lambda returning empty seq generalizes its element type`() {
        assertEquals("{} -> ['a]", typeOf("x", "let x = {} -> []"))
    }

    // ── PROPERTY: every binder shape that yields the identity function
    //   infers the SAME polymorphic scheme string ─────────────────────────
    @Test fun `property - identity has the same poly scheme across binder shapes`() {
        val shapes = listOf(
            "let id = { x } -> x" to "id",
            "let id = { x: 'a } -> x" to "id",
        )
        for ((src, name) in shapes) {
            assertEquals("{ x: 'a } -> 'a", typeOf(name, src),
                "identity should generalize identically for: $src")
        }
    }

    // ── PROPERTY: instantiation at two distinct types succeeds for every
    //   function-value binder, and never for a non-function binder ────────
    @Test fun `property - function-value binders instantiate at two types, others do not`() {
        // function value → polymorphic → two views OK
        assertNull(err("""
            let id = { x } -> x
            let a: Int = id { x = 1 }
            let b: Str = id { x = "y" }
        """))
        // do-block function value → polymorphic → two views OK
        assertNull(err("""
            let outer = {} -> do
              let id = { x } -> x
              let a: Int = id { x = 1 }
              let b: Str = id { x = "y" }
              a
        """))
        // non-function value → monomorphic → two views REJECTED (uniform)
        assertNotNull(err("""
            let e = []
            let a: Seq Int = e
            let b: Seq Str = e
        """))
    }
}
