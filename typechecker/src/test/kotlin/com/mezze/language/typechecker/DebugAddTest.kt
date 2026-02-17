package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test

class DebugAddTest {
    @Test
    fun debugAddFunction() {
        val source = "let add = {x, y} -> x + y"
        val parser = MezzeParser()
        val program = parser.parse(source)
        
        val typeChecker = TypeChecker()
        val result = typeChecker.typeCheck(program).program
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