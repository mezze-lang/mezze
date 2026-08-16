package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Repro for chain-map-fold-dispatch-picks-Bytes bug.
 * xs: [Int]. xs.map{f}.fold{init,f} should pick Seq.map / Seq.fold
 * but the current typechecker locks .fold to Bytes.fold and blows up.
 */
class ChainMapFoldDispatchTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(code: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `xs map fold chain routes to Seq not Bytes`() {
        val r = check(
            """
            pub let compute: { xs: [Int] } -> Int = { xs } ->
              xs.map { f = { x } -> x + 1 }.fold { init = 0, f = { acc, x } -> acc + x }
            """.trimIndent()
        )
        // The bug: dispatches .fold to Bytes.fold expecting {acc,byte}.
        // Correct: Seq.fold accepting {acc, x} -> Int.
        assertNotNull(r.getVariableType("compute"), "expected compute to typecheck")
    }
}
