package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class StringConstraintPropagationTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test string interpolation constraint propagation`() {
        val code = "let test = {a, b} -> \"$(a)$(b)\""
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program, Paths.get(".")).program

        val letBinding = typedProgram.items[0] as LetBinding
        val functionType = letBinding.inferredType as FunkType.FunctionType
        val paramType = functionType.paramType as FunkType.RecordType

        // Should infer { a: String, b: String } -> String
        assertEquals(FunkType.StringType, paramType.fields["a"])
        assertEquals(FunkType.StringType, paramType.fields["b"])
        assertEquals(FunkType.StringType, functionType.returnType)
    }

    @Test
    fun `test string with literal constraint propagation`() {
        val code = "let test = {value} -> \"$(value)\""
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program, Paths.get(".")).program

        val letBinding = typedProgram.items[0] as LetBinding
        val functionType = letBinding.inferredType as FunkType.FunctionType
        val paramType = functionType.paramType as FunkType.RecordType

        // Should infer { value: String } -> String
        assertEquals(FunkType.StringType, paramType.fields["value"])
        assertEquals(FunkType.StringType, functionType.returnType)
    }

    @Test
    fun `test string prefix constraint propagation`() {
        val code = "let test = {value} -> \"prefix: $(value)\""
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program, Paths.get(".")).program

        val letBinding = typedProgram.items[0] as LetBinding
        val functionType = letBinding.inferredType as FunkType.FunctionType
        val paramType = functionType.paramType as FunkType.RecordType

        // Should infer { value: String } -> String
        assertEquals(FunkType.StringType, paramType.fields["value"])
        assertEquals(FunkType.StringType, functionType.returnType)
    }

    @Test
    fun `test string suffix constraint propagation`() {
        val code = "let test = {value} -> \"$(value) suffix\""
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program, Paths.get(".")).program

        val letBinding = typedProgram.items[0] as LetBinding
        val functionType = letBinding.inferredType as FunkType.FunctionType
        val paramType = functionType.paramType as FunkType.RecordType

        // Should infer { value: String } -> String
        assertEquals(FunkType.StringType, paramType.fields["value"])
        assertEquals(FunkType.StringType, functionType.returnType)
    }

    @Test
    fun `test string multiple interpolation constraint propagation`() {
        val code = "let test = {a, b, c} -> \"$(a)$(b)$(c)\""
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program, Paths.get(".")).program

        val letBinding = typedProgram.items[0] as LetBinding
        val functionType = letBinding.inferredType as FunkType.FunctionType
        val paramType = functionType.paramType as FunkType.RecordType

        // Should infer { a: String, b: String, c: String } -> String
        assertEquals(FunkType.StringType, paramType.fields["a"])
        assertEquals(FunkType.StringType, paramType.fields["b"])
        assertEquals(FunkType.StringType, paramType.fields["c"])
        assertEquals(FunkType.StringType, functionType.returnType)
    }

    @Test
    fun `test string with literal comparison constraint propagation`() {
        val code = "let test = {value} -> \"$(value)test\""
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program, Paths.get(".")).program

        val letBinding = typedProgram.items[0] as LetBinding
        val functionType = letBinding.inferredType as FunkType.FunctionType
        val paramType = functionType.paramType as FunkType.RecordType

        // Should infer { value: String } -> String
        assertEquals(FunkType.StringType, paramType.fields["value"])
        assertEquals(FunkType.StringType, functionType.returnType)
    }

    @Test
    fun `test nested string interpolation constraint propagation`() {
        val code = "let test = {x, y} -> \"$(x)$(y)end\""
        val program = parser.parse(code)
        val typedProgram = typeChecker.typeCheck(program, Paths.get(".")).program

        val letBinding = typedProgram.items[0] as LetBinding
        val functionType = letBinding.inferredType as FunkType.FunctionType
        val paramType = functionType.paramType as FunkType.RecordType

        // Should infer { x: String, y: String } -> String
        assertEquals(FunkType.StringType, paramType.fields["x"])
        assertEquals(FunkType.StringType, paramType.fields["y"])
        assertEquals(FunkType.StringType, functionType.returnType)
    }
}
