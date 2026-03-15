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

/**
 * Tests for METHOD-LEVEL CONSTRAINT PROPAGATION.
 *
 * When a function calls an ability method that has method-level constraints,
 * those constraints must be satisfied. This can happen in two ways:
 *
 * 1. The calling function declares the same constraints (propagation)
 * 2. The calling function calls with concrete types that satisfy the constraints
 *
 * Example:
 *   ability Ord 'a where {
 *       compare: {l: 'a, r: 'a} with Eq 'a -> Int
 *   }
 *
 *   # This function MUST declare Eq 'a because it calls Ord.compare
 *   let myCompare: {...} with Ord 'a, Eq 'a -> Int = ...
 */
class MethodLevelConstraintPropagationTest {

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
    // SECTION 1: Basic Constraint Propagation
    // ========================================================================

    @Test
    fun `function calling method with constraint must declare that constraint`() {
        val code = """
            ability Eq 'a where {
                eq: {l: 'a, r: 'a} -> Bool
            }

            ability Ord 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            impl Eq Int where { eq = {l, r} -> l == r }
            impl Ord Int where { compare = {l, r} -> 0 }

            # WRONG: Only declares Ord, but Ord.compare requires Eq
            let myCompare: {x: 'a, y: 'a} with Ord 'a -> Int = {x, y} ->
                Ord.compare {l = x, r = y}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Eq")
    }

    @Test
    fun `function declaring all required constraints passes`() {
        val code = """
            ability Eq 'a where {
                eq: {l: 'a, r: 'a} -> Bool
            }

            ability Ord 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            impl Eq Int where { eq = {l, r} -> l == r }
            impl Ord Int where { compare = {l, r} -> 0 }

            # CORRECT: Declares both Ord and Eq
            let myCompare: {x: 'a, y: 'a} with Ord 'a, Eq 'a -> Int = {x, y} ->
                Ord.compare {l = x, r = y}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `concrete type call satisfies constraint without declaration`() {
        val code = """
            ability Eq 'a where {
                eq: {l: 'a, r: 'a} -> Bool
            }

            ability Ord 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            impl Eq Int where { eq = {l, r} -> l == r }
            impl Ord Int where { compare = {l, r} -> 0 }

            # CORRECT: Concrete Int satisfies both constraints
            let compareInts = {} -> Ord.compare {l = 1, r = 2}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 2: Multi-Level Propagation (Transitive)
    // ========================================================================

    @Test
    fun `multi-level constraint propagation works`() {
        val code = """
            ability Eq 'a where {
                eq: {l: 'a, r: 'a} -> Bool
            }

            ability Ord 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            ability Sortable 'a where {
                sort: {items: ['a]} with Ord 'a, Eq 'a -> ['a]
            }

            impl Eq Int where { eq = {l, r} -> l == r }
            impl Ord Int where { compare = {l, r} -> 0 }
            impl Sortable Int where { sort = {items} -> items }

            # Calling Sortable.sort requires Ord and Eq
            let mySorter: {xs: ['b]} with Sortable 'b, Ord 'b, Eq 'b -> ['b] = {xs} ->
                Sortable.sort {items = xs}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `missing transitive constraint fails`() {
        val code = """
            ability Eq 'a where {
                eq: {l: 'a, r: 'a} -> Bool
            }

            ability Ord 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            ability Sortable 'a where {
                sort: {items: ['a]} with Ord 'a, Eq 'a -> ['a]
            }

            impl Eq Int where { eq = {l, r} -> l == r }
            impl Ord Int where { compare = {l, r} -> 0 }
            impl Sortable Int where { sort = {items} -> items }

            # WRONG: Missing Eq constraint
            let mySorter: {xs: ['b]} with Sortable 'b, Ord 'b -> ['b] = {xs} ->
                Sortable.sort {items = xs}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Eq")
    }

    // ========================================================================
    // SECTION 3: Multiple Independent Constraints
    // ========================================================================

    @Test
    fun `multiple independent constraints on method`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Debug 'a where {
                debug: {val: 'a} with Show 'a, Eq 'a -> Str
            }

            impl Show Int where { show = {val} -> "Int" }
            impl Eq Int where { eq = {l, r} -> l == r }
            impl Debug Int where { debug = {val} -> "debug" }

            # Must declare both Show and Eq
            let myDebug: {x: 'b} with Debug 'b, Show 'b, Eq 'b -> Str = {x} ->
                Debug.debug {val = x}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `partial constraints fails`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Debug 'a where {
                debug: {val: 'a} with Show 'a, Eq 'a -> Str
            }

            impl Show Int where { show = {val} -> "Int" }
            impl Eq Int where { eq = {l, r} -> l == r }
            impl Debug Int where { debug = {val} -> "debug" }

            # WRONG: Only declares Show, missing Eq
            let myDebug: {x: 'b} with Debug 'b, Show 'b -> Str = {x} ->
                Debug.debug {val = x}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Eq")
    }

    // ========================================================================
    // SECTION 4: Method-Local Type Variables with Constraints
    // ========================================================================

    @Test
    fun `method-local type var constraint propagates`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }

            impl Show Int where { show = {val} -> "Int" }

            ability Printer 'a where {
                print: {prefix: 'a, item: 'b} with Show 'b -> Str
            }

            impl Printer Str where {
                print = {prefix, item} -> "printed"
            }

            # When polymorphic in 'x, must propagate Show constraint
            let printAny: {pre: Str, val: 'x} with Show 'x -> Str = {pre, val} ->
                Printer.print {prefix = pre, item = val}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `method-local type var without constraint fails`() {
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }

            impl Show Int where { show = {val} -> "Int" }

            ability Printer 'a where {
                print: {prefix: 'a, item: 'b} with Show 'b -> Str
            }

            impl Printer Str where {
                print = {prefix, item} -> "printed"
            }

            # WRONG: Missing Show 'x constraint
            let printAny: {pre: Str, val: 'x} -> Str = {pre, val} ->
                Printer.print {prefix = pre, item = val}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Show")
    }

    // ========================================================================
    // SECTION 5: Nested Ability Method Calls
    // ========================================================================

    @Test
    fun `nested method calls propagate all constraints`() {
        val code = """
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }
            ability Ord 'a where { compare: {l: 'a, r: 'a} with Eq 'a -> Int }
            ability Show 'a where { show: {val: 'a} -> Str }

            ability Combined 'a where {
                process: {val: 'a} with Eq 'a, Ord 'a, Show 'a -> Str
            }

            impl Eq Int where { eq = {l, r} -> l == r }
            impl Ord Int where { compare = {l, r} -> 0 }
            impl Show Int where { show = {val} -> "Int" }
            impl Combined Int where { process = {val} -> "processed" }

            # Must declare all three constraints
            let fullProcess: {x: 'b} with Combined 'b, Eq 'b, Ord 'b, Show 'b -> Str = {x} ->
                Combined.process {val = x}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 6: Impl Body Uses Constrained Method
    // ========================================================================

    @Test
    fun `impl method body can use constrained methods with concrete type`() {
        val code = """
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Ord 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            impl Eq Int where { eq = {l, r} -> l == r }

            # Impl body can use Eq.eq because Int implements Eq
            impl Ord Int where {
                compare = {l, r} -> if Eq.eq {l = l, r = r} then 0 else 1
            }
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 7: Error Messages Quality
    // ========================================================================

    @Test
    fun `error message mentions missing constraint name`() {
        val code = """
            ability Eq 'a where { eq: {l: 'a, r: 'a} -> Bool }

            ability Ord 'a where {
                compare: {l: 'a, r: 'a} with Eq 'a -> Int
            }

            impl Eq Int where { eq = {l, r} -> l == r }
            impl Ord Int where { compare = {l, r} -> 0 }

            let bad: {x: 'a, y: 'a} with Ord 'a -> Int = {x, y} ->
                Ord.compare {l = x, r = y}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        // Error should clearly indicate which constraint is missing
        assertContains(error.message!!, "Eq")
    }

    // ========================================================================
    // SECTION 8: Edge Cases
    // ========================================================================

    @Test
    fun `no propagation needed when no method-level constraints`() {
        val code = """
            ability Simple 'a where {
                process: {val: 'a} -> Str
            }

            impl Simple Int where {
                process = {val} -> "simple"
            }

            # No extra constraints needed - method has none
            let myProcess: {x: 'b} with Simple 'b -> Str = {x} ->
                Simple.process {val = x}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `constraint only needed on polymorphic calls`() {
        // This test verifies that when calling a method with a concrete type
        // that satisfies the method-level constraint, no constraint declaration
        // is needed on the calling function.
        val code = """
            ability Show 'a where { show: {val: 'a} -> Str }

            ability Printer 'a where {
                print: {prefix: 'a, item: 'b} with Show 'b -> Str
            }

            impl Show Int where { show = {val} -> "Int" }
            impl Printer Str where { print = {prefix, item} -> "printed" }

            # Concrete call with Int (which implements Show) - no Show constraint needed
            let printInt = {} -> Printer.print {prefix = "test", item = 42}

            # Polymorphic call - WOULD need Show constraint
            # let printAny: {x: 'z} -> Str = {x} -> Printer.print {prefix = "test", item = x}  # would fail
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }
}
