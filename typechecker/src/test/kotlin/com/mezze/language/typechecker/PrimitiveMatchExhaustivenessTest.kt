package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #83: a match on a non-enumerable primitive (Int, Str, Float, ...) can't be
 * exhaustive by listing literals, so it must have a catch-all (`_`/variable).
 * Same exhaustiveness error as a variant missing a constructor.
 */
class PrimitiveMatchExhaustivenessTest {

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

    private fun fails(src: String): Boolean = errorFor(src) != null

    private fun errorFor(src: String): String? {
        val r = typeChecker.checkParsedModule(parser.parseParsedModule(src, "m.mz"), Paths.get("."), enablePrelude = false)
        return when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors.firstOrNull()?.message
            is TypeChecker.TypeCheckResult.Success -> null
        }
    }

    @Test
    fun `int match without catch-all is rejected`() {
        assertTrue(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  1 -> 2\nf { n = 5 }"))
    }

    @Test
    fun `int match with wildcard is accepted`() {
        assertFalse(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  _ -> 2\nf { n = 5 }"))
    }

    @Test
    fun `int match with a variable catch-all is accepted`() {
        assertFalse(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  other -> other\nf { n = 5 }"))
    }

    @Test
    fun `str match without catch-all is rejected`() {
        assertTrue(fails("let f = { s: Str } -> match s is\n  \"a\" -> 1\n  \"b\" -> 2\nf { s = \"x\" }"))
    }

    @Test
    fun `str match with wildcard is accepted`() {
        assertFalse(fails("let f = { s: Str } -> match s is\n  \"a\" -> 1\n  _ -> 0\nf { s = \"x\" }"))
    }

    @Test
    fun `variant exhaustive still passes`() {
        assertFalse(fails("type C = | R, G, B |\nlet f = { c: C } -> match c is\n  R -> 1\n  G -> 2\n  B -> 3\nf { c = R }"))
    }

    @Test
    fun `variant non-exhaustive still fails`() {
        assertTrue(fails("type C = | R, G, B |\nlet f = { c: C } -> match c is\n  R -> 1\n  G -> 2\nf { c = R }"))
    }

    // ---- other non-enumerable primitives ----

    @Test
    fun `float match without catch-all is rejected`() {
        assertTrue(fails("let f = { x: Float } -> match x is\n  0.0 -> 1\n  1.0 -> 2\nf { x = 3.0 }"))
    }

    @Test
    fun `nat match without catch-all is rejected`() {
        assertTrue(fails("let f = { n: Nat } -> match n is\n  0n -> 1\n  1n -> 2\nf { n = 5n }"))
    }

    @Test
    fun `float match with wildcard is accepted`() {
        assertFalse(fails("let f = { x: Float } -> match x is\n  0.0 -> 1\n  _ -> 0\nf { x = 3.0 }"))
    }

    // ---- error content ----

    @Test
    fun `error message points at exhaustiveness and mentions wildcard`() {
        val msg = errorFor("let f = { n: Int } -> match n is\n  0 -> 1\nf { n = 5 }")
        assertNotNull(msg)
        assertTrue(
            msg!!.contains("all cases") || msg.contains("exhaustive") || msg.contains("wildcard"),
            "expected an exhaustiveness message, got: $msg"
        )
    }

    // ---- catch-all variations ----

    @Test
    fun `single-arm int match without catch-all is rejected`() {
        assertTrue(fails("let f = { n: Int } -> match n is\n  0 -> 1\nf { n = 5 }"))
    }

    @Test
    fun `wildcard-only int match is accepted`() {
        assertFalse(fails("let f = { n: Int } -> match n is\n  _ -> 0\nf { n = 5 }"))
    }

    @Test
    fun `variable-only int match is accepted`() {
        assertFalse(fails("let f = { n: Int } -> match n is\n  x -> x + 1\nf { n = 5 }"))
    }

    @Test
    fun `underscore-prefixed variable acts as catch-all`() {
        assertFalse(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  _rest -> 2\nf { n = 5 }"))
    }

    // ---- guard on the catch-all must NOT count as exhaustive ----

    @Test
    fun `guarded wildcard is not a real catch-all`() {
        // `_ if cond` doesn't cover the case when cond is false, so the match is
        // still non-exhaustive.
        assertTrue(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  x if x > 10 -> 2\nf { n = 5 }"))
    }

    // ---- nesting: the check must reach matches inside larger expressions ----

    @Test
    fun `int match without catch-all inside a do-block is rejected`() {
        assertTrue(fails(
            "let f = { n: Int } -> do\n  let r = match n is\n    0 -> 1\n    1 -> 2\n  r\nf { n = 5 }"
        ))
    }

    @Test
    fun `int match without catch-all as a lambda body is rejected`() {
        assertTrue(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  1 -> 2\nf { n = 5 }"))
    }

    // ---- literal-and-wildcard mix stays fine ----

    @Test
    fun `many int literals plus wildcard is accepted`() {
        assertFalse(fails(
            "let f = { n: Int } -> match n is\n  0 -> 1\n  1 -> 2\n  2 -> 3\n  _ -> 0\nf { n = 9 }"
        ))
    }

    // ---- string interpolation patterns as catch-alls ----

    @Test
    fun `whole-string capture pattern is a catch-all`() {
        // `"$(all)"` matches any string, so the match IS exhaustive.
        assertFalse(fails(
            "let f = { s: Str } -> match s is\n  \"\$(all)\" -> all\nf { s = \"x\" }"
        ))
    }

    @Test
    fun `string pattern with literal text is not a catch-all`() {
        // `"pre$(x)"` only matches strings starting with `pre`, so a Str match
        // with only this pattern is still non-exhaustive.
        assertTrue(fails(
            "let f = { s: Str } -> match s is\n  \"pre\$(x)\" -> x\nf { s = \"y\" }"
        ))
    }

    @Test
    fun `string pattern with a separator is not a catch-all`() {
        // `"$(a)-$(b)"` requires a `-`, so it doesn't cover all strings.
        assertTrue(fails(
            "let f = { s: Str } -> match s is\n  \"\$(a)-\$(b)\" -> a\nf { s = \"y\" }"
        ))
    }
}
