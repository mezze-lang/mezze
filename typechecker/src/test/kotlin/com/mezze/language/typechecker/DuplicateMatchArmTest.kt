package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #80: a match arm that repeats an earlier literal value or bare constructor is
 * unreachable (a duplicate) and rejected as an error — consistent with the
 * existing unreachable-pattern errors (e.g. an arm after a catch-all).
 */
class DuplicateMatchArmTest {

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

    private fun error(src: String): String? {
        val r = typeChecker.checkParsedModule(parser.parseParsedModule(src, "m.mz"), Paths.get("."), enablePrelude = false)
        return when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors.firstOrNull()?.message
            is TypeChecker.TypeCheckResult.Success -> null
        }
    }

    private fun fails(src: String) = error(src) != null

    // ---- duplicate constructor / bool arms ----

    @Test
    fun `duplicate Bool arm is rejected`() {
        assertTrue(fails("let f = { b: Bool } -> match b is\n  True -> 1\n  True -> 2\n  False -> 3\nf { b = True }"))
    }

    @Test
    fun `duplicate variant constructor arm is rejected`() {
        assertTrue(fails("type C = | R, G, B |\nlet f = { c: C } -> match c is\n  R -> 1\n  G -> 2\n  R -> 3\n  B -> 4\nf { c = R }"))
    }

    // ---- duplicate literal arms ----

    @Test
    fun `duplicate int literal arm is rejected`() {
        assertTrue(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  1 -> 2\n  0 -> 3\n  _ -> 4\nf { n = 5 }"))
    }

    @Test
    fun `duplicate string literal arm is rejected`() {
        assertTrue(fails("let f = { s: Str } -> match s is\n  \"a\" -> 1\n  \"b\" -> 2\n  \"a\" -> 3\n  _ -> 4\nf { s = \"x\" }"))
    }

    // ---- must PASS: distinct arms ----

    @Test
    fun `distinct constructor arms are accepted`() {
        assertFalse(fails("type C = | R, G, B |\nlet f = { c: C } -> match c is\n  R -> 1\n  G -> 2\n  B -> 3\nf { c = R }"))
    }

    @Test
    fun `distinct int literal arms are accepted`() {
        assertFalse(fails("let f = { n: Int } -> match n is\n  0 -> 1\n  1 -> 2\n  2 -> 3\n  _ -> 4\nf { n = 5 }"))
    }

    // ---- guards make a repeated pattern reachable ----

    @Test
    fun `repeated constructor with a guard on the first is allowed`() {
        // `R if cond` may not fire, so a later plain `R` is reachable — not a dup.
        assertFalse(fails("type C = | R, G |\nlet f = { c: C, k: Int } -> match c is\n  R if k > 0 -> 1\n  R -> 2\n  G -> 3\nf { c = R, k = 1 }"))
    }

    @Test
    fun `repeated int literal where the first is guarded is allowed`() {
        assertFalse(fails("let f = { n: Int } -> match n is\n  0 if False -> 1\n  0 -> 2\n  _ -> 3\nf { n = 0 }"))
    }

    // ---- the existing after-catch-all error still works ----

    @Test
    fun `arm after a wildcard is still rejected`() {
        assertTrue(fails("let f = { n: Int } -> match n is\n  _ -> 1\n  0 -> 2\nf { n = 5 }"))
    }
}
