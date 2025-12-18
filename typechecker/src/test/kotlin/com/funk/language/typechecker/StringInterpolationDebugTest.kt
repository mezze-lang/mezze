package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class StringInterpolationDebugTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test simple string interpolation lambda`() {
        val source = """
            let concat = {first, second} -> "$(first)$(second)"
            concat
        """.trimIndent()

        try {
            val program = parser.parse(source)
            val typedProgram = typeChecker.typeCheck(program).program
            println("SUCCESS: String interpolation lambda type checking passed")

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

    @Test
    fun `test string interpolation with multiple expressions`() {
        val source = """
            let format = {name, age} -> "Name: $(name), Age: $(age)"
            format
        """.trimIndent()

        try {
            val program = parser.parse(source)
            val typedProgram = typeChecker.typeCheck(program).program
            println("SUCCESS: Multiple expression string interpolation type checking passed")

            val formatBinding = typedProgram.items.find { it is com.funk.language.ast.LetBinding && it.variableName == "format" } as com.funk.language.ast.LetBinding
            println("Format type: ${com.funk.language.ast.prettyPrintType(formatBinding.inferredType!!)}")

        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }
}
