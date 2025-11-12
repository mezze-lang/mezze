package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class DebugAddTest {
    @Test
    fun debugAddFunction() {
        val source = "let add = {x, y} -> x + y"
        val parser = FunkParser()
        val program = parser.parse(source)
        
        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program)
        val typeAliases = typeChecker.getTypeAliases()
        
        println("Program: $source")
        println("AST: $program")
        println("Type check result: $result")
        
        for (item in result.items) {
            when (item) {
                is LetBinding -> {
                    val typeStr = item.inferredType?.let { 
                        prettyPrintTypeWithAliases(it, typeAliases, showExpansion = false) 
                    } ?: "unknown"
                    println("Variable '${item.variableName}' has type: $typeStr")
                    println("Raw type: ${item.inferredType}")
                }
                else -> {
                    // Skip other items
                }
            }
        }
    }
}