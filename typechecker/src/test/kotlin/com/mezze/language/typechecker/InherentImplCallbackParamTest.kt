package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #230 (cross-module): the original symptom was `nums.all { p = 5 }`
 * silently typechecking against the prelude's `Seq.all` impl. This locks
 * in the cross-module fix — exercises the prelude path.
 *
 * The fix harvests post-solve param types from the home module's
 * TypedAbilityImpl into ModuleImportContext.inherentImplOpParamTypes,
 * which ContextBuilder.registerImportedInherentImpl prefers over fresh
 * TypeVars when registering imported inherent methods.
 */
class InherentImplCallbackParamTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun runCheck(src: String): String? {
        val parsed = parser.parseParsedModule(src, "test.mz")
        return try {
            val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
            when (r) {
                is TypeChecker.TypeCheckResult.Failure -> r.errors.joinToString("\n") { it.message }
                else -> null  // success
            }
        } catch (e: TypeCheckError) {
            e.message
        }
    }

    @Test
    fun `Seq all rejects non-function p`() {
        val msg = runCheck("""
            let main = {} -> do
              let nums = [1, 2, 3]
              let bad = nums.all { p = 5 }
              0
        """.trimIndent())
        assertNotNull(msg, "expected nums.all { p = 5 } to fail typecheck")
        assertTrue("'p'" in msg!! || "p " in msg,
            "expected diagnostic mentioning the 'p' field, got: $msg")
    }

    @Test
    fun `Seq all accepts a real predicate`() {
        val msg = runCheck("""
            let main = {} -> do
              let nums = [1, 2, 3]
              let good = nums.all { p = {x} -> x > 0 }
              0
        """.trimIndent())
        assertNull(msg, "expected nums.all with a predicate to typecheck, got: $msg")
    }

    @Test
    fun `Seq filter rejects non-function p`() {
        val msg = runCheck("""
            let main = {} -> do
              let nums = [1, 2, 3]
              let bad = nums.filter { p = 5 }
              0
        """.trimIndent())
        assertNotNull(msg, "expected nums.filter { p = 5 } to fail typecheck")
    }

    @Test
    fun `Seq map rejects non-function f`() {
        val msg = runCheck("""
            let main = {} -> do
              let nums = [1, 2, 3]
              let bad = nums.map { f = 99 }
              0
        """.trimIndent())
        assertNotNull(msg, "expected nums.map { f = 99 } to fail typecheck")
    }

    @Test
    fun `Seq fold rejects non-function f`() {
        val msg = runCheck("""
            let main = {} -> do
              let nums = [1, 2, 3]
              let bad = nums.fold { init = 0, f = 7 }
              0
        """.trimIndent())
        assertNotNull(msg, "expected nums.fold { f = 7 } to fail typecheck")
    }
}
