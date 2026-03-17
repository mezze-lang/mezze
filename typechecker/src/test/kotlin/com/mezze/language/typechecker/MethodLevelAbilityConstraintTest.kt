package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exhaustive tests for METHOD-LEVEL ability constraints.
 *
 * Key Design Decision:
 * - Ability-level constraints are DISALLOWED: `ability Ord for 'a with Eq 'a where {...}`
 * - Method-level constraints are REQUIRED: `ability Ord for 'a where { compare: {...} with Eq 'a -> ... }`
 *
 * This provides a uniform constraint model where all constraints are on functions.
 */
class MethodLevelAbilityConstraintTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    // ========================================================================
    // SECTION 1: Basic Method-Level Constraints
    // ========================================================================

    @Test
    fun `single method-level constraint works`() {
        val code = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "Int"
            }

            ability Printer for 'a where {
                print: {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "printed"
            }

            let test = {} -> "Value".print {item = 42}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `multiple method-level constraints on single method parsed correctly`() {
        val code = """
            ability Show for 'a where { show: {} -> Str }
            ability Eq for 'a where { eq: {r: 'a} -> Bool }

            ability Formatter for 'a where {
                format: {val: 'b} with Show 'b, Eq 'b -> Str
            }
        """.trimIndent()

        val result = typeCheck(code)
        val formatter = result.items.filterIsInstance<AbilityDeclaration>()
            .find { it.name == "Formatter" }!!

        assertEquals(2, formatter.operations[0].abilityConstraints.size)
        assertEquals("Show", formatter.operations[0].abilityConstraints[0].abilityName)
        assertEquals("Eq", formatter.operations[0].abilityConstraints[1].abilityName)
    }

    @Test
    fun `different methods with different constraints`() {
        val code = """
            ability Show for 'a where { show: {} -> Str }
            ability Eq for 'a where { eq: {r: 'a} -> Bool }

            ability Collection for 'c where {
                length: {} -> Int,
                contains: {item: 'a} with Eq 'a -> Bool,
                display: {item: 'a} with Show 'a -> Str
            }
        """.trimIndent()

        val result = typeCheck(code)
        val collection = result.items.filterIsInstance<AbilityDeclaration>()
            .find { it.name == "Collection" }!!

        // length has no constraints
        assertEquals(0, collection.operations[0].abilityConstraints.size)
        // contains has Eq
        assertEquals(1, collection.operations[1].abilityConstraints.size)
        assertEquals("Eq", collection.operations[1].abilityConstraints[0].abilityName)
        // display has Show
        assertEquals(1, collection.operations[2].abilityConstraints.size)
        assertEquals("Show", collection.operations[2].abilityConstraints[0].abilityName)
    }

    // ========================================================================
    // SECTION 2: Constraint Satisfaction Failures
    // ========================================================================

    @Test
    fun `constraint fails when type does not implement ability`() {
        val code = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where { show = {} -> "Int" }

            ability Printer for 'a where {
                print: {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "result"
            }

            # Float does NOT implement Show
            let test = {} -> "hi".print {item = 3.14}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Show")
        assertContains(error.message!!, "Float")
    }

    @Test
    fun `constraint fails for record type without impl`() {
        val code = """
            ability Show for 'a where { show: {} -> Str }

            type Person = {name: Str, age: Int}

            ability Printer for 'a where {
                print: {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "result"
            }

            let test = {} -> "hi".print {item = {name = "Alice", age = 30}}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Show")
    }

    // ========================================================================
    // SECTION 3: Method-Local Type Variables
    // ========================================================================

    @Test
    fun `method introduces its own type variable with constraint`() {
        val code = """
            ability Show for 'a where { show: {} -> Str }
            impl Show for Int where { show = {} -> "Int" }
            impl Show for Bool where { show = {} -> "Bool" }

            ability Formatter for 'a where {
                format: {val: 'b} with Show 'b -> Str
            }

            impl Formatter for Str where {
                format = {val} -> "formatted"
            }

            let test1 = {} -> "A".format {val = 42}
            let test2 = {} -> "B".format {val = True}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `multiple method-local type variables`() {
        val code = """
            ability Show for 'a where { show: {} -> Str }
            impl Show for Int where { show = {} -> "Int" }
            impl Show for Str where { show = {} -> "Str" }

            ability Combiner for 'a where {
                combine: {left: 'b, right: 'c} with Show 'b, Show 'c -> Str
            }

            impl Combiner for Str where {
                combine = {left, right} -> "combined"
            }

            let test = {} -> "-".combine {left = 42, right = "hello"}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 4: Constraint on Ability's Own Type Parameter
    // ========================================================================

    @Test
    fun `constraint on ability type parameter works`() {
        val code = """
            ability Eq for 'a where { eq: {r: 'a} -> Bool }
            impl Eq for Int where { eq = {r} -> it == r }

            ability Comparable for 'a where {
                compare: {r: 'a} with Eq 'a -> Int
            }

            impl Comparable for Int where {
                compare = {r} -> if it.eq {r = r} then 0 else 1
            }

            let test = {} -> 1.compare {r = 2}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `constraint fails when ability type param doesnt satisfy it`() {
        val code = """
            ability Eq for 'a where { eq: {r: 'a} -> Bool }
            impl Eq for Int where { eq = {r} -> it == r }

            ability Comparable for 'a where {
                compare: {r: 'a} with Eq 'a -> Int
            }

            impl Comparable for Float where {
                compare = {r} -> 0
            }

            let test = {} -> 1.0.compare {r = 2.0}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Eq")
    }

    // ========================================================================
    // SECTION 5: Ability-Level Constraints are REJECTED
    // ========================================================================

    @Test
    fun `ability-level constraint is rejected`() {
        val code = """
            ability Eq for 'a where { eq: {r: 'a} -> Bool }

            ability Ord for 'a with Eq 'a where {
                compare: {r: 'a} -> Int
            }
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "ability-level constraints")
        assertContains(error.message!!, "method-level")
    }

    @Test
    fun `multiple ability-level constraints are rejected`() {
        val code = """
            ability Show for 'a where { show: {} -> Str }
            ability Eq for 'a where { eq: {r: 'a} -> Bool }

            ability Serializable for 'a with Show 'a, Eq 'a where {
                serialize: {} -> Str
            }
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "ability-level constraints")
    }

    @Test
    fun `error message suggests method-level alternative`() {
        val code = """
            ability Eq for 'a where { eq: {r: 'a} -> Bool }

            ability Ord for 'a with Eq 'a where {
                compare: {r: 'a} -> Int
            }
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "method")
    }

    // ========================================================================
    // SECTION 6: Associated Types with Method-Level Constraints
    // ========================================================================

    @Test
    fun `associated type with method-level constraint`() {
        val code = """
            ability Eq for 'a where { eq: {r: 'a} -> Bool }

            ability Container for 'c having 'item where {
                empty: {} -> 'c,
                add: {elem: 'item} -> 'c,
                contains: {elem: 'item} with Eq 'item -> Bool
            }
        """.trimIndent()

        val result = typeCheck(code)
        val container = result.items.filterIsInstance<AbilityDeclaration>()
            .find { it.name == "Container" }!!

        assertEquals("'item", container.associatedType)
        assertEquals(0, container.dependencies.size)
        assertEquals(1, container.operations[2].abilityConstraints.size)
    }

    // ========================================================================
    // SECTION 7: Nested/Chained Abilities
    // ========================================================================

    @Test
    fun `chained ability methods with constraints`() {
        val code = """
            ability Inner for 'a where {
                inner: {} -> Int
            }

            impl Inner for Int where {
                inner = {} -> it
            }

            ability Outer for 'a where {
                outer: {} with Inner 'a -> Int
            }

            impl Outer for Int where {
                outer = {} -> it.inner {} + 10
            }

            let test = {} -> 5.outer {}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 8: Edge Cases
    // ========================================================================

    @Test
    fun `empty constraint list is valid`() {
        val code = """
            ability Simple for 'a where {
                op: {} -> Str
            }

            impl Simple for Int where {
                op = {} -> "simple"
            }

            let test = {} -> 42.op {}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `ability without any impls is valid`() {
        val code = """
            ability Show for 'a where { show: {} -> Str }

            ability Printer for 'a where {
                print: {item: 'b} with Show 'b -> Str
            }
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `constraint type arg is correctly parsed`() {
        val code = """
            ability Container 'elem for 'c where {
                contains: {elem: 'elem} -> Bool
            }

            ability Searchable for 'a where {
                search: {data: 'c, item: 'b} with Container 'b 'c -> Bool
            }
        """.trimIndent()

        val result = typeCheck(code)
        val searchable = result.items.filterIsInstance<AbilityDeclaration>()
            .find { it.name == "Searchable" }!!

        val constraint = searchable.operations[0].abilityConstraints[0]
        assertEquals("Container", constraint.abilityName)
        assertEquals(2, constraint.typeArgs.size)
    }
}
