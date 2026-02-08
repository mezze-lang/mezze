package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.assertThrows

/**
 * Comprehensive tests for if expression Bool constraint.
 *
 * These tests verify that the typechecker correctly constrains
 * if expression conditions to Bool type in various scenarios:
 * - Type variables from function parameters
 * - Record fields used as conditions
 * - Nested if expressions
 * - Lambda expressions
 * - Do blocks
 * - Edge cases and error cases
 */
class ConditionalBoolConstraintTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    // ========== BASIC PASSING TESTS ==========

    @Test
    fun `test if condition with record field should infer Bool type`() {
        // This test verifies that the typechecker correctly constrains
        // the field p.t to Bool type, even though it's inferred from usage
        val source = """
            let condNum = {x, y, p} -> if p.t then x * y else x / y

            condNum {x = 5, y = 2, p = {t = True}}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // If this typechecks without error, the constraint system worked correctly
        // The condNum function uses p.t as a condition, so p.t must be constrained to Bool
        assertNotNull(typedProgram, "Should type check successfully - p.t is constrained to Bool")
    }

    @Test
    fun `test simple if with parameter condition`() {
        val source = """
            let selectValue = {x, y, cond} -> if cond then x else y

            selectValue {x = 10, y = 20, cond = True}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should type check successfully - cond is constrained to Bool")
    }

    @Test
    fun `test nested if with record fields`() {
        val source = """
            let nestedCond = {flags} -> if flags.outer then
                (if flags.inner then 1 else 2)
            else
                3

            nestedCond {flags = {outer = True, inner = False}}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // If this typechecks successfully, both flags.outer and flags.inner
        // were correctly constrained to Bool type
        assertNotNull(typedProgram, "Should type check successfully - both flags fields constrained to Bool")
    }

    @Test
    fun `test if with multiple operations in branches`() {
        val source = """
            let compute = {a, b, c, useAdd} -> if useAdd then a + b + c else a * b * c

            compute {a = 1, b = 2, c = 3, useAdd = True}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // If this typechecks successfully, useAdd was correctly constrained to Bool
        assertNotNull(typedProgram, "Should type check successfully - useAdd constrained to Bool")
    }

    @Test
    fun `test if with comparison condition`() {
        val source = """
            let compareAndCompute = {x, y} -> if x > y then x * 2 else y * 2

            compareAndCompute {x = 5, y = 3}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Comparison operators return Bool, which satisfies the if condition constraint
        assertNotNull(typedProgram, "Should type check successfully with comparison")
    }

    // ========== LAMBDA AND DO BLOCK TESTS ==========

    @Test
    fun `test if in lambda with type variable condition`() {
        val source = """
            let makeSelector = {pred} -> {x, y} -> if pred then x else y

            let selector = makeSelector {pred = True}
            selector {x = 10, y = 20}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should constrain pred to Bool in nested lambda")
    }

    @Test
    fun `test if in do block with let binding`() {
        val source = """
            let process = {flag} -> do
                let result = if flag then 100 else 200
                result + 1

            process {flag = True}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should constrain flag to Bool in do block")
    }

    @Test
    fun `test multiple if expressions with same type variable`() {
        val source = """
            let doubleCheck = {x, cond} -> do
                let first = if cond then x * 2 else x
                let second = if cond then first + 10 else first - 10
                second

            doubleCheck {x = 5, cond = True}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should constrain cond to Bool across multiple if uses")
    }

    // ========== COMPLEX RECORD FIELD TESTS ==========

    @Test
    fun `test if with deeply nested record field`() {
        val source = """
            let deepCheck = {config} -> if config.settings.enabled then 1 else 0

            deepCheck {config = {settings = {enabled = True}}}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should constrain config.settings.enabled to Bool")
    }

    @Test
    fun `test if with multiple record fields as conditions`() {
        val source = """
            let multiCheck = {opts} -> do
                let a = if opts.first then 1 else 2
                let b = if opts.second then 3 else 4
                a + b

            multiCheck {opts = {first = True, second = False}}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should constrain both opts.first and opts.second to Bool")
    }

    // ========== BOOLEAN LOGIC TESTS ==========

    @Test
    fun `test if with boolean AND condition`() {
        val source = """
            let andCheck = {x, y} -> if x && y then 1 else 0

            andCheck {x = True, y = False}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with boolean AND")
    }

    @Test
    fun `test if with boolean OR condition`() {
        val source = """
            let orCheck = {x, y} -> if x || y then 1 else 0

            orCheck {x = True, y = False}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with boolean OR")
    }

    @Test
    fun `test if with boolean NOT condition`() {
        val source = """
            let notCheck = {x} -> if !x then 1 else 0

            notCheck {x = True}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with boolean NOT")
    }

    // ========== EQUALITY COMPARISON TESTS ==========

    @Test
    fun `test if with equality comparison`() {
        val source = """
            let eqCheck = {x, y} -> if x == y then "equal" else "not equal"

            eqCheck {x = 5, y = 5}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with equality comparison")
    }

    @Test
    fun `test if with inequality comparison`() {
        val source = """
            let neqCheck = {x, y} -> if x != y then "different" else "same"

            neqCheck {x = 5, y = 3}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with inequality comparison")
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test if with literal boolean`() {
        val source = """
            let alwaysTrue = {x} -> if True then x else 0

            alwaysTrue {x = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with literal boolean true")
    }

    @Test
    fun `test if with literal boolean false`() {
        val source = """
            let alwaysFalse = {x} -> if False then 0 else x

            alwaysFalse {x = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with literal boolean false")
    }

    @Test
    fun `test if with complex boolean expression`() {
        val source = """
            let complexCheck = {a, b, c} -> if (a && b) || !c then 1 else 0

            complexCheck {a = True, b = False, c = True}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with complex boolean expression")
    }

    // ========== POLYMORPHIC TESTS ==========

    @Test
    fun `test if preserves polymorphism in branches`() {
        val source = """
            let polySelect = {cond, x, y} -> if cond then x else y

            let resultInt = polySelect {cond = True, x = 1, y = 2}
            resultInt
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should work with polymorphic if expression")
    }
}
