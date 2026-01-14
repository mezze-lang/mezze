package com.funk.language.typechecker

import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test

class ParserOnlyTest {

    @Test
    fun `test parsing recursive map only`() {
        println("TEST STARTED: Parsing recursive map")
        System.out.flush()

        val parser = FunkParser()
        println("Parser created")
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

        val startTime = System.currentTimeMillis()
        val program = parser.parse(code)
        val endTime = System.currentTimeMillis()

        println("Parsing completed in ${endTime - startTime}ms")
        println("Program has ${program.items.size} items")
        System.out.flush()
    }
}
