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
}
