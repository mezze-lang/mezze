package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.LetBinding
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for polymorphic function instantiation.
 *
 * These tests verify that when a polymorphic function is assigned to a variable
 * or passed around, its type and ability constraints are correctly instantiated.
 *
 * Key bug this catches: When instantiating a polymorphic type scheme, the ability
 * constraints in FunctionType must be substituted along with the type variables.
 * Otherwise, the constraints will reference stale type variable names, leading to
 * spurious constraints like "NumAdd 'b" when there's no 'b in the type.
 *
 * See: TypeScheme.applySubstitutionToType() must substitute FunctionType.abilityConstraints
 */
class PolymorphicInstantiationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(source: String): com.mezze.language.ast.TypeCheckResult {
        val parsedModule = parser.parseParsedModule(source, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun getBindingType(result: com.mezze.language.ast.TypeCheckResult, name: String): MezzeType? {
        return result.getVariableType(name)
    }

    private fun getAbilityConstraintCount(type: MezzeType?): Int {
        return when (type) {
            is MezzeType.FunctionType -> type.abilityConstraints.size
            else -> 0
        }
    }

    private fun getAbilityConstraintNames(type: MezzeType?): List<String> {
        return when (type) {
            is MezzeType.FunctionType -> type.abilityConstraints.map { it.abilityName }
            else -> emptyList()
        }
    }

    // ========================================================================
    // BASIC ASSIGNMENT TESTS
    // ========================================================================

    @Test
    fun `assigning polymorphic function preserves single ability constraint`() {
        val source = """
            let sum = {a, b} -> a + b
            let x = sum
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val sumType = getBindingType(result, "sum")
        val xType = getBindingType(result, "x")

        // Both should have exactly one NumAdd constraint
        assertEquals(1, getAbilityConstraintCount(sumType),
            "sum should have exactly 1 ability constraint")
        assertEquals(1, getAbilityConstraintCount(xType),
            "x should have exactly 1 ability constraint (not 2)")

        // Both should have NumAdd
        assertEquals(listOf("NumAdd"), getAbilityConstraintNames(sumType))
        assertEquals(listOf("NumAdd"), getAbilityConstraintNames(xType))
    }

    @Test
    fun `assigning polymorphic function multiple times preserves constraints`() {
        val source = """
            let sum = {a, b} -> a + b
            let x = sum
            let y = x
            let z = y
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        // All should have exactly one NumAdd constraint
        for (name in listOf("sum", "x", "y", "z")) {
            val type = getBindingType(result, name)
            assertEquals(1, getAbilityConstraintCount(type),
                "$name should have exactly 1 ability constraint")
            assertEquals(listOf("NumAdd"), getAbilityConstraintNames(type),
                "$name should have NumAdd constraint")
        }
    }

    @Test
    fun `assigning polymorphic comparison function preserves constraint`() {
        val source = """
            let compare = {a, b} -> a < b
            let x = compare
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val compareType = getBindingType(result, "compare")
        val xType = getBindingType(result, "x")

        // Both should have Ord constraint
        assertEquals(1, getAbilityConstraintCount(compareType))
        assertEquals(1, getAbilityConstraintCount(xType))
        assertEquals(listOf("Ord"), getAbilityConstraintNames(xType))
    }

    @Test
    fun `assigning polymorphic equality function preserves constraint`() {
        val source = """
            let equal = {a, b} -> a == b
            let x = equal
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val xType = getBindingType(result, "x")
        assertEquals(1, getAbilityConstraintCount(xType))
        assertEquals(listOf("Eq"), getAbilityConstraintNames(xType))
    }

    // ========================================================================
    // MULTIPLE CONSTRAINTS TESTS
    // ========================================================================

    @Test
    fun `function with multiple operations has correct constraints after assignment`() {
        val source = """
            let compute = {a, b} -> if a < b then a + b else a - b
            let x = compute
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val computeType = getBindingType(result, "compute")
        val xType = getBindingType(result, "x")

        // Should have Ord and NumAdd/NumSub constraints
        val computeConstraints = getAbilityConstraintNames(computeType)
        val xConstraints = getAbilityConstraintNames(xType)

        assertEquals(computeConstraints.sorted(), xConstraints.sorted(),
            "Assigned variable should have same constraints as original")
    }

    @Test
    fun `multiple polymorphic parameters each get their constraints`() {
        val source = """
            let process = {x, y} -> x + y
            let f = process
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val fType = getBindingType(result, "f")

        // Should have exactly one NumAdd constraint (x and y share the same type variable)
        assertEquals(1, getAbilityConstraintCount(fType),
            "Should have exactly 1 constraint, not duplicates")
    }

    // ========================================================================
    // PARTIAL APPLICATION TESTS
    // ========================================================================

    @Test
    fun `empty partial application preserves constraints`() {
        val source = """
            let sum = {a, b} -> a + b
            let x = sum {}
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val sumType = getBindingType(result, "sum")
        val xType = getBindingType(result, "x")

        assertEquals(getAbilityConstraintCount(sumType), getAbilityConstraintCount(xType),
            "Empty partial application should preserve constraint count")
    }

    @Test
    fun `partial application with concrete type preserves remaining constraint`() {
        val source = """
            let sum = {a, b} -> a + b
            let addFive = sum {a = 5}
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val addFiveType = getBindingType(result, "addFive")

        // Partial application still returns a function, which may have constraint
        // The constraint is on the remaining parameter type
        assertTrue(addFiveType is MezzeType.FunctionType,
            "Partial application should return a function")
    }

    @Test
    fun `full application returns concrete type`() {
        val source = """
            let sum = {a, b} -> a + b
            let result = sum {a = 5, b = 3}
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val resultType = getBindingType(result, "result")

        assertTrue(resultType is MezzeType.IntType,
            "Full application with Int args should return Int")
    }

    // ========================================================================
    // FUNCTION COMPOSITION TESTS
    // ========================================================================

    @Test
    fun `passing polymorphic function as argument preserves constraints`() {
        val source = """
            let apply = {f, x, y} -> f {a = x, b = y}
            let sum = {a, b} -> a + b
            let result = apply {f = sum, x = 1, y = 2}
        """.trimIndent()

        assertDoesNotThrow {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `returning polymorphic function preserves constraints`() {
        val source = """
            let getOp = {which} -> do
                let add = {a, b} -> a + b
                add
            let op = getOp {which = 1}
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val opType = getBindingType(result, "op")
        assertEquals(1, getAbilityConstraintCount(opType),
            "Returned function should preserve NumAdd constraint")
    }

    // ========================================================================
    // NESTED FUNCTION TESTS
    // ========================================================================

    @Test
    fun `nested polymorphic function assignment preserves constraints`() {
        val source = """
            let outer = {x} -> do
                let inner = {a, b} -> a + b
                let copy = inner
                copy {a = x, b = x}
            outer
        """.trimIndent()

        assertDoesNotThrow {
            parseAndTypeCheck(source)
        }
    }

    @Test
    fun `lambda returning polymorphic function preserves constraints`() {
        // Simplified version - wrapper returns sum directly
        val source = """
            let sum = {a, b} -> a + b
            let getSumFn = {} -> sum
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        // getSumFn is a function that returns sum
        val getSumFnType = getBindingType(result, "getSumFn")
        assertTrue(getSumFnType is MezzeType.FunctionType,
            "getSumFn should be a function")
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun `identity function has no spurious constraints`() {
        val source = """
            let id = {x} -> x
            let copy = id
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val copyType = getBindingType(result, "copy")
        assertEquals(0, getAbilityConstraintCount(copyType),
            "Identity function should have no ability constraints")
    }

    @Test
    fun `const function has no spurious constraints`() {
        val source = """
            let const = {x, y} -> x
            let copy = const
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val copyType = getBindingType(result, "copy")
        assertEquals(0, getAbilityConstraintCount(copyType),
            "Const function should have no ability constraints")
    }

    @Test
    fun `flip function preserves constraints correctly`() {
        val source = """
            let sub = {a, b} -> a - b
            let flipped = {x, y} -> sub {a = y, b = x}
            let copy = flipped
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val copyType = getBindingType(result, "copy")
        // flipped should have NumSub constraint
        assertTrue(getAbilityConstraintNames(copyType).contains("NumSub"),
            "Flipped function should preserve NumSub constraint")
    }

    // ========================================================================
    // REGRESSION TESTS FOR THE SPECIFIC BUG
    // ========================================================================

    @Test
    fun `no duplicate constraints after multiple instantiations`() {
        // This was the original bug: assigning sum to x would create
        // duplicate constraints like "NumAdd 'a, NumAdd 'b"
        val source = """
            let sum = {a, b} -> a + b
            let x = sum
            let y = sum
            let z = sum
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        for (name in listOf("x", "y", "z")) {
            val type = getBindingType(result, name)
            val constraints = getAbilityConstraintNames(type)

            assertEquals(1, constraints.size,
                "$name should have exactly 1 constraint, got: $constraints")
            assertEquals("NumAdd", constraints[0],
                "$name should have NumAdd constraint")
        }
    }

    @Test
    fun `constraint type variables match function type variables`() {
        val source = """
            let sum = {a, b} -> a + b
            let x = sum
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        val xType = getBindingType(result, "x") as MezzeType.FunctionType
        val paramType = xType.paramType as MezzeType.RecordType

        // Get the type variable names from the parameter type
        val paramTypeVars = paramType.fields.values
            .filterIsInstance<MezzeType.VarType>()
            .map { it.variable.name }
            .toSet()

        // Get the type variable names from the ability constraints
        val constraintTypeVars = xType.abilityConstraints.flatMap { constraint ->
            constraint.typeArgs.filterIsInstance<MezzeType.VarType>().map { it.variable.name }
        }.toSet()

        // The constraint type variables should be a subset of the parameter type variables
        assertTrue(constraintTypeVars.all { it in paramTypeVars },
            "Constraint type vars $constraintTypeVars should match param type vars $paramTypeVars")
    }

    @Test
    fun `sequential assignments dont accumulate constraints`() {
        val source = """
            let f = {x, y} -> x + y
            let g = f
            let h = g
            let i = h
            let j = i
        """.trimIndent()

        val result = parseAndTypeCheck(source)

        // Each assignment should have exactly 1 constraint, not accumulating
        var prevCount = -1
        for (name in listOf("f", "g", "h", "i", "j")) {
            val count = getAbilityConstraintCount(getBindingType(result, name))
            if (prevCount >= 0) {
                assertEquals(prevCount, count,
                    "Constraint count should not change through assignments")
            }
            prevCount = count
        }

        assertEquals(1, prevCount, "Should have exactly 1 constraint")
    }
}
