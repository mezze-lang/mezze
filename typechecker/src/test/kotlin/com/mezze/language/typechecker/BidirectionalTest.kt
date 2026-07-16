package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #56 bidirectional type checking: expected types are pushed down into
 * subexpressions rather than inferred bottom-up and reconciled afterward.
 *
 * These tests pin the two highest-value pushes:
 *  - record-literal field values receive their expected field type
 *  - call arguments receive the callee's parameter type (which fans, via the
 *    record-field push, into a lambda argument's params/return)
 *
 * Uses checkParsedModule directly so we can read the full error list.
 */
class BidirectionalTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun errorsFor(src: String): List<TypeChecker.TypeCheckError> {
        val parsed = parser.parseParsedModule(src, "bidi.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = false)
        return when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors
            is TypeChecker.TypeCheckResult.Success -> emptyList()
        }
    }

    @Test
    fun `lambda argument to higher-order function infers params from callee`() {
        // `apply` takes a function `f: {n: Int} -> Int` and an Int. The lambda
        // passed as `f` has an UN-annotated param `n`. Bidirectionally, `n`
        // should be inferred as Int from apply's declared param type, so `n + 1`
        // type-checks (Int + Int).
        val src = """
            let apply = { f: { n: Int } -> Int, x: Int } -> f { n = x }
            let r = apply { f = { n } -> n + 1, x = 5 }
            r
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "expected clean type-check, got: ${errors.map { it.message }}")
    }

    @Test
    fun `record field value checked against expected field type`() {
        // Field `f` expects a `{n: Int} -> Int`; the un-annotated lambda's `n`
        // is inferred as Int from the expected field type.
        val src = """
            type Handler = { f: { n: Int } -> Int }
            let h: Handler = { f = { n } -> n + 1 }
            h
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "expected clean type-check, got: ${errors.map { it.message }}")
    }

    @Test
    fun `wrong lambda body against pushed return type still errors`() {
        // Bidirectional push must not mask real errors: `f` must return Int but
        // the lambda body returns a String.
        val src = """
            let apply = { f: { n: Int } -> Int, x: Int } -> f { n = x }
            let r = apply { f = { n } -> "not an int", x = 5 }
            r
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isNotEmpty(), "expected a type error for Str-returning f, got none")
    }

    @Test
    fun `match arm receives the match's expected type`() {
        // The binding is annotated `{ n: Int } -> Int`, so the whole match is
        // expected to be that function type. Each arm is an un-annotated lambda
        // whose param `n` must be inferred as Int from the pushed expected type.
        val src = """
            let pick: { n: Int } -> Int = match True is
              True -> { n } -> n + 1
              False -> { n } -> n + 2
            pick { n = 5 }
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "expected clean type-check, got: ${errors.map { it.message }}")
    }

    @Test
    fun `plain call still type-checks (no regression)`() {
        val src = """
            let add = { a: Int, b: Int } -> a + b
            let r = add { a = 1, b = 2 }
            r
        """.trimIndent()

        val errors = errorsFor(src)
        assertTrue(errors.isEmpty(), "valid call should have no errors, got: ${errors.map { it.message }}")
    }

    @Test
    fun `method-call lambda argument infers params from the method signature`() {
        // `nums.all { p = { x } -> x > 0 }` — the predicate lambda's param `x`
        // is un-annotated and must be inferred as the element type (Int) from
        // Seq.all's signature, so `x > 0` type-checks. Uses the prelude path.
        val parsed = parser.parseParsedModule(
            """
            let main = {} -> do
              let nums = [1, 2, 3]
              let ok = nums.all { p = { x } -> x > 0 }
              ok
            """.trimIndent(),
            "bidi.mz"
        )
        val r = try {
            typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }
        assertNull(r, "expected predicate with un-annotated param to type-check, got: $r")
    }
}
