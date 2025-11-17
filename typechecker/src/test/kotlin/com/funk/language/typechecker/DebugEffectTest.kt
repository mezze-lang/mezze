package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class DebugEffectTest {
    
    private val parser = FunkParser()
    private val typeChecker = TypeChecker()
    
    @Test
    fun debugSimpleEffectCall() {
        val source = """
            effect Log {
              log : {msg: String} -> {}
            }
            
            let logSomething : {msg: String} -> {} <Log> = {msg} ->
              Log.log {msg = msg}
        """.trimIndent()
        
        val program = parser.parse(source)
        println("Parsed program items: ${program.items.size}")
        program.items.forEachIndexed { index, item ->
            println("Item $index: $item")
        }
        
        val typedProgram = typeChecker.typeCheck(program)
        
        // The logSomething function should be at index 1
        val logFunctionDecl = typedProgram.items[1] as LetBinding
        
        println("Function declaration: $logFunctionDecl")
        println("Function expression: ${logFunctionDecl.expression}")
        println("Inferred type: ${logFunctionDecl.inferredType}")
        
        if (logFunctionDecl.inferredType is FunkType.FunctionType) {
            val functionType = logFunctionDecl.inferredType as FunkType.FunctionType
            println("Function type effects: ${functionType.effects}")
            println("Function type effects set: ${functionType.effects?.effects}")
        }
    }
}
