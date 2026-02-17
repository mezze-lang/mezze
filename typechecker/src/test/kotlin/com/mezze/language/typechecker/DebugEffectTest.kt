package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

class DebugEffectTest {
    
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
    fun debugSimpleEffectCall() {
        val source = """
            effect Log where {
              log : {msg: String} -> {} <Log>
            }

            let logSomething : {msg: String} -> {} <Log> = {msg} ->
              Log.log {msg = msg}
        """.trimIndent()
        
        val program = parser.parse(source)
        println("Parsed program items: ${program.items.size}")
        program.items.forEachIndexed { index, item ->
            println("Item $index: $item")
        }
        
        val typedProgram = typeChecker.typeCheck(program).program
        
        // The logSomething function should be at index 1
        val logFunctionDecl = typedProgram.items[1] as LetBinding
        
        println("Function declaration: $logFunctionDecl")
        println("Function expression: ${logFunctionDecl.expression}")
        println("Inferred type: ${logFunctionDecl.inferredType}")
        
        if (logFunctionDecl.inferredType is MezzeType.FunctionType) {
            val functionType = logFunctionDecl.inferredType as MezzeType.FunctionType
            println("Function type effects: ${functionType.effects}")
            println("Function type effects set: ${functionType.effects?.effects}")
        }
    }
}
