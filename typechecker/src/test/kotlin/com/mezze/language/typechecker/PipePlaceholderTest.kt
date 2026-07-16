package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parser.ParseException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #77: pipe `@` placeholder rules.
 * - `@` may be an argument value: as a whole field, among fields, or nested in
 *   an expression (binary op, string interpolation).
 * - `@` may NOT be a method/field receiver (`x |> @.m {}`) — that gets a clear
 *   P0124 error.
 * - The placeholder counter must recurse into binary/unary/string-interp so it
 *   doesn't falsely report "missing @" for `@ + 1`.
 */
class PipePlaceholderTest {

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

    /** Returns null on success, or the error message (parse or type) on failure. */
    private fun check(src: String): String? = try {
        val parsed = parser.parseParsedModule(src, "pipe.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = false)
        when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors.firstOrNull()?.message ?: "type error"
            is TypeChecker.TypeCheckResult.Success -> null
        }
    } catch (e: ParseException) {
        e.message
    }

    @Test
    fun `at as a whole argument field is allowed`() {
        assertNull(check("let inc = { n: Int } -> n + 1\nlet r = 5 |> inc { n = @ }\nr"))
    }

    @Test
    fun `at among several fields is allowed`() {
        assertNull(check("let add = { a: Int, b: Int } -> a + b\nlet r = 5 |> add { a = @, b = 1 }\nr"))
    }

    @Test
    fun `at inside a binary expression is allowed`() {
        // Regression: previously reported a spurious "missing @" because the
        // counter didn't recurse into ParsedBinaryOp.
        assertNull(check("let inc = { n: Int } -> n + 1\nlet r = 5 |> inc { n = @ + 1 }\nr"))
    }

    @Test
    fun `at inside string interpolation is allowed`() {
        // @ is a Str here, so the interpolation is well-typed; the point is the
        // counter finds @ inside the interpolation (no spurious "missing @").
        assertNull(check("let tag = { s: Str } -> s\nlet r = \"hi\" |> tag { s = \"v: \$(@)\" }\nr"))
    }

    @Test
    fun `at as a method receiver is rejected with a clear error`() {
        val msg = check("let r = { a = 1 } |> @.a { }\nr")
        assertNotNull(msg, "expected `@.method` to be rejected")
        assertTrue(msg!!.contains("receiver"), "expected a receiver-focused message, got: $msg")
    }

    @Test
    fun `at as a field receiver is rejected`() {
        val msg = check("let r = { a = 1 } |> @.a\nr")
        assertNotNull(msg, "expected `@.field` to be rejected")
        assertTrue(msg!!.contains("receiver"), "expected a receiver-focused message, got: $msg")
    }

    @Test
    fun `missing at still errors`() {
        assertNotNull(check("let inc = { n: Int } -> n + 1\nlet r = 5 |> inc { n = 0 }\nr"))
    }

    @Test
    fun `two at still errors`() {
        assertNotNull(check("let add = { a: Int, b: Int } -> a + b\nlet r = 5 |> add { a = @, b = @ }\nr"))
    }

    @Test
    fun `bare at outside a pipe still errors`() {
        assertNotNull(check("let r = @\nr"))
    }
}
