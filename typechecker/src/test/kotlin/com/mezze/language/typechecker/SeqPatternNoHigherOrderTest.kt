package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class SeqPatternNoHigherOrderTest {

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
    fun `recursive with seq patterns but NO higher-order function`() {
        val code = """
            let sum = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [head, ...tail] -> head + sum {xs = tail}

            let result = sum {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        println("Starting typecheck...")
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        println("SUCCESS")
    }

    @Test
    fun `recursive map-like function with separate double function`() {
        val code = """
            use std::collections::seq

            let double = {x: Int} -> x * 2

            let map_double = {xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> seq::of {x = double {x = head}, xs = map_double {xs = tail}}

            let result = map_double {xs = [1, 2, 3]}
        """.trimIndent()

        println("Starting typecheck...")
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        val result = typeChecker.typeCheck(parsedModule, Paths.get("."))
        println("SUCCESS")
    }
}
