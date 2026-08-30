package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull

/**
 * BUG D (#332): exhaustiveness must apply to EVERY match scrutinee, not only a
 * bare function-parameter identifier.
 *
 * Exhaustiveness runs on the parsed AST; it used to recover the scrutinee's type
 * solely from the enclosing function's PARAMETER shape, so a scrutinee that was a
 * local `let` binding, a field access, or a call result had no recoverable type
 * and the check was SILENTLY SKIPPED. A non-exhaustive `match` over such a
 * scrutinee then type-checked and crashed at runtime with no matching arm.
 *
 * The fix records each scrutinee's solved type at constraint generation (keyed by
 * its Loc) so the validator sees it regardless of how the scrutinee was formed.
 */
class NonParamScrutineeExhaustivenessTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(code: String) =
        typeChecker.typeCheck(parser.parseParsedModule(code.trimIndent(), "test.mz"), Paths.get("."))

    @Test
    fun `local let scrutinee non-exhaustive is rejected`() {
        // `n` is a do-block `let`, not a parameter — used to skip the check.
        assertThrows<TypeCheckError> {
            check(
                """
                let f = { a: Int } -> do
                  let n = a + 1
                  match n is
                    0 -> 100
                    1 -> 200
                """
            )
        }
    }

    @Test
    fun `field-access scrutinee non-exhaustive is rejected`() {
        // `r.n` is a field access, not a parameter — used to skip the check.
        assertThrows<TypeCheckError> {
            check(
                """
                type R = { n: Int }
                let h = { r: R } -> match r.n is
                  0 -> 100
                  1 -> 200
                """
            )
        }
    }

    @Test
    fun `local let variant scrutinee missing constructor is rejected`() {
        assertThrows<TypeCheckError> {
            check(
                """
                type Color = | Red, Blue, Green |
                let pick = { c: Color } -> do
                  let x = c
                  match x is
                    Red -> 1
                    Blue -> 2
                """
            )
        }
    }

    @Test
    fun `local let scrutinee with catch-all stays accepted`() {
        val result = check(
            """
            let k = { a: Int } -> do
              let n = a + 1
              match n is
                0 -> 100
                _ -> 999
            """
        )
        assertNotNull(result)
    }

    @Test
    fun `direct-parameter scrutinee still rejected (control)`() {
        assertThrows<TypeCheckError> {
            check(
                """
                let g = { n: Int } -> match n is
                  0 -> 100
                  1 -> 200
                """
            )
        }
    }
}
