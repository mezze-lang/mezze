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

        // Under heterogeneous `+` (NumAdd 'r for 'l having 'out), an unannotated
        // {x, y} -> x + y infers {x:'l, y:'r} with NumAdd 'r 'l having 'out -> 'out,
        // where x and y are DISTINCT type variables (they may differ, e.g. Int + Float).
        if (xType is MezzeType.VarType && yType is MezzeType.VarType) {
            assertNotEquals(xType.variable.name, yType.variable.name,
                        "x and y should have distinct type variables under heterogeneous +. x: ${xType.variable.name}, y: ${yType.variable.name}")

            // The result type 'out is a separate type variable from the operands.
        } else {
            fail("Expected both x and y to be type variables. x: $xType, y: $yType")
        }
    }
}
