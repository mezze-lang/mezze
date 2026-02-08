package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertContains

/**
 * Comprehensive tests for array pattern matching type checking
 *
 * Tests cover:
 * - Empty array patterns
 * - Fixed-length array patterns
 * - Rest patterns ([head, ...tail])
 * - Nested array patterns
 * - Type inference and checking
 * - Exhaustiveness checking
 * - Error cases
 */
class ArrayPatternTypeCheckTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    private fun assertTypeCheckFails(code: String, expectedMessagePart: String? = null): TypeCheckError {
        val program = parser.parse(code)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program).program
        }
        if (expectedMessagePart != null) {
            assertContains(error.message ?: "", expectedMessagePart, ignoreCase = true)
        }
        return error
    }

    // ==========================================================================
    // SECTION 1: Empty Array Pattern
    // ==========================================================================

    @Test
    fun `empty array pattern - basic matching`() {
        val code = """
            let is_empty = {xs: [Int]} ->
                match xs is
                  [] -> True
                  _ -> False
            let result = is_empty { xs = [] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `empty array pattern - with non-empty case`() {
        val code = """
            let describe = {xs: [Int]} ->
                match xs is
                  [] -> "empty"
                  _ -> "non-empty"
            let result = describe { xs = [1, 2, 3] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 2: Fixed-Length Array Patterns
    // ==========================================================================

    @Test
    fun `single element array pattern`() {
        val code = """
            let get_only = {xs: [Int]} ->
                match xs is
                  [x] -> x
                  _ -> 0
            let result = get_only { xs = [42] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `two element array pattern`() {
        val code = """
            let add_pair = {xs: [Int]} ->
                match xs is
                  [x, y] -> x + y
                  _ -> 0
            let result = add_pair { xs = [1, 2] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `three element array pattern`() {
        val code = """
            let add_triple = {xs: [Int]} ->
                match xs is
                  [x, y, z] -> x + y + z
                  _ -> 0
            let result = add_triple { xs = [1, 2, 3] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `multiple fixed-length patterns`() {
        val code = """
            let count_elements = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [_] -> 1
                  [_, _] -> 2
                  _ -> 999
            let result = count_elements { xs = [1, 2] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 3: Rest Patterns
    // ==========================================================================

    @Test
    fun `rest pattern - head and tail`() {
        val code = """
            let get_head = {xs: [Int]} ->
                match xs is
                  [head, ...tail] -> head
                  _ -> 0
            let result = get_head { xs = [1, 2, 3] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `rest pattern - first two and rest`() {
        val code = """
            let get_sum_first_two = {xs: [Int]} ->
                match xs is
                  [x, y, ...rest] -> x + y
                  _ -> 0
            let result = get_sum_first_two { xs = [1, 2, 3, 4] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `rest pattern - only rest`() {
        val code = """
            let identity = {xs: [Int]} ->
                match xs is
                  [...all] -> all
            let result = identity { xs = [1, 2, 3] }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 4: Recursive Functions with Array Patterns
    // ==========================================================================

    @Test
    fun `recursive length function`() {
        val code = """
            let length = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [_, ...tail] -> 1 + length {xs = tail}
            let result = length {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `recursive sum function`() {
        val code = """
            let sum = {xs: [Int]} ->
                match xs is
                  [] -> 0
                  [head, ...tail] -> head + sum {xs = tail}
            let result = sum {xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `recursive map function`() {
        val code = """
            use std::collections::array

            let map = {f: {x: Int} -> Int, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] -> array::of {x = f {x = head}, xs = map {f = f, xs = tail}}

            let double = {x: Int} -> x * 2
            let result = map {f = double, xs = [1, 2, 3]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `recursive filter function`() {
        val code = """
            use std::collections::array

            let filter = {pred: {x: Int} -> Bool, xs: [Int]} ->
                match xs is
                  [] -> []
                  [head, ...tail] ->
                    if pred {x = head}
                    then array::of {x = head, xs = filter {pred = pred, xs = tail}}
                    else filter {pred = pred, xs = tail}

            let is_positive = {x: Int} -> x > 0
            let result = filter {pred = is_positive, xs = [-1, 2, -3, 4]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `recursive foldl function`() {
        val code = """
            let foldl = {f: {acc: Int, x: Int} -> Int, init: Int, xs: [Int]} ->
                match xs is
                  [] -> init
                  [head, ...tail] -> foldl {f = f, init = f {acc = init, x = head}, xs = tail}

            let add = {acc: Int, x: Int} -> acc + x
            let result = foldl {f = add, init = 0, xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 5: Nested Array Patterns
    // ==========================================================================

    @Test
    fun `nested array pattern - simple`() {
        val code = """
            let get_inner_first = {xs: [[Int]]} ->
                match xs is
                  [[x]] -> x
                  _ -> 0
            let result = get_inner_first {xs = [[42]]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `nested array pattern - matrix first element`() {
        val code = """
            let get_top_left = {matrix: [[Int]]} ->
                match matrix is
                  [[x, ...], ...] -> x
                  _ -> 0
            let result = get_top_left {matrix = [[1, 2], [3, 4]]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `nested array pattern - pairs`() {
        val code = """
            let sum_pairs = {pairs: [[Int]]} ->
                match pairs is
                  [] -> 0
                  [[a, b], ...rest] -> (a + b) + sum_pairs {pairs = rest}
                  _ -> 0
            let result = sum_pairs {pairs = [[1, 2], [3, 4], [5, 6]]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 6: Wildcard Patterns in Arrays
    // ==========================================================================

    @Test
    fun `wildcard pattern - ignore first element`() {
        val code = """
            let get_second = {xs: [Int]} ->
                match xs is
                  [_, x] -> x
                  _ -> 0
            let result = get_second {xs = [1, 2]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `wildcard pattern - ignore middle elements`() {
        val code = """
            let get_first_and_last = {xs: [Int]} ->
                match xs is
                  [first, _, _, last] -> first + last
                  _ -> 0
            let result = get_first_and_last {xs = [1, 2, 3, 4]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `wildcard pattern with rest`() {
        val code = """
            let skip_first = {xs: [Int]} ->
                match xs is
                  [_, ...tail] -> tail
                  _ -> []
            let result = skip_first {xs = [1, 2, 3, 4]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 7: Literal Patterns in Arrays
    // ==========================================================================

    @Test
    fun `literal pattern - match specific values`() {
        val code = """
            let is_one_two = {xs: [Int]} ->
                match xs is
                  [1, 2] -> True
                  _ -> False
            let result = is_one_two {xs = [1, 2]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `literal pattern - mixed with variables`() {
        val code = """
            let starts_with_zero = {xs: [Int]} ->
                match xs is
                  [0, x] -> x
                  _ -> 999
            let result = starts_with_zero {xs = [0, 42]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `literal pattern - boolean array`() {
        val code = """
            let check_pattern = {xs: [Bool]} ->
                match xs is
                  [True, False] -> "pattern1"
                  [False, True] -> "pattern2"
                  _ -> "other"
            let result = check_pattern {xs = [True, False]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 8: Variant Patterns in Arrays
    // ==========================================================================

    @Test
    fun `variant patterns in array`() {
        val code = """
            let unwrap_first_some = {xs} ->
                match xs is
                  [Some {val = x}, ...] -> x
                  _ -> 0
            let result = unwrap_first_some {xs = [Some {val = 42}, None, Some {val = 10}]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `variant patterns - filter Somes`() {
        val code = """
            use std::collections::array

            let get_all_somes = {xs} ->
                match xs is
                  [] -> []
                  [Some {val = x}, ...tail] -> array::of {x = x, xs = get_all_somes {xs = tail}}
                  [None, ...tail] -> get_all_somes {xs = tail}
            let result = get_all_somes {xs = [Some {val = 1}, None, Some {val = 2}]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 9: Or-Patterns (Pipe) with Arrays
    // ==========================================================================

    @Test
    fun `or pattern - empty or singleton`() {
        val code = """
            let is_small = {xs: [Int]} ->
                match xs is
                  [] | [_] -> True
                  _ -> False
            let result = is_small {xs = [1]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `or pattern - specific lengths`() {
        val code = """
            let classify_size = {xs: [Int]} ->
                match xs is
                  [] -> "empty"
                  [_] | [_, _] -> "small"
                  _ -> "large"
            let result = classify_size {xs = [1, 2]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 10: Guards with Array Patterns
    // ==========================================================================

    @Test
    fun `guard - check array element value`() {
        val code = """
            let starts_with_positive = {xs: [Int]} ->
                match xs is
                  [x, ...] if x > 0 -> True
                  _ -> False
            let result = starts_with_positive {xs = [5, -1, -2]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `guard - check multiple elements`() {
        val code = """
            let both_positive = {xs: [Int]} ->
                match xs is
                  [x, y] if x > 0 && y > 0 -> True
                  _ -> False
            let result = both_positive {xs = [5, 10]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `guard - comparison between elements`() {
        val code = """
            let is_sorted_pair = {xs: [Int]} ->
                match xs is
                  [x, y] if x <= y -> True
                  _ -> False
            let result = is_sorted_pair {xs = [1, 2]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 11: Type Inference
    // ==========================================================================

    @Test
    fun `type inference - infer array type from pattern`() {
        val code = """
            let get_head = {xs} ->
                match xs is
                  [head, ...tail] -> head
                  [] -> 0
            let result: Int = get_head {xs = [1, 2, 3]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `type inference - infer from literal pattern`() {
        val code = """
            let check = {xs} ->
                match xs is
                  [1, 2, 3] -> "match"
                  _ -> "no match"
            let result: String = check {xs = [1, 2, 3]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 12: String Arrays
    // ==========================================================================

    @Test
    fun `string array pattern matching`() {
        val code = """
            let get_greeting = {names: [String]} ->
                match names is
                  [] -> "Hello, nobody!"
                  [name] -> "Hello, $(name)!"
                  [first, ...] -> "Hello, $(first) and others!"
            let result = get_greeting {names = ["Alice", "Bob"]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `string array with literal patterns`() {
        val code = """
            let is_help_command = {args: [String]} ->
                match args is
                  ["help"] | ["--help"] | ["-h"] -> True
                  _ -> False
            let result = is_help_command {args = ["help"]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 13: Polymorphic Array Functions
    // ==========================================================================

    @Test
    fun `polymorphic reverse function`() {
        val code = """
            use std::collections::array

            let reverse_helper = {xs, acc} ->
                match xs is
                  [] -> acc
                  [head, ...tail] -> reverse_helper {xs = tail, acc = array::of {x = head, xs = acc}}

            let reverse = {xs} -> reverse_helper {xs = xs, acc = []}

            let result1 = reverse {xs = [1, 2, 3]}
            let result2 = reverse {xs = ["a", "b", "c"]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `polymorphic take function`() {
        val code = """
            use std::collections::array

            let take = {n: Int, xs} ->
                match xs is
                  [] -> []
                  [head, ...tail] ->
                    if n <= 0
                    then []
                    else do
                      let rest = take {n = (n - 1), xs = tail}
                      array::of {x = head, xs = rest}

            let result = take {n = 2, xs = [1, 2, 3, 4, 5]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 14: Edge Cases
    // ==========================================================================

    @Test
    fun `edge case - single wildcard for any array`() {
        val code = """
            let always_empty = {xs: [Int]} ->
                match xs is
                  _ -> []
            let result = always_empty {xs = [1, 2, 3]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `edge case - deeply nested arrays`() {
        val code = """
            let get_deep = {xs: [[[Int]]]} ->
                match xs is
                  [[[x]]] -> x
                  _ -> 0
            let result = get_deep {xs = [[[42]]]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `edge case - empty rest pattern`() {
        val code = """
            let get_all = {xs: [Int]} ->
                match xs is
                  [...all] -> all
                  _ -> []
            let result = get_all {xs = [1, 2, 3]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ==========================================================================
    // SECTION 15: Real-World Patterns
    // ==========================================================================

    @Test
    fun `real world - command line argument parsing`() {
        val code = """
            let parse_args = {args: [String]} ->
                match args is
                  [] -> "no args"
                  ["--version"] -> "version 1.0"
                  ["--help"] -> "help text"
                  [cmd, ...opts] -> "command: $(cmd)"
            let result = parse_args {args = ["build", "--verbose"]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `real world - partition function`() {
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

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `real world - zip two arrays`() {
        val code = """
            use std::collections::array

            let zip = {xs, ys} ->
                match xs is
                  [] -> []
                  [x, ...xs_tail] ->
                    match ys is
                      [] -> []
                      [y, ...ys_tail] -> array::of {x = {x = x, y = y}, xs = zip {xs = xs_tail, ys = ys_tail}}

            let result = zip {xs = [1, 2, 3], ys = ["a", "b", "c"]}
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
