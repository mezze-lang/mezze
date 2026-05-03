package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Locks in: type inference must propagate the impl operation's inferred
 * return type to method-call sites — without an explicit return-type
 * annotation. Mezze never requires annotation.
 *
 * The bug this guards against: `tryGenerateMethodCall` used to call
 * `instantiateTypeWithFreshVars(methodInfo.methodType)` on every method
 * call. The freshening severed the link between the method type's
 * TypeVars and the constraint generated for the impl body
 * (`Equality(methodType.returnType, bodyResult.type)`). The body's
 * inferred type therefore never reached the call site, and the call
 * was reported as `'a`.
 *
 * Fix: at the inherent-method call site, use `methodInfo.methodType`
 * directly. Receiver-type unification still happens through the
 * receiver expression's own constraints, so per-call freshness comes
 * from the receiver path — no separate clone needed.
 *
 * Note: these tests don't load the prelude (default `enablePrelude=false`
 * for unit tests). They demonstrate inference inside a single-module,
 * user-defined inherent impl.
 */
class InherentImplInferenceTest {

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
        return typeChecker.typeCheck(parsed, Paths.get("."))
    }

    /**
     * Inherent method whose body returns a Str literal — no annotation.
     * Method-call site should infer Str. This is the simplest shape that
     * proves the inferred body type reaches the call site.
     */
    @Test
    fun `same-module inherent method call infers Str return type`() {
        val code = """
            type Counter = { n: Int }

            impl Counter where {
              label = { it } -> "counter",
            }

            let c = Counter { n = 1 }
            let s = c.label {}
        """.trimIndent()

        val result = check(code)
        val actual = result.getVariableType("s")
            ?: fail("expected 's' to be type-checked; got null")

        assertFalse(actual is MezzeType.VarType,
            "expected 's' to be inferred as Str (body returns Str literal), " +
            "got VarType '${(actual as? MezzeType.VarType)?.variable?.name}'. Full: $actual")
        assertEquals(MezzeType.StringType, actual)
    }

    /**
     * Sanity check: a top-level `let` with the same shape works. Confirms
     * inference is otherwise healthy — the gap was specifically inherent
     * impl operations.
     */
    @Test
    fun `top-level let with no annotation infers Str return type`() {
        val code = """
            let label = { n } -> "n"
            let s = label { n = 5 }
        """.trimIndent()

        val r1 = check(code)
        val actual = r1.getVariableType("s")
            ?: fail("expected 's' to be type-checked; got null")

        assertFalse(actual is MezzeType.VarType,
            "expected 's' = Str (inference should propagate from body), got $actual")
        assertEquals(MezzeType.StringType, actual)
    }
}
