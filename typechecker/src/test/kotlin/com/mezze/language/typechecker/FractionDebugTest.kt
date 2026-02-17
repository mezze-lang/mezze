package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class FractionDebugTest {
    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }

    private fun getVariableType(program: Program, varName: String): MezzeType? {
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
        val isFrac = thirdType is MezzeType.FracType
        println("Is Frac: $isFrac")
    }
}
