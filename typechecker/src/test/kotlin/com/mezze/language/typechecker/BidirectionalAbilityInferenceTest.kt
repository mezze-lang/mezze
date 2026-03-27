package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows

/**
 * Tests for bidirectional type inference with abilities.
 *
 * In the new ability design, direct ability method calls (Ability.method {})
 * are blocked. Instead, we use method-call syntax (value.method {}).
 *
 * These tests verify that method return types are correctly inferred
 * when the result is used in a context that constrains the type.
 */
class BidirectionalAbilityInferenceTest {

    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }

    @Test
    fun `test bidirectional inference - case 1 - single Int implementation`() {
        // The ability method 'apply' takes the for-type as receiver and Str as arg,
        // returns the for-type. Bidirectional inference resolves 'a from usage (10 + x => Int).
        val code = """
            ability Transform for 'a where {
                apply: { src: Str } -> 'a
            }

            impl Transform for Int where {
                apply = { src } -> 2
            }

            let x: Int = 0
            let y = x.apply { src = "1233" }
            let ans = 10 + y
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val result = typeChecker.typeCheck(program)

            assertNotNull(result)

            val ansType = result.getVariableType("ans")
            assertNotNull(ansType)
            assertEquals(MezzeType.IntType, ansType)

        } catch (e: Exception) {
            fail("Case 1 should type check successfully: ${e.message}")
        }
    }

    @Test
    fun `test bidirectional inference - case 2 - no Float implementation`() {
        // Only Int impl exists, but usage context requires Float (10.5 + y)
        val code = """
            ability Transform for 'a where {
                apply: { src: Str } -> 'a
            }

            impl Transform for Int where {
                apply = { src } -> 2
            }

            let x: Int = 0
            let y = x.apply { src = "1233" }
            let ans = 10.5 + y
        """.trimIndent()

        // This should fail because y is Int but 10.5 + y requires Float
        assertThrows<TypeCheckError> {
            val program = parser.parse(code)
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test bidirectional inference - case 3 - first usage should determine type`() {
        // x is Int, y = x.apply returns Int. First usage (10 + y) is fine.
        // Second usage (10.4 + y) should fail because y is Int, not Float.
        val code = """
            ability Transform for 'a where {
                apply: { src: Str } -> 'a
            }

            impl Transform for Int where {
                apply = { src } -> 2
            }

            impl Transform for Float where {
                apply = { src } -> 2.2
            }

            let x: Int = 0
            let y = x.apply { src = "1233" }
            let ans = 10 + y
            let ans2 = 10.4 + y
        """.trimIndent()

        // y is Int (receiver x is Int, so 'a = Int). 10.4 + y fails.
        assertThrows<TypeCheckError> {
            val program = parser.parse(code)
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test bidirectional inference - both Int usages succeed`() {
        // Verify that using y as Int multiple times works
        val code = """
            ability Transform for 'a where {
                apply: { src: Str } -> 'a
            }

            impl Transform for Int where {
                apply = { src } -> 2
            }

            impl Transform for Float where {
                apply = { src } -> 2.2
            }

            let x: Int = 0
            let y = x.apply { src = "1233" }
            let ans = 10 + y
            let ans2 = 20 + y
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val result = typeChecker.typeCheck(program)

            assertNotNull(result)

        } catch (e: Exception) {
            fail("Multiple Int usages should succeed: ${e.message}")
        }
    }

    @Test
    fun `test bidirectional inference - Float usage with Float impl`() {
        // Verify that Float receiver with Float impl works
        val code = """
            ability Transform for 'a where {
                apply: { src: Str } -> 'a
            }

            impl Transform for Float where {
                apply = { src } -> 2.2
            }

            let x: Float = 0.0
            let y = x.apply { src = "1233" }
            let ans = 10.5 + y
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val result = typeChecker.typeCheck(program)

            assertNotNull(result)

        } catch (e: Exception) {
            fail("Float usage with Float impl should succeed: ${e.message}")
        }
    }

    @Test
    fun `test bidirectional inference - no usage context keeps type from receiver`() {
        // When using method-call syntax, the receiver determines the for-type.
        // Even without further usage context, the type is resolved from the receiver.
        val code = """
            ability Transform for 'a where {
                apply: { src: Str } -> 'a
            }

            impl Transform for Int where {
                apply = { src } -> 2
            }

            let x: Int = 0
            let y = x.apply { src = "1233" }
        """.trimIndent()

        try {
            val program = parser.parse(code)
            val result = typeChecker.typeCheck(program)

            assertNotNull(result)

            val yType = result.getVariableType("y")
            assertNotNull(yType)

            // y should be Int because receiver x is Int
            assertEquals(MezzeType.IntType, yType,
                "y should be Int because receiver x is Int")

        } catch (e: Exception) {
            fail("No usage context should still resolve type from receiver: ${e.message}")
        }
    }
}
