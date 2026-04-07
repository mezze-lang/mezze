package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test

class RecursiveMapCorrectSyntaxTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    @Test
    fun `test recursive map with correct concat syntax`() {
        println("TEST: Recursive map with array concat method")
        System.out.flush()

        val code = """
            use std::collections::seq
            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> seq::concat {xs = [f {x = head}], ys = map {f = f, xs = tail}}
        """.trimIndent()

        println("Parsing...")
        System.out.flush()
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        println("Parse successful!")
        System.out.flush()

        println("Type checking...")
        System.out.flush()
        typeChecker.typeCheck(parsedModule, Paths.get("."))
        println("Type check successful!")
        System.out.flush()

        println("TEST PASSED: Recursive map with concat works!")
        System.out.flush()
    }
}
