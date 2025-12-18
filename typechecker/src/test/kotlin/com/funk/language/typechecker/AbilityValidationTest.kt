package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbilityValidationTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    // ========== PASSING TESTS ==========

    @Test
    fun `test simple ability implementation with all operations`() {
        val source = """
            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool,
              neq: {l: 'a, r: 'a} -> Bool
            }

            impl Eq String where {
              eq: {l: String, r: String} -> Bool = {l, r} -> stringEquals {a = l, b = r},
              neq: {l: String, r: String} -> Bool = {l, r} -> stringEquals {a = l, b = r}

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
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show: {val: Int} -> String = {val} -> int::to_string {n = val}
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

            impl Convert String Int where {
              convert: {value: String} -> Int = {value} ->
                match int::from_string {s = value} is
                  Some {val} -> val
                  None -> 0
            }

            let transform: {value: String} -> Int = {value} -> Convert.convert {value = value}

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
              display: {val: 'a} -> {text: String}
            }

            impl Display Int where {
              display: {val: Int} -> {text: String} = {val} -> {text = int::to_string {n = val}}
            }

            let render = {value} -> Display.display {val = value}

            render {value = 100}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with record types should pass")
    }

    @Test
    fun `test ability with dependencies`() {
        val source = """
            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            ability Ord 'a with Eq 'a where {
              compare: {l: 'a, r: 'a} -> Int,
              lt: {l: 'a, r: 'a} -> Bool,
              gt: {l: 'a, r: 'a} -> Bool
            }

            impl Eq String where {
              eq: {l: String, r: String} -> Bool = {l, r} -> stringEquals {a = l, b = r}
            }

            impl Ord String where {
              compare: {l: String, r: String} -> Int = {l, r} -> if stringEquals {a = l, b = r} then 0 else if l < r then -1 else 1,
              lt: {l: String, r: String} -> Bool = {l, r} -> (Ord.compare {l = l, r = r}) < 0,
              gt: {l: String, r: String} -> Bool = {l, r} -> (Ord.compare {l = l, r = r}) > 0
            }

            let isLess = {x, y} -> Ord.lt {l = x, r = y}

            isLess {x = "apple", y = "banana"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Ability with dependencies should pass")
    }

    @Test
    fun `test ability with associated types for generic containers`() {
        val source = """
            ability Container 'c having 'item where {
              show: {container: 'c} -> String,
              get: {container: 'c, index: Int} -> 'item
            }

            impl Container [Int] having 'item = Int where {
              show = {container} -> "array of integers",
              get = {container, index} -> 42
            }

            impl Container [String] having 'item = String where {
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

            impl Eq String where {
              eq: {l: String, r: String} -> Bool = {l, r} -> stringEquals {a = l, b = r}
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

            impl Eq String where {
              eq: {l: String, r: String} -> Bool = {l, r} -> stringEquals {a = l, b = r},
              compare: {l: String, r: String} -> Int = {l, r} -> stringCompare {a = l, b = r}
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
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show: {val: 'a} -> String = {val} -> int::to_string {n = val}
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

            impl Eq String where {
              eq: {l: String, r: Int} -> Bool = {l, r} -> false
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
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show: {val: Int} -> Int = {val} -> val
            }

            let display = {value} -> Show.show {val = value}

            display {value = 42}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Return type should be String, not Int") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test record field mismatch should fail`() {
        val source = """
            use std::int

            ability Display 'a where {
              display: {val: 'a} -> {text: String}
            }

            impl Display Int where {
              display: {val: Int} -> {message: String} = {val} -> {message = int::to_string {n = val}}
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

            impl Convert String where {
              convert: {value: String} -> Int = {value} -> parseInt {str = value}
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
              process: {value: 'a} -> String
            }

            impl Process Int where {
              process: {val: Int} -> String = {val} -> int::to_string {n = val}
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
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show: {val: Int} -> String = {val} -> "number"
            }

            let display: {val: 'a} -> String with Show 'a = {val} ->
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
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            impl Show Float where {
              show: {val: Float} -> String = {val} -> "float"
            }

            let display: {val: 'a} -> String with Show 'a = {val} ->
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
              show: {val: 'a} -> String
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
              show: {val: 'a} -> String
            }

            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Show Int where {
              show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            impl Eq Int where {
              eq: {l: Int, r: Int} -> Bool = {l, r} -> l == r
            }

            let compare: {x: 'a, y: 'a} -> String with Show 'a, Eq 'a = {x, y} ->
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
              show: {val: 'a} -> String
            }

            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Show String where {
              show: {val: String} -> String = {val} -> val
            }

            # Missing Eq String implementation

            let compare: {x: String, y: String} -> String with Show String, Eq String = {x, y} ->
              if Eq.eq {l = x, r = y} then
                "Equal: $(Show.show {val = x})"
              else
                "Not equal"

            compare {x = "hello", y = "world"}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: String does not implement Eq") {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test constraint validation in nested function calls`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let display: {val: 'a} -> String with Show 'a = {val} ->
              Show.show {val = val}

            let format: {x: 'a} -> String with Show 'a = {x} ->
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

            impl Convert String Int where {
              convert: {value: String} -> Int = {value} -> 0
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
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let display: {val: 'a} -> String with Show 'a = {val} ->
              Show.show {val = val}

            # This should pass - Int has Show implementation
            display {val = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        assertNotNull(typedProgram, "Should pass: Int implements Show")
    }
}
