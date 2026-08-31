package com.mezze.language.typechecker.debug

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class InvariantCheckerIntegrationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.invariants", "true")
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @AfterEach
    fun teardown() {
        System.clearProperty("mezze.invariants")
    }

    @Test
    fun `invariants hold for simple bindings`() {
        val source = """
            let x = 42
            let y = "hello"
            let z = x
            z
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "invariant_simple.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))
    }

    @Test
    fun `invariants hold for function types`() {
        val source = """
            let add = {a: Int, b: Int} -> a + b
            let result = add { a = 1, b = 2 }
            result
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "invariant_function.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))
    }

    @Test
    fun `invariants hold for polymorphic identity`() {
        val source = """
            let identity = {x} -> x
            let a = identity { x = 42 }
            let b = identity { x = "hello" }
            a
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "invariant_poly.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))
    }

    @Test
    fun `invariants hold for records`() {
        val source = """
            let point = { x = 1, y = 2 }
            let px = point.x
            px
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "invariant_record.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))
    }

    @Test
    fun `invariants hold for match expressions`() {
        val source = """
            let classify = {n: Int} -> match n > 0 is
              True -> "positive"
              False -> "non-positive"
            let result = classify { n = 5 }
            result
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "invariant_match.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))
    }

    @Test
    fun `invariants hold for lists`() {
        val source = """
            let xs = [1, 2, 3]
            xs
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "invariant_list.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))
    }

    /**
     * S0.4/#373 — post-solve substitution IDEMPOTENCE on the associated-type
     * family. This is the shape S0.1 found red: an ability with a `having`
     * associated type whose generic use leaves the global solve holding a
     * FunctionType RHS that still mentions a var the SAME substitution rewrites
     * (`'d -> Fn(_ -> 'e)` beside `'e -> Str`), so `apply(apply(t)) != apply(t)`
     * on the raw map. The post-solve boundary now `saturate()`s the composed
     * substitution — resolving each RHS to the fixpoint `apply` already reaches —
     * and then asserts idempotence via `checkSubstitutionIdempotence` (a real gated
     * invariant under `mezze.invariants=true`, set in setup). This typeCheck runs
     * that invariant and would throw InvariantViolation if saturation regressed or
     * the non-idempotence otherwise reopened. Passing = the composed per-binding +
     * global substitution is idempotent in one apply.
     */
    @Test
    fun `post-solve substitution idempotent for ability associated types`() {
        val source = """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let mk = { x } -> x.produce {}
            let r = mk { x = 1 }
            r
        """.trimIndent()
        val parsed = parser.parseParsedModule(source, "invariant_assoc_type.mz")
        typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }
}
