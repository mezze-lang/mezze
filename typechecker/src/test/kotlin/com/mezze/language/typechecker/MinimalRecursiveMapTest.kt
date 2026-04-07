package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class MinimalRecursiveMapTest {

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
    fun `test simple recursive map with type annotations`() {
        val code = """
            use std::collections::seq

            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> seq::of {x = f {x = head}, xs = map {f = f, xs = tail}}

            let double = {x: Int} -> x * 2
            let result = map {f = double, xs = [1, 2, 3]}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        println("SUCCESS: Typechecking completed")
    }
}
