package com.mezze.language.typechecker

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

            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool,
              neq: {l: 'a, r: 'a} -> Bool
            }

            impl Eq Str where {
              eq: {l: Str, r: Str} -> Bool = {l, r} -> stringEquals {a = l, b = r},
              neq: {l: Str, r: Str} -> Bool = {l, r} -> !(stringEquals {a = l, b = r})

            }

            let areEqual = {x, y} -> Eq.eq {l = x, r = y}

            areEqual {x = "hello", y = "world"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Simple ability implementation should pass")
    }

    @Test
    fun `test ability with type parameter specialization`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            let display = {value} -> Show.show {val = value}

            display {value = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with type parameter specialization should pass")
    }

    @Test
    fun `test multi-parameter ability`() {
        val source = """
            use std::int

            ability Convert 'from 'to where {
              convert: {value: 'from} -> 'to
            }

            impl Convert Str Int where {
              convert: {value: Str} -> Int = {value} ->
                match int::from_string {s = value} is
                  Some {val} -> val
                  None -> 0
            }

            let transform: {value: Str} -> Int = {value} -> Convert.convert {value = value}

            transform {value = "123"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Multi-parameter ability should pass")
    }

    @Test
    fun `test ability with record types`() {
        val source = """
            use std::int

            ability Display 'a where {
              display: {val: 'a} -> {text: Str}
            }

            impl Display Int where {
              display: {val: Int} -> {text: Str} = {val} -> {text = int::to_str {n = val}}
            }

            let render = {value} -> Display.display {val = value}

            render {value = 100}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with record types should pass")
    }

    @Test
    fun `test ability with method-level constraints`() {
        val source = """
            # Define a helper function to compare strings
            let stringEquals = {a: Str, b: Str} -> a == b

            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            # Method-level constraints instead of ability-level
            ability Ord 'a where {
              compare: {l: 'a, r: 'a} with Eq 'a -> Int,
              lt: {l: 'a, r: 'a} with Eq 'a -> Bool,
              gt: {l: 'a, r: 'a} with Eq 'a -> Bool
            }

            impl Eq Str where {
              eq: {l: Str, r: Str} -> Bool = {l, r} -> stringEquals {a = l, b = r}
            }

            impl Ord Str where {
              compare: {l: Str, r: Str} -> Int = {l, r} -> if stringEquals {a = l, b = r} then 0 else if l < r then -1 else 1,
              lt: {l: Str, r: Str} -> Bool = {l, r} -> (Ord.compare {l = l, r = r}) < 0,
              gt: {l: Str, r: Str} -> Bool = {l, r} -> (Ord.compare {l = l, r = r}) > 0
            }

            let isLess = {x, y} -> Ord.lt {l = x, r = y}

            isLess {x = "apple", y = "banana"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with method-level constraints should pass")
    }

    @Test
    fun `test ability with associated types for generic containers`() {
        val source = """
            ability Container 'c having 'item where {
              show: {container: 'c} -> Str,
              get: {container: 'c, index: Int} -> 'item
            }

            impl Container [Int] having 'item = Int where {
              show = {container} -> "array of integers",
              get = {container, index} -> 42
            }

            impl Container [Str] having 'item = Str where {
              show = {container} -> "array of strings",
              get = {container, index} -> "hello"
            }

            let intArray = [1, 2, 3]
            let result = Container.show {container = intArray}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with associated types for generic containers should pass")
    }

    // ========== FAILING TESTS ==========

    @Test
    fun `test missing operation should fail`() {
        val source = """
            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool,
              neq: {l: 'a, r: 'a} -> Bool
            }

            impl Eq Str where {
              eq: {l: Str, r: Str} -> Bool = {l, r} -> stringEquals {a = l, b = r}
            }

            let areEqual = {x, y} -> Eq.eq {l = x, r = y}

            areEqual {x = "hello", y = "world"}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Missing 'neq' operation") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test extra operation should fail`() {
        val source = """
            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Eq Str where {
              eq: {l: Str, r: Str} -> Bool = {l, r} -> stringEquals {a = l, b = r},
              compare: {l: Str, r: Str} -> Int = {l, r} -> stringCompare {a = l, b = r}
            }

            let areEqual = {x, y} -> Eq.eq {l = x, r = y}

            areEqual {x = "hello", y = "world"}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Extra 'compare' operation not in ability") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test type variable not specialized should fail`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: 'a} -> Str = {val} -> int::to_str {n = val}
            }

            let display = {value} -> Show.show {val = value}

            display {value = 42}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Type variable 'a must be replaced with Int") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test wrong specialization type should fail`() {
        val source = """
            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Eq Str where {
              eq: {l: Str, r: Int} -> Bool = {l, r} -> False
            }

            let areEqual = {x, y} -> Eq.eq {l = x, r = y}

            areEqual {x = "hello", y = 5}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Both parameters must be String") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test return type mismatch should fail`() {
        val source = """
            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Int = {val} -> val
            }

            let display = {value} -> Show.show {val = value}

            display {value = 42}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Return type should be Str, not Int") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test record field mismatch should fail`() {
        val source = """
            use std::int

            ability Display 'a where {
              display: {val: 'a} -> {text: Str}
            }

            impl Display Int where {
              display: {val: Int} -> {message: Str} = {val} -> {message = int::to_str {n = val}}
            }

            let render = {value} -> Display.display {val = value}

            render {value = 100}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Field name is 'message' not 'text'") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test type parameter count mismatch should fail`() {
        val source = """
            ability Convert 'from 'to where {
              convert: {value: 'from} -> 'to
            }

            impl Convert Str where {
              convert: {value: Str} -> Int = {value} -> parseInt {str = value}
            }

            let transform = {value} -> Convert.convert {value = value}

            transform {value = "123"}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Two type parameters, but only one target type") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test parameter type mismatch should fail`() {
        val source = """
            use std::int

            ability Process 'a where {
              process: {value: 'a} -> Str
            }

            impl Process Int where {
              process: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            let run = {value} -> Process.process {value = value}

            run {value = 42}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Record field name is 'val' not 'value'") {
            typeChecker.typeCheck(program).program
        }
    }

    // ========== CONSTRAINT VALIDATION AT CALL SITES ==========

    @Test
    fun `test constraint validation at call site - missing implementation should fail`() {
        val source = """
            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> "number"
            }

            let display: {val: 'a} -> Str with Show 'a = {val} ->
              Show.show {val = val}

            display {val = 3.14}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Float does not implement Show") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test constraint validation at call site - with implementation should pass`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            impl Show Float where {
              show: {val: Float} -> Str = {val} -> "float"
            }

            let display: {val: 'a} -> Str with Show 'a = {val} ->
              Show.show {val = val}

            display {val = 3.14}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: Float implements Show")
    }

    @Test
    fun `test constraint validation in pipe operator - should fail`() {
        val source = """
            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            let noiseAdd = {x, y} -> do
              let sum = x + y
              let diff = x - y
              diff |> Show.show {val = @ }

            noiseAdd {x = 5.0, y = 6.0}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Float does not implement Show") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test constraint validation with multiple constraints - all satisfied`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            impl Eq Int where {
              eq: {l: Int, r: Int} -> Bool = {l, r} -> l == r
            }

            let compare: {x: 'a, y: 'a} -> Str with Show 'a, Eq 'a = {x, y} ->
              if Eq.eq {l = x, r = y} then
                "Equal: $(Show.show {val = x})"
              else
                "Not equal"

            compare {x = 5, y = 5}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: Int implements both Show and Eq")
    }

    @Test
    fun `test constraint validation with multiple constraints - one missing`() {
        val source = """
            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Show Str where {
              show: {val: Str} -> Str = {val} -> val
            }

            # Missing Eq String implementation

            let compare: {x: Str, y: Str} -> Str with Show Str, Eq String = {x, y} ->
              if Eq.eq {l = x, r = y} then
                "Equal: $(Show.show {val = x})"
              else
                "Not equal"

            compare {x = "hello", y = "world"}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Str does not implement Eq") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test constraint validation in nested function calls`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            let display: {val: 'a} -> Str with Show 'a = {val} ->
              Show.show {val = val}

            let format: {x: 'a} -> Str with Show 'a = {x} ->
              "[$(display {val = x})]"

            format {x = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: Constraint propagates through nested calls")
    }

    @Test
    fun `test constraint validation with multi-parameter ability at call site`() {
        val source = """
            use std::int

            ability Convert 'from 'to where {
              convert: {value: 'from} -> 'to
            }

            impl Convert Str Int where {
              convert: {value: Str} -> Int = {value} -> 0
            }

            let transform: {val: Float} -> Int with Convert Float Int = {val} ->
              Convert.convert {value = val}

            # This should fail - no Convert Float Int implementation
            let result = transform {val = 3.14}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Float, Int does not implement Convert") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test constraint validation passes when type matches exactly`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            let display: {val: 'a} -> Str with Show 'a = {val} ->
              Show.show {val = val}

            # This should pass - Int has Show implementation
            display {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: Int implements Show")
    }

    // ========== MULTI-PARAMETER ABILITY CONSTRAINT TESTS ==========
    // These tests verify that ability constraints with multiple type parameters
    // (like "with Encode Json 'a" or "with Convert 'a 'b") correctly track
    // all type arguments without double-counting.

    @Test
    fun `test single-parameter ability constraint does not duplicate type`() {
        // Regression test: "with Show 'a" should check for "impl Show Int",
        // not "impl Show Int Int" (which was the bug)
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            # Single-parameter constraint: Show 'a
            let format: {x: 'a} -> Str with Show 'a = {x} ->
              "Value: $(Show.show {val = x})"

            # Should find impl Show Int, not look for impl Show Int Int
            format {x = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Single-parameter ability should not duplicate type args")
    }

    @Test
    fun `test multi-parameter ability constraint preserves type order`() {
        // Test that "with Convert String Int" correctly checks for
        // "impl Convert Str Int" with types in the right order
        val source = """
            ability Convert 'from 'to where {
              convert: {value: 'from} -> 'to
            }

            impl Convert Str Int where {
              convert: {value: Str} -> Int = {value} -> 0
            }

            # Multi-parameter constraint: Convert 'a 'b with 'a=Str, 'b=Int
            let parse: {s: 'a} -> 'b with Convert 'a 'b = {s} ->
              Convert.convert {value = s}

            # Should find impl Convert Str Int
            parse {s = "42"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Multi-parameter ability should preserve type order")
    }

    @Test
    fun `test chained ability constraints with single-parameter abilities`() {
        // Test that nested function calls with constraints work correctly
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            impl Show Str where {
              show: {val: Str} -> Str = {val} -> val
            }

            let wrap: {x: 'a} -> Str with Show 'a = {x} ->
              "[$(Show.show {val = x})]"

            let doubleWrap: {x: 'a} -> Str with Show 'a = {x} ->
              "<<$(wrap {x = x})>>"

            # Should propagate Show constraint through both functions
            doubleWrap {x = 123}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Chained constraints should propagate correctly")
    }

    @Test
    fun `test ability constraint with concrete and polymorphic type args`() {
        // Test mixed concrete and polymorphic type args like "Encode Json 'a"
        val source = """
            use std::int

            type Json = { raw: Str }

            ability Encode 'format 'data where {
              encode: {val: 'data} -> 'format
            }

            impl Encode Json Int where {
              encode: {val: Int} -> Json = {val} -> { raw = int::to_str {n = val} }
            }

            # Constraint has concrete type (Json) and polymorphic type ('a)
            let toJson: {data: 'a} -> Json with Encode Json 'a = {data} ->
              Encode.encode {val = data}

            # Should find impl Encode Json Int
            toJson {data = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Mixed concrete and polymorphic type args should work")
    }

    @Test
    fun `test multiple different ability constraints on same function`() {
        // Test function with multiple constraints: "with Show 'a, Eq 'a"
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            impl Eq Int where {
              eq: {l: Int, r: Int} -> Bool = {l, r} -> l == r
            }

            # Multiple constraints on same type parameter
            let showIfEqual: {a: 'x, b: 'x} -> Str with Show 'x, Eq 'x = {a, b} ->
              if Eq.eq {l = a, r = b} then
                Show.show {val = a}
              else
                "not equal"

            showIfEqual {a = 5, b = 5}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Multiple constraints on same type param should work")
    }

    @Test
    fun `test ability constraint missing implementation fails correctly`() {
        // Verify that missing impl for constrained type still produces good error
        val source = """
            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            # No impl Show Float defined!

            let format: {x: 'a} -> Str with Show 'a = {x} ->
              Show.show {val = x}

            # Should fail - no impl Show Float
            format {x = 3.14}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Float does not implement Show") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test higher-order function with ability constraint`() {
        // Test that ability constraints work with higher-order functions
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> Str
            }

            impl Show Int where {
              show: {val: Int} -> Str = {val} -> int::to_str {n = val}
            }

            # Higher-order: takes a function that requires Show constraint
            let applyAndShow: {f: {x: 'a} -> 'a, val: 'a} -> Str with Show 'a = {f, val} -> do
              let result = f {x = val}
              Show.show {val = result}

            let double = {x: Int} -> x * 2

            applyAndShow {f = double, val = 21}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Higher-order functions with ability constraints should work")
    }
}
