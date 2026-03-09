package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

class SimpleParseTest {
    @Test
    fun `test take parse`() {
        val parser = MezzeParser()
        // Test 1: With parentheses
        val code1 = """
let test1 = take {n = (n - 1), xs = tail}
        """.trim()

        // Test 2: Without parentheses
        val code2 = """
let test2 = take {n = n - 1, xs = tail}
        """.trim()

        // Test 3: Full function
        val code3 = """
use std::collections::seq

let take = {n: Int, xs} ->
    match xs is
      [] -> []
      [head, ...tail] ->
        if n <= 0
        then []
        else seq::of {x = head, xs = take {n = n - 1, xs = tail}}

let result = take {n = 2, xs = [1, 2, 3, 4, 5]}
        """.trim()

        // Test 4: Nested record literals
        val code4 = """
let foo = bar {x = 1, y = baz {a = 2, b = 3}}
        """.trim()

        // Test 5: With parentheses
        val code5 = """
use std::collections::seq

let take = {n: Int, xs} ->
    match xs is
      [] -> []
      [head, ...tail] ->
        if n <= 0
        then []
        else seq::of {x = head, xs = (take {n = n - 1, xs = tail})}

let result = take {n = 2, xs = [1, 2, 3, 4, 5]}
        """.trim()

        // Test 6: Nested with arithmetic
        val code6 = """
let foo = bar {x = 1, y = baz {a = n - 1, b = 3}}
        """.trim()

        // Test 7: Inside match expression
        val code7 = """
let foo = match xs is
  [head, ...tail] -> seq::of {x = head, xs = bar {n = n - 1, y = tail}}
        """.trim()

        // Test 8: With if-then-else
        val code8 = """
let foo = match xs is
  [head, ...tail] ->
    if n <= 0
    then []
    else seq::of {x = head, xs = bar {n = n - 1, y = tail}}
        """.trim()

        // Test 9: With multiple match cases
        val code9 = """
let foo = match xs is
  [] -> []
  [head, ...tail] ->
    if n <= 0
    then []
    else seq::of {x = head, xs = bar {n = n - 1, y = tail}}
        """.trim()

        // Test 10: Wrapped in lambda
        val code10 = """
let foo = {n: Int, xs} ->
  match xs is
    [] -> []
    [head, ...tail] ->
      if n <= 0
      then []
      else seq::of {x = head, xs = bar {n = n - 1, y = tail}}
        """.trim()

        // Test 11: With let binding to extract nested call
        val code11 = """
let foo = {n: Int, xs} ->
  match xs is
    [] -> []
    [head, ...tail] ->
      if n <= 0
      then []
      else
        let rest = bar {n = n - 1, y = tail}
        seq::of {x = head, xs = rest}
        """.trim()

        // Test 12: With parentheses around function call
        val code12 = """
let foo = {n: Int, xs} ->
  match xs is
    [] -> []
    [head, ...tail] ->
      if n <= 0
      then []
      else
        let rest = (bar {n = n - 1, y = tail})
        seq::of {x = head, xs = rest}
        """.trim()

        // Test 13: Minimal failing case
        val code13 = """
let foo = {n} -> bar {x = 1, y = 2}
        """.trim()

        // Test 14: With typed parameter
        val code14 = """
let foo = {n: Int} -> bar {x = 1, y = 2}
        """.trim()

        // Test 15: With two typed parameters
        val code15 = """
let foo = {n: Int, xs} -> bar {x = 1, y = 2}
        """.trim()

        // Test 16: Two untyped parameters
        val code16 = """
let foo = {n, xs} -> bar {x = 1, y = 2}
        """.trim()

        val code = code15

        println("===== CODE TO PARSE =====")
        println(code)
        println("===== ATTEMPTING PARSE =====")
        try {
            val ast = parser.parse(code)
            println("SUCCESS! Parsed to AST")
            assertNotNull(ast, "AST should not be null")
            println("\n===== AST ITEMS =====")
            ast.items.forEachIndexed { i, item ->
                println("Item $i: ${item::class.simpleName}")
                if (item is com.mezze.language.ast.LetBinding) {
                    println("  Pattern: ${item.pattern}")
                    println("  Expression: ${item.expression}")
                    println("  Expression class: ${item.expression::class.simpleName}")
                    if (item.expression is com.mezze.language.ast.FunctionCall) {
                        val call = item.expression as com.mezze.language.ast.FunctionCall
                        println("  Function: ${call.function}")
                        println("  Arguments: ${call.arguments}")
                        println("  Arguments class: ${call.arguments::class.simpleName}")
                        if (call.arguments is com.mezze.language.ast.RecordLiteral) {
                            val rec = call.arguments as com.mezze.language.ast.RecordLiteral
                            println("  Record entries (${rec.entries.size} entries):")
                            rec.entries.forEach { entry ->
                                when (entry) {
                                    is com.mezze.language.ast.RecordFieldEntry -> {
                                        val className = entry.value?.let { it::class.simpleName } ?: "null"
                                        println("    Field: ${entry.name} = ${entry.value} ($className)")
                                    }
                                    is com.mezze.language.ast.RecordSpreadEntry -> {
                                        println("    Spread: ${entry.expression}")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("PARSE ERROR: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
}
