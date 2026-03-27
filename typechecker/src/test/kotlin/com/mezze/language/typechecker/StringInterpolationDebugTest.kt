package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class StringInterpolationDebugTest {

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
    fun `test simple string interpolation lambda`() {
        val source = """
            let concat = {first, second} -> "$(first)$(second)"
            concat
        """.trimIndent()

        try {
            val program = parser.parse(source)
            val typedProgram = typeChecker.typeCheck(program)
            println("SUCCESS: Str interpolation lambda type checking passed")

            val concatBinding = typedProgram.items.find { it is com.mezze.language.ast.LetBinding && it.variableName == "concat" } as com.mezze.language.ast.LetBinding
            println("Concat type: ${com.mezze.language.ast.prettyPrintType(concatBinding.inferredType!!)}")

            // Check parameter types specifically
            val concatType = concatBinding.inferredType as com.mezze.language.ast.MezzeType.FunctionType
            val paramType = concatType.paramType as com.mezze.language.ast.MezzeType.RecordType
            println("First param type: ${com.mezze.language.ast.prettyPrintType(paramType.fields["first"]!!)}")
            println("Second param type: ${com.mezze.language.ast.prettyPrintType(paramType.fields["second"]!!)}")

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
            val typedProgram = typeChecker.typeCheck(program)
            println("SUCCESS: Multiple expression string interpolation type checking passed")

            val formatBinding = typedProgram.items.find { it is com.mezze.language.ast.LetBinding && it.variableName == "format" } as com.mezze.language.ast.LetBinding
            println("Format type: ${com.mezze.language.ast.prettyPrintType(formatBinding.inferredType!!)}")

        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }
}
