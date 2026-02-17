package com.mezze.language.interpreter

import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Comprehensive tests for array pattern matching in the interpreter
 *
 * Tests runtime behavior of:
 * - Empty array patterns
 * - Fixed-length array patterns
 * - Rest patterns ([head, ...tail])
 * - Nested array patterns
 * - Recursive functions using array patterns
 */
class ArrayPatternInterpreterTest {

    private val parser = MezzeParser()
    private val typeChecker = TypeChecker()

    private fun eval(source: String): MezzeValue {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val result = MezzeTestHelper.execute(typeCheckResult.program, typeCheckResult.monomorphicInstances, java.nio.file.Paths.get(System.getProperty("mezze.project.root", "/Users/shriyans/Projects/mezze")))
        return result
    }

    private fun parseTypeCheckAndRun(source: String): Map<String, MezzeValue> {
        val program = parser.parse(source)
        val typeCheckResult = typeChecker.typeCheck(program)
        val (_, context) = MezzeTestHelper.executeWithContext(typeCheckResult.program, typeCheckResult.monomorphicInstances)
        return context
    }

    // ==========================================================================
    // SECTION 1: Empty Array Pattern
    // ==========================================================================

    @Test
    fun `empty array pattern - matches empty array`() {
        val code = """
            match [] is
              [] -> "empty"
              _ -> "not empty"
        """.trimIndent()

        val result = eval(code)
        assertEquals("empty", (result as MezzeString).value)
    }

    @Test
    fun `empty array pattern - does not match non-empty array`() {
        val code = """
            match [1, 2, 3] is
              [] -> "empty"
              _ -> "not empty"
        """.trimIndent()

        val result = eval(code)
        assertEquals("not empty", (result as MezzeString).value)
    }

    @Test
    fun `is_empty function using array patterns`() {
        val code = """
            let is_empty = {xs: [Int]} ->
                match xs is
                  [] -> True
                  _ -> False

            is_empty {xs = []}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, (result as MezzeBool).value)
    }

    // ==========================================================================
    // SECTION 2: Fixed-Length Array Patterns
    // ==========================================================================

    @Test
    fun `single element array pattern`() {
        val code = """
            match [42] is
              [x] -> x
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `two element array pattern`() {
        val code = """
            match [10, 20] is
              [x, y] -> x + y
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(30L, (result as MezzeInt).value)
    }

    @Test
    fun `three element array pattern`() {
        val code = """
            match [1, 2, 3] is
              [a, b, c] -> a + b + c
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(6L, (result as MezzeInt).value)
    }

    @Test
    fun `pattern matching by length`() {
        val code = """
            let count = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [_] -> 1
                  [_, _] -> 2
                  [_, _, _] -> 3
                  _ -> 999

            count {xs = [1, 2]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(2L, (result as MezzeInt).value)
    }

    // ==========================================================================
    // SECTION 3: Rest Patterns
    // ==========================================================================

    @Test
    fun `rest pattern - get head`() {
        val code = """
            match [1, 2, 3, 4, 5] is
              [head, ...tail] -> head
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(1L, (result as MezzeInt).value)
    }

    @Test
    fun `rest pattern - get tail`() {
        val code = """
            let get_tail = {xs: [Int]} ->
                match xs is
                  [head, ...tail] -> tail
                  _ -> []

            get_tail {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(4, result.size())
        assertEquals(2L, (result.get(0) as MezzeInt).value)
        assertEquals(3L, (result.get(1) as MezzeInt).value)
        assertEquals(4L, (result.get(2) as MezzeInt).value)
        assertEquals(5L, (result.get(3) as MezzeInt).value)
    }

    @Test
    fun `rest pattern - first two elements`() {
        val code = """
            match [10, 20, 30, 40] is
              [x, y, ...rest] -> x + y
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(30L, (result as MezzeInt).value)
    }

    @Test
    fun `rest pattern - only rest`() {
        val code = """
            match [1, 2, 3] is
              [...all] -> all
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(3, result.size())
        assertEquals(1L, (result.get(0) as MezzeInt).value)
        assertEquals(2L, (result.get(1) as MezzeInt).value)
        assertEquals(3L, (result.get(2) as MezzeInt).value)
    }

    // ==========================================================================
    // SECTION 4: Recursive Functions
    // ==========================================================================

    @Test
    fun `recursive length function`() {
        val code = """
            let length = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [_, ...tail] -> 1 + length {xs = tail}

            length {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(5L, (result as MezzeInt).value)
    }

    @Test
    fun `recursive sum function`() {
        val code = """
            let sum = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [head, ...tail] -> head + sum {xs = tail}

            sum {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(15L, (result as MezzeInt).value)
    }

    @Test
    fun `recursive map function`() {
        val code = """
            use std::collections::array
            let my_map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> array::of {x = f {x = head}, xs = my_map {f = f, xs = tail}}

            let double = {x: Int} -> x * 2
            my_map {f = double, xs = [1, 2, 3]}
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(3, result.size())
        assertEquals(2L, (result.get(0) as MezzeInt).value)
        assertEquals(4L, (result.get(1) as MezzeInt).value)
        assertEquals(6L, (result.get(2) as MezzeInt).value)
    }

    @Test
    fun `recursive filter function`() {
        val code = """
            use std::collections::array
            let my_filter = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] ->
                    if pred {x = head}
                    then array::of {x = head, xs = my_filter {pred = pred, xs = tail}}
                    else my_filter {pred = pred, xs = tail}

            let is_positive = {x: Int} -> x > 0
            my_filter {pred = is_positive, xs = [-1, 2, -3, 4, 5]}
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(3, result.size())
        assertEquals(2L, (result.get(0) as MezzeInt).value)
        assertEquals(4L, (result.get(1) as MezzeInt).value)
        assertEquals(5L, (result.get(2) as MezzeInt).value)
    }

    @Test
    fun `recursive foldl function`() {
        val code = """
            let foldl = {f: {acc: Int, x: Int} -> Int, init: Int, xs: [Int]} ->
                match xs is
                  [] -> init
                  [head, ...tail] -> foldl {f = f, init = f {acc = init, x = head}, xs = tail}

            let add = {acc: Int, x: Int} -> acc + x
            foldl {f = add, init = 0, xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(15L, (result as MezzeInt).value)
    }

    // ==========================================================================
    // SECTION 5: Wildcard Patterns
    // ==========================================================================

    @Test
    fun `wildcard pattern - skip first element`() {
        val code = """
            match [10, 20, 30] is
              [_, x, _] -> x
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(20L, (result as MezzeInt).value)
    }

    @Test
    fun `wildcard pattern with rest`() {
        val code = """
            let skip_first = {xs: [Int]} ->
                match xs is
                  [_, ...tail] -> tail
                  _ -> []

            skip_first {xs = [1, 2, 3, 4]}
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(3, result.size())
        assertEquals(2L, (result.get(0) as MezzeInt).value)
    }

    // ==========================================================================
    // SECTION 6: Literal Patterns in Arrays
    // ==========================================================================

    @Test
    fun `literal pattern - match specific values`() {
        val code = """
            match [1, 2] is
              [1, 2] -> "match"
              _ -> "no match"
        """.trimIndent()

        val result = eval(code)
        assertEquals("match", (result as MezzeString).value)
    }

    @Test
    fun `literal pattern - mixed with variables`() {
        val code = """
            match [0, 42] is
              [0, x] -> x
              _ -> 999
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `literal pattern - boolean array`() {
        val code = """
            match [True, False] is
              [True, False] -> "pattern1"
              [False, True] -> "pattern2"
              _ -> "other"
        """.trimIndent()

        val result = eval(code)
        assertEquals("pattern1", (result as MezzeString).value)
    }

    // ==========================================================================
    // SECTION 7: Nested Array Patterns
    // ==========================================================================

    @Test
    fun `nested array pattern - simple`() {
        val code = """
            match [[42]] is
              [[x]] -> x
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(42L, (result as MezzeInt).value)
    }

    @Test
    fun `nested array pattern - matrix first element`() {
        val code = """
            match [[1, 2], [3, 4]] is
              [[x, ...rest], ...rows] -> x
              _ -> 0
        """.trimIndent()

        val result = eval(code)
        assertEquals(1L, (result as MezzeInt).value)
    }

    // ==========================================================================
    // SECTION 8: String Arrays
    // ==========================================================================

    @Test
    fun `string array pattern matching`() {
        val code = """
            let get_greeting = {names: [String]} ->
                match names is
                  [] -> "Hello, nobody!"
                  [name] -> "Hello, $(name)!"
                  [first, ...rest] -> "Hello, $(first) and others!"

            get_greeting {names = ["Alice", "Bob"]}
        """.trimIndent()

        val result = eval(code)
        assertEquals("Hello, Alice and others!", (result as MezzeString).value)
    }

    @Test
    fun `string array with literal patterns`() {
        val code = """
            let is_help_command = {args: [String]} ->
                match args is
                  ["help"] | ["--help"] | ["-h"] -> True
                  _ -> False

            is_help_command {args = ["help"]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, (result as MezzeBool).value)
    }

    // ==========================================================================
    // SECTION 9: Or-Patterns (Pipe)
    // ==========================================================================

    @Test
    fun `or pattern - empty or singleton`() {
        val code = """
            let is_small = {xs: [Int]} ->
                match xs is
                  [] | [_] -> True
                  _ -> False

            is_small {xs = [1]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, (result as MezzeBool).value)
    }

    @Test
    fun `or pattern - specific lengths`() {
        val code = """
            let classify_size = {xs: [Int]} ->
                match xs is
                  [] -> "empty"
                  [_] | [_, _] -> "small"
                  _ -> "large"

            classify_size {xs = [1, 2]}
        """.trimIndent()

        val result = eval(code)
        assertEquals("small", (result as MezzeString).value)
    }

    // ==========================================================================
    // SECTION 10: Guards with Array Patterns
    // ==========================================================================

    @Test
    fun `guard - check array element value`() {
        val code = """
            let starts_with_positive = {xs: [Int]} ->
                match xs is
                  [x, ...tail] if x > 0 -> True
                  _ -> False

            starts_with_positive {xs = [5, -1, -2]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, (result as MezzeBool).value)
    }

    @Test
    fun `guard - check multiple elements`() {
        val code = """
            let both_positive = {xs: [Int]} ->
                match xs is
                  [x, y] if x > 0 && y > 0 -> True
                  _ -> False

            both_positive {xs = [5, 10]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, (result as MezzeBool).value)
    }

    @Test
    fun `guard - comparison between elements`() {
        val code = """
            let is_sorted_pair = {xs: [Int]} ->
                match xs is
                  [x, y] if x <= y -> True
                  _ -> False

            is_sorted_pair {xs = [1, 2]}
        """.trimIndent()

        val result = eval(code)
        assertEquals(true, (result as MezzeBool).value)
    }

    // ==========================================================================
    // SECTION 11: Real-World Patterns
    // ==========================================================================

    @Test
    fun `real world - reverse function`() {
        val code = """
            use std::collections::array
            let reverse_helper = {xs: [Int], acc: [Int]} ->
                match xs is
                  [] -> acc
                  [head, ...tail] -> reverse_helper {xs = tail, acc = array::of {x = head, xs = acc}}

            let reverse = {xs: [Int]} -> reverse_helper {xs = xs, acc = []}
            reverse {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(5, result.size())
        assertEquals(5L, (result.get(0) as MezzeInt).value)
        assertEquals(4L, (result.get(1) as MezzeInt).value)
        assertEquals(3L, (result.get(2) as MezzeInt).value)
        assertEquals(2L, (result.get(3) as MezzeInt).value)
        assertEquals(1L, (result.get(4) as MezzeInt).value)
    }

    @Test
    fun `real world - take n elements`() {
        val code = """
            use std::collections::array
            let my_take = {n: Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] ->
                    if n <= 0
                    then []
                    else array::of {x = head, xs = my_take {n = n - 1, xs = tail}}

            my_take {n = 3, xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(3, result.size())
        assertEquals(1L, (result.get(0) as MezzeInt).value)
        assertEquals(2L, (result.get(1) as MezzeInt).value)
        assertEquals(3L, (result.get(2) as MezzeInt).value)
    }

    @Test
    fun `real world - drop n elements`() {
        val code = """
            let drop = {n: Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [_, ...tail] ->
                    if n <= 1
                    then tail
                    else drop {n = n - 1, xs = tail}

            drop {n = 2, xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val result = eval(code) as MezzeArrayList
        assertEquals(3, result.size())
        assertEquals(3L, (result.get(0) as MezzeInt).value)
        assertEquals(4L, (result.get(1) as MezzeInt).value)
        assertEquals(5L, (result.get(2) as MezzeInt).value)
    }

    @Test
    fun `real world - partition function`() {
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

        val result = eval(code) as MezzeRecord
        val yes = result.fields["yes"] as MezzeArrayList
        val no = result.fields["no"] as MezzeArrayList

        assertEquals(3, yes.size())
        assertEquals(2L, (yes.get(0) as MezzeInt).value)
        assertEquals(4L, (yes.get(1) as MezzeInt).value)
        assertEquals(6L, (yes.get(2) as MezzeInt).value)

        assertEquals(3, no.size())
        assertEquals(1L, (no.get(0) as MezzeInt).value)
        assertEquals(3L, (no.get(1) as MezzeInt).value)
        assertEquals(5L, (no.get(2) as MezzeInt).value)
    }
}
