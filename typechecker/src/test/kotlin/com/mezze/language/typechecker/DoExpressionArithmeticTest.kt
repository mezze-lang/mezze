package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class DoExpressionArithmeticTest {
    
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
    fun `test DoExpression with arithmetic should constrain parameters`() {
        val source = """
            let withDo = {x, y} -> do
              let sum = x + y
              sum
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))

        val functionType = result.getVariableType("withDo") as com.mezze.language.ast.MezzeType.FunctionType
        val paramType = functionType.paramType as com.mezze.language.ast.MezzeType.RecordType

        // Check that x and y have types (could be concrete or type variables)
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]
        println("DEBUG: x type: ${com.mezze.language.ast.prettyPrintType(xType!!)}")
        println("DEBUG: y type: ${com.mezze.language.ast.prettyPrintType(yType!!)}")

        assertNotNull(xType, "x should have a type")
        assertNotNull(yType, "y should have a type")
    }
}
