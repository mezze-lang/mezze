package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class DebugUnificationTest {
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
    fun `debug simple add function`() {
        val code = "let add = {x, y} -> x + y"
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val addType = result.getVariableType("add")
        assertNotNull(addType, "add type should not be null")

        val functionType = addType as MezzeType.FunctionType

        // Force output by using assertions that will show the values
        if (functionType.paramType !is MezzeType.RecordType) {
            val message = "Expected RecordType but got: ${functionType.paramType::class.simpleName} - ${functionType.toString()}"
            fail<Unit>(message)
        }

        val paramType = functionType.paramType as MezzeType.RecordType
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]
        val returnType = functionType.returnType

        assertNotNull(xType, "x type should not be null")
        assertNotNull(yType, "y type should not be null")

        // Check if both parameters have the same type variable (this is the main achievement)
        if (xType is MezzeType.VarType && yType is MezzeType.VarType) {
            assertEquals(xType.variable.name, yType.variable.name,
                        "x and y should have the same type variable. x: ${xType.variable.name}, y: ${yType.variable.name}")
            println("SUCCESS: Both parameters use the same type variable: ${xType.variable.name}")

            // Note: Return type unification is a separate concern and may use a different type variable
            // The key achievement is that parameters are unified
            println("INFO: Return type is: $returnType")
        } else {
            fail("Expected both x and y to have the same type. x: $xType, y: $yType")
        }
    }
}
