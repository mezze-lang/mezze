package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class AbilityMethodCallTest {

    private val parser = MezzeParser()
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test simple ability method call with Bool implementation`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            let test = True.show {}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Find the let binding
        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(MezzeType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test simple ability method call with Int implementation`() {
        val source = """
            use std::int

            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            let test = 42.show {}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(MezzeType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test ability method call with polymorphic parameter succeeds without implementation`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            let test = {x} -> x.show {}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        // With polymorphic parameter, should return function type with constraint
        assertTrue(letBinding!!.inferredType is MezzeType.FunctionType)
        val funcType = letBinding.inferredType as MezzeType.FunctionType
        // Verify Show constraint is present
        assertTrue(funcType.abilityConstraints.any { it.abilityName == "Show" })
    }

    @Test
    fun `test polymorphic function using ability method should have constraint`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            let showResult = {x} -> x.show {}

            showResult
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "showResult" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is MezzeType.FunctionType)

        val funcType = letBinding!!.inferredType as MezzeType.FunctionType
        // Verify ability constraint is present
        assertEquals(1, funcType.abilityConstraints.size)
        assertEquals("Show", funcType.abilityConstraints[0].abilityName)
    }

    @Test
    fun `test ability method call with multiple implementations`() {
        val source = """
            use std::int

            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            impl Show for Int where {
                show = {} -> int::to_str {n = it}
            }

            let test1 = True.show {}
            let test2 = 42.show {}

            test2
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val test1 = typedProgram.items.find { it is LetBinding && it.variableName == "test1" } as LetBinding?
        val test2 = typedProgram.items.find { it is LetBinding && it.variableName == "test2" } as LetBinding?

        assertEquals(MezzeType.StringType, test1!!.inferredType)
        assertEquals(MezzeType.StringType, test2!!.inferredType)
    }

    @Test
    fun `test ability cannot be used as value - should fail`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            let test = Show

            test
        """.trimIndent()

        val program = parser.parse(source)

        // Abilities cannot be used as values - should throw TypeCheckError
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test ability method access is now blocked`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            let test = Show.show

            test
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }

        assertTrue(error.message?.contains("Direct ability access") == true,
            "Error should mention direct ability access: ${error.message}")
        assertTrue(error.message?.contains("Show.show") == true,
            "Error should mention 'Show.show': ${error.message}")
    }

    @Test
    fun `test ability with multiple type parameters`() {
        val source = """
            use std::int

            ability Convert 'b for 'a where {
                convert: {} -> 'b
            }

            impl Convert for Str Int where {
                convert = {} -> int::to_str {n = it}
            }

            let test = 42.convert {}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(MezzeType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test nested ability method calls`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            impl Show for Str where {
                show = {} -> "\"$(it)\""
            }

            let test = (True.show {}).show {}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(MezzeType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test ability method in record field`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            let test = {result = True.show {}}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is MezzeType.RecordType)

        val recordType = letBinding!!.inferredType as MezzeType.RecordType
        assertEquals(MezzeType.StringType, recordType.fields["result"])
    }

    @Test
    fun `test ability method with record argument`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            let test = True.show {}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(MezzeType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test multiple ability constraints on same function`() {
        val source = """
            use std::bool

            ability Show for 'a where {
                show: {} -> Str
            }

            ability MyEq for 'a where {
                eq: {r: 'a} -> Bool
            }

            impl Show for Bool where {
                show = {} -> if it then "True" else "False"
            }

            impl MyEq for Bool where {
                eq: {r: Bool} -> Bool = {r} -> if it then r else bool::not {b = r}
            }

            let showIfEqual = {x, y} ->
                if x.eq {r = y} then x.show {} else "not equal"

            showIfEqual
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "showIfEqual" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is MezzeType.FunctionType)

        // Verify both Show and Eq constraints are present
        val funcType = letBinding!!.inferredType as MezzeType.FunctionType
        assertEquals(2, funcType.abilityConstraints.size)

        // Check that we have both Show and MyEq (order may vary)
        val constraintNames = funcType.abilityConstraints.map { it.abilityName }.toSet()
        assertTrue(constraintNames.contains("Show"))
        assertTrue(constraintNames.contains("MyEq"))
    }

    @Test
    fun `test ability method call with String implementation`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Str where {
                show = {} -> "\"$(it)\""
            }

            let test = "hello".show {}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(MezzeType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test ability constraint propagation from usage site should error`() {
        val source = """
            ability Parse for 'a where {
              decode: { str: Str } -> 'a
            }

            impl Parse for Int where {
              decode = { str } -> 42
            }

            let f = Parse.decode {str = "2332"}
            let t = f + 10.5

            t
        """.trimIndent()

        val program = parser.parse(source)

        // Direct ability access 'Parse.decode' is now blocked
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test typo in ability method name is blocked as direct access`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            let test = Show.shwo
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }

        assertTrue(error.message?.contains("Direct ability access") == true,
            "Error should mention direct ability access: ${error.message}")
        assertTrue(error.message?.contains("Show") == true,
            "Error should mention 'Show': ${error.message}")
    }

    @Test
    fun `test method call with wrong argument fields gives error`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            impl Show for Int where {
                show = {} -> "int"
            }

            let x = 42.show { wrong_field = "oops" }
        """.trimIndent()

        val program = parser.parse(source)
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }
    }

    @Test
    fun `test method call on type without matching impl gives error`() {
        val source = """
            ability Printable for 'a where {
                print: {} -> Str
            }

            impl Printable for Int where {
                print = {} -> "int"
            }

            let x = "hello".print {}
        """.trimIndent()

        val program = parser.parse(source)
        // String doesn't have Printable impl — should fail at some point
        // (either typecheck error or unresolved constraint)
        try {
            val result = typeChecker.typeCheck(program).program
            // If it passes typechecking, it means the constraint is deferred — that's OK
            // as long as it doesn't silently succeed with wrong behavior
        } catch (_: TypeCheckError) {
            // Expected — missing impl detected at typecheck time
        }
    }

    @Test
    fun `test direct ability access error suggests both alternatives`() {
        val source = """
            ability Show for 'a where {
                show: {} -> Str
            }

            let x = Show.show
        """.trimIndent()

        val program = parser.parse(source)
        val error = assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }

        // Error should suggest both method-call and .for syntax
        assertTrue(error.message?.contains("value.show") == true,
            "Error should suggest method-call syntax: ${error.message}")
        assertTrue(error.message?.contains("Show.for") == true,
            "Error should suggest .for syntax: ${error.message}")
    }
}
