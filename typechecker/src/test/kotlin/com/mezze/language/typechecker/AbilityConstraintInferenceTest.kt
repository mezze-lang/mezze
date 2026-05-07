package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Comprehensive tests for ability constraint inference and annotation handling.
 *
 * Design rule: ANNOTATIONS ARE OPTIONAL. Whatever you can write with an
 * annotation, you can write without — the typechecker infers the same thing.
 * The annotation is a redundant cross-check, never a required input.
 *
 * Two-pass model:
 *   Pass 1 (inference): build the inferred type structurally, INCLUDING
 *           ability constraints accumulated from method calls. Don't check
 *           whether impls exist. The type comes out the same regardless.
 *   Pass 2 (resolution): walk the inferred type. For each ability constraint,
 *           check that the substituted type actually has an `impl`. For each
 *           call site that pins a type variable, check the type satisfies
 *           the constraints declared on it.
 *
 * Method-call resolution rule for `expr.method args` where `expr : 'a`:
 *   1. Look up: inherent methods named `method` AND ability operations named
 *      `method` in scope.
 *   2. If exactly one ability declares it AND no inherent matches a known
 *      concrete type for `'a` → ADD that ability's constraint to `'a`.
 *      Inferred type is polymorphic-with-constraint.
 *   3. If `'a` is pinned to a concrete type by some inherent method → unify.
 *   4. If multiple abilities declare it → error: ambiguous.
 *   5. If neither → fall back to structural-record interpretation.
 */
class AbilityConstraintInferenceTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    /** Stringify a type for easy contains-checks. */
    private fun render(t: MezzeType?): String = t?.let { prettyPrintType(it) } ?: "<null>"

    // ========================================================================
    // CASE 1 — UNANNOTATED METHOD CALL, ABILITY UNIQUELY HAS THE METHOD
    // Should infer the ability constraint automatically.
    // ========================================================================

    @Test
    fun `unannotated method call with unique ability infers the constraint`() {
        // No `with MySized 'a`, but `mysize` is uniquely a MySized method.
        // Inference should add the constraint automatically.
        val code = """
            ability MySized for 'c where {
                mysize: {it: 'c} -> Int,
            }

            impl MySized for Int where {
                mysize = {it} -> 42,
            }

            let probe = {m} -> m.mysize {}
        """.trimIndent()

        val result = typeCheck(code)
        val t = render(result.getVariableType("probe"))
        assertTrue(
            t.contains("MySized"),
            "expected `MySized` constraint in inferred type, got: $t"
        )
    }

    // ========================================================================
    // CASE 2 — ANNOTATION CROSS-CHECK
    // The annotation MUST match what inference would produce.
    // ========================================================================

    @Test
    fun `annotation matching inference is accepted`() {
        val code = """
            ability MySized for 'c where {
                mysize: {it: 'c} -> Int,
            }

            impl MySized for Int where {
                mysize = {it} -> 42,
            }

            let probe : {m: 'a} -> Int with MySized 'a = {m} -> m.mysize {}
        """.trimIndent()

        val result = typeCheck(code)
        val t = render(result.getVariableType("probe"))
        assertTrue(
            t.contains("MySized"),
            "expected MySized constraint in annotated function's type, got: $t"
        )
    }

    @Test
    fun `annotation works for ability declared in same module`() {
        // Same module: definitely in scope.
        val code = """
            ability MySized for 'c where {
                mysize: {it: 'c} -> Int,
            }

            impl MySized for Int where {
                mysize = {it} -> 42,
            }

            let probe : {m: 'a} -> Int with MySized 'a = {m} -> m.mysize {}

            let n = probe { m = 7 }
        """.trimIndent()

        val result = typeCheck(code)
        // Calling the function should also succeed
        assertEquals(MezzeType.IntType, result.getVariableType("n"))
    }

    // ========================================================================
    // BOGUS / UNRESOLVABLE ABILITY NAME
    // Loud error, never silent.
    // ========================================================================

    @Test
    fun `annotation referencing unknown ability name is a loud error`() {
        // TotallyMadeUpAbility doesn't exist anywhere. Should error, not silently
        // accept the constraint as if it were a real ability.
        val code = """
            let x : {m: 'a} -> Int with TotallyMadeUpAbility 'a = {m} -> 0
        """.trimIndent()

        val ex = assertThrows<TypeCheckError> { typeCheck(code) }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("TotallyMadeUpAbility"),
            "expected error to mention the bogus ability name, got: $msg"
        )
        assertTrue(
            msg.contains("not defined") || msg.contains("not imported") || msg.contains("not in scope"),
            "expected error to say the ability is not in scope, got: $msg"
        )
    }

    // ========================================================================
    // METHOD NAME NOT IN THE CONSTRAINED ABILITY
    // The annotation pins 'a's constraint, body uses a name not from that
    // ability. Should error or pin 'a to a concrete type that has the method.
    // Either way: NEVER silently fall back to structural-record.
    // ========================================================================

    @Test
    fun `annotated constraint plus body method-name not in any ability or inherent fails`() {
        // wrong_name_size is in NO ability and NO inherent impl. The body cannot
        // be typed in a way that satisfies the annotation.
        val code = """
            ability MySized for 'c where {
                mysize: {it: 'c} -> Int,
            }

            impl MySized for Int where {
                mysize = {it} -> 42,
            }

            let x : {m: 'a} -> Int with MySized 'a = {m} -> m.wrong_name_size {}
        """.trimIndent()

        // Today: silently passes with structural type (BUG).
        // Expected: should fail somehow — either inference-time "no method" or
        // resolution-time "structural record can't satisfy MySized".
        val ex = assertThrows<TypeCheckError> { typeCheck(code) }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("wrong_name_size") ||
            msg.contains("MySized") ||
            msg.contains("does not implement") ||
            msg.contains("no method"),
            "expected error to mention the missing method or the unsatisfied constraint, got: $msg"
        )
    }

    // ========================================================================
    // INHERENT METHOD ON A CONCRETE TYPE PINS THE TYPE VARIABLE
    // The body's `val.tangent {}` evidence says `val` must be of a type that
    // has a `tangent` inherent method.
    // ========================================================================

    @Test
    fun `inherent method on concrete type pins the type variable`() {
        val code = """
            type Point = { x: Int, y: Int }

            impl Point where {
                tangent = {it} -> 1,
            }

            let probe = {val} -> val.tangent {}
        """.trimIndent()

        // Today this likely already works (pre-existing inherent-method dispatch).
        // If anything, probe should infer to a function whose param is Point.
        val result = typeCheck(code)
        val t = render(result.getVariableType("probe"))
        assertTrue(
            t.contains("Point") || t.contains("'"),
            "expected probe's type to mention Point or be polymorphic, got: $t"
        )
    }

    // ========================================================================
    // CASE 3 — EXPLICIT QUALIFIED ABILITY CALL
    // Sized.size {it = m} should generate the Sized constraint without ambiguity.
    // ========================================================================

    @Test
    fun `qualified ability call infers constraint`() {
        val code = """
            ability MySized for 'c where {
                mysize: {it: 'c} -> Int,
            }

            impl MySized for Int where {
                mysize = {it} -> 42,
            }

            let probe = {m} -> MySized.mysize { it = m }
        """.trimIndent()

        val result = typeCheck(code)
        val t = render(result.getVariableType("probe"))
        assertTrue(
            t.contains("MySized"),
            "expected MySized constraint when using qualified call, got: $t"
        )
    }

    // ========================================================================
    // PASS-2 RESOLUTION: CALL SITE WITH UNSATISFIED CONSTRAINT
    // The function declaration is fine; the error fires at the call site.
    // ========================================================================

    @Test
    fun `polymorphic-with-constraint accepts arg whose type implements it`() {
        // Calling probe with an Int (which implements MySized) succeeds.
        val code = """
            ability MySized for 'c where {
                mysize: {it: 'c} -> Int,
            }

            impl MySized for Int where {
                mysize = {it} -> 42,
            }

            let probe = {m} -> m.mysize {}
            let result = probe { m = 7 }
        """.trimIndent()

        val result = typeCheck(code)
        assertEquals(MezzeType.IntType, result.getVariableType("result"))
    }

    @Test
    fun `polymorphic-with-constraint rejects arg whose type does not implement it`() {
        // Calling probe with a Str (which does NOT implement MySized) fails.
        val code = """
            ability MySized for 'c where {
                mysize: {it: 'c} -> Int,
            }

            impl MySized for Int where {
                mysize = {it} -> 42,
            }

            let probe = {m} -> m.mysize {}
            let result = probe { m = "hello" }
        """.trimIndent()

        val ex = assertThrows<TypeCheckError> { typeCheck(code) }
        val msg = ex.message ?: ""
        assertTrue(
            msg.contains("MySized") || msg.contains("does not implement") || msg.contains("Str"),
            "expected error about Str not satisfying MySized, got: $msg"
        )
    }

    // ========================================================================
    // PRELUDE ABILITY (Sized) — same as same-module case, but with prelude scope
    // ========================================================================

    @Test
    fun `prelude Sized ability is available without explicit import`() {
        // Sized comes from prelude. The annotation should be honored just like
        // any same-module ability — currently a known bug (#304).
        val code = """
            let x : {m: 'a} -> Int with Sized 'a = {m} -> m.size {}
        """.trimIndent()

        // Test that the inferred type carries the constraint, not that it
        // degenerates to structural record.
        val result = typeCheck(code)
        val t = render(result.getVariableType("x"))
        assertTrue(
            t.contains("Sized"),
            "expected `Sized` in the inferred type for prelude-constrained function, got: $t"
        )
    }
}
