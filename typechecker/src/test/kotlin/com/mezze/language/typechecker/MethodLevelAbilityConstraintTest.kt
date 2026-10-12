package com.mezze.language.typechecker

import java.nio.file.Paths
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
 * - Ability-level constraints are DISALLOWED: `ability Ord for 'a with Same 'a where {...}`
 * - Method-level constraints are REQUIRED: `ability Ord for 'a where { compare: {...} with Same 'a -> ... }`
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

    private fun typeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // ========================================================================
    // SECTION 1: Basic Method-Level Constraints
    // ========================================================================

    @Test
    fun `single method-level constraint works`() {
        val code = """
            ability Show for 'a where {
                show: {it: 'a} -> Str
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
            ability Show for 'a where { show: {it: 'a} -> Str }
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Formatter for 'a where {
                format: {val: 'b} with Show 'b, Same 'b -> Str
            }
        """.trimIndent()

        typeCheck(code) // ensure type checking succeeds
        val module = parser.parseParsedModule(code, "test.mz")
        val formatter = module.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
            .find { it.name == "Formatter" }!!

        assertEquals(2, formatter.operations[0].abilityConstraints.size)
        assertEquals("Show", formatter.operations[0].abilityConstraints[0].abilityName)
        assertEquals("Same", formatter.operations[0].abilityConstraints[1].abilityName)
    }

    @Test
    fun `different methods with different constraints`() {
        val code = """
            ability Show for 'a where { show: {it: 'a} -> Str }
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Collection for 'c where {
                length: {} -> Int,
                contains: {item: 'a} with Same 'a -> Bool,
                display: {item: 'a} with Show 'a -> Str
            }
        """.trimIndent()

        typeCheck(code) // ensure type checking succeeds
        val module = parser.parseParsedModule(code, "test.mz")
        val collection = module.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
            .find { it.name == "Collection" }!!

        // length has no constraints
        assertEquals(0, collection.operations[0].abilityConstraints.size)
        // contains has Eq
        assertEquals(1, collection.operations[1].abilityConstraints.size)
        assertEquals("Same", collection.operations[1].abilityConstraints[0].abilityName)
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
                show: {it: 'a} -> Str
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
            ability Show for 'a where { show: {it: 'a} -> Str }

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
            ability Show for 'a where { show: {it: 'a} -> Str }
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
            ability Show for 'a where { show: {it: 'a} -> Str }
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
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }
            impl Same for Int where { same = {it, r} -> it == r }

            ability Comparable for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            impl Comparable for Int where {
                compare = {it, r} -> if it.same {r = r} then 0 else 1
            }

            let test = {} -> 1.compare {r = 2}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `constraint fails when ability type param doesnt satisfy it`() {
        // Use a custom type (not a built-in primitive) to test that constraint
        // checking works — built-in primitives always satisfy core abilities.
        val code = """
            type Wrapper = { val: Int }

            ability MyEq for 'a where { my_eq: {r: 'a} -> Bool }
            impl MyEq for Int where { my_eq = {it, r} -> it == r }

            ability Comparable for 'a where {
                compare: {r: 'a} with MyEq 'a -> Int
            }

            impl Comparable for Wrapper where {
                compare = {r} -> 0
            }

            let w1: Wrapper = { val = 1 }
            let w2: Wrapper = { val = 2 }
            let test = {} -> w1.compare {r = w2}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "MyEq")
    }

    // ========================================================================
    // SECTION 5: Ability-Level Constraints are REJECTED
    // ========================================================================

    // Ability-level `with` constraints are now rejected at parse time
    // (P0119) rather than at typecheck (V0410). See audit task #212.
    // `typeCheck(code)` rethrows the parser's exception unchanged.

    @Test
    fun `ability-level constraint is rejected`() {
        val code = """
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Ord for 'a with Same 'a where {
                compare: {r: 'a} -> Int
            }
        """.trimIndent()

        val error = assertThrows<Throwable> { typeCheck(code) }
        assertContains(error.message!!, "ability-level 'with' constraint")
    }

    @Test
    fun `multiple ability-level constraints are rejected`() {
        val code = """
            ability Show for 'a where { show: {it: 'a} -> Str }
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Serializable for 'a with Show 'a, Same 'a where {
                serialize: {} -> Str
            }
        """.trimIndent()

        val error = assertThrows<Throwable> { typeCheck(code) }
        assertContains(error.message!!, "ability-level 'with' constraint")
    }

    @Test
    fun `error message suggests method-level alternative`() {
        val code = """
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Ord for 'a with Same 'a where {
                compare: {r: 'a} -> Int
            }
        """.trimIndent()

        val error = assertThrows<Throwable> { typeCheck(code) }
        // The parser hints reference operations and `with` — the canonical
        // per-method form.
        assertTrue((error.message ?: "").let { it.contains("operation") || it.contains("with") })
    }

    // ========================================================================
    // SECTION 6: Associated Types with Method-Level Constraints
    // ========================================================================

    @Test
    fun `associated type with method-level constraint`() {
        val code = """
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Container for 'c having 'item where {
                empty: {} -> 'c,
                put: {elem: 'item} -> 'c,
                contains: {elem: 'item} with Same 'item -> Bool
            }
        """.trimIndent()

        typeCheck(code) // ensure type checking succeeds
        val module = parser.parseParsedModule(code, "test.mz")
        val container = module.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
            .find { it.name == "Container" }!!

        assertEquals(listOf("'item"), container.associatedTypes)
        // Ability-level dependencies were removed in #212.
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
                inner = {it} -> it
            }

            ability Outer for 'a where {
                outer: {} with Inner 'a -> Int
            }

            impl Outer for Int where {
                outer = {it} -> it.inner {} + 10
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
            ability Show for 'a where { show: {it: 'a} -> Str }

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

        typeCheck(code) // ensure type checking succeeds
        val module = parser.parseParsedModule(code, "test.mz")
        val searchable = module.decls.filterIsInstance<com.mezze.language.parsedast.ParsedAbilityDecl>()
            .find { it.name == "Searchable" }!!

        val constraint = searchable.operations[0].abilityConstraints[0]
        assertEquals("Container", constraint.abilityName)
        assertEquals(2, constraint.typeArgs.size)
    }
}
