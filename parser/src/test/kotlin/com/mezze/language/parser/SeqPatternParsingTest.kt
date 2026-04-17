package com.mezze.language.parser

import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for parsing seq patterns in match expressions
 *
 * Seq patterns support:
 * - Empty arrays: []
 * - Fixed-length arrays: [x], [x, y], [x, y, z]
 * - Rest patterns: [head, ...tail], [x, y, ...rest]
 * - Nested patterns: [[x]], [Some {val = x}], etc.
 */
class SeqPatternParsingTest {

    private val parser = MezzeParser()

    // ========== Empty Array Pattern ==========

    @Test
    fun `parse match with empty seq pattern`() {
        val code = """
            match xs is
              [] -> "empty"
              _ -> "non-empty"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(0, arrayPattern.elements.size)
        assertNull(arrayPattern.restPattern)
    }

    // ========== Fixed-Length Array Patterns ==========

    @Test
    fun `parse match with single element seq pattern`() {
        val code = """
            match xs is
              [x] -> x
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(1, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is ParsedVariablePattern)
        assertEquals("x", (arrayPattern.elements[0] as ParsedVariablePattern).name)
        assertNull(arrayPattern.restPattern)
    }

    @Test
    fun `parse match with two element seq pattern`() {
        val code = """
            match xs is
              [x, y] -> x + y
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is ParsedVariablePattern)
        assertTrue(arrayPattern.elements[1] is ParsedVariablePattern)
        assertEquals("x", (arrayPattern.elements[0] as ParsedVariablePattern).name)
        assertEquals("y", (arrayPattern.elements[1] as ParsedVariablePattern).name)
        assertNull(arrayPattern.restPattern)
    }

    @Test
    fun `parse match with three element seq pattern`() {
        val code = """
            match xs is
              [a, b, c] -> a + b + c
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(3, arrayPattern.elements.size)
        assertEquals("a", (arrayPattern.elements[0] as ParsedVariablePattern).name)
        assertEquals("b", (arrayPattern.elements[1] as ParsedVariablePattern).name)
        assertEquals("c", (arrayPattern.elements[2] as ParsedVariablePattern).name)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(1, arrayPattern.elements.size)
        assertEquals("head", (arrayPattern.elements[0] as ParsedVariablePattern).name)
        assertEquals("tail", arrayPattern.restPattern)
    }

    @Test
    fun `parse match with multiple elements and rest pattern`() {
        val code = """
            match xs is
              [first, second, ...rest] -> first + second
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(2, arrayPattern.elements.size)
        assertEquals("first", (arrayPattern.elements[0] as ParsedVariablePattern).name)
        assertEquals("second", (arrayPattern.elements[1] as ParsedVariablePattern).name)
        assertEquals("rest", arrayPattern.restPattern)
    }

    @Test
    fun `parse match with only rest pattern`() {
        val code = """
            match xs is
              [...all] -> all
              _ -> []
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(0, arrayPattern.elements.size)
        assertEquals("all", arrayPattern.restPattern)
    }

    // ========== Wildcard Patterns in Arrays ==========

    @Test
    fun `parse match with wildcard in seq pattern`() {
        val code = """
            match xs is
              [_, x] -> x
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is ParsedWildcardPattern)
        assertTrue(arrayPattern.elements[1] is ParsedVariablePattern)
    }

    @Test
    fun `parse match with multiple wildcards in seq pattern`() {
        val code = """
            match xs is
              [_, _, x] -> x
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(3, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is ParsedWildcardPattern)
        assertTrue(arrayPattern.elements[1] is ParsedWildcardPattern)
        assertTrue(arrayPattern.elements[2] is ParsedVariablePattern)
    }

    // ========== Literal Patterns in Arrays ==========

    @Test
    fun `parse match with literal patterns in seq`() {
        val code = """
            match xs is
              [1, 2] -> "pair"
              _ -> "other"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is ParsedLiteralPattern)
        assertTrue(arrayPattern.elements[1] is ParsedLiteralPattern)
        assertEquals(1L, ((arrayPattern.elements[0] as ParsedLiteralPattern).value as ParsedLiteralValue.IntValue).value)
        assertEquals(2L, ((arrayPattern.elements[1] as ParsedLiteralPattern).value as ParsedLiteralValue.IntValue).value)
    }

    @Test
    fun `parse match with mixed literal and variable patterns in seq`() {
        val code = """
            match xs is
              [0, x] -> x
              [x, 0] -> x
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch

        val pattern1 = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
        assertTrue(pattern1.elements[0] is ParsedLiteralPattern)
        assertTrue(pattern1.elements[1] is ParsedVariablePattern)

        val pattern2 = matchExpr.cases[1].patterns.first() as ParsedSeqPattern
        assertTrue(pattern2.elements[0] is ParsedVariablePattern)
        assertTrue(pattern2.elements[1] is ParsedLiteralPattern)
    }

    // ========== Nested Array Patterns ==========

    @Test
    fun `parse match with nested seq pattern`() {
        val code = """
            match xs is
              [[x]] -> x
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val outerArray = firstPattern as ParsedSeqPattern
        assertEquals(1, outerArray.elements.size)
        assertTrue(outerArray.elements[0] is ParsedSeqPattern)

        val innerArray = outerArray.elements[0] as ParsedSeqPattern
        assertEquals(1, innerArray.elements.size)
        assertTrue(innerArray.elements[0] is ParsedVariablePattern)
    }

    @Test
    fun `parse match with deeply nested seq pattern`() {
        val code = """
            match xs is
              [[[x]]] -> x
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val level1 = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
        val level2 = level1.elements[0] as ParsedSeqPattern
        val level3 = level2.elements[0] as ParsedSeqPattern
        assertTrue(level3.elements[0] is ParsedVariablePattern)
    }

    // ========== Constructor Patterns in Arrays ==========

    @Test
    fun `parse match with variant patterns in seq`() {
        val code = """
            match xs is
              [Some {val = x}, None] -> x
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(2, arrayPattern.elements.size)
        assertTrue(arrayPattern.elements[0] is ParsedConstructorPattern)
        assertTrue(arrayPattern.elements[1] is ParsedConstructorPattern)
    }

    // ========== Multiple Array Patterns with | ==========

    @Test
    fun `parse match with multiple seq patterns using pipe`() {
        val code = """
            match xs is
              [] | [_] -> "zero or one"
              _ -> "many"
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val patterns = matchExpr.cases[0].patterns

        assertEquals(2, patterns.size)
        assertTrue(patterns[0] is ParsedSeqPattern)
        assertTrue(patterns[1] is ParsedSeqPattern)
        assertEquals(0, (patterns[0] as ParsedSeqPattern).elements.size)
        assertEquals(1, (patterns[1] as ParsedSeqPattern).elements.size)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val lambda = letBinding.value as ParsedLambda
        val matchExpr = lambda.body as ParsedMatch

        // First pattern: []
        val emptyPattern = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
        assertEquals(0, emptyPattern.elements.size)
        assertNull(emptyPattern.restPattern)

        // Second pattern: [_, ...tail]
        val consPattern = matchExpr.cases[1].patterns.first() as ParsedSeqPattern
        assertEquals(1, consPattern.elements.size)
        assertTrue(consPattern.elements[0] is ParsedWildcardPattern)
        assertEquals("tail", consPattern.restPattern)
    }

    @Test
    fun `parse match with map-style pattern`() {
        val code = """
            use std::collections::seq
            let map = {f, xs} ->
                match xs is
                    [] -> []
                    [head, ...tail] -> seq::of {x = f {x = head}, xs = map {f = f, xs = tail}}
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.filterIsInstance<ParsedLetDecl>().first()
        val lambda = letBinding.value as ParsedLambda
        val matchExpr = lambda.body as ParsedMatch

        // First pattern: []
        val emptyPattern = matchExpr.cases[0].patterns.first() as ParsedSeqPattern
        assertEquals(0, emptyPattern.elements.size)

        // Second pattern: [head, ...tail]
        val consPattern = matchExpr.cases[1].patterns.first() as ParsedSeqPattern
        assertEquals(1, consPattern.elements.size)
        assertEquals("head", (consPattern.elements[0] as ParsedVariablePattern).name)
        assertEquals("tail", consPattern.restPattern)
    }

    // ========== Edge Cases ==========

    @Test
    fun `parse match with seq pattern containing newlines`() {
        val code = """
            match xs is
              [
                x,
                y,
                z
              ] -> x + y + z
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(3, arrayPattern.elements.size)
    }

    @Test
    fun `parse match with trailing comma in seq pattern`() {
        val code = """
            match xs is
              [x, y,] -> x + y
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first()

        assertTrue(firstPattern is ParsedSeqPattern)
        val arrayPattern = firstPattern as ParsedSeqPattern
        assertEquals(2, arrayPattern.elements.size)
    }

    @Test
    fun `parse complex nested pattern matching`() {
        val code = """
            match data is
              [[x, y], [a, b]] -> x + y + a + b
              _ -> 0
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstPattern = matchExpr.cases[0].patterns.first() as ParsedSeqPattern

        assertEquals(2, firstPattern.elements.size)
        assertTrue(firstPattern.elements[0] is ParsedSeqPattern)
        assertTrue(firstPattern.elements[1] is ParsedSeqPattern)

        val first = firstPattern.elements[0] as ParsedSeqPattern
        val second = firstPattern.elements[1] as ParsedSeqPattern
        assertEquals(2, first.elements.size)
        assertEquals(2, second.elements.size)
    }

    // ========== Record Expressions with Empty Arrays ==========

    @Test
    fun `parse record with one empty seq field`() {
        val code = "let foo = {x = []}"

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
        assertEquals(1, record.entries.size)
    }

    @Test
    fun `parse record with two empty seq fields`() {
        val code = "let foo = {x = [], y = []}"

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val letBinding = module.decls.first() as ParsedLetDecl
        val record = letBinding.value as ParsedRecordLit
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse just record expression with empty arrays`() {
        val code = "{yes = [], no = []}"

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val topExpr = module.decls.first() as ParsedTopLevelExpr
        val record = topExpr.expression as ParsedRecordLit
        assertEquals(2, record.entries.size)
    }

    @Test
    fun `parse match case returning record with empty arrays`() {
        val code = """
            match xs is
              [] -> {yes = [], no = []}
              _ -> {yes = [1], no = [2]}
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)

        val matchExpr = (module.decls.first() as ParsedTopLevelExpr).expression as ParsedMatch
        val firstCaseExpr = matchExpr.cases[0].body as ParsedRecordLit
        assertEquals(2, firstCaseExpr.entries.size)
    }

    @Test
    fun `parse modulo operator`() {
        val code = "let is_even = {x: Int} -> x % 2 == 0"

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse partition function - simplified`() {
        val code = """
            use std::collections::seq
            let my_partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  _ -> {yes = [], no = []}

            my_partition
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse seq rest pattern with typed lambda params`() {
        val code = """
            let my_func = {xs: [Int]} ->
                match xs is
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse nested function type in lambda params`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                xs

            my_func
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse nested function type with seq pattern`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse nested function type with empty seq pattern first`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> "empty"
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse nested function type with both seq match cases`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> head

            my_func
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse nested function type - call with two params`() {
        val code = """
            let my_func = {pred: {x: Int} -> Bool, xs: [Int]} ->
                my_func {pred = pred, xs = tail}

            my_func
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
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

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse partition - with second case`() {
        val code = """
            use std::collections::seq
            let my_partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> do
                    let rest = my_partition {pred = pred, xs = tail}
                    rest

            my_partition
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }

    @Test
    fun `parse partition function - full version`() {
        val code = """
            use std::collections::seq
            let my_partition = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> {yes = [], no = []}
                  [head, ...tail] -> do
                    let rest = my_partition {pred = pred, xs = tail}
                    if pred {x = head}
                    then {yes = seq::of {x = head, xs = rest.yes}, no = rest.no}
                    else {yes = rest.yes, no = seq::of {x = head, xs = rest.no}}

            let is_even = {x: Int} -> x % 2 == 0
            my_partition {pred = is_even, xs = [1, 2, 3, 4, 5, 6]}
        """.trimIndent()

        val module = parser.parseParsedModule(code)
        assertNotNull(module)
    }
}
