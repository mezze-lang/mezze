package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Soundness tests for `Async.spawn` — the runtime catches Exception thrown by
 * the callback and turns it into a failed Promise. The typechecker must NOT
 * silently hide effects performed by the callback. Either:
 *   (a) the effect surfaces in the spawn'd result's type (honest but noisy), or
 *   (b) spawn's signature explicitly says which effects it handles (row
 *       subtraction) and only those get stripped from the return row.
 *
 * These tests assume shared-`<...>` semantics: a single `<...>` in a signature
 * refers to ONE row var across the entire signature, so effects the callback
 * performs surface at the caller — unless spawn's declared return row
 * explicitly excludes them.
 */
class SpawnEffectSoundnessTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /**
     * Get the effect row of a top-level let binding by name.
     */
    private fun effectsOf(module: ParsedModule, name: String): Set<String> {
        val result = typeChecker.typeCheck(module, Paths.get("."))
        val t = result.getVariableType(name) as? MezzeType.FunctionType
        assertNotNull(t, "expected $name to be a FunctionType, got ${result.getVariableType(name)}")
        return t!!.effects?.effects ?: emptySet()
    }

    private fun parse(source: String): ParsedModule =
        parser.parseParsedModule(source.trimIndent(), "test.mz")

    // ─── (1) Effects performed only by the spawned callback surface at the caller ─────────

    @Test
    fun `Time performed by spawn callback surfaces at spawn caller`() {
        // Async.spawn's signature: { f: {} -> 'a <...> } -> Promise 'a <Async, ...>
        // Both `<...>` share the row var. Callback performs Time.
        // Expected: outer row contains BOTH Async and Time.
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::time::{ Time, SystemTime }
            use std::result

            let outer = {} -> do
              let p = Async.spawn { f = {} -> Async.sleep { ms = 1 } }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertTrue("Async" in effects,
            "Async must appear in outer row (spawn/await). Got: $effects")
        assertTrue("Time" in effects,
            "Time performed by callback must surface at caller under shared `<...>`. " +
            "Got: $effects. If Time is missing, the typechecker is losing effects " +
            "through the callback → caller boundary (row-var independence bug).")
    }

    // ─── (2) Handling Time at the caller removes Time from the outer row ──────────────

    @Test
    fun `handling SystemTime + LoomAsync removes both Async and Time from row`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::time::{ Time, SystemTime }
            use std::result

            let outer = {} -> perform SystemTime in perform LoomAsync in do
              let p = Async.spawn { f = {} -> Async.sleep { ms = 1 } }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertTrue(effects.isEmpty(),
            "With both LoomAsync + SystemTime installed, outer row must be empty. Got: $effects")
    }

    // ─── (3) Handling only LoomAsync leaves Time in the row ───────────────────────────

    @Test
    fun `handling only LoomAsync leaves Time in outer row`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::time::{ Time }
            use std::result

            let outer = {} -> perform LoomAsync in do
              let p = Async.spawn { f = {} -> Async.sleep { ms = 1 } }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertEquals(setOf("Time"), effects,
            "With only LoomAsync handled, Time must remain. Got: $effects")
    }

    // ─── (4) Handling only SystemTime leaves Async in the row ─────────────────────────

    @Test
    fun `handling only SystemTime leaves Async in outer row`() {
        val src = """
            use std::effects::async::{ Async }
            use std::effects::time::{ Time, SystemTime }
            use std::result

            let outer = {} -> perform SystemTime in do
              let p = Async.spawn { f = {} -> Async.sleep { ms = 1 } }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertEquals(setOf("Async"), effects,
            "With only SystemTime handled, Async must remain. Got: $effects")
    }

    // ─── (5) Exception performed inside a spawn callback FLOWS UP through <...> ──────
    //
    // spawn is effect-transparent — its callback row is `<...>`. Any effect the
    // callback performs (including Exception via `unwrap`) surfaces at the caller.
    // The runtime's Promise-failure safety net exists for Promise state invariants;
    // it is NOT a type-level catch.

    @Test
    fun `Exception performed inside a spawn callback surfaces at caller`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::exception::{ Exception, unwrap }
            use std::result

            let outer = {} -> do
              let p = Async.spawn { f = {} -> do
                let r = Async.await { p = Async.spawn { f = {} -> 1 } }
                unwrap { result = r }
              }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertTrue("Exception" in effects,
            "spawn is effect-transparent — Exception performed by the callback must " +
            "surface at the caller. Got: $effects.")
        assertTrue("Async" in effects, "Async must be present. Got: $effects")
    }

    // ─── (6) Nested spawn — deep child effects surface ───────────────────────────────

    @Test
    fun `deeply nested spawn callback effects surface at outermost caller`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync }
            use std::effects::time::{ Time }
            use std::result

            let outer = {} -> do
              let p = Async.spawn { f = {} -> do
                let q = Async.spawn { f = {} -> Async.sleep { ms = 1 } }
                Async.await { p = q }
              }
              Async.await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertTrue("Time" in effects,
            "Time performed at spawn depth 2 must surface at the outer scope. Got: $effects")
    }
}
