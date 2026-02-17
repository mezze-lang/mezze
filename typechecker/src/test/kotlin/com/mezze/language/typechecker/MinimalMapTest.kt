package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class MinimalMapTest {

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
    fun `minimal recursive map with explicit types`() {
        println("TEST STARTED: minimal recursive map with explicit types")
        System.out.flush()

        val code = """
            use std::collections::array

            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> array::of {x = f {x = head}, xs = map {f = f, xs = tail}}
        """.trimIndent()

        println("About to parse...")
        System.out.flush()
        val program = parser.parse(code)
        println("Parsing done. About to typecheck...")
        System.out.flush()
        val result = typeChecker.typeCheck(program)
        println("SUCCESS WITH type annotations")
        System.out.flush()
    }

    @Test
    fun `minimal recursive map WITHOUT type annotations`() {
        println("TEST STARTED: minimal recursive map WITHOUT type annotations")
        System.out.flush()

        val code = """
            use std::collections::array

            let map = {f, xs} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> array::of {x = f {x = head}, xs = map {f = f, xs = tail}}
        """.trimIndent()

        println("About to parse...")
        System.out.flush()
        val program = parser.parse(code)
        println("Parsing done. About to typecheck...")
        System.out.flush()
        val result = typeChecker.typeCheck(program)
        println("SUCCESS WITHOUT type annotations")
        System.out.flush()
    }
}
