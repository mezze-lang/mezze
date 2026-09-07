package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Generalization / let-polymorphism audit, focused on the VALUE RESTRICTION.
 *
 * This file was originally a println-only probe; it is rewritten into real
 * assertions covering three things:
 *
 *  1. Genuine polymorphism: a top-level polymorphic `let` instantiated at two
 *     distinct types must check (baseline — the sound-and-complete case).
 *
 *  2. The value-restriction hole (audit #257 / #8): a MUTABLE cell produced by a
 *     pure polymorphic call (`tvar { init = [] } : TVar (Seq 'a)`) must NOT be
 *     generalized. If it were, the *same* cell could be viewed as both
 *     `TVar (Seq Int)` and `TVar (Seq Str)`, letting an Int written through one
 *     alias be read as a Str through the other. A sound value restriction rejects
 *     this. It is wrongly accepted today, so those cases are marked
 *     EXPECTED-CORRECT and will be @Disabled by the orchestrator until #257 lands.
 *
 *  3. Constraint capture: a do-block `let` whose body forces its result equal to
 *     an outer MONOMORPHIC param (through a `'a` annotation) must not be
 *     generalized — using it at two types must fail.
 */
class PolyAuditProbeTest {
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

    // ── (1) genuine polymorphism — MUST PASS ─────────────────────────────

    @Test fun `a top-level polymorphic let is instantiable at two types`() {
        // The sound baseline: `id : { x: 'a } -> 'a` is a value (a lambda), so
        // generalizing it is safe; each call site instantiates 'a fresh.
        assertNull(err("""
            let id = { x: 'a } -> x
            let a: Int = id { x = 1 }
            let b: Str = id { x = "y" }
        """))
    }

    @Test fun `a top-level empty seq bound to a non-lambda is monomorphic`() {
        // #257 value restriction: `[]` is a non-lambda RHS → its type is not a
        // function → monomorphic. The first annotation pins `e` to Seq Int; the
        // Seq Str view is a type error (`e` is Seq Int). Uniform rule — no
        // "immutable so it's fine" exception. To get polymorphism, wrap in a
        // lambda (`let e = {} -> []`), which returns a fresh value per call.
        assertNotNull(err("""
            let e = []
            let a: Seq Int = e
            let b: Seq Str = e
            a
        """), "a non-lambda top-level binding is monomorphic — the second view must be rejected")
    }

    // ── (2) the value-restriction hole with a real mutable cell (TVar) ───

    @Test fun `a TVar built from a pure polymorphic call must not be generalized to two element types`() {
        // EXPECTED-CORRECT: fails today under bug #257 — a sound value restriction
        // rejects this; today it is WRONGLY ACCEPTED.
        //
        // `tvar { init = [] } : TVar (Seq 'a)` is a MUTABLE cell. If the checker
        // generalizes `box` over the element type, the *same* underlying cell can
        // be aliased at both `TVar (Seq Int)` and `TVar (Seq Str)` — an Int written
        // through `asInt` would be observable as a Str through `asStr`. A sound
        // value restriction must lock `box` to a single element type here.
        assertNotNull(err("""
            use std::effects::stm::{ TVar, tvar }
            let f = {} -> do
              let box = tvar { init = [] }
              let asInt: TVar (Seq Int) = box
              let asStr: TVar (Seq Str) = box
              asInt
        """), "a shared TVar cell must not be viewed at two incompatible element types (value restriction)")
    }

    @Test fun `a TVar viewed at two scalar element types must be rejected`() {
        // EXPECTED-CORRECT: fails today under bug #257 — sound value restriction
        // rejects; today wrongly accepted.
        //
        // Same exploit with a scalar payload to isolate it from any Seq/[] special
        // casing: one mutable cell, two incompatible views.
        assertNotNull(err("""
            use std::effects::stm::{ TVar, tvar }
            let f = { seed: 'a } -> do
              let box = tvar { init = seed }
              let asInt: TVar Int = box
              let asStr: TVar Str = box
              asInt
        """), "a TVar bound in a do block must not generalize across two payload types")
    }

    @Test fun `a TVar used at a single consistent element type checks`() {
        // Sanity for the machinery above: the same construction with ONE view is
        // perfectly sound and must PASS — so the rejection above is about the
        // double-view, not about TVar/annotations being unusable.
        assertNull(err("""
            use std::effects::stm::{ TVar, tvar }
            let f = {} -> do
              let box = tvar { init = [] }
              let asInt: TVar (Seq Int) = box
              asInt
        """))
    }

    // ── (3) constraint capture must not over-generalize ──────────────────

    @Test fun `a do-block let whose body forces it equal to an outer mono param is not generalized`() {
        // `mk`'s body binds `r : 'a = k`, forcing `mk`'s result to the ENCLOSING
        // param's monomorphic type var `'a`. Even though `mk` is a lambda (which
        // normally generalizes in a do block), the capture of the outer mono var
        // must pin it: using it at both Int and Str must FAIL.
        assertNotNull(err("""
            let f = { k: 'a } -> do
              let mk = { z: 'b } -> do
                let r: 'a = k
                r
              let n: Int = mk { z = 1 }
              let s: Str = mk { z = "q" }
              n
        """), "a do-block let capturing an outer monomorphic type var must not generalize over it")
    }

    @Test fun `a do-block alias of an outer monomorphic param does not over-generalize`() {
        // Companion to the above without the inner lambda: `g = chooser` aliases the
        // enclosing param's mono type var; using it at two types must fail.
        assertNotNull(err("""
            let f = { chooser: { x: 'a } -> 'a } -> do
              let g = chooser
              let n: Int = g { x = 1 }
              let s: Str = g { x = "y" }
              n
        """), "aliasing an outer monomorphic param must not generalize it")
    }

    // ── a genuinely-polymorphic do-block lambda still generalizes ────────

    @Test fun `a do-block lambda with no outer capture generalizes`() {
        // Control: the SAME shape as the constraint-capture case, but the inner
        // lambda's result is its own fresh type var (no `'a` from the outside), so
        // generalization is sound and using it at two types must PASS.
        assertNull(err("""
            let f = {} -> do
              let mk = { z: 'b } -> z
              let n: Int = mk { z = 1 }
              let s: Str = mk { z = "q" }
              n
        """))
    }

    @Test fun `a do-block lambda capturing a CONCRETE outer binding still generalizes its own param`() {
        // #15: `k = { ignore } -> p` captures `p : Int` (CONCRETE — no free type
        // vars). Its param `ignore`'s var is independent of `p` and is NOT free in
        // the environment, so `k` must generalize to `forall 'b. { ignore: 'b } -> Int`
        // and calling it at Int then Str must PASS. Capturing a binding whose type
        // has no free vars must remove NOTHING from the generalizable set. (The
        // over-broad rule was: any capture suppresses ALL generalization.)
        assertNull(err("""
            let f = {} -> do
              let p = 5
              let k = { ignore } -> p
              let _a = k { ignore = 1 }
              let _b = k { ignore = "s" }
              p
        """), "a do-block lambda capturing a concrete outer binding must still generalize over its own param")
    }

    @Test fun `a genuinely polymorphic do-block lambda has a schematic type`() {
        // Directly inspect the generalized type: the inner `mk` binding is
        // polymorphic, so the enclosing `f` returns whatever the last use fixes,
        // and the program checks. We assert the outer fn is inferred (non-null).
        val t = typeOf("f", """
            let f = {} -> do
              let mk = { z: 'b } -> z
              let n: Int = mk { z = 1 }
              n
        """)
        assertNotNull(t, "f should type-check and have an inferred type")
    }
}
