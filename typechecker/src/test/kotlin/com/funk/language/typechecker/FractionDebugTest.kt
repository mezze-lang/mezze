package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class FractionDebugTest {
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: FunkParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = FunkParser()
    }

    private fun getVariableType(program: Program, varName: String): FunkType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }

    @Test
    fun debugFractionLiteral() {
        val source = """
            let third = 1/3
        """.trimIndent()

        val ast = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(ast).program

        val thirdType = getVariableType(typedProgram, "third")
        println("third type: $thirdType")
        
        // Should be FracType
        val isFrac = thirdType is FunkType.FracType
        println("Is Frac: $isFrac")
    }
}
