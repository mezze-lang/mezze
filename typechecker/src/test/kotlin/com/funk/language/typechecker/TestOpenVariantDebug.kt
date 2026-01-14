package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.parser.Lexer
import org.junit.jupiter.api.Test

class TestOpenVariantDebug {
    @Test
    fun `test tokenize open variant`() {
        val code = """type Color = |Red, Blue, Green, ... |
Red"""

        println("Code to parse:")
        println(code)
        println()

        val lexer = Lexer(code)
        val tokens = lexer.tokenize()

        println("Tokens:")
        tokens.forEachIndexed { i, token ->
            println("[$i] ${token.type}: '${token.text}'")
        }
    }
}
