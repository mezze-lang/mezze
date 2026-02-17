package com.mezze.language.parser

import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for parsing array patterns in match expressions
 *
 * Array patterns support:
 * - Empty arrays: []
 * - Fixed-length arrays: [x], [x, y], [x, y, z]
 * - Rest patterns: [head, ...tail], [x, y, ...rest]
 * - Nested patterns: [[x]], [Some {val = x}], etc.
 */
class ArrayPatternParsingTest {

    private val parser = MezzeParser()

    // ========== Empty Array Pattern ==========

    @Test
    fun `parse match with empty array pattern`() {
        val code = """
            match xs is
              [] -> "empty"
              _ -> "non-empty"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(0, arrayPattern.elements.size)
        assertNull(arrayPattern.restPattern)
    }

    // ========== Fixed-Length Array Patterns ==========

    @Test
    fun `parse match with single element array pattern`() {
        val code = """
            match xs is
              [x] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(1, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is VariablePattern)
        assertEquals("x", (arrayPattern.elements[0] as VariablePattern).name)
        assertNull(arrayPattern.restPattern)
    }

    @Test
    fun `parse match with two element array pattern`() {
        val code = """
            match xs is
              [x, y] -> x + y
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is VariablePattern)
        assertTrue(arrayPattern.elements[1] is VariablePattern)
        assertEquals("x", (arrayPattern.elements[0] as VariablePattern).name)
        assertEquals("y", (arrayPattern.elements[1] as VariablePattern).name)
        assertNull(arrayPattern.restPattern)
    }

    @Test
    fun `parse match with three element array pattern`() {
        val code = """
            match xs is
              [a, b, c] -> a + b + c
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(3, arrayPattern.elements.size)
        assertEquals("a", (arrayPattern.elements[0] as VariablePattern).name)
        assertEquals("b", (arrayPattern.elements[1] as VariablePattern).name)
        assertEquals("c", (arrayPattern.elements[2] as VariablePattern).name)
        assertNull(arrayPattern.restPattern)
    }

    // ========== Rest Pattern ==========

    @Test
    fun `parse match with head and tail rest pattern`() {
        val code = """
            match xs is
              [head, ...tail] -> head
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(1, arrayPattern.elements.size)
        assertEquals("head", (arrayPattern.elements[0] as VariablePattern).name)
        assertEquals("tail", arrayPattern.restPattern)
    }

    @Test
    fun `parse match with multiple elements and rest pattern`() {
        val code = """
            match xs is
              [first, second, ...rest] -> first + second
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(2, arrayPattern.elements.size)
        assertEquals("first", (arrayPattern.elements[0] as VariablePattern).name)
        assertEquals("second", (arrayPattern.elements[1] as VariablePattern).name)
        assertEquals("rest", arrayPattern.restPattern)
    }

    @Test
    fun `parse match with only rest pattern`() {
        val code = """
            match xs is
              [...all] -> all
              _ -> []
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(0, arrayPattern.elements.size)
        assertEquals("all", arrayPattern.restPattern)
    }

    // ========== Wildcard Patterns in Arrays ==========

    @Test
    fun `parse match with wildcard in array pattern`() {
        val code = """
            match xs is
              [_, x] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is WildcardPattern)
        assertTrue(arrayPattern.elements[1] is VariablePattern)
    }

    @Test
    fun `parse match with multiple wildcards in array pattern`() {
        val code = """
            match xs is
              [_, _, x] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(3, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is WildcardPattern)
        assertTrue(arrayPattern.elements[1] is WildcardPattern)
        assertTrue(arrayPattern.elements[2] is VariablePattern)
    }

    // ========== Literal Patterns in Arrays ==========

    @Test
    fun `parse match with literal patterns in array`() {
        val code = """
            match xs is
              [1, 2] -> "pair"
              _ -> "other"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is LiteralPattern)
        assertTrue(arrayPattern.elements[1] is LiteralPattern)
        assertEquals(1L, ((arrayPattern.elements[0] as LiteralPattern).value as IntLiteralValue).value)
        assertEquals(2L, ((arrayPattern.elements[1] as LiteralPattern).value as IntLiteralValue).value)
    }

    @Test
    fun `parse match with mixed literal and variable patterns in array`() {
        val code = """
            match xs is
              [0, x] -> x
              [x, 0] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression

        val pattern1 = matchExpr.cases[0].patterns.first() as ArrayPattern
        assertTrue(pattern1.elements[0] is LiteralPattern)
        assertTrue(pattern1.elements[1] is VariablePattern)

        val pattern2 = matchExpr.cases[1].patterns.first() as ArrayPattern
        assertTrue(pattern2.elements[0] is VariablePattern)
        assertTrue(pattern2.elements[1] is LiteralPattern)
    }

    // ========== Nested Array Patterns ==========

    @Test
    fun `parse match with nested array pattern`() {
        val code = """
            match xs is
              [[x]] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val outerArray = firstPattern as ArrayPattern
        assertEquals(1, outerArray.elements.size)
        assertTrue(outerArray.elements[0] is ArrayPattern)

        val innerArray = outerArray.elements[0] as ArrayPattern
        assertEquals(1, innerArray.elements.size)
        assertTrue(innerArray.elements[0] is VariablePattern)
    }

    @Test
    fun `parse match with deeply nested array pattern`() {
        val code = """
            match xs is
              [[[x]]] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val level1 = matchExpr.cases[0].patterns.first() as ArrayPattern
        val level2 = level1.elements[0] as ArrayPattern
        val level3 = level2.elements[0] as ArrayPattern
        assertTrue(level3.elements[0] is VariablePattern)
    }

    // ========== Constructor Patterns in Arrays ==========

    @Test
    fun `parse match with variant patterns in array`() {
        val code = """
            match xs is
              [Some {val = x}, None] -> x
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is ConstructorPattern)
        assertTrue(arrayPattern.elements[1] is ConstructorPattern)
    }

    // ========== Multiple Array Patterns with | ==========

    @Test
    fun `parse match with multiple array patterns using pipe`() {
        val code = """
            match xs is
              [] | [_] -> "zero or one"
              _ -> "many"
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val patterns = matchExpr.cases[0].patterns

        assertEquals(2, patterns.size)
        assertTrue(patterns[0] is ArrayPattern)
        assertTrue(patterns[1] is ArrayPattern)
        assertEquals(0, (patterns[0] as ArrayPattern).elements.size)
        assertEquals(1, (patterns[1] as ArrayPattern).elements.size)
    }

    // ========== Common Patterns ==========

    @Test
    fun `parse match with classic list-style patterns`() {
        val code = """
            let length = {xs} ->
                match xs is
                    [] -> 0
                    [_, ...tail] -> 1 + length {xs = tail}
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val lambda = letBinding.expression as LambdaExpression
        val matchExpr = lambda.body as MatchExpression

        // First pattern: []
        val emptyPattern = matchExpr.cases[0].patterns.first() as ArrayPattern
        assertEquals(0, emptyPattern.elements.size)
        assertNull(emptyPattern.restPattern)

        // Second pattern: [_, ...tail]
        val consPattern = matchExpr.cases[1].patterns.first() as ArrayPattern
        assertEquals(1, consPattern.elements.size)
        assertTrue(consPattern.elements[0] is WildcardPattern)
        assertEquals("tail", consPattern.restPattern)
    }

    @Test
    fun `parse match with map-style pattern`() {
        val code = """
            use std::collections::array
            let map = {f, xs} ->
                match xs is
                    [] -> []
                    [head, ...tail] -> array::of {x = f {x = head}, xs = map {f = f, xs = tail}}
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.filterIsInstance<LetBinding>().first()
        val lambda = letBinding.expression as LambdaExpression
        val matchExpr = lambda.body as MatchExpression

        // First pattern: []
        val emptyPattern = matchExpr.cases[0].patterns.first() as ArrayPattern
        assertEquals(0, emptyPattern.elements.size)

        // Second pattern: [head, ...tail]
        val consPattern = matchExpr.cases[1].patterns.first() as ArrayPattern
        assertEquals(1, consPattern.elements.size)
        assertEquals("head", (consPattern.elements[0] as VariablePattern).name)
        assertEquals("tail", consPattern.restPattern)
    }

    // ========== Edge Cases ==========

    @Test
    fun `parse match with array pattern containing newlines`() {
        val code = """
            match xs is
              [
                x,
                y,
                z
              ] -> x + y + z
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(3, arrayPattern.elements.size)
    }

    @Test
    fun `parse match with trailing comma in array pattern`() {
        val code = """
            match xs is
              [x, y,] -> x + y
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ArrayPattern)
        val arrayPattern = firstPattern as ArrayPattern
        assertEquals(2, arrayPattern.elements.size)
    }

    @Test
    fun `parse complex nested pattern matching`() {
        val code = """
            match data is
              [[x, y], [a, b]] -> x + y + a + b
              _ -> 0
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstPattern = matchExpr.cases[0].patterns.first() as ArrayPattern

        assertEquals(2, firstPattern.elements.size)
        assertTrue(firstPattern.elements[0] is ArrayPattern)
        assertTrue(firstPattern.elements[1] is ArrayPattern)

        val first = firstPattern.elements[0] as ArrayPattern
        val second = firstPattern.elements[1] as ArrayPattern
        assertEquals(2, first.elements.size)
        assertEquals(2, second.elements.size)
    }

    // ========== Record Expressions with Empty Arrays ==========

    @Test
    fun `parse record with one empty array field`() {
        val code = "let foo = {x = []}"

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(1, record.entries.size)
    }

    @Test
    fun `parse record with two empty array fields`() {
        val code = "let foo = {x = [], y = []}"

        val program = parser.parse(code)
        assertNotNull(program)

        val letBinding = program.items.first() as LetBinding
        val record = letBinding.expression as RecordLiteral
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse just record expression with empty arrays`() {
        val code = "{yes = [], no = []}"

        val program = parser.parse(code)
        assertNotNull(program)

        val topExpr = program.items.first() as TopLevelExpression
        val record = topExpr.expression as RecordLiteral
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse match case returning record with empty arrays`() {
        val code = """
            match xs is
              [] -> {yes = [], no = []}
              _ -> {yes = [1], no = [2]}
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)

        val matchExpr = (program.items.first() as TopLevelExpression).expression as MatchExpression
        val firstCaseExpr = matchExpr.cases[0].expression as RecordLiteral
        assertEquals(2, firstCaseExpr.entries.size)
    }

    @Test
    fun `parse modulo operator`() {
        val code = "let is_even = {x: Int} -> x % 2 == 0"

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse partition function - simplified`() {
        val code = """
            use std::collections::array
            let my_partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  _ -> {yes = [], no = []}

            my_partition
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse array rest pattern with typed lambda params`() {
        val code = """
            let my_func = {xs: [Int]} ->
                match xs is
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse nested function type in lambda params`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                xs

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse nested function type with array pattern`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse nested function type with empty array pattern first`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> "empty"
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse nested function type with both array match cases`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse nested function type - call with two params`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                my_func {pred = pred, xs = tail}

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse nested function type with recursive call - no empty arrays in first case`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> "empty"
                  [head, ...tail] -> do
                    let rest = my_func {pred = pred, xs = tail}
                    rest

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse nested function type with recursive call in second case`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> do
                    let rest = my_func {pred = pred, xs = tail}
                    rest

            my_func
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse partition - with second case`() {
        val code = """
            use std::collections::array
            let my_partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> do
                    let rest = my_partition {pred = pred, xs = tail}
                    rest

            my_partition
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }

    @Test
    fun `parse partition function - full version`() {
        val code = """
            use std::collections::array
            let my_partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> do
                    let rest = my_partition {pred = pred, xs = tail}
                    if pred {x = head}
                    then {yes = array::of {x = head, xs = rest.yes}, no = rest.no}
                    else {yes = rest.yes, no = array::of {x = head, xs = rest.no}}

            let is_even = {x: Int} -> x % 2 == 0
            my_partition {pred = is_even, xs = [1, 2, 3, 4, 5, 6]}
        """.trimIndent()

        val program = parser.parse(code)
        assertNotNull(program)
    }
}
