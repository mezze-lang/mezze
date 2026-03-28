package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
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

            val concatType = typedProgram.getVariableType("concat")
            println("Concat type: ${concatType?.let { prettyPrintType(it) } ?: "unknown"}")

            // Check parameter types specifically
            if (concatType is MezzeType.FunctionType) {
                val paramType = concatType.paramType as MezzeType.RecordType
                println("First param type: ${paramType.fields["first"]?.let { prettyPrintType(it) }}")
                println("Second param type: ${paramType.fields["second"]?.let { prettyPrintType(it) }}")
            }

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

            val formatType = typedProgram.getVariableType("format")
            println("Format type: ${formatType?.let { prettyPrintType(it) } ?: "unknown"}")

        } catch (e: Exception) {
            println("ERROR: ${e.message}")
            e.printStackTrace()
        }
    }
}
