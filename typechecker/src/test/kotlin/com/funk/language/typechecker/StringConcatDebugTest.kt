package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class StringConcatDebugTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    
    @Test
    fun `test simple string concat lambda`() {
        val source = """
            let concat = {first, second} -> first ++ second
            concat
        """.trimIndent()
        
        try {
            val program = parser.parse(source)
            val typedProgram = typeChecker.typeCheck(program)
            println("SUCCESS: String concat lambda type checking passed")
            
            val concatBinding = typedProgram.items.find { it is com.funk.language.ast.LetBinding && it.variableName == "concat" } as com.funk.language.ast.LetBinding
            println("Concat type: ${com.funk.language.ast.prettyPrintType(concatBinding.inferredType!!)}")
            
            // Check parameter types specifically
            val concatType = concatBinding.inferredType as com.funk.language.ast.FunkType.FunctionType
            val paramType = concatType.paramType as com.funk.language.ast.FunkType.RecordType
            println("First param type: ${com.funk.language.ast.prettyPrintType(paramType.fields["first"]!!)}")
            println("Second param type: ${com.funk.language.ast.prettyPrintType(paramType.fields["second"]!!)}")
            
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }
}