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
 *  - #257 value restriction (UNIFORM, everywhere including top level): ONLY a
 *    LAMBDA binding generalizes. A non-lambda RHS (`[]`, `None`, a call result,
 *    a literal, a variable) is monomorphic — its type is pinned at the first use,
 *    and a later use at a different type is a TYPE ERROR. `let x = []; y:[Int]=x;
 *    z:[Str]=x` rejects the second view (x is already [Int]). No exception for
 *    "immutable" data — one rule for everything.
 *  - A lambda like `{} -> []` still generalizes, because it returns a FRESH value
 *    per call, so each call site instantiates its own element type.
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

    // ── the value restriction: a non-lambda binding is monomorphic ──────

    @Test fun `an empty seq bound in a do block does NOT generalize (value restriction)`() {
        // `[]` is a non-lambda RHS, so `let e = []` stays monomorphic and locks to
        // the first use — a later use at a different element type is an error.
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

    // ── top-level non-lambda is MONOMORPHIC (#257 value restriction) ─────

    @Test fun `a top-level empty seq is a seq type`() {
        val t = typeOf("e", "let e = []")
        assertNotNull(t)
        assertTrue(t!!.contains("[") || t.contains("Seq"),
            "top-level [] should be a seq type, got: $t")
    }

    @Test fun `top-level empty seq used at two element types is rejected`() {
        // #257: `[]` is a non-lambda RHS → monomorphic. The first annotation pins
        // `e` to Seq Int; viewing it as Seq Str is a type error (`e` is Seq Int).
        assertNotNull(err("""
            let e = []
            let a: Seq Int = e
            let b: Seq Str = e
        """), "a top-level non-lambda binding is monomorphic — the second view must be rejected")
    }

    @Test fun `wrapping the empty seq in a lambda makes it usable at two element types`() {
        // The escape hatch: a lambda returns a FRESH value per call, so each call
        // site instantiates its own element type — this is the sound way to get
        // polymorphism from `[]` (the user's `let x = {} -> []` example).
        assertNull(err("""
            let x = {} -> []
            let a: Seq Int = x {}
            let b: Seq Str = x {}
        """), "a lambda returning [] is polymorphic per call site")
    }
}
