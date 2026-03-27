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
        val result = typeChecker.typeCheck(program)
        val typeAliases = typeChecker.getTypeAliases()

        println("Program: $source")
        println("AST: $program")
        println("Type check result: $result")

        val addType = result.getVariableType("add")
        if (addType != null) {
            val typeStr = prettyPrintTypeWithAliases(addType, typeAliases, showExpansion = false)
            println("Variable 'add' has type: $typeStr")
            println("Raw type: $addType")
        } else {
            println("Variable 'add' type not found in typedModule")
        }
    }
}
