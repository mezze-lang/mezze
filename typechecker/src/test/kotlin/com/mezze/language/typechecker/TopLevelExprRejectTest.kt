package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import java.nio.file.Paths

/**
 * A bare expression at the top level is the REPL/eval affordance (a single
 * expression IS the program). In a real program file it silently never runs, so
 * PROGRAM mode (`strictTopLevel = true`, set by `mez check`/`dev`/`build`/`exec`
 * and — per the product call — WASM) must reject it (V0710); eval/REPL mode
 * (`strictTopLevel = false`) must still allow it.
 */
class TopLevelExprRejectTest {

    private val parser = MezzeParser()

    private fun errorsFor(src: String, strict: Boolean): List<TypeChecker.TypeCheckError> {
        val module = parser.parseParsedModule(src, "t.mz")
        return when (val r = TypeChecker(strictTopLevel = strict)
            .checkParsedModule(module, Paths.get("."))) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors
            is TypeChecker.TypeCheckResult.Success -> emptyList()
        }
    }

    @Test
    fun `program mode rejects a bare top-level expression`() {
        val errors = errorsFor("let name = \"Mezze\"\n\nlet main = {} -> name\n\nname\n", strict = true)
        assertTrue(
            errors.any { it.code == ErrorCode.V0710_TOP_LEVEL_EXPRESSION },
            "expected V0710 for the top-level `name`; got: ${errors.map { it.code to it.message }}"
        )
    }

    @Test
    fun `eval mode allows a bare top-level expression`() {
        val errors = errorsFor("let x = 1\n\nx\n", strict = false)
        assertFalse(
            errors.any { it.code == ErrorCode.V0710_TOP_LEVEL_EXPRESSION },
            "eval/REPL mode must not flag a top-level expression"
        )
    }
}
