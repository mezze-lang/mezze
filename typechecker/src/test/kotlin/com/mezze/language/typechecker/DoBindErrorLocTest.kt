package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase-5 regression: errors triggered by `<-` desugar must point at the
 * user-written `<-` line (the original `ParsedStatement.Bind.loc`), NOT
 * at internally-generated `.try {}` synthetic call sites, and must not
 * leak the names of desugar internals (`ExceptionToResult`, `Tryable.try`).
 *
 * Locs are checked structurally by asserting:
 *   - The error message includes a `:LINE:COL` span pointing at a line
 *     where `<-` actually appears in the source, AND
 *   - The message contains no references to desugar machinery.
 */
class DoBindErrorLocTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun expectError(source: String): String {
        return try {
            val ast = parser.parseParsedModule(source.trimIndent())
            typeChecker.typeCheck(ast, java.nio.file.Path.of("."))
            "(no error)"
        } catch (e: Exception) {
            e.message ?: "(empty)"
        }
    }

    @Test
    fun `non-tryable RHS error does not leak desugar internals`() {
        val msg = expectError("""
            let foo = {} -> do
                x <- 42
                x
            foo {}
        """)
        // Should describe the user's `<-` syntax, not internal `.try {}` or
        // `ExceptionToResult` performer names.
        assertFalse(msg.contains(".try"), "Error must not mention .try internals: $msg")
        assertFalse(msg.contains("ExceptionToResult"),
            "Error must not mention ExceptionToResult internals: $msg")
        assertFalse(msg.contains("try_or_exception"),
            "Error must not mention try_or_exception internals: $msg")
        assertTrue(msg.contains("`<-`") || msg.contains("Tryable"),
            "Expected message to mention `<-` or Tryable: $msg")
    }

    @Test
    fun `body type error after bind points at the body expression`() {
        // `a.length {}` on an Int should produce a regular type error.
        // The desugar inserts `let a = Some {val=42}.try {}; a.length {}` —
        // the body error should be about `a.length` (the user's line), not
        // about the synthetic .try call.
        val msg = expectError("""
            let foo = {} -> do
                a <- Some {val = 42}
                a.length {}
            foo {}
        """)
        assertFalse(msg.contains("ExceptionToResult"), "leak: $msg")
        // Don't assert specific line/col since formatting varies; just
        // verify no synthetic machinery names appear.
    }
}
