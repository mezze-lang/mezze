package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A type variable written twice in one parameter list is ONE variable, so the
 * two parameters must agree.
 *
 *     let same = { x: 'a, y: 'a } -> x
 *     same { x = 1, y = "two" }          -- must NOT typecheck
 *
 * It did, and returned 1. `TypeAnnotationConverter.convertWith` defaults to a
 * FRESH variable cache per call, and three places converted a parameter list one
 * field at a time — constraint generation for a lambda, the binding-level
 * generator, and the typing context. Each `'a` therefore became its own variable
 * and nothing ever tied `x` to `y`.
 *
 * The SAME signature written as a full arrow was rejected correctly:
 *
 *     let same: { x: 'a, y: 'a } -> 'a = { x, y } -> x
 *
 * because that path converts the whole arrow in one scope. So the two spellings
 * of one signature disagreed about whether a program is well typed — and the
 * inline one was the unsound direction. This is the same defect as the annotated
 * lambda parameter in [DynamicPrintTypeFidelityTest], which lost a newtype's
 * identity rather than an equality.
 *
 * The fix is `TypeAnnotationConverter.convertSignature`, which all three sites
 * now use.
 */
class SharedTypeVarAcrossParamsTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun rejects(src: String, what: String) {
        val e = runCatching { MezzeTestHelper.run(src) }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("Type mismatch") == true ||
                e?.message?.contains("TypeCheckError") == true,
            "$what must be rejected; got: ${e?.message ?: "no error, it ran"}",
        )
    }

    @Test
    fun `two params sharing a variable must agree`() {
        rejects(
            """
            let same = { x: 'a, y: 'a } -> x
            same { x = 1, y = "two" }
            """.trimIndent(),
            "`{ x: 'a, y: 'a }` applied to Int and Str",
        )
    }

    @Test
    fun `the full-arrow spelling of the same signature agrees`() {
        rejects(
            """
            let same: { x: 'a, y: 'a } -> 'a = { x, y } -> x
            same { x = 1, y = "two" }
            """.trimIndent(),
            "the full-arrow form",
        )
    }

    @Test
    fun `the return type is tied to the shared variable too`() {
        rejects(
            """
            let pick = { x: 'a, y: 'a } -> y
            pick { x = 1, y = "two" }.concat { r = "!" }
            """.trimIndent(),
            "a result read at the wrong type",
        )
    }

    @Test
    fun `matching arguments still typecheck`() {
        // Guards against a fix that simply rejects everything.
        assertEquals(1L, MezzeTestHelper.run("let same = { x: 'a, y: 'a } -> x\nsame { x = 1, y = 2 }"))
        assertEquals("a", MezzeTestHelper.run("let same = { x: 'a, y: 'a } -> x\nsame { x = \"a\", y = \"b\" }"))
    }

    @Test
    fun `two params with DIFFERENT variables are still independent`() {
        // The complement: `'a` and `'b` must NOT be forced together.
        assertEquals(1L, MezzeTestHelper.run("let pair = { x: 'a, y: 'b } -> x\npair { x = 1, y = \"two\" }"))
    }
}
