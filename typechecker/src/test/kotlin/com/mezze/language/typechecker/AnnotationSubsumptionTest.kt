package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Dimension 3 — ANNOTATION SUBSUMPTION IS DIRECTIONAL (S6.1 / #366).
 *
 * An annotation is the UPPER BOUND: the inferred type must be ≤ the annotation.
 * An annotation may NARROW or exactly match the inferred type; it must NEVER
 * WIDEN it. A wrong (over-general) annotation is rejected by
 * validateLetAnnotation → validatePolymorphismPreserved, not silently accepted
 * by unifying the annotation's var with the body. This file pins the direction
 * by asserting the inferred TYPE on the accept cases and the REJECTION on the
 * widen cases.
 *
 * Note on `let g: 'a = 5`: the checker ACCEPTS it and infers `g : Int` — the
 * annotation var unifies with the body and NARROWS to Int; it does NOT become
 * `forall a. a`. This is sound (verified by the double-view rejection test); it
 * is pinned as current behaviour, not a leak.
 */
class AnnotationSubsumptionTest {
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

    // ── NARROW / match: accepted, inferred type = the annotation ─────────

    @Test fun `an annotation narrows an inferred poly identity to concrete`() {
        assertEquals("{ x: Int } -> Int", typeOf("id2", "let id2: { x: Int } -> Int = { x } -> x"))
    }

    @Test fun `an annotation exactly matching the inferred poly type is kept`() {
        assertEquals("{ x: 'a } -> 'a", typeOf("id", "let id: { x: 'a } -> 'a = { x } -> x"))
    }

    @Test fun `a user alias annotation is adopted as identity (narrowing a structural record)`() {
        assertEquals("Point", typeOf("origin", """
            type Point = { x: Int, y: Int }
            let origin: Point = { x = 0, y = 0 }
        """))
    }

    // ── WIDEN: rejected (annotation cannot be MORE general than the body) ─

    @Test fun `an annotation cannot widen a concrete function return to a variable`() {
        // body returns Int; annotation claims `{ x: Int } -> 'a`. Accepting it
        // would let `k { x = 1 }` be viewed as any type. REJECT.
        assertNotNull(err("let k: { x: Int } -> 'a = { x } -> x"),
            "annotation return 'a is more general than the body's Int — must reject")
    }

    @Test fun `an annotation cannot claim a param is any type when the body forces numeric`() {
        // body `x + 1` forces `x` numeric; annotation `{ x: 'a } -> 'a` over-claims.
        assertNotNull(err("let f: { x: 'a } -> 'a = { x } -> x + 1"),
            "annotation over-generalizes a param the body constrains — must reject")
    }

    // ── the `let g: 'a = 5` case: accepted, NARROWED to Int (sound) ──────

    @Test fun `a fully-variable annotation on a literal narrows to the concrete body type`() {
        assertEquals("Int", typeOf("g", "let g: 'a = 5"))
    }

    @Test fun `the narrowed literal is monomorphic - a second incompatible view is rejected`() {
        // Proves `let g: 'a = 5` did NOT become forall a. a: viewing g as Str fails.
        assertNotNull(err("""
            let g: 'a = 5
            let a: Int = g
            let b: Str = g
        """), "g:'a=5 is Int, not forall a.a — the Str view must be rejected")
    }
}
