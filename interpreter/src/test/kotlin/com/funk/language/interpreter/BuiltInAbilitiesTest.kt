package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Comprehensive tests for built-in abilities (NumAdd, NumSub, NumMul, NumDiv, Eq, Inspect)
 * Tests both operator desugaring and automatic derivation for composite types.
 *
 * Automatic derivation is implemented for:
 * - Records: Structural equality and inspect
 * - Variants: Constructor and value-based equality and inspect
 * - Arrays ([]): Element-wise equality and inspect (when array literals are supported)
 * - Maps: Key-value equality and inspect (when available)
 * - Sets: Element-wise equality and inspect (when available)
 */
class BuiltInAbilitiesTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker
    private lateinit var interpreter: FunkInterpreter

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
        interpreter = FunkInterpreter()
    }

    // ============================================================================
    // NumAdd Tests - Addition operator (+)
    // ============================================================================

    @Test
    @DisplayName("Int addition works without imports")
    fun testIntAdditionWithoutImports() {
        val source = """
            let x = 10
            let y = 20
            let sum = x + y
            sum
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(30L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("Float addition works without imports")
    fun testFloatAdditionWithoutImports() {
        val source = """
            let x = 10.5
            let y = 2.5
            let sum = x + y
            sum
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkFloat)
        assertEquals(13.0, (result as FunkFloat).value, 0.001)
    }

    @Test
    @DisplayName("Multiple additions in expression")
    fun testMultipleAdditions() {
        val source = """
            let result = 1 + 2 + 3 + 4 + 5
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(15L, (result as FunkInt).value)
    }

    // ============================================================================
    // NumSub Tests - Subtraction operator (-)
    // ============================================================================

    @Test
    @DisplayName("Int subtraction works without imports")
    fun testIntSubtraction() {
        val source = """
            let x = 50
            let y = 20
            let diff = x - y
            diff
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(30L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("Float subtraction works without imports")
    fun testFloatSubtraction() {
        val source = """
            let x = 10.5
            let y = 2.5
            let diff = x - y
            diff
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkFloat)
        assertEquals(8.0, (result as FunkFloat).value, 0.001)
    }

    // ============================================================================
    // NumMul Tests - Multiplication operator (*)
    // ============================================================================

    @Test
    @DisplayName("Int multiplication works without imports")
    fun testIntMultiplication() {
        val source = """
            let x = 6
            let y = 7
            let product = x * y
            product
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("Float multiplication works without imports")
    fun testFloatMultiplication() {
        val source = """
            let x = 10.5
            let y = 2.0
            let product = x * y
            product
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkFloat)
        assertEquals(21.0, (result as FunkFloat).value, 0.001)
    }

    // ============================================================================
    // NumDiv Tests - Division operator (/)
    // ============================================================================

    @Test
    @DisplayName("Int division works without imports")
    fun testIntDivision() {
        val source = """
            let x = 42
            let y = 6
            let quotient = x / y
            quotient
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(7L, (result as FunkInt).value)
    }

    @Test
    @DisplayName("Float division works without imports")
    fun testFloatDivision() {
        val source = """
            let x = 21.0
            let y = 2.0
            let quotient = x / y
            quotient
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkFloat)
        assertEquals(10.5, (result as FunkFloat).value, 0.001)
    }

    // ============================================================================
    // Combined Arithmetic Tests
    // ============================================================================

    @Test
    @DisplayName("All arithmetic operators work together")
    fun testCombinedArithmetic() {
        val source = """
            let a = 10
            let b = 5
            let c = 2
            let result = (a + b) * c - a / b
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        // (10 + 5) * 2 - 10 / 5 = 15 * 2 - 2 = 30 - 2 = 28
        assertEquals(28L, (result as FunkInt).value)
    }

    // ============================================================================
    // Eq Tests - Equality operator (==) for primitives
    // ============================================================================

    @Test
    @DisplayName("Int equality returns true for equal values")
    fun testIntEqualityTrue() {
        val source = """
            let x = 42
            let y = 42
            let result = x == y
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Int equality returns false for different values")
    fun testIntEqualityFalse() {
        val source = """
            let x = 42
            let y = 99
            let result = x == y
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertFalse((result as FunkBool).value)
    }

    @Test
    @DisplayName("Float equality works")
    fun testFloatEquality() {
        val source = """
            let x = 3.14
            let y = 3.14
            let result = x == y
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("String equality works")
    fun testStringEquality() {
        val source = """
            let x = "hello"
            let y = "hello"
            let result = x == y
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Boolean equality works")
    fun testBooleanEquality() {
        val source = """
            let x = true
            let y = true
            let result = x == y
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    // ============================================================================
    // Derived Eq Tests - Automatic equality for records and variants
    // ============================================================================

    @Test
    @DisplayName("Record equality is derived automatically - equal records")
    fun testRecordEqualityTrue() {
        val source = """
            let person1 = { name = "Alice", age = 30 }
            let person2 = { name = "Alice", age = 30 }
            let result = person1 == person2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Record equality is derived automatically - different records")
    fun testRecordEqualityFalse() {
        val source = """
            let person1 = { name = "Alice", age = 30 }
            let person2 = { name = "Bob", age = 25 }
            let result = person1 == person2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertFalse((result as FunkBool).value)
    }

    @Test
    @DisplayName("Nested record equality works")
    fun testNestedRecordEquality() {
        val source = """
            let address1 = { street = "Main St", city = "NYC" }
            let person1 = { name = "Alice", address = address1 }

            let address2 = { street = "Main St", city = "NYC" }
            let person2 = { name = "Alice", address = address2 }

            let result = person1 == person2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Variant equality is derived automatically - equal variants")
    fun testVariantEqualityTrue() {
        val source = """
            type Option 'a =
                | Some { val: 'a }
                | None

            let opt1 = Some { val = 42 }
            let opt2 = Some { val = 42 }
            let result = opt1 == opt2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Variant equality is derived automatically - different values")
    fun testVariantEqualityDifferentValues() {
        val source = """
            type Option 'a =
                | Some { val: 'a }
                | None

            let opt1 = Some { val = 42 }
            let opt2 = Some { val = 99 }
            let result = opt1 == opt2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertFalse((result as FunkBool).value)
    }

    @Test
    @DisplayName("Variant equality is derived automatically - different constructors")
    fun testVariantEqualityDifferentConstructors() {
        val source = """
            type Option 'a =
                | Some { val: 'a }
                | None

            let opt1 = Some { val = 42 }
            let opt2 = None
            let result = opt1 == opt2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertFalse((result as FunkBool).value)
    }

    @Test
    @DisplayName("Variant equality for None values")
    fun testVariantEqualityNone() {
        val source = """
            type Option 'a =
                | Some { val: 'a }
                | None

            let opt1 = None
            let opt2 = None
            let result = opt1 == opt2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Complex nested structure equality")
    fun testComplexNestedEquality() {
        val source = """
            type Option 'a =
                | Some { val: 'a }
                | None

            let person1 = { name = "Alice", age = 30 }
            let person2 = { name = "Alice", age = 30 }

            let opt1 = Some { val = person1 }
            let opt2 = Some { val = person2 }

            let nested1 = { user = opt1, active = true }
            let nested2 = { user = opt2, active = true }

            let result = nested1 == nested2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    // ============================================================================
    // Integration Tests - Real-world scenarios
    // ============================================================================

    @Test
    @DisplayName("All operators work in complex expression")
    fun testComplexExpression() {
        val source = """
            let a = 10
            let b = 20
            let c = 5

            let sum = a + b
            let diff = b - a
            let product = c * 2
            let quotient = b / c

            let all_equal = (sum == 30) && (diff == 10) && (product == 10) && (quotient == 4)
            all_equal
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Operators work without any stdlib imports")
    fun testNoImportsRequired() {
        val source = """
            # No imports at all!
            let x = 10 + 20
            let y = x * 2
            let z = y - 10
            let result = z / 5
            let check = result == 10
            check
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Record with mixed field types equality")
    fun testRecordWithMixedTypes() {
        val source = """
            let rec1 = { name = "Alice", age = 30, active = true, score = 98.5 }
            let rec2 = { name = "Alice", age = 30, active = true, score = 98.5 }
            let result = rec1 == rec2
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkBool)
        assertTrue((result as FunkBool).value)
    }

    @Test
    @DisplayName("Conditional expression using operators")
    fun testConditionalWithOperators() {
        val source = """
            let x = 10
            let y = 20
            let result = if (x + y) == 30 then 1 else 0
            result
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        val result = interpreter.execute(typedProgram)

        assertTrue(result is FunkInt)
        assertEquals(1L, (result as FunkInt).value)
    }
}
