package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class AbilityImplementationVerificationTest {

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
    fun `test ability call with concrete type but no implementation should fail`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            let add = {x, y} -> ((x + y) > (x - y)).show {}

            add
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should fail because (x + y) > (x - y) is Bool but there's no impl Show for Bool
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("Show") == true, "Error should mention Show ability")
        assertTrue(exception.message?.contains("Bool") == true, "Error should mention Bool type")
    }

    @Test
    fun `test ability call with concrete type and valid implementation should succeed`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Bool where {
                show = {it} -> if it then "True" else "False"
            }

            let add = {x, y} -> ((x + y) > (x - y)).show {}

            add
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should succeed because impl Show for Bool exists
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        // Just verify it doesn't throw
        assertNotNull(result)
    }

    @Test
    fun `test ability call with polymorphic type should succeed`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            let showResult = {x} -> x.show {}

            showResult
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should succeed because x is polymorphic, not a concrete type
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(result)
    }

    @Test
    fun `test ability call with Int without implementation should fail`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            let test = 42.show {}

            test
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should fail because 42 is Int but there's no impl Show for Int
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Int") == true)
    }

    @Test
    fun `test ability call with String without implementation should fail`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            let test = "hello".show {}

            test
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Should fail because "hello" is String but there's no impl Show for Str
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Str") == true)
    }

    @Test
    fun `test nested ability calls with missing implementation`() {
        val source = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
            }

            impl Show for Str where {
                show = {it} -> "\"$(it)\""
            }

            let test = True.show {}.show {}

            test
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Inner call true.show {} should fail - no impl Show for Bool
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Bool") == true)
    }
}
