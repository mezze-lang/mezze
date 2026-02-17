package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue

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
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let add = {x, y} -> Show.show {val = ((x + y) > (x - y))}

            add
        """.trimIndent()

        val program = parser.parse(source)

        // Should fail because (x + y) > (x - y) is Bool but there's no impl Show Bool
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true, "Error should mention Show ability")
        assertTrue(exception.message?.contains("Bool") == true, "Error should mention Bool type")
    }

    @Test
    fun `test ability call with concrete type and valid implementation should succeed`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "True" else "False"
            }

            let add = {x, y} -> Show.show {val = ((x + y) > (x - y))}

            add
        """.trimIndent()

        val program = parser.parse(source)

        // Should succeed because impl Show Bool exists
        val typedProgram = typeChecker.typeCheck(program).program
        // Just verify it doesn't throw
        assertTrue(typedProgram.items.isNotEmpty())
    }

    @Test
    fun `test ability call with polymorphic type should succeed`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let showResult = {x} -> Show.show {val = x}

            showResult
        """.trimIndent()

        val program = parser.parse(source)

        // Should succeed because x is polymorphic, not a concrete type
        val typedProgram = typeChecker.typeCheck(program).program
        assertTrue(typedProgram.items.isNotEmpty())
    }

    @Test
    fun `test ability call with Int without implementation should fail`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let test = Show.show {val = 42}

            test
        """.trimIndent()

        val program = parser.parse(source)

        // Should fail because 42 is Int but there's no impl Show Int
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Int") == true)
    }

    @Test
    fun `test ability call with String without implementation should fail`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let test = Show.show {val = "hello"}

            test
        """.trimIndent()

        val program = parser.parse(source)

        // Should fail because "hello" is String but there's no impl Show String
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("String") == true)
    }

    @Test
    fun `test nested ability calls with missing implementation`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show String where {
                show: {val: String} -> String = {val} -> "\"$(val)\""
            }

            let test = Show.show {val = Show.show {val = True}}

            test
        """.trimIndent()

        val program = parser.parse(source)

        // Inner call Show.show {val = true} should fail - no impl Show Bool
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Bool") == true)
    }
}
