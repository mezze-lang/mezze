package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * T0211 — callback lambda param names don't match the HOF signature.
 *
 * Repro'd from real-world: a user wrote
 *   seq::fold { xs = xs, init = 0, f = { acc, i } -> acc + i }
 * where seq::fold's signature says `f: { acc: 'b, x: 'a } -> 'b`.
 *
 * Old behavior — misleading T0201:
 *   error[T0201]: Field `xs` expects Seq 'a but got Seq Int in call to `fold`
 * The `xs` field wasn't the problem; the `f`-field's callback record didn't
 * unify because its destructured field names ({acc, i}) differed from the
 * caller's ({acc, x}). Once the record-name mismatch aborts unification,
 * `'a` is left unbound and blamed at the first field with a concrete
 * mismatch, which happens to be `xs`.
 *
 * New behavior — targeted T0211:
 *   error[T0211]: Callback param names don't match at `f` in call to `fold`:
 *     expected `{ acc, x }` but lambda destructures `{ acc, i }`
 *
 * #58.
 */
class CallbackParamNamesMismatchTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private data class Failure(val message: String, val code: ErrorCode?)

    private fun typeCheck(source: String): Failure? {
        return try {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
            null
        } catch (e: TypeCheckError) {
            Failure(e.message ?: "", e.code)
        }
    }

    @Test
    fun `seq_fold with wrong callback param name reports T0211`() {
        val src = """
            use std::collections::seq
            let xs = seq::range { start = 0, end = 20 }
            seq::fold { xs = xs, init = 0, f = { acc, i } -> acc + i }
        """.trimIndent()
        val f = typeCheck(src) ?: fail("expected type error, got clean typecheck")
        assertEquals(ErrorCode.T0211_CALLBACK_PARAM_NAMES_MISMATCH, f.code)
        assertTrue(f.message.contains("acc") && f.message.contains("x") && f.message.contains("i"),
            "message should mention the expected & actual field names; got: ${f.message}")
    }

    @Test
    fun `seq_fold with correct callback param names typechecks`() {
        val src = """
            use std::collections::seq
            let xs = seq::range { start = 0, end = 20 }
            seq::fold { xs = xs, init = 0, f = { acc, x } -> acc + x }
        """.trimIndent()
        assertNull(typeCheck(src), "expected clean typecheck")
    }

    @Test
    fun `seq_map with wrong callback param name reports T0211`() {
        val src = """
            use std::collections::seq
            let xs = seq::range { start = 0, end = 10 }
            seq::map { xs = xs, f = { y } -> y + 1 }
        """.trimIndent()
        val f = typeCheck(src) ?: fail("expected type error")
        assertEquals(ErrorCode.T0211_CALLBACK_PARAM_NAMES_MISMATCH, f.code)
    }

    @Test
    fun `T0211 does not fire on legitimate type mismatch at outer field`() {
        // xs field has wrong TYPE, not wrong field-name shape. Should stay T0201.
        val src = """
            use std::collections::seq
            seq::fold { xs = "not a seq", init = 0, f = { acc, x } -> acc + x }
        """.trimIndent()
        val f = typeCheck(src) ?: fail("expected type error")
        assertNotEquals(ErrorCode.T0211_CALLBACK_PARAM_NAMES_MISMATCH, f.code,
            "outer-field-type mismatch should not be classified as T0211")
    }
}
