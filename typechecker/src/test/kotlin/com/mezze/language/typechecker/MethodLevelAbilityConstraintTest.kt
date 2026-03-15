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
 * - Ability-level constraints are DISALLOWED: `ability Ord 'a with Eq 'a where {...}`
 * - Method-level constraints are REQUIRED: `ability Ord 'a where { compare: {...} with Eq 'a -> ... }`
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
            ability Show 'a where {
                show: {val: 'a} -> Str
            }

            impl Show Int where {
                show = {val} -> "Int"
            }

            ability Printer 'a where {
                print: {prefix: 'a, item: 'b} with Show 'b -> Str
            }

            impl Printer Str where {
                print = {prefix, item} -> "printed"
            }

            let test = {} -> Printer.print {prefix = "Value", item = 42}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `multiple method-level constraints on single method parsed correctly`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Formatter 'a where {
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
            ability Show 'a where { show: {val: 'a} -> Str }
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Collection 'c where {
                length: {col: 'c} -> Int,
                contains: {col: 'c, item: 'a} with Eq 'a -> Bool,
                display: {col: 'c, item: 'a} with Show 'a -> Str
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
            ability Show 'a where {
                show: {val: 'a} -> Str
            }

            impl Show Int where { show = {val} -> "Int" }

            ability Printer 'a where {
                print: {prefix: 'a, item: 'b} with Show 'b -> Str
            }

            impl Printer Str where {
                print = {prefix, item} -> "result"
            }

            # Float does NOT implement Show
            let test = {} -> Printer.print {prefix = "hi", item = 3.14}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Show")
        assertContains(error.message!!, "Float")
    }

    @Test
    fun `constraint fails for record type without impl`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }

            type Person = {name: Str, age: Int}

            ability Printer 'a where {
                print: {prefix: 'a, item: 'b} with Show 'b -> Str
            }

            impl Printer Str where {
                print = {prefix, item} -> "result"
            }

            let test = {} -> Printer.print {prefix = "hi", item = {name = "Alice", age = 30}}
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
            ability Show 'a where { show: {val: 'a} -> Str }
            impl Show Int where { show = {val} -> "Int" }
            impl Show Bool where { show = {val} -> "Bool" }

            ability Formatter 'a where {
                format: {tag: 'a, val: 'b} with Show 'b -> Str
            }

            impl Formatter Str where {
                format = {tag, val} -> "formatted"
            }

            let test1 = {} -> Formatter.format {tag = "A", val = 42}
            let test2 = {} -> Formatter.format {tag = "B", val = True}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `multiple method-local type variables`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }
            impl Show Int where { show = {val} -> "Int" }
            impl Show Str where { show = {val} -> "Str" }

            ability Combiner 'a where {
                combine: {sep: 'a, left: 'b, right: 'c} with Show 'b, Show 'c -> Str
            }

            impl Combiner Str where {
                combine = {sep, left, right} -> "combined"
            }

            let test = {} -> Combiner.combine {sep = "-", left = 42, right = "hello"}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 4: Constraint on Ability's Own Type Parameter
    // ========================================================================

    @Test
    fun `constraint on ability type parameter works`() {
        val code = """
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }
            impl Eq Int where { eq = {l, r} -> l == r }

            ability Comparable 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            impl Comparable Int where {
                compare = {l, r} -> if Eq.eq {l = l, r = r} then 0 else 1
            }

            let test = {} -> Comparable.compare {l = 1, r = 2}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `constraint fails when ability type param doesnt satisfy it`() {
        val code = """
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }
            impl Eq Int where { eq = {l, r} -> l == r }

            ability Comparable 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            impl Comparable Float where {
                compare = {l, r} -> 0
            }

            let test = {} -> Comparable.compare {l = 1.0, r = 2.0}
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
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Ord 'a with Eq 'a where {
                compare: {l: 'a, r: 'a} -> Int
            }
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "ability-level constraints")
        assertContains(error.message!!, "method-level")
    }

    @Test
    fun `multiple ability-level constraints are rejected`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Serializable 'a with Show 'a, Eq 'a where {
                serialize: {val: 'a} -> Str
            }
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "ability-level constraints")
    }

    @Test
    fun `error message suggests method-level alternative`() {
        val code = """
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Ord 'a with Eq 'a where {
                compare: {l: 'a, r: 'a} -> Int
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
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Container 'c having 'item where {
                empty: {} -> 'c,
                add: {container: 'c, elem: 'item} -> 'c,
                contains: {container: 'c, elem: 'item} with Eq 'item -> Bool
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
            ability Inner 'a where {
                inner: {val: 'a} -> Int
            }

            impl Inner Int where {
                inner = {val} -> val
            }

            ability Outer 'a where {
                outer: {val: 'a} with Inner 'a -> Int
            }

            impl Outer Int where {
                outer = {val} -> Inner.inner {val = val} + 10
            }

            let test = {} -> Outer.outer {val = 5}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 8: Edge Cases
    // ========================================================================

    @Test
    fun `empty constraint list is valid`() {
        val code = """
            ability Simple 'a where {
                op: {val: 'a} -> Str
            }

            impl Simple Int where {
                op = {val} -> "simple"
            }

            let test = {} -> Simple.op {val = 42}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `ability without any impls is valid`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }

            ability Printer 'a where {
                print: {item: 'b} with Show 'b -> Str
            }
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `constraint type arg is correctly parsed`() {
        val code = """
            ability Container 'c 'elem where {
                contains: {c: 'c, elem: 'elem} -> Bool
            }

            ability Searchable 'a where {
                search: {data: 'c, item: 'b} with Container 'c 'b -> Bool
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
