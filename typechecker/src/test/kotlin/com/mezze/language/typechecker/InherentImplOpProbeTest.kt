package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.TypedLambda
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #230: an inherent impl op whose param record contains a callback
 * (e.g. `p: { x: 'a } -> Bool`) used to register `p` as a fresh TypeVar.
 * Callers like `nums.all { p = 5 }` then unified `'p` with `Int` and
 * silently typechecked.
 *
 * Fix: ConstraintGenerator stashes the impl op's full FunctionType under
 * `_inherent_<loc>_<op>` (was just the body's return type, for #213).
 * TypedAstBuilder threads it back into the impl op's TypedLambda's params,
 * so the post-solve `p: { x: 'a } -> Bool` survives into TypedAbilityImpl.
 *
 * These tests pin the same-module behaviour. Cross-module is covered by a
 * separate test that exercises the prelude path (#230 root case).
 */
class InherentImplOpProbeTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /**
     * Same-module impl op with a callback param: the post-solve TypedLambda
     * must carry `p: { x: 'a } -> Bool` (or some FunctionType) — NOT a
     * fresh TypeVar.
     */
    @Test
    fun `inherent impl op TypedLambda param p stores solved FunctionType`() {
        val src = """
            type Container 'a = { items: ['a] }
            let bare_all = { xs: Container 'a, p: { x: 'a } -> Bool } ->
              True
            impl Container 'a where {
              all = { it, p } -> bare_all { xs = it, p = p }
            }
        """.trimIndent()

        val parsed = parser.parseParsedModule(src, "test.mz")
        val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = false)
        val mod = r.typedModule!!
        val impl = mod.abilityImpls.first()
        val opExpr = impl.operations["all"]!!
        val lambda = opExpr as TypedLambda

        assertEquals(2, lambda.params.size, "lambda must have 2 params")
        val pParam = lambda.params.first { it.name == "p" }
        assertTrue(
            pParam.type is MezzeType.FunctionType,
            "p param's type must be a FunctionType (post-solve), got ${pParam.type}"
        )
    }

    /**
     * Same-module call site: passing a non-function to `p` must be
     * rejected. This exercises the local-module dispatch path
     * (registerLocal/methodInfo flow), not the cross-module import path.
     */
    @Test
    fun `same-module call rejects non-function passed where callback expected`() {
        // Would have silently typechecked before #230.
        val src = """
            type Container 'a = { items: ['a] }
            let bare_all = { xs: Container 'a, p: { x: 'a } -> Bool } ->
              True
            impl Container 'a where {
              all = { it, p } -> bare_all { xs = it, p = p }
            }
            let c: Container Int = { items = [1, 2, 3] }
            let bad = c.all { p = 5 }
        """.trimIndent()

        val parsed = parser.parseParsedModule(src, "test.mz")
        val msg = try {
            val r = typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = false)
            when (r) {
                is TypeChecker.TypeCheckResult.Failure -> r.errors.joinToString("\n") { it.message }
                else -> fail<String>("expected typecheck to fail on `c.all { p = 5 }`, got success")
            }
        } catch (e: TypeCheckError) {
            e.message
        }
        // The exact wording is decided by the field-mismatch path; we care
        // that p's mismatch is mentioned, not that an Int snuck through.
        assertTrue(
            "Field 'p'" in msg || "p '" in msg || "callback" in msg.lowercase() || "expected" in msg.lowercase(),
            "expected diagnostic about 'p' field type mismatch, got: $msg"
        )
    }
}
