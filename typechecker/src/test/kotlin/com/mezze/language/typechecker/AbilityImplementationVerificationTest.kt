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
            ability Show for 'a where {
                show: {} -> Str
            }

            let add = {x, y} -> ((x + y) > (x - y)).show {}

            add
        """.trimIndent()

        val program = parser.parse(source)

        // Should fail because (x + y) > (x - y) is Bool but there's no impl Show for Bool
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true, "Error should mention Show ability")
        assertTrue(exception.message?.contains("Bool") == true, "Error should mention Bool type")
    }

    @Test
    fun `test ability call with concrete type and valid implementation should succeed`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            let add = {x, y} -> ((x + y) > (x - y)).show {}

            add
        """.trimIndent()

        val program = parser.parse(source)

        // Should succeed because impl Show for Bool exists
        val typedProgram = typeChecker.typeCheck(program).program
        // Just verify it doesn't throw
        assertTrue(typedProgram.items.isNotEmpty())
    }

    @Test
    fun `test ability call with polymorphic type should succeed`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            let showResult = {x} -> x.show {}

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
            ability Show for 'a where {
                show: {} -> Str
            }

            let test = 42.show {}

            test
        """.trimIndent()

        val program = parser.parse(source)

        // Should fail because 42 is Int but there's no impl Show for Int
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Int") == true)
    }

    @Test
    fun `test ability call with String without implementation should fail`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            let test = "hello".show {}

            test
        """.trimIndent()

        val program = parser.parse(source)

        // Should fail because "hello" is String but there's no impl Show for Str
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Str") == true)
    }

    @Test
    fun `test nested ability calls with missing implementation`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Str where {
                show = {} -> "\"$(it)\""
            }

            let test = True.show {}.show {}

            test
        """.trimIndent()

        val program = parser.parse(source)

        // Inner call true.show {} should fail - no impl Show for Bool
        val exception = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }

        assertTrue(exception.message?.contains("Show") == true)
        assertTrue(exception.message?.contains("Bool") == true)
    }
}
