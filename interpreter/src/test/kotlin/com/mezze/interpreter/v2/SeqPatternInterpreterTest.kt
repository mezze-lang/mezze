package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Comprehensive tests for seq pattern matching in the interpreter
 *
 * Tests runtime behavior of:
 * - Empty seq patterns
 * - Fixed-length seq patterns
 * - Rest patterns ([head, ...tail])
 * - Nested seq patterns
 * - Recursive functions using seq patterns
 *
 * Migrated from V1 to V2 with unboxed values.
 */
class SeqPatternInterpreterTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ==========================================================================
    // SECTION 1: Empty Seq Pattern
    // ==========================================================================

    @Test
    fun `empty seq pattern - matches empty seq`() {
        val code = """
            match [] is
              [] -> "empty"
              _ -> "not empty"
        """
        assertEquals("empty", eval(code))
    }

    @Test
    fun `empty seq pattern - does not match non-empty seq`() {
        val code = """
            match [1, 2, 3] is
              [] -> "empty"
              _ -> "not empty"
        """
        assertEquals("not empty", eval(code))
    }

    @Test
    fun `is_empty function using seq patterns`() {
        val code = """
            let is_empty = {xs: [Int]} ->
                match xs is
                  [] -> True
                  _ -> False

            is_empty {xs = []}
        """
        assertEquals(true, eval(code))
    }

    // ==========================================================================
    // SECTION 2: Fixed-Length Seq Patterns
    // ==========================================================================

    @Test
    fun `single element seq pattern`() {
        val code = """
            match [42] is
              [x] -> x
              _ -> 0
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `two element seq pattern`() {
        val code = """
            match [10, 20] is
              [x, y] -> x + y
              _ -> 0
        """
        assertEquals(30L, eval(code))
    }

    @Test
    fun `three element seq pattern`() {
        val code = """
            match [1, 2, 3] is
              [a, b, c] -> a + b + c
              _ -> 0
        """
        assertEquals(6L, eval(code))
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
        """
        assertEquals(2L, eval(code))
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
        """
        assertEquals(1L, eval(code))
    }

    @Test
    fun `rest pattern - get tail length`() {
        val code = """
            use std::collections::seq
            let get_tail = {xs: [Int]} ->
                match xs is
                  [head, ...tail] -> tail
                  _ -> []

            let tail = get_tail {xs = [1, 2, 3, 4, 5]}
            seq::length {xs = tail}
        """
        assertEquals(4L, eval(code))
    }

    @Test
    fun `rest pattern - first two elements`() {
        val code = """
            match [10, 20, 30, 40] is
              [x, y, ...rest] -> x + y
              _ -> 0
        """
        assertEquals(30L, eval(code))
    }

    @Test
    fun `rest pattern - only rest length`() {
        val code = """
            use std::collections::seq
            match [1, 2, 3] is
              [...all] -> seq::length {xs = all}
        """
        assertEquals(3L, eval(code))
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
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `recursive sum function`() {
        val code = """
            let sum = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [head, ...tail] -> head + sum {xs = tail}

            sum {xs = [1, 2, 3, 4, 5]}
        """
        assertEquals(15L, eval(code))
    }

    @Test
    fun `recursive map function length`() {
        val code = """
            use std::collections::seq
            let my_map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> seq::of {x = f {x = head}, xs = my_map {f = f, xs = tail}}

            let double = {x: Int} -> x * 2
            let result = my_map {f = double, xs = [1, 2, 3]}
            seq::length {xs = result}
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `recursive filter function length`() {
        val code = """
            use std::collections::seq
            let my_filter = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] ->
                    if pred {x = head}
                    then seq::of {x = head, xs = my_filter {pred = pred, xs = tail}}
                    else my_filter {pred = pred, xs = tail}

            let is_positive = {x: Int} -> x > 0
            let result = my_filter {pred = is_positive, xs = [-1, 2, -3, 4, 5]}
            seq::length {xs = result}
        """
        assertEquals(3L, eval(code))
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
        """
        assertEquals(15L, eval(code))
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
        """
        assertEquals(20L, eval(code))
    }

    @Test
    fun `wildcard pattern with rest length`() {
        val code = """
            use std::collections::seq
            let skip_first = {xs: [Int]} ->
                match xs is
                  [_, ...tail] -> tail
                  _ -> []

            let result = skip_first {xs = [1, 2, 3, 4]}
            seq::length {xs = result}
        """
        assertEquals(3L, eval(code))
    }

    // ==========================================================================
    // SECTION 6: Literal Patterns in Seqs
    // ==========================================================================

    @Test
    fun `literal pattern - match specific values`() {
        val code = """
            match [1, 2] is
              [1, 2] -> "match"
              _ -> "no match"
        """
        assertEquals("match", eval(code))
    }

    @Test
    fun `literal pattern - mixed with variables`() {
        val code = """
            match [0, 42] is
              [0, x] -> x
              _ -> 999
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `literal pattern - boolean seq`() {
        val code = """
            match [True, False] is
              [True, False] -> "pattern1"
              [False, True] -> "pattern2"
              _ -> "other"
        """
        assertEquals("pattern1", eval(code))
    }

    // ==========================================================================
    // SECTION 7: Nested Seq Patterns
    // ==========================================================================

    @Test
    fun `nested seq pattern - simple`() {
        val code = """
            match [[42]] is
              [[x]] -> x
              _ -> 0
        """
        assertEquals(42L, eval(code))
    }

    @Test
    fun `nested seq pattern - matrix first element`() {
        val code = """
            match [[1, 2], [3, 4]] is
              [[x, ...rest], ...rows] -> x
              _ -> 0
        """
        assertEquals(1L, eval(code))
    }

    // ==========================================================================
    // SECTION 8: String Seqs
    // ==========================================================================

    @Test
    fun `string seq pattern matching`() {
        val code = """
            let get_greeting = {names: [String]} ->
                match names is
                  [] -> "Hello, nobody!"
                  [name] -> "Hello, $(name)!"
                  [first, ...rest] -> "Hello, $(first) and others!"

            get_greeting {names = ["Alice", "Bob"]}
        """
        assertEquals("Hello, Alice and others!", eval(code))
    }

    @Test
    fun `string seq with literal patterns`() {
        val code = """
            let is_help_command = {args: [String]} ->
                match args is
                  ["help"] | ["--help"] | ["-h"] -> True
                  _ -> False

            is_help_command {args = ["help"]}
        """
        assertEquals(true, eval(code))
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
        """
        assertEquals(true, eval(code))
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
        """
        assertEquals("small", eval(code))
    }

    // ==========================================================================
    // SECTION 10: Guards with Seq Patterns
    // ==========================================================================

    @Test
    fun `guard - check seq element value`() {
        val code = """
            let starts_with_positive = {xs: [Int]} ->
                match xs is
                  [x, ...tail] if x > 0 -> True
                  _ -> False

            starts_with_positive {xs = [5, -1, -2]}
        """
        assertEquals(true, eval(code))
    }

    @Test
    fun `guard - check multiple elements`() {
        val code = """
            let both_positive = {xs: [Int]} ->
                match xs is
                  [x, y] if x > 0 && y > 0 -> True
                  _ -> False

            both_positive {xs = [5, 10]}
        """
        assertEquals(true, eval(code))
    }

    @Test
    fun `guard - comparison between elements`() {
        val code = """
            let is_sorted_pair = {xs: [Int]} ->
                match xs is
                  [x, y] if x <= y -> True
                  _ -> False

            is_sorted_pair {xs = [1, 2]}
        """
        assertEquals(true, eval(code))
    }

    // ==========================================================================
    // SECTION 11: Real-World Patterns
    // ==========================================================================

    @Test
    fun `real world - reverse function length`() {
        val code = """
            use std::collections::seq
            let reverse_helper = {xs: [Int], acc: [Int]} ->
                match xs is
                  [] -> acc
                  [head, ...tail] -> reverse_helper {xs = tail, acc = seq::of {x = head, xs = acc}}

            let reverse = {xs: [Int]} -> reverse_helper {xs = xs, acc = []}
            let result = reverse {xs = [1, 2, 3, 4, 5]}
            seq::length {xs = result}
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `real world - reverse function first element`() {
        val code = """
            use std::collections::seq
            let reverse_helper = {xs: [Int], acc: [Int]} ->
                match xs is
                  [] -> acc
                  [head, ...tail] -> reverse_helper {xs = tail, acc = seq::of {x = head, xs = acc}}

            let reverse = {xs: [Int]} -> reverse_helper {xs = xs, acc = []}
            let result = reverse {xs = [1, 2, 3, 4, 5]}
            match seq::get {xs = result, i = 0} is
                Some { val } -> val
                None -> -1
        """
        assertEquals(5L, eval(code))
    }

    @Test
    fun `real world - take n elements length`() {
        val code = """
            use std::collections::seq
            let my_take = {n: Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] ->
                    if n <= 0
                    then []
                    else seq::of {x = head, xs = my_take {n = n - 1, xs = tail}}

            let result = my_take {n = 3, xs = [1, 2, 3, 4, 5]}
            seq::length {xs = result}
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `real world - drop n elements length`() {
        val code = """
            use std::collections::seq
            let drop = {n: Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [_, ...tail] ->
                    if n <= 1
                    then tail
                    else drop {n = n - 1, xs = tail}

            let result = drop {n = 2, xs = [1, 2, 3, 4, 5]}
            seq::length {xs = result}
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `real world - partition function yes count`() {
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
            let result = my_partition {pred = is_even, xs = [1, 2, 3, 4, 5, 6]}
            seq::length {xs = result.yes}
        """
        assertEquals(3L, eval(code))
    }

    @Test
    fun `real world - partition function no count`() {
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
            let result = my_partition {pred = is_even, xs = [1, 2, 3, 4, 5, 6]}
            seq::length {xs = result.no}
        """
        assertEquals(3L, eval(code))
    }
}
