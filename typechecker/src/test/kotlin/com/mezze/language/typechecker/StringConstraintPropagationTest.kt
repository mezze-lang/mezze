package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class StringConstraintPropagationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `test string interpolation constraint propagation`() {
        val code = "let test = {a, b} -> \"$(a)$(b)\""
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { a: Str, b: Str } -> Str
        assertEquals(MezzeType.StringType, paramType.fields["a"])
        assertEquals(MezzeType.StringType, paramType.fields["b"])
        assertEquals(MezzeType.StringType, functionType.returnType)
    }

    @Test
    fun `test string with literal constraint propagation`() {
        val code = "let test = {value} -> \"$(value)\""
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { value: Str } -> Str
        assertEquals(MezzeType.StringType, paramType.fields["value"])
        assertEquals(MezzeType.StringType, functionType.returnType)
    }

    @Test
    fun `test string prefix constraint propagation`() {
        val code = "let test = {value} -> \"prefix: $(value)\""
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { value: Str } -> Str
        assertEquals(MezzeType.StringType, paramType.fields["value"])
        assertEquals(MezzeType.StringType, functionType.returnType)
    }

    @Test
    fun `test string suffix constraint propagation`() {
        val code = "let test = {value} -> \"$(value) suffix\""
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { value: Str } -> Str
        assertEquals(MezzeType.StringType, paramType.fields["value"])
        assertEquals(MezzeType.StringType, functionType.returnType)
    }

    @Test
    fun `test string multiple interpolation constraint propagation`() {
        val code = "let test = {a, b, c} -> \"$(a)$(b)$(c)\""
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { a: Str, b: Str, c: Str } -> Str
        assertEquals(MezzeType.StringType, paramType.fields["a"])
        assertEquals(MezzeType.StringType, paramType.fields["b"])
        assertEquals(MezzeType.StringType, paramType.fields["c"])
        assertEquals(MezzeType.StringType, functionType.returnType)
    }

    @Test
    fun `test string with literal comparison constraint propagation`() {
        val code = "let test = {value} -> \"$(value)test\""
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { value: Str } -> Str
        assertEquals(MezzeType.StringType, paramType.fields["value"])
        assertEquals(MezzeType.StringType, functionType.returnType)
    }

    @Test
    fun `test nested string interpolation constraint propagation`() {
        val code = "let test = {x, y} -> \"$(x)$(y)end\""
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { x: Str, y: Str } -> Str
        assertEquals(MezzeType.StringType, paramType.fields["x"])
        assertEquals(MezzeType.StringType, paramType.fields["y"])
        assertEquals(MezzeType.StringType, functionType.returnType)
    }
}
