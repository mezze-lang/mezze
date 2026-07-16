package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * #86 case-A shadowing: reusing a name already bound in the SAME function scope
 * (the enclosing lambda's param, or an earlier `let` in the same do-block) is a
 * hard error [ErrorCode.T0306_SHADOWED_BINDING_IN_SCOPE] — the same class of
 * mistake as a duplicate lambda param (#79). Shadowing an OUTER/nested-scope
 * name (case B) stays legal.
 */
class SameScopeShadowTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(code: String) =
        typeChecker.typeCheck(parser.parseParsedModule(code, "test.mz"), Paths.get("."))

    private fun expectT0306(code: String) {
        val e = runCatching { check(code) }.exceptionOrNull()
        assertTrue(e is TypeCheckError, "Expected a TypeCheckError, got: $e")
        assertEquals(ErrorCode.T0306_SHADOWED_BINDING_IN_SCOPE, (e as TypeCheckError).code,
            "Expected T0306, got ${e.code}: ${e.message}")
    }

    private fun expectOk(code: String) {
        val e = runCatching { check(code) }.exceptionOrNull()
        assertTrue(e == null, "Expected clean typecheck, but got: $e")
    }

    // ── case A: errors ───────────────────────────────────────────────────

    @Test
    fun `do-let reusing the enclosing param is an error`() {
        expectT0306("""
            let f = {x: Int} -> do
              let x = 99
              x
        """.trimIndent())
    }

    @Test
    fun `second let of the same name in one do-block is an error`() {
        expectT0306("""
            let g = {} -> do
              let y = 5
              let y = 10
              y
        """.trimIndent())
    }

    // ── case B and normal code: allowed ──────────────────────────────────

    @Test
    fun `distinct names in a do-block are fine`() {
        expectOk("""
            let f = {} -> do
              let x = 5
              let y = x + 1
              y
        """.trimIndent())
    }

    @Test
    fun `a fresh lambda param that hides nothing is fine`() {
        expectOk("""
            let h = {} -> do
              let k = {x: Int} -> x + 1
              k {x = 2}
        """.trimIndent())
    }

    @Test
    fun `shadowing a top-level global is allowed (case B)`() {
        expectOk("""
            let x = 100
            let use_it = {} -> do
              let x = 5
              x + 1
        """.trimIndent())
    }

    @Test
    fun `inner lambda param shadowing an outer name is allowed (case B)`() {
        expectOk("""
            let outer = 100
            let h = {} -> do
              let inner = {outer: Int} -> outer + 1
              inner { outer = 5 }
        """.trimIndent())
    }

    @Test
    fun `nested do-block shadowing an outer do-let is allowed (case B)`() {
        // The inner `do` is a distinct nested scope, not the same function
        // scope — reusing `x` there is a legal nested rebind, not case A.
        expectOk("""
            let f = {} -> do
              let x = 5
              let y = do
                let x = 10
                x + 1
              x + y
        """.trimIndent())
    }

    @Test
    fun `param rebound in a NESTED do-block is still an error (param visible throughout body)`() {
        // The param `x` is in scope for the entire lambda body, including nested
        // blocks. Rebinding it silently — even one block deep — is the same
        // accident. Treat it as case A. (Documents current behavior; if we later
        // decide nested-block param-shadow is OK, flip this to expectOk.)
        expectT0306("""
            let f = {x: Int} -> do
              let y = do
                let x = 10
                x
              x + y
        """.trimIndent())
    }

    @Test
    fun `_-prefixed repeated name opts out`() {
        expectOk("""
            let f = {_x: Int} -> do
              let _x = 99
              42
        """.trimIndent())
    }
}
