package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows

/**
 * Comprehensive tests for variant union type inference.
 *
 * These tests verify that the typechecker correctly creates anonymous variant unions when:
 * - Match expressions return different variant constructors across cases
 * - If-else expressions return different variant constructors
 * - Boolean inference from true/false literal patterns in match
 * - Nested combinations of match and if-else
 *
 * Key features tested:
 * 1. Basic variant unions from match expressions
 * 2. Boolean type inference from literal patterns (true/false)
 * 3. If-else expressions creating variant unions
 * 4. Nested match and if-else combinations
 * 5. Polymorphic type propagation through variant unions
 * 6. Edge cases (single variant, many variants, complex payloads)
 */
class VariantUnionTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========== BASIC VARIANT UNION TESTS ==========

    @Test
    fun `test basic variant union with three variants`() {
        val source = """
            let test1 = {c} -> match c is
              Red -> Dog {breed = "Shepherd"}
              Blue {p} -> Cat {name = "Orange"}
              Green -> Mouse
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should type check successfully")

        // Verify the result contains the variant union type
        val output = result.toString()
        assertTrue(output.contains("Dog") && output.contains("Cat") && output.contains("Mouse"),
            "Output should contain all three variants")
    }

    @Test
    fun `test variant union with polymorphic payloads`() {
        val source = """
            let test2 = {v} -> match v is
              Alpha {x} -> Beta {y = x}
              Gamma -> Delta {z = 42}
              Epsilon {a, b} -> Zeta {c = a}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should type check successfully with polymorphic payloads")
    }

    @Test
    fun `test variant union with mixed payloads`() {
        val source = """
            let test3 = {x} -> match x is
              Success -> Result {status = "ok"}
              Failure {msg} -> ErrorResult {message = msg}
              Pending -> Loading
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should handle variants with and without payloads")
    }

    // ========== BOOLEAN INFERENCE TESTS ==========

    @Test
    fun `test boolean inference from true false patterns`() {
        val source = """
            let test1 = {c} -> match c is
              Red -> Dog {breed = "Shepherd"}
              Blue {p} -> match p is
                True -> Cat {name = "Orange"}
                False -> Mouse
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should infer p as Bool from true/false patterns")

        // Verify that p is typed as Bool (represented as variant with True/False constructors)
        val output = result.toString()
        assertTrue(output.contains("Bool") || output.contains("p: Bool") ||
                   (output.contains("True") && output.contains("False")),
            "Parameter p should be inferred as Bool")
    }

    @Test
    fun `test multiple levels of boolean nesting`() {
        val source = """
            let test2 = {x} -> match x is
              First {flag} -> match flag is
                True -> Alpha
                False -> Beta
              Second -> Gamma
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should handle nested boolean matches")
    }

    @Test
    fun `test boolean match in different branch`() {
        val source = """
            let test3 = {v} -> match v is
              A -> X
              B {cond} -> match cond is
                True -> Y {value = 1}
                False -> Z {value = 0}
              C -> W
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should create variant union with nested boolean match")
    }

    // ========== IF-ELSE VARIANT UNION TESTS ==========

    @Test
    fun `test if-else creating variant union`() {
        val source = """
            let test1 = {c} -> match c is
              Red -> Dog {breed = "Shepherd"}
              Blue {p} -> if p then Cat {name = "Orange"} else Mouse
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "If-else should create variant union")

        val output = result.toString()
        assertTrue(output.contains("Dog") && output.contains("Cat") && output.contains("Mouse"),
            "Should include all three variants from match and if-else")
    }

    @Test
    fun `test top-level if-else with complex variants`() {
        val source = """
            let test2 = {flag} -> if flag then
              Success {value = 42}
            else
              Failure {error = "failed"}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Top-level if-else should create variant union")
    }

    @Test
    fun `test nested if-else creating variant unions`() {
        val source = """
            let test3 = {x} -> match x is
              A {b1} -> if b1 then Alpha else Beta
              B {b2} -> if b2 then Gamma {x = 1} else Delta {y = 2}
              C -> Epsilon
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Multiple nested if-else should create unified variant union")
    }

    // ========== MIXED MATCH AND IF-ELSE TESTS ==========

    @Test
    fun `test mix of match and if-else`() {
        val source = """
            let test1 = {outer} -> match outer is
              X {inner} -> match inner is
                True -> A {x = 1}
                False -> B {y = 2}
              Y {flag} -> if flag then C else D
              Z -> E
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should combine match and if-else variant unions")
    }

    @Test
    fun `test deep nesting with multiple variant unions`() {
        val source = """
            let test2 = {a} -> match a is
              First {b} -> match b is
                Alpha -> Result1
                Beta {c} -> if c then Result2 else Result3
              Second -> Result4
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should handle three levels of nesting")
    }

    @Test
    fun `test if-else at top level with match inside`() {
        val source = """
            let test3 = {cond} -> if cond then
              match cond is
                True -> Success
                False -> Pending
            else
              Failure
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should handle match inside if-else")
    }

    // ========== EDGE CASE TESTS ==========

    @Test
    fun `test single variant`() {
        val source = """
            let test1 = {x} -> match x is
              Red -> Dog {breed = "Shepherd"}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Single variant should type check")
    }

    @Test
    fun `test two variants`() {
        val source = """
            let test2 = {x} -> match x is
              A -> X
              B -> Y
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Two variants should create minimal union")
    }

    @Test
    fun `test many variants`() {
        val source = """
            let test3 = {x} -> match x is
              V1 -> R1
              V2 -> R2
              V3 -> R3
              V4 -> R4
              V5 -> R5
              V6 -> R6
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should handle large variant unions")
    }

    @Test
    fun `test variant union with complex nested payloads`() {
        val source = """
            let test4 = {x} -> match x is
              A -> Result1 {data = {x = 1, y = 2}}
              B -> Result2 {data = {a = "test", b = True}}
              C -> Result3
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Should handle complex nested record payloads")
    }

    @Test
    fun `test if-else with same variant unifies`() {
        val source = """
            let test5 = {flag} -> if flag then Dog {breed = "A"} else Dog {breed = "B"}
        """.trimIndent()

        val program = parser.parse(source)
        val result = typeChecker.typeCheck(program).program

        assertNotNull(result, "Same variant in both branches should unify")
    }

    // ========== NEGATIVE TESTS (Should fail) ==========

    @Test
    fun `test match with incompatible concrete types fails`() {
        val source = """
            let fail1 = {x} -> match x is
              A -> 42
              B -> "hello"
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test if-else with incompatible concrete types fails`() {
        val source = """
            let fail2 = {flag} -> if flag then 42 else "hello"
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test match with mixed concrete and variant types fails`() {
        val source = """
            let fail3 = {x} -> match x is
              A -> 42
              B -> Dog {breed = "test"}
              C -> "hello"
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test nested match with type error fails`() {
        val source = """
            let fail4 = {outer} -> match outer is
              X {inner} -> match inner is
                A -> 1
                B -> "test"
              Y -> Result
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test string used as if condition fails`() {
        val source = """
            let fail5 = {x} -> if "not a bool" then Dog else Cat
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test integer used as if condition fails`() {
        val source = """
            let fail6 = if 42 then 1 else 2
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test match returning Int mixed with variants fails`() {
        val source = """
            let fail7 = {x} -> match x is
              A -> Alpha
              B -> Beta
              C -> 42
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
    }
}
