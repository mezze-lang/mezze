package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class IncrementalMapTest {

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
    fun `step 1 - just a lambda with typed parameters`() {
        println("STEP 1: Testing lambda with typed params")
        System.out.flush()

        val code = """
            let map = {f: {x: Int} -> Int, xs: [Int]} -> 42
        """.trimIndent()

        val program = parser.parse(code)
        println("STEP 1: Parsed. Typechecking...")
        System.out.flush()
        typeChecker.typeCheck(program)
        println("STEP 1: SUCCESS")
        System.out.flush()
    }

    @Test
    fun `step 2 - lambda with match but no recursion`() {
        println("STEP 2: Testing lambda with match, no recursion")
        System.out.flush()

        val code = """
            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> [42]
        """.trimIndent()

        val program = parser.parse(code)
        println("STEP 2: Parsed. Typechecking...")
        System.out.flush()
        typeChecker.typeCheck(program)
        println("STEP 2: SUCCESS")
        System.out.flush()
    }

    @Test
    fun `step 3 - lambda with match and call to f`() {
        println("STEP 3: Testing lambda with match and call to f")
        System.out.flush()

        val code = """
            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> [f {x = head}]
        """.trimIndent()

        val program = parser.parse(code)
        println("STEP 3: Parsed. Typechecking...")
        System.out.flush()
        typeChecker.typeCheck(program)
        println("STEP 3: SUCCESS")
        System.out.flush()
    }

    @Test
    fun `step 4 - full recursive map`() {
        println("STEP 4: Testing full recursive map")
        System.out.flush()

        val code = """
            use std::collections::seq
            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> seq::of {x = f {x = head}, xs = map {f = f, xs = tail}}
        """.trimIndent()

        val program = parser.parse(code)
        println("STEP 4: Parsed. Typechecking...")
        System.out.flush()
        typeChecker.typeCheck(program)
        println("STEP 4: SUCCESS")
        System.out.flush()
    }

}
