package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Ability-impl COHERENCE under the strict "no specialization" rule (#96).
 *
 * Contract:
 *   - A generic `impl X for 'a` is ACCEPTED at declaration.
 *   - A generic `impl X for 'a` DOES make any concrete type satisfy `X` —
 *     dispatch instantiates `'a := T` for whatever T shows up at the call.
 *   - You CANNOT coexist a generic `impl X for 'a` and a concrete
 *     `impl X for T` for the same ability in a single compilation.
 *     That's the strict coherence rule (matches Rust stable — no
 *     specialization). Overlap is reported by V0407.
 *   - Two impls for the SAME concrete type is a duplicate — V0407.
 *
 * Previously this file pinned pre-#96 "blanket impls are inert" behaviour.
 * Rewritten 2026-08-21 to match the current strict-coherence design.
 */
class AbilityCoherenceTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── generic `impl X for 'a` ──────────────────────────────────────────

    @Test
    fun `a generic impl is accepted at declaration`() {
        assertNull(err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for 'a where { sz = { it } -> 999 }
            pub let g = { x } -> x
        """.trimIndent()), "a generic `impl Sz for 'a` should be accepted at declaration")
    }

    @Test
    fun `a generic impl dispatches on any concrete receiver`() {
        // Under strict coherence, the generic impl covers every type
        // (there can't be a concrete override), so `(5).sz {}` succeeds.
        assertNull(err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for 'a where { sz = { it } -> 999 }
            pub let f = {} -> (5).sz {}
        """.trimIndent()), "a generic impl should dispatch on Int")
    }

    @Test
    fun `a generic impl dispatches on Str too - no exclusions`() {
        assertNull(err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for 'a where { sz = { it } -> 999 }
            pub let f = {} -> ("hi").sz {}
        """.trimIndent()), "a generic impl covers Str just like Int")
    }

    // ── concrete + generic in the same module — strict overlap ───────────

    @Test
    fun `concrete plus generic - concrete-after-generic is rejected`() {
        val e = err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for 'a where { sz = { it } -> 999 }
            impl Sz for Int where { sz = { it } -> 111 }
        """.trimIndent())
        assertNotNull(e, "concrete impl after generic must be rejected")
        assertTrue(e!!.contains("Overlapping"), "error should say Overlapping, got: $e")
    }

    @Test
    fun `concrete plus generic - generic-after-concrete is rejected`() {
        val e = err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for Int where { sz = { it } -> 111 }
            impl Sz for 'a where { sz = { it } -> 999 }
        """.trimIndent())
        assertNotNull(e, "generic impl after concrete must be rejected")
        assertTrue(e!!.contains("Overlapping"), "error should say Overlapping, got: $e")
    }

    // ── duplicate concrete impls ─────────────────────────────────────────

    @Test
    fun `two impls for the same concrete type is a duplicate error`() {
        val e = err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for Int where { sz = { it } -> 1 }
            impl Sz for Int where { sz = { it } -> 2 }
            pub let f = {} -> (5).sz {}
        """.trimIndent())
        assertNotNull(e, "duplicate `impl Sz for Int` must be rejected")
        assertTrue(e!!.lowercase().contains("duplicate") || e.contains("already"),
            "error should mention duplicate/already, got: $e")
    }

    @Test
    fun `two generic impls of the same ability is a duplicate error`() {
        val e = err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for 'a where { sz = { it } -> 1 }
            impl Sz for 'a where { sz = { it } -> 2 }
        """.trimIndent())
        assertNotNull(e, "duplicate generic `impl Sz for 'a` must be rejected")
    }
}
