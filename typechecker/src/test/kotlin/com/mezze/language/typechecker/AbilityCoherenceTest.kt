package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Ability-impl COHERENCE: what happens when impls overlap, and how a blanket
 * `impl X for 'a` behaves. These pin the CURRENT observed behaviour so a future
 * change to dispatch/coherence is caught.
 *
 * Observed contract (as of this test):
 *   - A blanket `impl X for 'a` is ACCEPTED at declaration but is INERT for
 *     dispatch — it does not make any concrete type satisfy `X`. A concrete
 *     receiver with only a blanket impl in scope errors "T does not implement X".
 *   - A concrete `impl X for Int` alongside a blanket does NOT extend coverage to
 *     other types (`Str` still fails); only the concretely-implemented type works.
 *   - Two impls for the SAME concrete type is a hard error (duplicate).
 *
 * (Whether a silently-inert blanket impl SHOULD instead be a declaration-time
 *  error is a design question — this test documents today's behaviour, not a
 *  preference. If the design changes, update these expectations deliberately.)
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

    // ── blanket `impl X for 'a` ──────────────────────────────────────────

    @Test
    fun `a blanket impl is accepted at declaration`() {
        assertNull(err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for 'a where { sz = { it } -> 999 }
            pub let g = { x } -> x
        """.trimIndent()), "a blanket `impl Sz for 'a` should be accepted at declaration")
    }

    @Test
    fun `a blanket impl is inert - a concrete call still fails`() {
        val e = err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for 'a where { sz = { it } -> 999 }
            pub let f = {} -> (5).sz {}
        """.trimIndent())
        assertNotNull(e, "a blanket impl does not make Int satisfy Sz")
        assertTrue(e!!.contains("Int") && e.contains("Sz"),
            "error should say Int doesn't implement Sz, got: $e")
    }

    // ── concrete + blanket ───────────────────────────────────────────────

    @Test
    fun `concrete plus blanket - the concrete type dispatches`() {
        assertNull(err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for Int where { sz = { it } -> 111 }
            impl Sz for 'a where { sz = { it } -> 999 }
            pub let f = {} -> (5).sz {}
        """.trimIndent()), "Int (the concretely-implemented type) must dispatch")
    }

    @Test
    fun `concrete plus blanket - an unimplemented type still fails`() {
        val e = err("""
            ability Sz for 'a where { sz: { it: 'a } -> Int }
            impl Sz for Int where { sz = { it } -> 111 }
            impl Sz for 'a where { sz = { it } -> 999 }
            pub let f = {} -> ("hi").sz {}
        """.trimIndent())
        assertNotNull(e, "the blanket impl does not extend Sz to Str")
        assertTrue(e!!.contains("Str"), "error should name Str, got: $e")
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
        assertTrue(e!!.lowercase().contains("duplicate"), "error should mention duplicate, got: $e")
    }
}
