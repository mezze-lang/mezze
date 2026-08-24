package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbilityValidationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========== PASSING TESTS ==========

    @Test
    fun `test simple ability implementation with all operations`() {
        val source = """
            # Define a helper function to compare strings
            let stringEquals = {a: Str, b: Str} -> a == b

            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool,
              neq: {it: 'a, r: 'a} -> Bool
            }

            impl Eq for Str where {
              eq: {it: Str, r: Str} -> Bool = {it, r} -> stringEquals {a = it, b = r},
              neq: {it: Str, r: Str} -> Bool = {it, r} -> (stringEquals {a = it, b = r}).not {}

            }

            let areEqual = {x, y} -> x.eq {r = y}

            areEqual {x = "hello", y = "world"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Simple ability implementation should pass")
    }

    @Test
    fun `test ability with type parameter specialization`() {
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show: {it: Int} -> Str = {it} -> int::to_str {n = it}
            }

            let display = {value} -> value.show {}

            display {value = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability with type parameter specialization should pass")
    }

    @Test
    fun `test multi-parameter ability`() {
        val source = """
            use std::int

            ability Convert 'to for 'from where {
              convert: {it: 'from} -> 'to
            }

            impl Convert for Int Str where {
              convert: {it: Str} -> Int = {it} ->
                match int::from_string {s = it} is
                  Some {val} -> val
                  None -> 0
            }

            let transform: {value: Str} -> Int = {value} -> value.convert {}

            transform {value = "123"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Multi-parameter ability should pass")
    }

    @Test
    fun `test ability with record types`() {
        val source = """
            use std::int

            ability Display for 'a where {
              display: {it: 'a} -> {text: Str}
            }

            impl Display for Int where {
              display: {it: Int} -> {text: Str} = {it} -> {text = int::to_str {n = it}}
            }

            let render = {value} -> value.display {}

            render {value = 100}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability with record types should pass")
    }

    @Test
    fun `test ability with method-level constraints`() {
        val source = """
            # Define a helper function to compare strings
            let stringEquals = {a: Str, b: Str} -> a == b

            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool
            }

            # Method-level constraints instead of ability-level
            ability Ord for 'a where {
              compare: {it: 'a, r: 'a} with Eq 'a -> Int,
              lt: {it: 'a, r: 'a} with Eq 'a -> Bool,
              gt: {it: 'a, r: 'a} with Eq 'a -> Bool
            }

            impl Eq for Str where {
              eq: {it: Str, r: Str} -> Bool = {it, r} -> stringEquals {a = it, b = r}
            }

            impl Ord for Str where {
              compare: {it: Str, r: Str} -> Int = {it, r} -> if stringEquals {a = it, b = r} then 0 else if it < r then -1 else 1,
              lt: {it: Str, r: Str} -> Bool = {it, r} -> (it.compare {r = r}) < 0,
              gt: {it: Str, r: Str} -> Bool = {it, r} -> (it.compare {r = r}) > 0
            }

            let isLess = {x, y} -> x.lt {r = y}

            isLess {x = "apple", y = "banana"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability with method-level constraints should pass")
    }

    @Test
    fun `test ability with associated types for generic containers`() {
        val source = """
            ability Container for 'c having 'item where {
              show: {it: 'c} -> Str,
              fetch: {it: 'c, index: Int} -> 'item
            }

            impl Container for [Int] having 'item = Int where {
              show = {it} -> "array of integers",
              fetch = {it, index} -> 42
            }

            impl Container for [Str] having 'item = Str where {
              show = {it} -> "array of strings",
              fetch = {it, index} -> "hello"
            }

            let intArray = [1, 2, 3]
            let result = intArray.show {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Ability with associated types for generic containers should pass")
    }

    // ========== FAILING TESTS ==========

    @Test
    fun `test missing operation should fail`() {
        val source = """
            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool,
              neq: {it: 'a, r: 'a} -> Bool
            }

            impl Eq for Str where {
              eq: {it: Str, r: Str} -> Bool = {it, r} -> stringEquals {a = it, b = r}
            }

            let areEqual = {x, y} -> x.eq {r = y}

            areEqual {x = "hello", y = "world"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Missing 'neq' operation") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test extra operation should fail`() {
        val source = """
            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool
            }

            impl Eq for Str where {
              eq: {it: Str, r: Str} -> Bool = {it, r} -> stringEquals {a = it, b = r},
              compare: {it: Str, r: Str} -> Int = {it, r} -> stringCompare {a = it, b = r}
            }

            let areEqual = {x, y} -> x.eq {r = y}

            areEqual {x = "hello", y = "world"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Extra 'compare' operation not in ability") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test type variable not specialized should fail`() {
        // With method-call syntax, the ability constraint is inferred on the wrapper.
        // display gets type {value: 'a} with Show 'a -> Str
        // Calling display {value = 42} resolves Show Int — should succeed.
        // Test instead that calling with a type that has no impl FAILS.
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show: {it: Int} -> Str = {it} -> int::to_str {n = it}
            }

            let display = {value} -> value.show {}

            display {value = {x = 1, y = 2}}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: No impl Show for record type") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test wrong specialization type should fail`() {
        val source = """
            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool
            }

            impl Eq for Str where {
              eq: {r: Int} -> Bool = {r} -> False
            }

            let areEqual = {x, y} -> x.eq {r = y}

            areEqual {x = "hello", y = 5}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Both parameters must be String") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test return type mismatch should fail`() {
        val source = """
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show: {it: Int} -> Int = {it} -> it
            }

            let display = {value} -> value.show {}

            display {value = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Return type should be Str, not Int") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test record field mismatch should fail`() {
        val source = """
            use std::int

            ability Display for 'a where {
              display: {it: 'a} -> {text: Str}
            }

            impl Display for Int where {
              display: {it: Int} -> {message: Str} = {it} -> {message = int::to_str {n = it}}
            }

            let render = {value} -> value.display {}

            render {value = 100}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Field name is 'message' not 'text'") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test type parameter count mismatch should fail`() {
        val source = """
            ability Convert 'to for 'from where {
              convert: {it: 'from} -> 'to
            }

            impl Convert for Str where {
              convert: {it: Str} -> Int = {it} -> parseInt {str = it}
            }

            let transform = {value} -> value.convert {}

            transform {value = "123"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Two type parameters, but only one target type") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test parameter type mismatch should fail`() {
        val source = """
            use std::int

            ability Process for 'a where {
              process: {it: 'a} -> Str
            }

            impl Process for Int where {
              process: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            let run = {value} -> value.process {}

            run {value = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Record field name is 'val' not 'value'") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    // ========== CONSTRAINT VALIDATION AT CALL SITES ==========

    @Test
    fun `test constraint validation at call site - missing implementation should fail`() {
        val source = """
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> "number"
            }

            let display: {val: 'a} with Show 'a -> Str = {val} ->
              val.show {}

            display {val = 3.14}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Float does not implement Show") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test constraint validation at call site - with implementation should pass`() {
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            impl Show for Float where {
              show = {it} -> "float"
            }

            let display: {val: 'a} with Show 'a -> Str = {val} ->
              val.show {}

            display {val = 3.14}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Should pass: Float implements Show")
    }

    @Test
    fun `test constraint validation in pipe operator - should fail`() {
        val source = """
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            let noiseAdd = {x, y} -> do
              let sum = x + y
              let diff = x - y
              diff.show {}

            noiseAdd {x = 5.0, y = 6.0}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Float does not implement Show") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test constraint validation with multiple constraints - all satisfied`() {
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            impl Eq for Int where {
              eq: {it: Int, r: Int} -> Bool = {it, r} -> it == r
            }

            let compare: {x: 'a, y: 'a} with Show 'a, Eq 'a -> Str = {x, y} ->
              if x.eq {r = y} then
                "Equal: $(x.show {})"
              else
                "Not equal"

            compare {x = 5, y = 5}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Should pass: Int implements both Show and Eq")
    }

    @Test
    fun `test constraint validation with multiple constraints - one missing`() {
        val source = """
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool
            }

            impl Show for Str where {
              show = {it} -> it
            }

            # Missing Eq implementation for undeclared type MissingT

            let compare: {x: Str, y: Str} with Show Str, Eq MissingT -> Str = {x, y} ->
              if x.eq {r = y} then
                "Equal: $(x.show {})"
              else
                "Not equal"

            compare {x = "hello", y = "world"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Str does not implement Eq") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test constraint validation in nested function calls`() {
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            let display: {val: 'a} with Show 'a -> Str = {val} ->
              val.show {}

            let format: {x: 'a} with Show 'a -> Str = {x} ->
              "[$(display {val = x})]"

            format {x = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Should pass: Constraint propagates through nested calls")
    }

    @Test
    fun `test constraint validation with multi-parameter ability at call site`() {
        val source = """
            use std::int

            ability Convert 'to for 'from where {
              convert: {it: 'from} -> 'to
            }

            impl Convert for Int Str where {
              convert = {it} -> 0
            }

            let transform: {val: Float} with Convert Int Float -> Int = {val} ->
              val.convert {}

            # This should fail - no Convert Int Float implementation
            let result = transform {val = 3.14}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Float, Int does not implement Convert") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test constraint validation passes when type matches exactly`() {
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            let display: {val: 'a} with Show 'a -> Str = {val} ->
              val.show {}

            # This should pass - Int has Show implementation
            display {val = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Should pass: Int implements Show")
    }

    // ========== MULTI-PARAMETER ABILITY CONSTRAINT TESTS ==========
    // These tests verify that ability constraints with multiple type parameters
    // (like "with Encode Json 'a" or "with Convert 'a 'b") correctly track
    // all type arguments without double-counting.

    @Test
    fun `test single-parameter ability constraint does not duplicate type`() {
        // Regression test: "with Show 'a" should check for "impl Show for Int",
        // not "impl Show for Int Int" (which was the bug)
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            # Single-parameter constraint: Show 'a
            let format: {x: 'a} with Show 'a -> Str = {x} ->
              "Value: $(x.show {})"

            # Should find impl Show for Int, not look for impl Show for Int Int
            format {x = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Single-parameter ability should not duplicate type args")
    }

    @Test
    fun `test multi-parameter ability constraint preserves type order`() {
        // Test that "with Convert Int Str" correctly checks for
        // "impl Convert for Int Str" with types in the right order
        val source = """
            ability Convert 'to for 'from where {
              convert: {it: 'from} -> 'to
            }

            impl Convert for Int Str where {
              convert = {it} -> 0
            }

            # Multi-parameter constraint: Convert 'b 'a with 'to='b, 'from='a
            let parse: {s: 'a} with Convert 'b 'a -> 'b = {s} ->
              s.convert {}

            # Should find impl Convert for Int Str
            parse {s = "42"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Multi-parameter ability should preserve type order")
    }

    @Test
    fun `test chained ability constraints with single-parameter abilities`() {
        // Test that nested function calls with constraints work correctly
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            impl Show for Str where {
              show = {it} -> it
            }

            let wrap: {x: 'a} with Show 'a -> Str = {x} ->
              "[$(x.show {})]"

            let doubleWrap: {x: 'a} with Show 'a -> Str = {x} ->
              "<<$(wrap {x = x})>>"

            # Should propagate Show constraint through both functions
            doubleWrap {x = 123}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Chained constraints should propagate correctly")
    }

    @Test
    fun `test ability constraint with concrete and polymorphic type args`() {
        // Test mixed concrete and polymorphic type args like "Encode Json 'a"
        val source = """
            use std::int

            type Json = { raw: Str }

            ability Encode 'format for 'data where {
              encode: {} -> 'format
            }

            impl Encode for Json Int where {
              encode = {it} -> { raw = int::to_str {n = it} }
            }

            # Constraint has concrete type (Json) and polymorphic type ('a)
            let toJson: {data: 'a} with Encode Json 'a -> Json = {data} ->
              data.encode {}

            # Should find impl Encode for Json Int
            toJson {data = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Mixed concrete and polymorphic type args should work")
    }

    @Test
    fun `test multiple different ability constraints on same function`() {
        // Test function with multiple constraints: "with Show 'a, Eq 'a"
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            impl Eq for Int where {
              eq: {it: Int, r: Int} -> Bool = {it, r} -> it == r
            }

            # Multiple constraints on same type parameter
            let showIfEqual: {a: 'x, b: 'x} with Show 'x, Eq 'x -> Str = {a, b} ->
              if a.eq {r = b} then
                a.show {}
              else
                "not equal"

            showIfEqual {a = 5, b = 5}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Multiple constraints on same type param should work")
    }

    @Test
    fun `test ability constraint missing implementation fails correctly`() {
        // Verify that missing impl for constrained type still produces good error
        val source = """
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            # No impl Show for Float defined!

            let format: {x: 'a} with Show 'a -> Str = {x} ->
              x.show {}

            # Should fail - no impl Show for Float
            format {x = 3.14}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Float does not implement Show") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test higher-order function with ability constraint`() {
        // Test that ability constraints work with higher-order functions
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            # Higher-order: takes a function that requires Show constraint
            let applyAndShow: {f: {x: 'a} -> 'a, val: 'a} with Show 'a -> Str = {f, val} -> do
              let result = f {x = val}
              result.show {}

            let double = {x: Int} -> x * 2

            applyAndShow {f = double, val = 21}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Higher-order functions with ability constraints should work")
    }
}
