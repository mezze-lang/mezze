package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Let-generalization soundness and the value-restriction boundary.
 *
 * Rules pinned here (verified against the real checker):
 *  - Top-level `let` generalizes freely (even non-lambda RHS): `let e = []` gets
 *    `['a]` and is usable at many element types.
 *  - Inside a `do` block, only LAMBDA bindings generalize (#76 guard #1); a
 *    do-block `let` bound to `[]` or a CALL RESULT stays monomorphic — locked to
 *    its first use.
 *  - Generalizing `[]` is SOUND because `[]` is an immutable Seq (`['a]`): each
 *    use is a distinct value (COW), so there is no shared mutable cell to corrupt
 *    — the classic value-restriction exploit is not expressible.
 *  - The soundness-critical cases (a value used at two incompatible types; a
 *    binding that captures an outer monomorphic type var) are rejected.
 *
 * Where the checker is conservative-but-sound (rejects a program that would in
 * fact be safe), the test asserts the REJECT and documents that it is deliberate.
 */
class LetGeneralizationSoundnessTest {
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

    // ── genuine polymorphism (sound, must PASS) ──────────────────────────

    @Test fun `a top-level lambda is polymorphic`() {
        assertNull(err("""
            let id = { x: 'a } -> x
            let a: Int = id { x = 1 }
            let b: Str = id { x = "y" }
        """))
    }

    @Test fun `a forward-referenced polymorphic fn instantiates at two types`() {
        // #126 regression: use-before-def polymorphic fn.
        assertNull(err("""
            let useIt = {} -> do
              let a: Int = later { x = 1 }
              let b: Str = later { x = "y" }
              a
            let later = { x: 'a } -> x
        """))
    }

    @Test fun `a do-block lambda binding generalizes`() {
        assertNull(err("""
            let f = {} -> do
              let myid = { x: 'a } -> x
              let n: Int = myid { x = 1 }
              let s: Str = myid { x = "y" }
              n
        """))
    }

    // ── soundness-critical rejections (must REJECT) ──────────────────────

    @Test fun `a param used at two incompatible types is rejected`() {
        assertNotNull(err("""
            let f = { x: 'a } -> do
              let n: Int = x
              let s: Str = x
              n
        """))
    }

    @Test fun `a concrete value used polymorphically is rejected`() {
        assertNotNull(err("""
            let f = {} -> do
              let n = 5
              let s: Str = n
              n
        """))
    }

    @Test fun `a do-block alias of an outer monomorphic param does not over-generalize`() {
        // `g = chooser` captures the enclosing param's mono type var; using it at
        // two types must fail — generalizing here would be UNSOUND.
        assertNotNull(err("""
            let f = { chooser: { x: 'a } -> 'a } -> do
              let g = chooser
              let n: Int = g { x = 1 }
              let s: Str = g { x = "y" }
              n
        """))
    }

    // ── the value restriction inside a do block (conservative but sound) ──

    @Test fun `an empty seq bound in a do block does NOT generalize (value restriction)`() {
        // Deliberate: `[]` is a non-lambda RHS, so a do-block `let e = []` stays
        // monomorphic and locks to the first use. Sound (never unsafe), just less
        // complete than full let-polymorphism. #76 guard #1 "lambda-only".
        assertNotNull(err("""
            let g = {} -> do
              let e = []
              let a: Seq Int = e
              let b: Seq Str = e
              a
        """), "a do-block `let e = []` must not generalize across two element types")
    }

    @Test fun `a do-block let bound to a call result does NOT generalize`() {
        assertNotNull(err("""
            let mkEmpty = {} -> do
              let e = []
              e
            let f = {} -> do
              let e2 = mkEmpty {}
              let a: Seq Int = e2
              let b: Seq Str = e2
              a
        """), "a do-block `let` bound to a call result must not generalize")
    }

    // ── moving `[]` into a lambda restores polymorphism (the split fix) ──

    @Test fun `wrapping the empty seq in a lambda generalizes its element type`() {
        // `f = {} -> do; let e = []; e`  is a LAMBDA binding, so `[]` generalizes
        // through the return type: f : {} -> ['a].
        val t = typeOf("f", """
            let f = {} -> do
              let e = []
              e
        """)
        assertNotNull(t)
        assertTrue(Regex("""->\s*\[\s*'?\w+\s*\]""").containsMatchIn(t!!),
            "f should be {} -> ['a] (polymorphic element), got: $t")
    }

    @Test fun `an empty-seq-returning fn is usable at two element types across callers`() {
        // The user's split example: same `[]` at Seq Int in one caller, Seq Str
        // in another. Each call instantiates the element type fresh → PASS.
        assertNull(err("""
            let f = {} -> do
              let e = []
              e
            let x1 = {} -> do
              let a: Seq Int = f {}
              a
            let x2 = {} -> do
              let b: Seq Str = f {}
              b
        """), "an empty-seq-returning lambda is polymorphic per call site")
    }

    // ── top-level non-lambda generalization + its soundness ──────────────

    @Test fun `a top-level empty seq generalizes and is an immutable Seq`() {
        val t = typeOf("e", "let e = []")
        assertNotNull(t)
        assertTrue(t!!.contains("[") || t.contains("Seq"),
            "top-level [] should be an (immutable) seq type, got: $t")
    }

    @Test fun `top-level empty seq used at two element types is accepted`() {
        // Sound because [] is immutable: each use is a distinct empty value.
        assertNull(err("""
            let e = []
            let a: Seq Int = e
            let b: Seq Str = e
        """))
    }

    @Test fun `pushing into an empty seq at one type does not leak that type to another view`() {
        // The value-restriction exploit shape, made harmless by immutability:
        // pushing an Int produces a NEW Seq Int; the original `e` is untouched, so
        // reading `e` as Seq Str stays consistent (no Int observed as Str).
        assertNull(err("""
            use std::collections::seq::{ push }
            let e = []
            let ints: Seq Int = push { xs = e, x = 1 }
            let strs: Seq Str = push { xs = e, x = "a" }
            ints
        """), "immutable push must not couple the two element-type views of `e`")
    }
}
