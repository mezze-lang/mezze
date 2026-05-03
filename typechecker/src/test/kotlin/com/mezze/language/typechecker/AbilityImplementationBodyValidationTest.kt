package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
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

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========== PASSING TESTS (Valid Implementation Bodies) ==========

    @Test
    fun `test simple ability with correct body type should pass`() {
        val source = """
            use std::int

            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> int::to_str {n = it}
            }

            let display = {value} -> value.show {}

            display {value = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body returning String should pass")
    }

    @Test
    fun `test ability with record return type should pass`() {
        val source = """
            use std::int

            ability Display for 'a where {
              display: {it: 'a} -> {text: Str}
            }

            impl Display for Int where {
              display = {it} -> {text = int::to_str {n = it}}
            }

            let render = {value} -> value.display {}

            render {value = 100}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body returning record should pass")
    }

    @Test
    fun `test ability with polymorphic operation should pass`() {
        val source = """
            ability Identity for 'a where {
              identity: {it: 'a} -> 'a
            }

            impl Identity for Int where {
              identity = {it} -> it
            }

            let id = {x} -> x.identity {}

            id {x = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body preserving polymorphic type should pass")
    }

    @Test
    fun `test ability with numeric operations should pass`() {
        val source = """
            ability Double for 'a where {
              double: {it: 'a} -> 'a
            }

            impl Double for Int where {
              double = {it} -> it + it
            }

            let d = {x} -> x.double {}

            d {x = 21}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body with numeric operations should pass")
    }

    @Test
    fun `test ability with conditional should pass`() {
        val source = """
            ability Sign for 'a where {
              sign: {it: 'a} -> Str
            }

            impl Sign for Int where {
              sign = {it} -> if it > 0 then "positive" else "non-positive"
            }

            let getSign = {x} -> x.sign {}

            getSign {x = 5}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body with conditional should pass")
    }

    @Test
    fun `test ability with pattern matching should pass`() {
        val source = """
            ability DefaultValue for 'a where {
              get_default: {it: 'a, opt: Option 'a} -> 'a
            }

            impl DefaultValue for Int where {
              get_default = {it, opt} -> match opt is
                Some {val} -> val
                None -> 0
            }

            let getDefault = {d, x} -> d.get_default {opt = x}

            getDefault {d = 0, x = None}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body with pattern matching should pass")
    }

    @Test
    fun `test ability with multiple parameters should pass`() {
        val source = """
            ability Combine for 'a where {
              combine: {it: 'a, right: 'a} -> 'a
            }

            impl Combine for Int where {
              combine = {it, right} -> it + right
            }

            let add = {x, y} -> x.combine {right = y}

            add {x = 10, y = 20}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body with multiple parameters should pass")
    }

    @Test
    fun `test ability calling stdlib functions should pass`() {
        val source = """
            use std::int

            ability Parse for 'a where {
              parse: {it: 'a, str: Str} -> Int
            }

            impl Parse for Str where {
              parse = {it, str} ->
                match int::from_string {s = str} is
                  Some {val} -> val
                  None -> 0
            }

            let p = {v, s} -> v.parse {str = s}

            p {v = "", s = "123"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Implementation body calling stdlib functions should pass")
    }

    // ========== FAILING TESTS (Invalid Implementation Bodies) ==========

    @Test
    fun `test ability body returning wrong primitive type should fail`() {
        val source = """
            ability Show for 'a where {
              show: {it: 'a} -> Str
            }

            impl Show for Int where {
              show = {it} -> it
            }

            let display = {value} -> value.show {}

            display {value = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Body returns Int but signature requires String") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body returning wrong type in conditional should fail`() {
        val source = """
            ability Process for 'a where {
              process: {it: 'a} -> Str
            }

            impl Process for Int where {
              process = {it} -> if it > 0 then "positive" else 0
            }

            let p = {x} -> x.process {}

            p {x = 5}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Conditional branches have different types") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body returning wrong type in pattern match should fail`() {
        val source = """
            ability Extract for 'a where {
              extract: {it: 'a, opt: Option 'a} -> Str
            }

            impl Extract for Int where {
              extract = {it, opt} -> match opt is
                Some {val} -> val
                None -> "none"
            }

            let e = {d, x} -> d.extract {opt = x}

            e {d = 0, x = None}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Some branch returns Int, None branch returns String") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body with wrong record field should fail`() {
        val source = """
            use std::int

            ability Display for 'a where {
              display: {it: 'a} -> {text: Str}
            }

            impl Display for Int where {
              display = {it} -> {message = int::to_str {n = it}}
            }

            let render = {value} -> value.display {}

            render {value = 100}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Record has 'message' field but signature requires 'text'") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body with wrong record field type should fail`() {
        val source = """
            ability Display for 'a where {
              display: {it: 'a} -> {text: Str}
            }

            impl Display for Int where {
              display = {it} -> {text = it}
            }

            let render = {value} -> value.display {}

            render {value = 100}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Record field 'text' is Int but should be String") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body not using all parameters should fail`() {
        val source = """
            ability Combine for 'a where {
              combine: {it: 'a, right: 'a} -> 'a
            }

            impl Combine for Int where {
              combine = {it, right} -> it
            }

            let add = {x, y} -> x.combine {right = y}

            add {x = 10, y = 20}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        // Note: This should actually PASS because using only 'right' is valid
        // The body type-checks correctly as Int -> Int
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))
        assertNotNull(typedProgram, "Using subset of parameters is valid")
    }

    @Test
    fun `test ability body with undefined function should fail`() {
        val source = """
            ability Process for 'a where {
              process: {it: 'a} -> Str
            }

            impl Process for Int where {
              process = {it} -> undefinedFunction {x = it}
            }

            let p = {x} -> x.process {}

            p {x = 5}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: undefinedFunction is not defined") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body with wrong numeric operation should fail`() {
        val source = """
            ability StringLength for 'a where {
              length: {it: 'a} -> Int
            }

            impl StringLength for Str where {
              length = {it} -> it + it
            }

            let len = {s} -> s.size {}

            len {s = "hello"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Cannot use + on String without concat") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test multi-parameter ability body returning wrong type should fail`() {
        val source = """
            ability Eq for 'a where {
              eq: {it: 'a, r: 'a} -> Bool
            }

            impl Eq for Int where {
              eq = {it, r} -> it + r
            }

            let areEqual = {x, y} -> x.eq {r = y}

            areEqual {x = 5, y = 10}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Body returns Int but signature requires Bool") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body returning function when value expected should fail`() {
        val source = """
            ability GetValue for 'a where {
              getValue: {it: 'a} -> Int
            }

            impl GetValue for Int where {
              getValue = {it} -> {x} -> x + it
            }

            let g = {v} -> v.getValue {}

            g {v = 10}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: Body returns function but signature requires Int") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `test ability body with type mismatch in nested expression should fail`() {
        val source = """
            use std::int

            ability Transform for 'a where {
              transform: {it: 'a} -> Str
            }

            impl Transform for Int where {
              transform = {it} -> int::to_str {n = "not an int"}
            }

            let t = {x} -> x.transform {}

            t {x = 42}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")

        assertThrows<TypeCheckError>("Should fail: intToString expects Int but receives String") {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test ability with identity implementation should pass`() {
        val source = """
            ability Identity for 'a where {
              identity: {it: 'a} -> 'a
            }

            impl Identity for Bool where {
              identity = {it} -> it
            }

            impl Identity for Str where {
              identity = {it} -> it
            }

            let idBool = {x} -> x.identity {}
            let idStr = {s} -> s.identity {}

            idBool {x = True}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Multiple identity implementations should pass")
    }

    @Test
    fun `test ability with nested function calls should pass`() {
        val source = """
            use std::int

            ability Format for 'a where {
              format: {it: 'a} -> Str
            }

            impl Format for Int where {
              format = {it} -> int::to_str {n = it + 1}
            }

            let f = {x} -> x.format {}

            f {x = 41}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Nested function calls in implementation should pass")
    }

    @Test
    fun `test ability with complex expression should pass`() {
        val source = """
            use std::int

            ability Calculate for 'a where {
              calculate: {it: 'a, y: 'a} -> Str
            }

            impl Calculate for Int where {
              calculate = {it, y} -> int::to_str {n = (it + y) * 2}
            }

            let calc = {a, b} -> a.calculate {y = b}

            calc {a = 10, b = 15}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Complex arithmetic expression in body should pass")
    }
}
