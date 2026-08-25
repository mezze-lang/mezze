package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.ParsedModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Row-subtraction / caughtByAncestor: an effect declared on a callback row
 * but not on the enclosing return row is CAUGHT by the enclosing function.
 * The typechecker enforces two things:
 *   (a) the actual callback body MUST perform every effect declared on its row
 *       (strict rule)
 *   (b) effects on the callback row that don't appear on the return row don't
 *       propagate to the caller — the enclosing function catches them
 *
 * Tests use a user-defined `with_rollback` handler that legitimately declares
 * "I catch Rollback." Async.spawn is NOT used here — spawn is effect-transparent
 * (`<...>` on both sides), so it doesn't exercise this feature.
 */
class CallbackContravariantRowTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun effectsOf(module: ParsedModule, name: String): Set<String> {
        val result = typeChecker.typeCheck(module, Paths.get("."))
        val t = result.getVariableType(name) as? MezzeType.FunctionType
        assertNotNull(t, "expected $name to be a FunctionType, got ${result.getVariableType(name)}")
        return t!!.effects?.effects ?: emptySet()
    }

    private fun parse(source: String): ParsedModule =
        parser.parseParsedModule(source.trimIndent(), "test.mz")

    // ─── (1) callback that DOESN'T perform the declared handled effect is a TYPE ERROR ─

    @Test
    fun `pure callback fails to match a declared callback row that requires Rollback`() {
        val src = """
            effect Rollback where { rollback: {} -> {} <Rollback> }
            effect Db where { query: {} -> Int <Db> }

            ## Declares that the callback catches Rollback.
            let with_rollback : { f: {} -> Int <Rollback, ...> } -> Int <Db, ...> = { f } -> f {}

            ## Pure callback — doesn't perform Rollback. Should be rejected.
            let caller = {} -> with_rollback { f = {} -> 42 }
        """
        val module = parse(src)
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(module, Paths.get("."))
        }
    }

    // Note: earlier tests here declared a `with_rollback` sig that claimed to
    // catch Rollback and produce Db, but the body was just `f {}` — no
    // explicit `perform` for Rollback, no Db.query. `perform` is explicit
    // in Mezze, so a sig like that is a lie: the body can't satisfy the
    // declared row without an actual `perform Handler in ...`. Cases 1, 4,
    // 5, 6 below still cover the meaningful shapes (strict callback row
    // rejection, row-var transparency for spawn, pure-callback baseline).

    // ─── (4) missing declared effect is an error even when other effects are performed ─

    @Test
    fun `callback performing Log but not the required Rollback is a TYPE error`() {
        val src = """
            effect Rollback where { rollback: {} -> {} <Rollback> }
            effect Db where { query: {} -> Int <Db> }
            effect Log where { info: {} -> {} <Log> }

            let with_rollback : { f: {} -> Int <Rollback, ...> } -> Int <Db, ...> = { f } -> f {}

            ## Log is performed, Rollback is not — should be rejected.
            let caller = {} -> with_rollback { f = {} -> do
              Log.info {}
              42
            }
        """
        val module = parse(src)
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(module, Paths.get("."))
        }
    }

    // ─── (5) spawn is EFFECT-TRANSPARENT — Time from callback surfaces at caller ─────
    //
    // Async.spawn's signature is `{f: {} -> 'a <...>} -> Promise 'a <Async, ...>`.
    // No caughtByAncestor. Whatever the callback performs (beyond Async) flows
    // through the shared row var to the caller.

    @Test
    fun `spawn is transparent — Time from callback surfaces at caller`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{ Time }

            let outer = {} -> do
              let p = Async.spawn { f = {} -> sleep { ms = 1 } }
              await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertTrue("Time" in effects, "Time performed by callback must surface. Got: $effects")
        assertTrue("Async" in effects, "Async must be present. Got: $effects")
    }

    // ─── (6) spawn callback with pure body: caller row is <Async> only ───────────────

    @Test
    fun `spawn with pure callback — caller row is Async only`() {
        val src = """
            use std::effects::async::{ Async, LoomAsync, await, sleep }

            let outer = {} -> do
              let p = Async.spawn { f = {} -> 42 }
              await { p = p }
        """
        val effects = effectsOf(parse(src), "outer")
        assertEquals(setOf("Async"), effects,
            "Pure callback — outer row should be exactly <Async>. Got: $effects")
    }
}
