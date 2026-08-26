package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #TypeIdentity.Named removal: an uppercase type name in a genuine TYPE position
 * that resolves to no declaration/import/builtin is a hard TypeCheckError
 * (T0213), not the former silent acceptance (which laundered the name into a
 * structurally-anonymous type). Positive cases guard against over-rejection.
 */
class UnknownTypeValidationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `undeclared type in variant payload is rejected`() {
        val source = """
            type Box = | Wrap { val: Gadget } |
            let main = {} -> 0
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError>("undeclared 'Gadget' in a variant payload must be rejected") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(
            error.message?.contains("Gadget") == true,
            "error must name the undeclared type; got: ${error.message}"
        )
    }

    @Test
    fun `declared type in variant payload type-checks clean`() {
        val source = """
            type Gadget = { n: Int }
            type Box = | Wrap { val: Gadget } |
            let main = {} -> 0
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typed = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typed, "declared 'Gadget' should type-check clean")
    }

    @Test
    fun `undeclared type in a let annotation is rejected`() {
        val source = """
            let x: Gizmo = 0
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val error = assertThrows<TypeCheckError>("undeclared 'Gizmo' in a let annotation must be rejected") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(
            error.message?.contains("Gizmo") == true,
            "error must name the undeclared type; got: ${error.message}"
        )
    }

    @Test
    fun `valid associated-type program still type-checks (no over-rejection)`() {
        val source = """
            ability Producer for 'a having 'out where { produce: { it: 'a } -> 'out }
            impl Producer for Int having 'out = Str where { produce = { it } -> "n" }
            let f = {} -> (5).produce {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typed = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typed, "an ability with `having 'out` + impl must not be over-rejected")
    }
}
