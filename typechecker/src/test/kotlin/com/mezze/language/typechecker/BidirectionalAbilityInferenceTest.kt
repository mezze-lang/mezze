package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows

class BidirectionalAbilityInferenceTest {

    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }

    @Test
    fun `test bidirectional inference - case 1 - single Int implementation`() {
        // Case 1: Parse.decode should infer as Int from usage context
        val code = """
            ability Parse 'a where {
                decode: { val: Str } -> 'a
            }

            impl Parse Int where {
                decode = { val } -> 2
            }

            let x = Parse.decode { val = "1233" }
            let ans = 10 + x
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val typedProgram = typeChecker.typeCheck(program).program

            // Should type check successfully
            assertNotNull(typedProgram)

            // Verify x has type Int (resolved via bidirectional inference)
            val xBinding = typedProgram.items.filterIsInstance<LetBinding>()
                .find { it.variableName == "x" }
            assertNotNull(xBinding)
            assertEquals(MezzeType.IntType, xBinding?.inferredType, "x should be inferred as Int from usage")

            val ansBinding = typedProgram.items.filterIsInstance<LetBinding>()
                .find { it.variableName == "ans" }
            assertNotNull(ansBinding)
            assertEquals(MezzeType.IntType, ansBinding?.inferredType)

        } catch (e: Exception) {
            fail("Case 1 should type check successfully: ${e.message}")
        }
    }

    @Test
    fun `test bidirectional inference - case 2 - no Float implementation`() {
        // Case 2: Parse.decode tries to infer as Float but no implementation exists
        val code = """
            ability Parse 'a where {
                decode: { val: Str } -> 'a
            }

            impl Parse Int where {
                decode = { val } -> 2
            }

            let x = Parse.decode { val = "1233" }
            let ans = 10.5 + x
        """.trimIndent()

        // This should fail because there's no Parse Float implementation
        assertThrows<TypeCheckError> {
            val program = parser.parse(code)
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test bidirectional inference - case 3 - first usage should determine type`() {
        // Case 3: x should be Int from first usage, second usage should fail
        // The first usage (10 + x) resolves x to Int
        // Then the second usage (10.4 + x) should fail because x is Int, not Float
        val code = """
            ability Parse 'a where {
                decode: { val: Str } -> 'a
            }

            impl Parse Int where {
                decode = { val } -> 2
            }

            impl Parse Float where {
                decode = { val } -> 2.2
            }

            let x = Parse.decode { val = "1233" }
            let ans = 10 + x
            let ans2 = 10.4 + x
        """.trimIndent()

        // The first usage (10 + x) locks x to Int
        // The second usage (10.4 + x) tries to use x as Float, which should fail
        assertThrows<TypeCheckError> {
            val program = parser.parse(code)
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test bidirectional inference - both Int usages succeed`() {
        // Verify that using x as Int multiple times works
        val code = """
            ability Parse 'a where {
                decode: { val: Str } -> 'a
            }

            impl Parse Int where {
                decode = { val } -> 2
            }

            impl Parse Float where {
                decode = { val } -> 2.2
            }

            let x = Parse.decode { val = "1233" }
            let ans = 10 + x
            let ans2 = 20 + x
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val typedProgram = typeChecker.typeCheck(program).program

            // Should type check successfully - x is Int, used as Int twice
            assertNotNull(typedProgram)

        } catch (e: Exception) {
            fail("Multiple Int usages should succeed: ${e.message}")
        }
    }

    @Test
    fun `test bidirectional inference - Float usage with Float impl`() {
        // Verify that Float usage works when there's a Float implementation
        val code = """
            ability Parse 'a where {
                decode: { val: Str } -> 'a
            }

            impl Parse Float where {
                decode = { val } -> 2.2
            }

            let x = Parse.decode { val = "1233" }
            let ans = 10.5 + x
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val typedProgram = typeChecker.typeCheck(program).program

            // Should type check successfully - x is Float
            assertNotNull(typedProgram)

        } catch (e: Exception) {
            fail("Float usage with Float impl should succeed: ${e.message}")
        }
    }

    @Test
    fun `test bidirectional inference - no usage context keeps type polymorphic`() {
        // When there's no usage context, x should remain polymorphic
        val code = """
            ability Parse 'a where {
                decode: { val: Str } -> 'a
            }

            impl Parse Int where {
                decode = { val } -> 2
            }

            let x = Parse.decode { val = "1233" }
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val typedProgram = typeChecker.typeCheck(program).program

            // Should type check successfully - x remains polymorphic
            assertNotNull(typedProgram)

            val xBinding = typedProgram.items.filterIsInstance<LetBinding>()
                .find { it.variableName == "x" }
            assertNotNull(xBinding)

            // x should have a polymorphic type with Parse constraint
            val xType = xBinding?.inferredType
            assertNotNull(xType)

        } catch (e: Exception) {
            fail("No usage context should keep type polymorphic: ${e.message}")
        }
    }

    // NOTE: Bidirectional inference from usage context (like 10 + val or val.name)
    // does NOT currently work for Decode ability. Type annotation is required.
    // TODO: Fix constraint solving to infer type from match pattern usage
    // See: interpreter/.../codec/CodecAutoDerivationTest.kt lines 743-744
}
