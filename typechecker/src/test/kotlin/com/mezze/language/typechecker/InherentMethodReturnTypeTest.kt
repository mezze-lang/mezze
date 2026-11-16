package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * BUG 47 — an inherent impl method that uses `it` WITHOUT declaring it.
 *
 * `impl Box where { get = {} -> it.v }` is accepted and then fails: bound to a
 * `let` it refuses to compile (T0206, advising an annotation — which by the
 * author's rule means inference is wrong), and when it does reach the runtime
 * the interpreter dies with
 * `FrameSlotTypeException: Frame slot kind Object expected, but got Illegal`.
 *
 * The cause is a split between the two halves:
 *  - the TYPECHECKER binds `it` only when the lambda lists it as a parameter
 *    (`ConstraintGenerator.processInherentImpl`), so for `{} -> it.v` the field
 *    read yields a fresh variable and the method's return type never resolves;
 *  - EMIT always passes the receiver under `receiverFieldName = it`, but a
 *    lambda written `{}` declares no `it` parameter, so there is NO FRAME SLOT
 *    to bind it to.
 *
 * The declared form `get = { it } -> it.v` works end to end, and the stdlib is
 * unanimous: 1264 impl methods declare `it`, zero use it undeclared. Abilities
 * already require the declaration (an op whose params omit `it` gets
 * "Undefined variable 'it'"), so the fix is to REJECT the undeclared form with
 * that same diagnostic rather than to make it work.
 *
 * The first two tests are the regression guards for the form that must keep
 * working. The last two CHARACTERIZE the defect: when BUG 47 is fixed they will
 * fail, and should then be rewritten to assert the rejection.
 */
class InherentMethodReturnTypeTest {

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun check(code: String): TypeCheckResult =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(code.trimIndent(), "test.mz"),
            Paths.get("."),
        )

    @Test
    fun `a declared receiver infers the return type`() {
        assertDoesNotThrow {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  get = { it } -> it.v,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  let d = b.get {}
                  d.to_str {}
                """
            )
        }
    }

    @Test
    fun `a declared receiver infers through arithmetic too`() {
        assertDoesNotThrow {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  double = { it } -> it.v * 2,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  let d = b.double {}
                  d.to_str {}
                """
            )
        }
    }

    @Test
    fun `BUG 47 - an UNDECLARED receiver leaves the return type unresolved`() {
        // Should be rejected for naming an undeclared `it`. Instead the method's
        // return type stays a fresh variable and the error lands on a LATER
        // call, blaming the wrong expression.
        val e = assertThrows(Throwable::class.java) {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  get = {} -> it.v,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  let d = b.get {}
                  d.to_str {}
                """
            )
        }
        assertTrue(
            e.message?.contains("to_str") == true,
            "BUG 47: the complaint is about `to_str`, not about the undeclared " +
                "`it` that actually caused it. Got: ${e.message}"
        )
    }

    @Test
    fun `BUG 47 - an UNDECLARED receiver passes the typechecker when chained`() {
        // Accepted here, then dies at run time with FrameSlotTypeException
        // because no `it` frame slot exists in a lambda declaring no params.
        // BUG 47: this type-checks and then crashes the interpreter.
        assertDoesNotThrow {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  get = {} -> it.v,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  b.get {}.to_str {}
                """
            )
        }
    }
}
