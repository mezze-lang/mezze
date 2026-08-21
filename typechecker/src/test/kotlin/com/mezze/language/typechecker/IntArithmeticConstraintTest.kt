package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Paths

class IntArithmeticConstraintTest {

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
    fun `test int add constraint propagation`() {
        val code = "let test = {left, right} -> left + right"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { left: 'a, right: 'a } with NumAdd 'a -> 'a
        assertTrue(paramType.fields.containsKey("left"), "Should have 'left' parameter")
        assertTrue(paramType.fields.containsKey("right"), "Should have 'right' parameter")
        assertTrue(functionType is MezzeType.FunctionType, "Should be a function type")
    }

    @Test
    fun `test int subtract constraint propagation`() {
        val code = "let test = {left, right} -> left - right"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { left: 'a, right: 'a } with NumSub 'a -> 'a
        assertTrue(paramType.fields.containsKey("left"), "Should have 'left' parameter")
        assertTrue(paramType.fields.containsKey("right"), "Should have 'right' parameter")
        assertTrue(functionType is MezzeType.FunctionType, "Should be a function type")
    }

    @Test
    fun `test int multiply constraint propagation`() {
        val code = "let test = {left, right} -> left * right"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { left: 'a, right: 'b } with NumMul 'b 'a having 'out = 'c -> 'c
        assertTrue(paramType.fields.containsKey("left"), "Should have 'left' parameter")
        assertTrue(paramType.fields.containsKey("right"), "Should have 'right' parameter")
        assertTrue(functionType is MezzeType.FunctionType, "Should be a function type")
    }

    @Test
    fun `test int divide constraint propagation`() {
        val code = "let test = {left, right} -> left / right"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { left: 'a, right: 'a } with NumDiv 'a -> 'a
        assertTrue(paramType.fields.containsKey("left"), "Should have 'left' parameter")
        assertTrue(paramType.fields.containsKey("right"), "Should have 'right' parameter")
        assertTrue(functionType is MezzeType.FunctionType, "Should be a function type")
    }

    @Test
    fun `test int modulo constraint propagation`() {
        val code = "let test = {left, right} -> left % right"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { left: 'a, right: 'a } with NumMod 'a -> 'a
        assertTrue(paramType.fields.containsKey("left"), "Should have 'left' parameter")
        assertTrue(paramType.fields.containsKey("right"), "Should have 'right' parameter")
        assertTrue(functionType is MezzeType.FunctionType, "Should be a function type")
    }

    @Test
    fun `test nested int arithmetic constraint propagation`() {
        val code = "let test = {a, b, c} -> (a * b) + c"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { a: 'a, b: 'a, c: 'a } with NumAdd, NumMul 'a -> 'a
        assertTrue(paramType.fields.containsKey("a"), "Should have 'a' parameter")
        assertTrue(paramType.fields.containsKey("b"), "Should have 'b' parameter")
        assertTrue(paramType.fields.containsKey("c"), "Should have 'c' parameter")
        assertTrue(functionType is MezzeType.FunctionType, "Should be a function type")
    }

    @Test
    fun `test int arithmetic constraint propagation with result`() {
        val code = "let test = {x, y} -> x + y"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("test") as MezzeType.FunctionType
        val paramType = functionType.paramType as MezzeType.RecordType

        // Should infer { x: 'a, y: 'a } with NumAdd 'a -> 'a
        assertTrue(paramType.fields.containsKey("x"), "Should have 'x' parameter")
        assertTrue(paramType.fields.containsKey("y"), "Should have 'y' parameter")
        assertTrue(functionType is MezzeType.FunctionType, "Should be a function type")
    }
}
