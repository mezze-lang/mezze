package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test

class RecursiveMapCorrectSyntaxTest {

    private val parser = FunkParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test recursive map with correct concat syntax`() {
        println("TEST: Recursive map with array concat method")
        System.out.flush()

        val code = """
            use std::collections::array
            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> array::concat {xs = [f {x = head}], ys = map {f = f, xs = tail}}
        """.trimIndent()

        println("Parsing...")
        System.out.flush()
        val program = parser.parse(code)
        println("Parse successful!")
        System.out.flush()

        println("Type checking...")
        System.out.flush()
        typeChecker.typeCheck(program)
        println("Type check successful!")
        System.out.flush()

        println("TEST PASSED: Recursive map with concat works!")
        System.out.flush()
    }
}
