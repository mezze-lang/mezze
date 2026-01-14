package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class ArrayPatternNoHigherOrderTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `recursive with array patterns but NO higher-order function`() {
        val code = """
            let sum = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [head, ...tail] -> head + sum {xs = tail}

            let result = sum {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        println("Starting typecheck...")
        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        println("SUCCESS")
    }

    @Test
    fun `recursive map-like function with separate double function`() {
        val code = """
            use std::collections::array

            let double = {x: Int} -> x * 2

            let map_double = {xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> array::of {x = double {x = head}, xs = map_double {xs = tail}}

            let result = map_double {xs = [1, 2, 3]}
        """.trimIndent()

        println("Starting typecheck...")
        val program = parser.parse(code)
        val result = typeChecker.typeCheck(program)
        println("SUCCESS")
    }
}
