package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class DebugTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    
    @Test
    fun `test simple lambda`() {
        val source = """
            let add = {a, b} -> a + b
            let multiply = {x, y} -> x * y
            let divide = {a, b} -> a / b
        """.trimIndent()
        
        try {
            val program = parser.parse(source)
            val typedProgram = typeChecker.typeCheck(program)
            println("SUCCESS: Type checking passed")
            
            // Add the same assertions as the real test
            val addBinding = typedProgram.items.find { it is com.funk.language.ast.LetBinding && it.variableName == "add" } as com.funk.language.ast.LetBinding
            println("Add binding type: ${com.funk.language.ast.prettyPrintType(addBinding.inferredType!!)}")
            
        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }
}