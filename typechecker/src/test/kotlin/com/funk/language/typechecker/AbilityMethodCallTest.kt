package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class AbilityMethodCallTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test simple ability method call with Bool implementation`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "true" else "false"
            }

            let test = Show.show {val = true}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        // Find the let binding
        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(FunkType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test simple ability method call with Int implementation`() {
        val source = """
            use std::int

            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Int where {
                show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let test = Show.show {val = 42}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(FunkType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test ability method call with polymorphic parameter succeeds without implementation`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let test = {x} -> Show.show {val = x}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        // With polymorphic parameter, should return function type with constraint
        assertTrue(letBinding!!.inferredType is FunkType.FunctionType)
        val funcType = letBinding.inferredType as FunkType.FunctionType
        // Verify Show constraint is present
        assertTrue(funcType.abilityConstraints.any { it.abilityName == "Show" })
    }

    @Test
    fun `test polymorphic function using ability method should have constraint`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "true" else "false"
            }

            let showResult = {x} -> Show.show {val = x}

            showResult
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "showResult" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is FunkType.FunctionType)

        val funcType = letBinding!!.inferredType as FunkType.FunctionType
        // Verify ability constraint is present
        assertEquals(1, funcType.abilityConstraints.size)
        assertEquals("Show", funcType.abilityConstraints[0].abilityName)
    }

    @Test
    fun `test ability method call with multiple implementations`() {
        val source = """
            use std::int

            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "true" else "false"
            }

            impl Show Int where {
                show: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let test1 = Show.show {val = true}
            let test2 = Show.show {val = 42}

            test2
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val test1 = typedProgram.items.find { it is LetBinding && it.variableName == "test1" } as LetBinding?
        val test2 = typedProgram.items.find { it is LetBinding && it.variableName == "test2" } as LetBinding?

        assertEquals(FunkType.StringType, test1!!.inferredType)
        assertEquals(FunkType.StringType, test2!!.inferredType)
    }

    @Test
    fun `test ability cannot be used as value - should fail`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
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
    fun `test ability method access returns function type`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            let test = Show.show

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        println("DEBUG: test type = ${letBinding!!.inferredType}")
        println("DEBUG: test type class = ${letBinding!!.inferredType?.javaClass?.simpleName}")
        assertTrue(letBinding!!.inferredType is FunkType.FunctionType)

        val funcType = letBinding!!.inferredType as FunkType.FunctionType
        assertTrue(funcType.paramType is FunkType.RecordType)
        assertEquals(FunkType.StringType, funcType.returnType)
    }

    @Test
    fun `test ability with multiple type parameters`() {
        val source = """
            use std::int

            ability Convert 'a 'b where {
                convert: {val: 'a} -> 'b
            }

            impl Convert Int String where {
                convert: {val: Int} -> String = {val} -> int::to_string {n = val}
            }

            let test = Convert.convert {val = 42}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(FunkType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test nested ability method calls`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "true" else "false"
            }

            impl Show String where {
                show: {val: String} -> String = {val} -> "\"$(val)\""
            }

            let test = Show.show {val = Show.show {val = true}}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(FunkType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test ability method in record field`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "true" else "false"
            }

            let test = {result = Show.show {val = true}}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is FunkType.RecordType)

        val recordType = letBinding!!.inferredType as FunkType.RecordType
        assertEquals(FunkType.StringType, recordType.fields["result"])
    }

    @Test
    fun `test ability method with record argument`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "true" else "false"
            }

            let test = Show.show {val = true}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(FunkType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test multiple ability constraints on same function`() {
        val source = """
            use std::bool

            ability Show 'a where {
                show: {val: 'a} -> String
            }

            ability Eq 'a where {
                eq: {x: 'a, y: 'a} -> Bool
            }

            impl Show Bool where {
                show: {val: Bool} -> String = {val} -> if val then "true" else "false"
            }

            impl Eq Bool where {
                eq: {x: Bool, y: Bool} -> Bool = {x, y} -> if x then y else bool::not {b = y}
            }

            let showIfEqual = {x, y} ->
                if Eq.eq {x = x, y = y} then Show.show {val = x} else "not equal"

            showIfEqual
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "showIfEqual" } as LetBinding?
        assertNotNull(letBinding)
        assertTrue(letBinding!!.inferredType is FunkType.FunctionType)

        // Verify both Show and Eq constraints are present
        val funcType = letBinding!!.inferredType as FunkType.FunctionType
        assertEquals(2, funcType.abilityConstraints.size)

        // Check that we have both Show and Eq (order may vary)
        val constraintNames = funcType.abilityConstraints.map { it.abilityName }.toSet()
        assertTrue(constraintNames.contains("Show"))
        assertTrue(constraintNames.contains("Eq"))
    }

    @Test
    fun `test ability method call with String implementation`() {
        val source = """
            ability Show 'a where {
                show: {val: 'a} -> String
            }

            impl Show String where {
                show: {val: String} -> String = {val} -> "\"$(val)\""
            }

            let test = Show.show {val = "hello"}

            test
        """.trimIndent()

        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program

        val letBinding = typedProgram.items.find { it is LetBinding && it.variableName == "test" } as LetBinding?
        assertNotNull(letBinding)
        assertEquals(FunkType.StringType, letBinding!!.inferredType)
    }

    @Test
    fun `test ability constraint propagation from usage site should error`() {
        val source = """
            ability Parse 'a where {
              decode: { str: String } -> 'a
            }

            impl Parse Int where {
              decode = { str } -> 42
            }

            let f = Parse.decode {str = "2332"}
            let t = f + 10.5

            t
        """.trimIndent()

        val program = parser.parse(source)

        // Should fail because:
        // 1. f = Parse.decode {str = "2332"} has type 'a with Parse 'a constraint
        // 2. f + 10.5 requires f to be Float (10.5 is Float)
        // 3. No impl Parse Float exists
        assertThrows(TypeCheckError::class.java) {
            typeChecker.typeCheck(program).program
        }
    }
}
