package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for ability implementation body validation WITHOUT type annotations.
 * This tests that the implementation body type-checks correctly against the ability signature,
 * catching soundness bugs where the body returns the wrong type.
 */
class AbilityImplementationBodyValidationTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    // ========== PASSING TESTS (Valid Implementation Bodies) ==========

    @Test
    fun `test simple ability with correct body type should pass`() {
        val source = """
            use std::int

            ability Show 'a where {
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show = {val} -> int::to_string {n = val}
            }

            let display = {value} -> Show.show {val = value}

            display {value = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body returning String should pass")
    }

    @Test
    fun `test ability with record return type should pass`() {
        val source = """
            use std::int

            ability Display 'a where {
              display: {val: 'a} -> {text: String}
            }

            impl Display Int where {
              display = {val} -> {text = int::to_string {n = val}}
            }

            let render = {value} -> Display.display {val = value}

            render {value = 100}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body returning record should pass")
    }

    @Test
    fun `test ability with polymorphic operation should pass`() {
        val source = """
            ability Identity 'a where {
              identity: {val: 'a} -> 'a
            }

            impl Identity Int where {
              identity = {val} -> val
            }

            let id = {x} -> Identity.identity {val = x}

            id {x = 42}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body preserving polymorphic type should pass")
    }

    @Test
    fun `test ability with numeric operations should pass`() {
        val source = """
            ability Double 'a where {
              double: {val: 'a} -> 'a
            }

            impl Double Int where {
              double = {val} -> val + val
            }

            let d = {x} -> Double.double {val = x}

            d {x = 21}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body with numeric operations should pass")
    }

    @Test
    fun `test ability with conditional should pass`() {
        val source = """
            ability Sign 'a where {
              sign: {val: 'a} -> String
            }

            impl Sign Int where {
              sign = {val} -> if val > 0 then "positive" else "non-positive"
            }

            let getSign = {x} -> Sign.sign {val = x}

            getSign {x = 5}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body with conditional should pass")
    }

    @Test
    fun `test ability with pattern matching should pass`() {
        val source = """
            ability DefaultValue 'a where {
              default: {opt: Option 'a} -> 'a
            }

            impl DefaultValue Int where {
              default = {opt} -> match opt is
                Some {val} -> val
                None -> 0
            }

            let getDefault = {x} -> DefaultValue.default {opt = x}

            getDefault {x = None}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body with pattern matching should pass")
    }

    @Test
    fun `test ability with multiple parameters should pass`() {
        val source = """
            ability Combine 'a where {
              combine: {left: 'a, right: 'a} -> 'a
            }

            impl Combine Int where {
              combine = {left, right} -> left + right
            }

            let add = {x, y} -> Combine.combine {left = x, right = y}

            add {x = 10, y = 20}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body with multiple parameters should pass")
    }

    @Test
    fun `test ability calling stdlib functions should pass`() {
        val source = """
            use std::int

            ability Parse 'a where {
              parse: {str: String} -> Int
            }

            impl Parse String where {
              parse = {str} ->
                match int::from_string {s = str} is
                  Some {val} -> val
                  None -> 0
            }

            let p = {s} -> Parse.parse {str = s}

            p {s = "123"}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Implementation body calling stdlib functions should pass")
    }

    // ========== FAILING TESTS (Invalid Implementation Bodies) ==========

    @Test
    fun `test ability body returning wrong primitive type should fail`() {
        val source = """
            ability Show 'a where {
              show: {val: 'a} -> String
            }

            impl Show Int where {
              show = {val} -> val
            }

            let display = {value} -> Show.show {val = value}

            display {value = 42}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Body returns Int but signature requires String") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body returning wrong type in conditional should fail`() {
        val source = """
            ability Process 'a where {
              process: {val: 'a} -> String
            }

            impl Process Int where {
              process = {val} -> if val > 0 then "positive" else 0
            }

            let p = {x} -> Process.process {val = x}

            p {x = 5}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Conditional branches have different types") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body returning wrong type in pattern match should fail`() {
        val source = """
            ability Extract 'a where {
              extract: {opt: Option 'a} -> String
            }

            impl Extract Int where {
              extract = {opt} -> match opt is
                Some {val} -> val
                None -> "none"
            }

            let e = {x} -> Extract.extract {opt = x}

            e {opt = None}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Some branch returns Int, None branch returns String") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body with wrong record field should fail`() {
        val source = """
            ability Display 'a where {
              display: {val: 'a} -> {text: String}
            }

            impl Display Int where {
              display = {val} -> {message = intToString {n = val}}
            }

            let render = {value} -> Display.display {val = value}

            render {value = 100}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Record has 'message' field but signature requires 'text'") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body with wrong record field type should fail`() {
        val source = """
            ability Display 'a where {
              display: {val: 'a} -> {text: String}
            }

            impl Display Int where {
              display = {val} -> {text = val}
            }

            let render = {value} -> Display.display {val = value}

            render {value = 100}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Record field 'text' is Int but should be String") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body not using all parameters should fail`() {
        val source = """
            ability Combine 'a where {
              combine: {left: 'a, right: 'a} -> 'a
            }

            impl Combine Int where {
              combine = {left, right} -> left
            }

            let add = {x, y} -> Combine.combine {left = x, right = y}

            add {x = 10, y = 20}
        """.trimIndent()

        val program = parser.parse(source)

        // Note: This should actually PASS because using only 'left' is valid
        // The body type-checks correctly as Int -> Int -> Int
        val typedProgram = typeChecker.typeCheck(program)
        assertNotNull(typedProgram, "Using subset of parameters is valid")
    }

    @Test
    fun `test ability body with undefined function should fail`() {
        val source = """
            ability Process 'a where {
              process: {val: 'a} -> String
            }

            impl Process Int where {
              process = {val} -> undefinedFunction {x = val}
            }

            let p = {x} -> Process.process {val = x}

            p {x = 5}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: undefinedFunction is not defined") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body with wrong numeric operation should fail`() {
        val source = """
            ability StringLength 'a where {
              length: {val: 'a} -> Int
            }

            impl StringLength String where {
              length = {val} -> val + val
            }

            let len = {s} -> StringLength.length {val = s}

            len {s = "hello"}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Cannot use + on String without concat") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test multi-parameter ability body returning wrong type should fail`() {
        val source = """
            ability Eq 'a where {
              eq: {l: 'a, r: 'a} -> Bool
            }

            impl Eq Int where {
              eq = {l, r} -> l + r
            }

            let areEqual = {x, y} -> Eq.eq {l = x, r = y}

            areEqual {x = 5, y = 10}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Body returns Int but signature requires Bool") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body returning function when value expected should fail`() {
        val source = """
            ability GetValue 'a where {
              getValue: {val: 'a} -> Int
            }

            impl GetValue Int where {
              getValue = {val} -> {x} -> x + val
            }

            let g = {v} -> GetValue.getValue {val = v}

            g {v = 10}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: Body returns function but signature requires Int") {
            typeChecker.typeCheck(program)
        }
    }

    @Test
    fun `test ability body with type mismatch in nested expression should fail`() {
        val source = """
            ability Transform 'a where {
              transform: {val: 'a} -> String
            }

            impl Transform Int where {
              transform = {val} -> intToString {n = "not an int"}
            }

            let t = {x} -> Transform.transform {val = x}

            t {x = 42}
        """.trimIndent()

        val program = parser.parse(source)

        assertThrows<TypeCheckError>("Should fail: intToString expects Int but receives String") {
            typeChecker.typeCheck(program)
        }
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test ability with identity implementation should pass`() {
        val source = """
            ability Identity 'a where {
              identity: {val: 'a} -> 'a
            }

            impl Identity Bool where {
              identity = {val} -> val
            }

            impl Identity String where {
              identity = {val} -> val
            }

            let idBool = {x} -> Identity.identity {val = x}
            let idStr = {s} -> Identity.identity {val = s}

            idBool {x = true}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Multiple identity implementations should pass")
    }

    @Test
    fun `test ability with nested function calls should pass`() {
        val source = """
            use std::int

            ability Format 'a where {
              format: {val: 'a} -> String
            }

            impl Format Int where {
              format = {val} -> int::to_string {n = val + 1}
            }

            let f = {x} -> Format.format {val = x}

            f {x = 41}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Nested function calls in implementation should pass")
    }

    @Test
    fun `test ability with complex expression should pass`() {
        val source = """
            use std::int

            ability Calculate 'a where {
              calculate: {x: 'a, y: 'a} -> String
            }

            impl Calculate Int where {
              calculate = {x, y} -> int::to_string {n = (x + y) * 2}
            }

            let calc = {a, b} -> Calculate.calculate {x = a, y = b}

            calc {a = 10, b = 15}
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)

        assertNotNull(typedProgram, "Complex arithmetic expression in body should pass")
    }
}
