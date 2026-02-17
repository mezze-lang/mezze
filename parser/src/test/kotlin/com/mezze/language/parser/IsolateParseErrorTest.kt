package com.mezze.language.parser

import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class IsolateParseErrorTest {

    private val parser = MezzeParser()

    @Test
    fun `test simplified partition function`() {
        val code = """
            use std::collections::array
            let partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  [head, ...tail] -> do
                    let rest = partition {pred = pred, xs = tail}
                    if pred {x = head}
                    then { yes = array::of {x = head, xs = rest.yes}, no = rest.no }
                    else { yes = rest.yes, no = array::of {x = head, xs = rest.no} }

            let is_even = {x: Int} -> x % 2 == 0
            let result = partition {pred = is_even, xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        println("About to parse code:")
        println(code)
        println("\n")

        try {
            val result = parser.parse(code)
            assertNotNull(result)
            println("✓ Parse succeeded!")
        } catch (e: Exception) {
            println("✗ Parse failed: ${e.message}")
            throw e
        }
    }

    @Test
    fun `test record with two empty arrays only`() {
        val code = "{ yes = [], no = [] }"
        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test match with record in first case`() {
        val code = """
            match [] is
              [] -> { yes = [], no = [] }
              _ -> { yes = [], no = [] }
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test lambda with match and record`() {
        val code = """
            let f = {xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  _ -> { yes = [], no = [] }
            f
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test lambda with nested function type`() {
        val code = """
            let f = {pred: {x: Int} -> Bool, xs: [Int]} -> { yes = [], no = [] }
            f
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test lambda with nested function type and match`() {
        val code = """
            let f = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  _ -> { yes = [], no = [] }
            f
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test with rest pattern and do block`() {
        val code = """
            let f = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  [head, ...tail] -> do
                    { yes = [], no = [] }
            f
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test with recursive call`() {
        val code = """
            let partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  [head, ...tail] -> partition {pred = pred, xs = tail}

            partition
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test with rest pattern do block and simple body`() {
        val code = """
            let partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  [head, ...tail] -> do
                    let rest = partition {pred = pred, xs = tail}
                    rest

            partition
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test with rest pattern do block and if expression`() {
        val code = """
            use std::collections::array
            let partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  [head, ...tail] -> do
                    let rest = partition {pred = pred, xs = tail}
                    if pred {x = head}
                    then { yes = array::of {x = head, xs = rest.yes}, no = rest.no }
                    else { yes = rest.yes, no = array::of {x = head, xs = rest.no} }

            partition
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test if expression with record containing array cons`() {
        val code = """
            let partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> { yes = [], no = [] }
                  [head, ...tail] -> if pred {x = head}
                    then { yes = [head], no = [] }
                    else { yes = [], no = [head] }

            partition
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test record with array field containing array literal not empty`() {
        val code = "{ yes = [1], no = [] }"
        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test in lambda context with match`() {
        val code = """
            let f = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [head, ...tail] -> { yes = [head], no = [] }
                  _ -> { yes = [], no = [] }
            f
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test record with array concat in field`() {
        val code = """
            use std::collections::array
            { yes = array::concat {xs = [1], ys = [2]}, no = [] }
        """.trimIndent()
        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }

    @Test
    fun `test in match context with array concat`() {
        val code = """
            use std::collections::array
            let f = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [head, ...tail] -> { yes = array::of {x = head, xs = tail}, no = [] }
                  _ -> { yes = [], no = [] }
            f
        """.trimIndent()

        println("Parsing: $code")
        val result = parser.parse(code)
        assertNotNull(result)
        println("✓ Parse succeeded!")
    }
}
