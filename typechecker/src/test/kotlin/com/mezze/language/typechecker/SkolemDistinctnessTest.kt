package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #57 skolem-style polymorphism checking. When a binding is annotated with
 * quantified type variables, the body must honour them:
 *  - a variable claimed polymorphic can't be forced to a concrete type (already
 *    caught by validatePolymorphismPreserved), and
 *  - two DISTINCT annotation variables (`'a`, `'b`) can't be conflated by the
 *    body — treating each as a rigid skolem, unifying two skolems is illegal.
 *
 * These pin the distinctness check added in this pass, plus the pre-existing
 * concrete-forcing checks, plus the legitimate-polymorphism cases that must
 * still be accepted.
 */
class SkolemDistinctnessTest {

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

    private fun fails(src: String): Boolean {
        val parsed = parser.parseParsedModule(src, "sk.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = false)
        return r is TypeChecker.TypeCheckResult.Failure
    }

    // ---- must FAIL: distinct vars conflated ----

    @Test
    fun `distinct annotation vars conflated by body is rejected`() {
        // Claims -> 'a but returns b (of type 'b). Real type is -> 'b.
        assertTrue(fails("let f: { a: 'a, b: 'b } -> 'a = { a, b } -> b\nf { a = 1, b = 2 }"))
    }

    // ---- must FAIL: over-claimed (already handled, guard against regression) ----

    @Test
    fun `poly var forced to concrete by arithmetic is rejected`() {
        assertTrue(fails("let f: { x: 'a } -> 'a = { x } -> x + 1\nf { x = 3 }"))
    }

    @Test
    fun `poly return forced to fixed Int is rejected`() {
        assertTrue(fails("let f: { x: 'a } -> 'a = { x } -> 42\nf { x = 3 }"))
    }

    @Test
    fun `poly var forced to a record by field access is rejected`() {
        assertTrue(fails("let f: { x: 'a } -> Int = { x } -> x.n\nf { x = { n = 5 } }"))
    }

    // ---- must PASS: genuinely polymorphic ----

    @Test
    fun `identity is genuinely polymorphic`() {
        assertFalse(fails("let f: { x: 'a } -> 'a = { x } -> x\nf { x = 3 }"))
    }

    @Test
    fun `swap keeps two vars distinct and is accepted`() {
        assertFalse(fails(
            "let f: { a: 'a, b: 'b } -> { x: 'b, y: 'a } = { a, b } -> { x = b, y = a }\n" +
            "f { a = 1, b = \"s\" }"
        ))
    }

    @Test
    fun `returning the correct one of two distinct vars is accepted`() {
        // -> 'b returning b is correct.
        assertFalse(fails("let f: { a: 'a, b: 'b } -> 'b = { a, b } -> b\nf { a = 1, b = 2 }"))
    }
}
