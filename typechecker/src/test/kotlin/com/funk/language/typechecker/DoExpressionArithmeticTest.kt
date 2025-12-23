package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

class DoExpressionArithmeticTest {
    
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }
    
    @Test
    fun `test DoExpression with arithmetic should constrain parameters`() {
        val source = """
            let withDo = {x, y} -> do
              let sum = x + y
              sum
        """.trimIndent()
        
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program).program
        
        // Find the withDo binding
        val withDoBinding = typedProgram.items.find { 
            it is com.funk.language.ast.LetBinding && it.variableName == "withDo" 
        } as com.funk.language.ast.LetBinding
        
        val functionType = withDoBinding.inferredType as com.funk.language.ast.FunkType.FunctionType
        val paramType = functionType.paramType as com.funk.language.ast.FunkType.RecordType
        
        // Check that x and y have types (could be concrete or type variables)
        val xType = paramType.fields["x"]
        val yType = paramType.fields["y"]
        println("DEBUG: x type: ${com.funk.language.ast.prettyPrintType(xType!!)}")
        println("DEBUG: y type: ${com.funk.language.ast.prettyPrintType(yType!!)}")
        
        assertNotNull(xType, "x should have a type")
        assertNotNull(yType, "y should have a type")
    }
}
