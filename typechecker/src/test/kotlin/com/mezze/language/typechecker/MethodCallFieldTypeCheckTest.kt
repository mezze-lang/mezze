package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #229: method-call dispatch on stdlib-impl methods does not enforce
 * field-value types after the method resolves to its inherent impl.
 *
 * Repro from #228: `nums.all {p = 5}` typechecks successfully even though
 * `Seq.all`'s `p` field is `{ x: 'a } -> Bool`, not `Int`. The structural
 * field-name check passes because the user-passed record has the right
 * field NAME (`p`), but the field VALUE type isn't unified against the
 * method's expected param type.
 *
 * Adjacent: `nums.add {p = 5}` resolves ambiguously between `Seq.add`
 * (inherent, expects `{x}`) and `NumAdd.add` (operator, expects `{r}`).
 * Neither matches `{p}`, so two cascading errors fire. The receiver type
 * is concrete (`[Int]`) and only `Seq.add` can dispatch — the receiver
 * should disambiguate before field-shape comparison.
 */
class MethodCallFieldTypeCheckTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun checkExpectingError(code: String): String? {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return try {
            typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }
    }

    @Test
    fun `nums all with correct predicate typechecks`() {
        // Sanity: the right shape works. Locks in the dispatch fix from #229
        // — `nums.all {…}` routes to Seq.all (inherent), not NumAdd.add.
        val parsed = parser.parseParsedModule(
            """
            let main = {} -> do
              let nums = [1, 2, 3]
              let r = nums.all {p = {x} -> x > 0}
              0
            """.trimIndent(),
            "test.mz"
        )
        try {
            typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
        } catch (e: TypeCheckError) {
            fail("nums.all with proper predicate function should typecheck; got: ${e.message}")
        }
    }

    /**
     * This was recorded here as a KNOWN BUG with its test deliberately omitted
     * "so the suite stays green" — `nums.all { p = 5 }` was said to typecheck
     * because an imported inherent impl's method type lost the body's inferred
     * constraint, needing the impl-method type registered post-solve.
     *
     * It is fixed. Measured on 2026-10-26: the error is
     * `Field 'p' type mismatch: expected { x: Int } -> Bool, got Int`, which
     * names the field, the expected arrow and what arrived. The omitted test is
     * restored below, because a bug with no test is a bug that can come back.
     */
    @Test
    fun `all with a non-function predicate is rejected`() {
        val parsed = parser.parseParsedModule(
            """
            let f = {} -> do
              let nums = [1, 2, 3]
              nums.all { p = 5 }
            f {}
            """.trimIndent(),
            "test.mz",
        )
        val message = try {
            typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }
        assertNotNull(message, "`p = 5` is not a predicate, so this must be refused")
        assertTrue(
            message!!.contains("'p'") && message.contains("-> Bool"),
            "the error must name the field and the arrow it wanted; got: $message",
        )
    }

    @Test
    fun `Seq add with wrong field name reports a single useful error`() {
        // `nums.add {p = 5}` — neither Seq.add {x} nor NumAdd.add {r}
        // matches {p}. The receiver is [Int]; only Seq.add can apply.
        // Should produce one error pointing at field `p` vs expected `x`,
        // not a cascading NumAdd error.
        val msg = checkExpectingError(
            """
            let main = {} -> do
              let nums = [1, 2, 3]
              let bad = nums.add {p = 5}
              0
            """.trimIndent()
        )
        assertNotNull(msg, "expected typecheck failure for nums.add {p = 5}")
        // The error should NOT mention NumAdd — the receiver's type makes
        // that impl impossible.
        assertFalse(
            msg!!.contains("NumAdd"),
            "error should not mention NumAdd for [Int] receiver; got: $msg"
        )
    }

    @Test
    fun `Seq add succeeds with correct field name and value type`() {
        val parsed = parser.parseParsedModule(
            """
            let main = {} -> do
              let nums = [1, 2, 3]
              let pushed = nums.add {x = 4}
              0
            """.trimIndent(),
            "test.mz"
        )
        // Should not throw.
        typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }
}
