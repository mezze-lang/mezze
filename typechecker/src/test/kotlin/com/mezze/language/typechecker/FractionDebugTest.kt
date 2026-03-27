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

    private fun getVariableType(result: TypeCheckResult, varName: String): MezzeType? {
        return result.getVariableType(varName)
    }

    @Test
    fun debugFractionLiteral() {
        val source = """
            let third = 1/3
        """.trimIndent()

        val ast = parser.parse(source)
        val typedResult = typeChecker.typeCheck(ast)

        val thirdType = getVariableType(typedResult, "third")
        println("third type: $thirdType")
        
        // Should be FracType
        val isFrac = thirdType is MezzeType.FracType
        println("Is Frac: $isFrac")
    }
}
