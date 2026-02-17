package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser

fun main() {
    println("Starting parser test...")
    System.out.flush()

    val parser = MezzeParser()
    println("Parser created")
    System.out.flush()

    val code = """
        use std::collections::array

        let map = {f: {x: Int} -> Int, xs: [Int]} ->
            match xs is
              [] -> []
              [head, ...tail] -> array::of {x = f {x = head}, xs = map {f = f, xs = tail}}
    """.trimIndent()

    println("About to parse recursive map function...")
    System.out.flush()

    val program = parser.parse(code)

    println("Parsing completed successfully!")
    println("Program has ${program.items.size} items")
    System.out.flush()
}
