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
 *   ability Ord for 'a where {
 *       compare: {r: 'a} with Same 'a -> Int
 *   }
 *
 *   # This function MUST declare Same 'a because it calls .compare
 *   let myCompare: {...} with Ord 'a, Same 'a -> Int = ...
 */
class MethodLevelConstraintPropagationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = MezzeParser()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    // ========================================================================
    // SECTION 1: Basic Constraint Propagation
    // ========================================================================

    @Test
    fun `function calling method with constraint must declare that constraint`() {
        val code = """
            ability Same for 'a where {
                same: {r: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            impl Same for Int where { same = {it, r} -> it == r }
            impl Ord for Int where { compare = {r} -> 0 }

            # WRONG: Only declares Ord, but .compare requires Eq
            let myCompare: {x: 'a, y: 'a} with Ord 'a -> Int = {x, y} ->
                x.compare {r = y}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Same")
    }

    @Test
    fun `function declaring all required constraints passes`() {
        val code = """
            ability Same for 'a where {
                same: {r: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            impl Same for Int where { same = {it, r} -> it == r }
            impl Ord for Int where { compare = {r} -> 0 }

            # CORRECT: Declares both Ord and Eq
            let myCompare: {x: 'a, y: 'a} with Ord 'a, Same 'a -> Int = {x, y} ->
                x.compare {r = y}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `concrete type call satisfies constraint without declaration`() {
        val code = """
            ability Same for 'a where {
                same: {r: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            impl Same for Int where { same = {it, r} -> it == r }
            impl Ord for Int where { compare = {r} -> 0 }

            # CORRECT: Concrete Int satisfies both constraints
            let compareInts = {} -> 1.compare {r = 2}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 2: Multi-Level Propagation (Transitive)
    // ========================================================================

    @Test
    fun `multi-level constraint propagation works`() {
        val code = """
            ability Same for 'a where {
                same: {r: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            ability Sortable for 'a where {
                sort: {items: ['a]} with Ord 'a, Same 'a -> ['a]
            }

            impl Same for Int where { same = {it, r} -> it == r }
            impl Ord for Int where { compare = {r} -> 0 }
            impl Sortable for Int where { sort = {items} -> items }

            # Calling .sort requires Ord and Eq
            let mySorter: {elem: 'b, xs: ['b]} with Sortable 'b, Ord 'b, Same 'b -> ['b] = {elem, xs} ->
                elem.sort {items = xs}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `missing transitive constraint fails`() {
        val code = """
            ability Same for 'a where {
                same: {r: 'a} -> Bool
            }

            ability Ord for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            ability Sortable for 'a where {
                sort: {items: ['a]} with Ord 'a, Same 'a -> ['a]
            }

            impl Same for Int where { same = {it, r} -> it == r }
            impl Ord for Int where { compare = {r} -> 0 }
            impl Sortable for Int where { sort = {items} -> items }

            # WRONG: Missing Eq constraint
            let mySorter: {elem: 'b, xs: ['b]} with Sortable 'b, Ord 'b -> ['b] = {elem, xs} ->
                elem.sort {items = xs}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Same")
    }

    // ========================================================================
    // SECTION 3: Multiple Independent Constraints
    // ========================================================================

    @Test
    fun `multiple independent constraints on method`() {
        val code = """
            ability Show for 'a where { show: {it: 'a} -> Str }
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Debug for 'a where {
                debug: {} with Show 'a, Same 'a -> Str
            }

            impl Show for Int where { show = {} -> "Int" }
            impl Same for Int where { same = {it, r} -> it == r }
            impl Debug for Int where { debug = {} -> "debug" }

            # Must declare both Show and Eq — use 'a to match ability's type param convention
            let myDebug: {x: 'a} with Debug 'a, Show 'a, Same 'a -> Str = {x} ->
                x.debug {}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `partial constraints fails`() {
        val code = """
            ability Show for 'a where { show: {it: 'a} -> Str }
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Debug for 'a where {
                debug: {} with Show 'a, Same 'a -> Str
            }

            impl Show for Int where { show = {} -> "Int" }
            impl Same for Int where { same = {it, r} -> it == r }
            impl Debug for Int where { debug = {} -> "debug" }

            # WRONG: Only declares Show, missing Eq — use 'a to match ability's type param convention
            let myDebug: {x: 'a} with Debug 'a, Show 'a -> Str = {x} ->
                x.debug {}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "Same")
    }

    // ========================================================================
    // SECTION 4: Method-Local Type Variables with Constraints
    // ========================================================================

    @Test
    fun `method-local type var constraint propagates`() {
        val code = """
            ability Show for 'a where { show: {it: 'a} -> Str }

            impl Show for Int where { show = {} -> "Int" }

            ability Printer for 'a where {
                print: {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "printed"
            }

            # When polymorphic in 'x, must propagate Show constraint
            let printAny: {pre: Str, val: 'x} with Show 'x -> Str = {pre, val} ->
                pre.print {item = val}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `method-local type var without constraint fails`() {
        val code = """
            ability Show for 'a where { show: {it: 'a} -> Str }

            impl Show for Int where { show = {} -> "Int" }

            ability Printer for 'a where {
                print: {item: 'b} with Show 'b -> Str
            }

            impl Printer for Str where {
                print = {item} -> "printed"
            }

            # WRONG: Missing Show 'x constraint
            let printAny: {pre: Str, val: 'x} -> Str = {pre, val} ->
                pre.print {item = val}
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
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }
            ability Ord for 'a where { compare: {r: 'a} with Same 'a -> Int }
            ability Show for 'a where { show: {it: 'a} -> Str }

            ability Combined for 'a where {
                process: {} with Same 'a, Ord 'a, Show 'a -> Str
            }

            impl Same for Int where { same = {it, r} -> it == r }
            impl Ord for Int where { compare = {r} -> 0 }
            impl Show for Int where { show = {} -> "Int" }
            impl Combined for Int where { process = {} -> "processed" }

            # Must declare all three constraints — use 'a to match ability's type param convention
            let fullProcess: {x: 'a} with Combined 'a, Same 'a, Ord 'a, Show 'a -> Str = {x} ->
                x.process {}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 6: Impl Body Uses Constrained Method
    // ========================================================================

    @Test
    fun `impl method body can use constrained methods with concrete type`() {
        val code = """
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Ord for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            impl Same for Int where { same = {it, r} -> it == r }

            # Impl body can use .eq because Int implements Eq
            impl Ord for Int where {
                compare = {it, r} -> if it.same {r = r} then 0 else 1
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
            ability Same for 'a where { same: {it: 'a, r: 'a} -> Bool }

            ability Ord for 'a where {
                compare: {r: 'a} with Same 'a -> Int
            }

            impl Same for Int where { same = {it, r} -> it == r }
            impl Ord for Int where { compare = {r} -> 0 }

            let bad: {x: 'a, y: 'a} with Ord 'a -> Int = {x, y} ->
                x.compare {r = y}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        // Error should clearly indicate which constraint is missing
        assertContains(error.message!!, "Same")
    }

    // ========================================================================
    // SECTION 8: Edge Cases
    // ========================================================================

    @Test
    fun `no propagation needed when no method-level constraints`() {
        val code = """
            ability Simple for 'a where {
                process: {} -> Str
            }

            impl Simple for Int where {
                process = {} -> "simple"
            }

            # No extra constraints needed - method has none
            let myProcess: {x: 'b} with Simple 'b -> Str = {x} ->
                x.process {}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `constraint only needed on polymorphic calls`() {
        // This test verifies that when calling a method with a concrete type
        // that satisfies the method-level constraint, no constraint declaration
        // is needed on the calling function.
        val code = """
            ability Show for 'a where { show: {it: 'a} -> Str }

            ability Printer for 'a where {
                print: {item: 'b} with Show 'b -> Str
            }

            impl Show for Int where { show = {} -> "Int" }
            impl Printer for Str where { print = {item} -> "printed" }

            # Concrete call with Int (which implements Show) - no Show constraint needed
            let printInt = {} -> "test".print {item = 42}

            # Polymorphic call - WOULD need Show constraint
            # let printAny: {x: 'z} -> Str = {x} -> "test".print {item = x}  # would fail
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    // ========================================================================
    // SECTION 9: Generic Ability Impls
    // ========================================================================

    @Test
    fun `generic impl for seq type matches concrete seq`() {
        // impl Same for ['a] with method-level constraint
        // When checking Eq [Int], should match if Eq Int is satisfied
        val code = """
            ability MyEq for 'a where {
                my_eq: {r: 'a} -> Bool
            }

            impl MyEq for Int where {
                my_eq = {r} -> True
            }

            impl MyEq for ['a] where {
                my_eq: {r: ['a]} with MyEq 'a -> Bool = {r} -> True
            }

            let test = {} -> [1, 2].my_eq {r = [3, 4]}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `generic impl fails when element type lacks ability`() {
        // impl MyEq for ['a] requires MyEq 'a
        // If MyEq is NOT implemented for the element type, should fail
        val code = """
            type Wrapper = { val: Int }

            ability MyEq for 'a where {
                my_eq: {r: 'a} -> Bool
            }

            # No impl MyEq for Wrapper!

            impl MyEq for ['a] where {
                my_eq: {r: ['a]} with MyEq 'a -> Bool = {r} -> True
            }

            let w1: Wrapper = { val = 1 }
            let w2: Wrapper = { val = 2 }
            let test = {} -> [w1].my_eq {r = [w2]}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> { typeCheck(code) }
        assertContains(error.message!!, "MyEq")
    }

    @Test
    fun `generic impl for Option type`() {
        val code = """
            ability MyEq for 'a where {
                my_eq: {r: 'a} -> Bool
            }

            impl MyEq for Int where { my_eq = {r} -> True }

            impl MyEq for Option 'a where {
                my_eq: {r: Option 'a} with MyEq 'a -> Bool = {r} -> True
            }

            let test = {} -> (Some {val = 42}).my_eq {r = Some {val = 43}}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }

    @Test
    fun `generic impl without constraints matches any element`() {
        // A generic impl with NO constraints should match any element type
        val code = """
            ability HasLength for 'a where {
                len: {} -> Int
            }

            impl HasLength for ['a] where {
                len = {} -> 0
            }

            let test = {} -> [1, 2, 3].len {}
        """.trimIndent()

        assertDoesNotThrow { typeCheck(code) }
    }
}
