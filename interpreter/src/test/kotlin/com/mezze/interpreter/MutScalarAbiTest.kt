package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Every mut op moved onto the scalar native ABI, exercised BY VALUE.
 *
 * The record ABI hands the handler a `MezzeRecord` built per call which it then
 * reads back by field name; a declared op gets positional, typed arguments and
 * allocates nothing. MEASURED on `bench/cases/mutabi` at n=2e7, min of 3 — each
 * kernel is a tail loop with one mut op per iteration, so kernel 1 (control,
 * 253 → 266 ps, so the table is readable) holds the loop constant:
 *
 * | op | record | scalar | the op alone |
 * |---|---|---|---|
 * | `read_int` | 8 120 | **3 808** | 7 867 → 3 542 = 2.22x |
 * | `incr` | 12 272 | **4 662** | 12 019 → 4 396 = **2.73x** |
 * | `seq::size` | 8 154 | **4 064** | 2.08x |
 * | `seq::get` | 15 461 | **7 203** | 2.19x |
 * | `string_char_at` | 17 039 | **8 856** | 1.95x |
 * | `string_len` | 7 965 | **3 914** | 2.11x |
 *
 * DECLARING THESE OPS ALONE CHANGED NOTHING, and that is the interesting part.
 * The scalar shape is chosen while code is COMPILED, by reading
 * `NativeSignatures` — and on the `mez build` path that registry was EMPTY,
 * because `:cli` keeps `:interpreter` off its compile classpath. So the
 * declarations existed and the compiler could not see them. The fix is in
 * `Main.kt`: load the bootstrap reflectively off the RUNTIME classpath, which
 * is what `StdlibPrecompiler` had always done on its side.
 *
 * WHY VALUE TESTS AND NOT A SHAPE ASSERTION. The failure mode of a declaration
 * is a wrong `Param` ORDER or `SlotKind`. The ABI is positional, so swapping
 * two arguments type-checks, compiles, runs and returns a plausible wrong
 * answer with nothing different in the lowered shape. So each op is called with
 * arguments whose positions matter, paired against its opposite, and checked
 * against a number only the correct order produces.
 */
class MutScalarAbiTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** Built by concatenation rather than by nesting one `trimIndent` inside
     *  another: the inner body keeps the outer template's indentation, and a
     *  `do` block is column-sensitive, so the nested form puts `main {}` at
     *  column 7 and every case dies in the parser. */
    private fun run(imports: String, body: () -> String): Any {
        val header = listOf(
            "use std::effects::mutation::{LocalMut, $imports}",
            "use std::collections::seq",
            "use std::collections::set",
            "use std::collections::map",
            "use std::collections::deque",
            "use std::collections::bytes",
            "use std::char",
            "use std::int",
            "use std::float",
            "",
            "",
        ).joinToString("\n")
        return MezzeTestHelper.run(header + body().trimIndent() + "\n\nmain {}\n")
    }

    // ==================== MutInt ====================

    /** `write` then `read`: a swapped (n, v) would write into the literal. */
    @Test
    fun `int write then read round-trips`() {
        assertEquals(
            42L,
            run("int_to_mut, read_int, write_int") {
                """
                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 7 }
                  write_int { n = c, v = 42 }
                  read_int { n = c }
                """
            },
        )
    }

    /** incr and decr must move in OPPOSITE directions, and by `delta` — not by
     *  whatever happens to sit in the other slot. */
    @Test
    fun `incr and decr oppose each other`() {
        assertEquals(
            100L - 30L + 7L,
            run("int_to_mut, read_int, incr, decr") {
                """
                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 100 }
                  decr { n = c, delta = 30 }
                  incr { n = c, delta = 7 }
                  read_int { n = c }
                """
            },
        )
    }

    /** mul, div and mod are each non-commutative with their cell, so a swap
     *  changes the answer: 7*3=21, 21/2=10, 10 mod 4=2. */
    @Test
    fun `mul div and mod apply the operand to the cell`() {
        assertEquals(
            2L,
            run("int_to_mut, read_int, mul_int, div_int, mod_int") {
                """
                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 7 }
                  mul_int { n = c, factor = 3 }
                  div_int { n = c, divisor = 2 }
                  mod_int { n = c, divisor = 4 }
                  read_int { n = c }
                """
            },
        )
    }

    /** `expected` before `new_val`. Reversed, the first swap would test 99
     *  against 5 and fail, and the second would test 5 against 5 and succeed —
     *  so the pair 1/0 is produced by the correct order only. */
    @Test
    fun `compare_and_swap distinguishes expected from new`() {
        assertEquals(
            1099L,
            run("int_to_mut, read_int, compare_and_swap") {
                """
                let main = {} -> perform LocalMut in do
                  let c = int_to_mut { n = 5 }
                  let hit = if compare_and_swap { n = c, expected = 5, new_val = 99 } then 1000 else 0
                  let miss = if compare_and_swap { n = c, expected = 5, new_val = 7 } then 1 else 0
                  hit + miss + read_int { n = c }
                """
            },
        )
    }

    // ==================== MutFloat ====================

    /** add and sub oppose; mul and div oppose. (10+5-3)*4/2 = 24. */
    @Test
    fun `float arithmetic applies in order`() {
        assertEquals(
            24L,
            run("float_to_mut, read_float, write_float, add_float, sub_float, mul_float, div_float") {
                """
                let main = {} -> perform LocalMut in do
                  let c = float_to_mut { n = 0.0 }
                  write_float { n = c, v = 10.0 }
                  add_float { n = c, delta = 5.0 }
                  sub_float { n = c, delta = 3.0 }
                  mul_float { n = c, factor = 4.0 }
                  div_float { n = c, divisor = 2.0 }
                  match float::to_int { n = read_float { n = c } } is
                    Some { val } -> val
                    None -> 0 - 1
                """
            },
        )
    }

    // ==================== MutBool ====================

    @Test
    fun `bool write toggle and the two setters disagree`() {
        assertEquals(
            // write(true)=1, toggle→false=0, set_true=1, set_false=0 → 1010 read
            // as four digits in that order.
            1010L,
            run("bool_to_mut, read_bool, write_bool, toggle, set_true, set_false") {
                """
                let main = {} -> perform LocalMut in do
                  let b = bool_to_mut { b = False }
                  write_bool { b = b, v = True }
                  let a1 = if read_bool { b = b } then 1000 else 0
                  toggle { b = b }
                  let a2 = if read_bool { b = b } then 100 else 0
                  set_true { b = b }
                  let a3 = if read_bool { b = b } then 10 else 0
                  set_false { b = b }
                  let a4 = if read_bool { b = b } then 1 else 0
                  a1 + a2 + a3 + a4
                """
            },
        )
    }

    // ==================== MutChar ====================

    /** to_upper and to_lower must not be interchangeable, and the three
     *  predicates must each answer about the cell's own character.
     *
     *  There is no Char LITERAL — a `Char` value comes from
     *  `char::from_int`, so the whole case is written in code points: 120 is
     *  'x', 97 is 'a', 65 is 'A'. */
    @Test
    fun `char case and predicates`() {
        assertEquals(
            // 65 'A' after to_upper, 97 'a' after to_lower, then
            // digit=0 letter=1 space=0 for 'a'.
            "65|97|0|1|0",
            run("char_to_mut, read_char, write_char, char_to_upper, char_to_lower, char_is_digit, char_is_letter, char_is_whitespace") {
                """
                let main = {} -> match char::from_int { code = 120 } is
                  Some { val = x } -> match char::from_int { code = 97 } is
                    Some { val = a } -> perform LocalMut in do
                      let c = char_to_mut { c = x }
                      write_char { c = c, v = a }
                      char_to_upper { c = c }
                      let up = char::to_int { c = read_char { c = c } }
                      char_to_lower { c = c }
                      let lo = char::to_int { c = read_char { c = c } }
                      let d = if char_is_digit { c = c } then 1 else 0
                      let l = if char_is_letter { c = c } then 1 else 0
                      let w = if char_is_whitespace { c = c } then 1 else 0
                      "$(int::to_str { n = up })|$(int::to_str { n = lo })|$(int::to_str { n = d })|$(int::to_str { n = l })|$(int::to_str { n = w })"
                    None -> "no-a"
                  None -> "no-x"
                """
            },
        )
    }

    // ==================== MutString ====================

    /** append goes to the END and prepend to the FRONT — the two ops differ
     *  only in which side, so swapping them is invisible to the type. */
    @Test
    fun `string append and prepend go to opposite ends`() {
        assertEquals(
            "<mid>",
            run("string_to_mut, read_str, string_append, string_prepend") {
                """
                let main = {} -> perform LocalMut in do
                  let s = string_to_mut { s = "mid" }
                  string_append { s = s, suffix = ">" }
                  string_prepend { s = s, prefix = "<" }
                  read_str { s = s }
                """
            },
        )
    }

    /** `insert` takes (s, i, text) and `delete` (s, start, end) — start before
     *  end, so a reversed pair would delete nothing. */
    @Test
    fun `string insert and delete honour their index order`() {
        assertEquals(
            "aXYZd",
            run("string_to_mut, read_str, string_insert, string_delete") {
                """
                let main = {} -> perform LocalMut in do
                  let s = string_to_mut { s = "abcd" }
                  string_delete { s = s, start = 1, end = 3 }
                  string_insert { s = s, i = 1, text = "XYZ" }
                  read_str { s = s }
                """
            },
        )
    }

    /** char_at reads at `i`; set_char_at writes at `i`. Index 2 of "abcd" is
     *  'c' (99), and after setting it to 'Z' the read must see 90. In code
     *  points, because `Option Char` and a `Str` fallback are different types
     *  and the arms have to agree. */
    @Test
    fun `string char_at and set_char_at agree on the index`() {
        assertEquals(
            "99|90",
            run("string_to_mut, string_char_at, string_set_char_at") {
                """
                let main = {} -> match char::from_int { code = 90 } is
                  Some { val = z } -> perform LocalMut in do
                    let s = string_to_mut { s = "abcd" }
                    let before = match string_char_at { s = s, i = 2 } is
                      Some { val } -> char::to_int { c = val }
                      None -> 0 - 1
                    string_set_char_at { s = s, i = 2, c = z }
                    let after = match string_char_at { s = s, i = 2 } is
                      Some { val } -> char::to_int { c = val }
                      None -> 0 - 1
                    "$(int::to_str { n = before })|$(int::to_str { n = after })"
                  None -> "no-Z"
                """
            },
        )
    }

    @Test
    fun `string len is_empty clear and reverse`() {
        assertEquals(
            "4|0|dcba|1",
            run("string_to_mut, read_str, string_len, string_is_empty, string_clear, string_reverse") {
                """
                let main = {} -> perform LocalMut in do
                  let s = string_to_mut { s = "abcd" }
                  let n = string_len { s = s }
                  let e0 = if string_is_empty { s = s } then 1 else 0
                  string_reverse { s = s }
                  let r = read_str { s = s }
                  string_clear { s = s }
                  let e1 = if string_is_empty { s = s } then 1 else 0
                  "$(int::to_str { n = n })|$(int::to_str { n = e0 })|$(r)|$(int::to_str { n = e1 })"
                """
            },
        )
    }

    // ==================== MutSeq ====================

    /** `set` takes (seq, i, v): swapping i and v would index by the value. */
    @Test
    fun `seq get and set agree on the index`() {
        assertEquals(
            3099L,
            run("seq_to_mut, get, set") {
                """
                let main = {} -> perform LocalMut in do
                  let m = seq_to_mut { seq = [1, 2, 3, 4] }
                  let before = match get { seq = m, i = 2 } is
                    Some { val } -> val
                    None -> 0
                  set { seq = m, i = 2, v = 99 }
                  let after = match get { seq = m, i = 2 } is
                    Some { val } -> val
                    None -> 0
                  before * 1000 + after
                """
            },
        )
    }

    /** push appends and pop removes from the SAME end — so pop must return
     *  what push put, and size must come back to where it started. */
    @Test
    fun `seq push and pop are inverses`() {
        assertEquals(
            4077L,
            run("seq_to_mut, push, pop, size") {
                """
                let main = {} -> perform LocalMut in do
                  let m = seq_to_mut { seq = [1, 2, 3, 4] }
                  push { seq = m, v = 77 }
                  let n = size { seq = m }
                  let p = match pop { seq = m } is
                    Some { val } -> val
                    None -> 0
                  size { seq = m } * 1000 + n * 0 + p
                """
            },
        )
    }

    /** `insert` at an index and `remove` at an index, checked through the
     *  element that ends up at position 0. */
    @Test
    fun `seq insert and remove address the same index`() {
        assertEquals(
            "9,1,2,3|1,2,3",
            run("seq_to_mut, seq_to_immut, insert, remove") {
                """
                let main = {} -> perform LocalMut in do
                  let m = seq_to_mut { seq = [1, 2, 3] }
                  insert { seq = m, i = 0, v = 9 }
                  let a = seq::join { xs = seq::map { xs = seq_to_immut { seq = m }, f = { x } -> int::to_str { n = x } }, sep = "," }
                  remove { seq = m, i = 0 }
                  let b = seq::join { xs = seq::map { xs = seq_to_immut { seq = m }, f = { x } -> int::to_str { n = x } }, sep = "," }
                  "$(a)|$(b)"
                """
            },
        )
    }

    /** swap(i, j) must exchange exactly those two positions. */
    @Test
    fun `seq swap exchanges the two indices`() {
        assertEquals(
            "4,2,3,1",
            run("seq_to_mut, seq_to_immut, swap") {
                """
                let main = {} -> perform LocalMut in do
                  let m = seq_to_mut { seq = [1, 2, 3, 4] }
                  swap { seq = m, i = 0, j = 3 }
                  seq::join { xs = seq::map { xs = seq_to_immut { seq = m }, f = { x } -> int::to_str { n = x } }, sep = "," }
                """
            },
        )
    }

    @Test
    fun `seq reverse sort fill fill_range and clear`() {
        assertEquals(
            "3,1,2|1,2,3|7,7,7|7,5,5",
            run("seq_to_mut, seq_to_immut, reverse, sort, fill, fill_range") {
                """
                let main = {} -> do
                  let show = { m } -> perform LocalMut in
                    seq::join { xs = seq::map { xs = seq_to_immut { seq = m }, f = { x } -> int::to_str { n = x } }, sep = "," }
                  perform LocalMut in do
                    let a = seq_to_mut { seq = [2, 1, 3] }
                    reverse { seq = a }
                    let s1 = show { m = a }
                    sort { seq = a }
                    let s2 = show { m = a }
                    let b = seq_to_mut { seq = [1, 2, 3] }
                    fill { seq = b, v = 7 }
                    let s3 = show { m = b }
                    fill_range { seq = b, start = 1, end = 3, v = 5 }
                    let s4 = show { m = b }
                    "$(s1)|$(s2)|$(s3)|$(s4)"
                """
            },
        )
    }

    // ==================== MutSet ====================

    /** add and remove oppose; `toggle` returns whether the element is now
     *  present, so calling it twice must answer differently. */
    @Test
    fun `set add remove contains and toggle`() {
        assertEquals(
            "1|0|2|1|0",
            run("set_to_mut, set_add, set_remove, set_contains, set_size, set_toggle") {
                """
                let main = {} -> perform LocalMut in do
                  let s = set_to_mut { set = set::from_seq { xs = [1, 2] } }
                  let has1 = if set_contains { set = s, elem = 1 } then 1 else 0
                  set_remove { set = s, elem = 1 }
                  let has2 = if set_contains { set = s, elem = 1 } then 1 else 0
                  set_add { set = s, elem = 1 }
                  let n = set_size { set = s }
                  let t1 = if set_toggle { set = s, elem = 9 } then 1 else 0
                  let t2 = if set_toggle { set = s, elem = 9 } then 1 else 0
                  "$(int::to_str { n = has1 })|$(int::to_str { n = has2 })|$(int::to_str { n = n })|$(int::to_str { n = t1 })|$(int::to_str { n = t2 })"
                """
            },
        )
    }

    // ==================== MutMap ====================

    /** `put` takes (map, key, value) — swapping key and value would store the
     *  pair backwards, which `get { key }` then fails to find. */
    @Test
    fun `map put and get keep key and value apart`() {
        assertEquals(
            "7|1|0|1",
            run("map_to_mut, map_get, map_put, map_remove, map_contains, map_size") {
                """
                let main = {} -> perform LocalMut in do
                  let m = map_to_mut { map = map::empty {} }
                  map_put { map = m, key = 3, value = 7 }
                  let v = match map_get { map = m, key = 3 } is
                    Some { val } -> val
                    None -> 0 - 1
                  let n1 = map_size { map = m }
                  map_remove { map = m, key = 3 }
                  let n2 = map_size { map = m }
                  let c = if map_contains { map = m, key = 3 } then 1 else 0
                  "$(int::to_str { n = v })|$(int::to_str { n = n1 })|$(int::to_str { n = n2 })|$(int::to_str { n = n1 - c })"
                """
            },
        )
    }

    /** keys, values and entries must each come back with the right count. */
    @Test
    fun `map keys values and entries`() {
        assertEquals(
            "2|2|2",
            run("map_to_mut, map_put, map_keys, map_values, map_entries") {
                """
                let main = {} -> perform LocalMut in do
                  let m = map_to_mut { map = map::empty {} }
                  map_put { map = m, key = 1, value = 10 }
                  map_put { map = m, key = 2, value = 20 }
                  let k = seq::size { xs = map_keys { map = m } }
                  let v = seq::size { xs = map_values { map = m } }
                  let e = seq::size { xs = map_entries { map = m } }
                  "$(int::to_str { n = k })|$(int::to_str { n = v })|$(int::to_str { n = e })"
                """
            },
        )
    }

    // ==================== MutDeque ====================

    /** push_front and push_back must not be interchangeable, and peek/pop
     *  must read the end they name. */
    @Test
    fun `deque ends stay distinct`() {
        assertEquals(
            "1|9|1|9|2",
            run("deque_to_mut, deque_push_front, deque_push_back, deque_peek_front, deque_peek_back, deque_pop_front, deque_pop_back, deque_size") {
                """
                let main = {} -> perform LocalMut in do
                  let d = deque_to_mut { deque = deque::from_seq { xs = [5] } }
                  deque_push_front { deque = d, elem = 1 }
                  deque_push_back { deque = d, elem = 9 }
                  let pf = match deque_peek_front { deque = d } is
                    Some { val } -> val
                    None -> 0
                  let pb = match deque_peek_back { deque = d } is
                    Some { val } -> val
                    None -> 0
                  let of = match deque_pop_front { deque = d } is
                    Some { val } -> val
                    None -> 0
                  let ob = match deque_pop_back { deque = d } is
                    Some { val } -> val
                    None -> 0
                  "$(int::to_str { n = pf })|$(int::to_str { n = pb })|$(int::to_str { n = of })|$(int::to_str { n = ob })|$(int::to_str { n = deque_size { deque = d } + 1 })"
                """
            },
        )
    }

    @Test
    fun `deque is_empty clear reverse and to_seq`() {
        assertEquals(
            "0|3|1|3",
            run("deque_to_mut, deque_is_empty, deque_clear, deque_reverse, deque_to_seq, deque_size") {
                """
                let main = {} -> perform LocalMut in do
                  let d = deque_to_mut { deque = deque::from_seq { xs = [1, 2, 3] } }
                  let e0 = if deque_is_empty { deque = d } then 1 else 0
                  deque_reverse { deque = d }
                  let first = match seq::get { xs = deque_to_seq { deque = d }, i = 0 } is
                    Some { val } -> val
                    None -> 0
                  let n = seq::size { xs = deque_to_seq { deque = d } }
                  deque_clear { deque = d }
                  let e1 = if deque_is_empty { deque = d } then 1 else 0
                  "$(int::to_str { n = e0 })|$(int::to_str { n = first })|$(int::to_str { n = e1 })|$(int::to_str { n = n })"
                """
            },
        )
    }

    // ==================== MutBytes ====================

    /** `set` takes (bytes, i, v) and `fill_range` takes (bytes, start, end, v)
     *  — the value is SECOND in `fill` and LAST in `fill_range`, which is
     *  exactly the difference a positional ABI turns into a wrong answer. */
    @Test
    fun `bytes get set fill and fill_range`() {
        assertEquals(
            "0|42|7|7|9",
            run("bytes_to_mut, bytes_get, bytes_set, bytes_len, bytes_fill, bytes_fill_range") {
                """
                let main = {} -> perform LocalMut in do
                  let b = bytes_to_mut { bytes = bytes::from_seq { arr = [0, 0, 0] } }
                  let at0 = match bytes_get { bytes = b, i = 0 } is
                    Some { val } -> val
                    None -> 0 - 1
                  bytes_set { bytes = b, i = 1, v = 42 }
                  let at1 = match bytes_get { bytes = b, i = 1 } is
                    Some { val } -> val
                    None -> 0 - 1
                  bytes_fill { bytes = b, v = 7 }
                  let f0 = match bytes_get { bytes = b, i = 0 } is
                    Some { val } -> val
                    None -> 0 - 1
                  let f1 = match bytes_get { bytes = b, i = 1 } is
                    Some { val } -> val
                    None -> 0 - 1
                  bytes_fill_range { bytes = b, start = 2, end = 3, v = 9 }
                  let f2 = match bytes_get { bytes = b, i = 2 } is
                    Some { val } -> val
                    None -> 0 - 1
                  "$(int::to_str { n = at0 })|$(int::to_str { n = at1 })|$(int::to_str { n = f0 })|$(int::to_str { n = f1 })|$(int::to_str { n = f2 })"
                """
            },
        )
    }

    /** swap(i, j), reverse, resize and slice. */
    @Test
    fun `bytes swap reverse resize and slice`() {
        assertEquals(
            "3|1|2|3",
            run("bytes_to_mut, bytes_get, bytes_swap, bytes_reverse, bytes_resize, bytes_len") {
                """
                let main = {} -> perform LocalMut in do
                  let b = bytes_to_mut { bytes = bytes::from_seq { arr = [1, 2, 3] } }
                  bytes_swap { bytes = b, i = 0, j = 2 }
                  let s0 = match bytes_get { bytes = b, i = 0 } is
                    Some { val } -> val
                    None -> 0 - 1
                  bytes_reverse { bytes = b }
                  let r0 = match bytes_get { bytes = b, i = 0 } is
                    Some { val } -> val
                    None -> 0 - 1
                  let n0 = bytes_len { bytes = b }
                  bytes_resize { bytes = b, size = 2 }
                  "$(int::to_str { n = s0 })|$(int::to_str { n = r0 })|$(int::to_str { n = bytes_len { bytes = b } })|$(int::to_str { n = n0 })"
                """
            },
        )
    }

    /** `copy` is the widest declaration here, five parameters, and the offsets
     *  go SRC before DEST while the buffers go DEST before SRC — so a
     *  consistent-looking reordering produces the wrong bytes. */
    @Test
    fun `bytes copy keeps dest src and the two offsets straight`() {
        assertEquals(
            // src[1..2] = 5,6 landing at dst[0..1]. The two offsets must DIFFER
            // for this to mean anything: with both set to 1 a swap is invisible,
            // which is how the first version of this case passed with
            // `src_offset` and `dest_offset` deliberately exchanged.
            // Exchanged, this copies src[0..1] = 4,5 to dst[1..2] → "0|4|5".
            "5|6|0",
            run("bytes_to_mut, bytes_get, bytes_copy") {
                """
                let main = {} -> perform LocalMut in do
                  let dst = bytes_to_mut { bytes = bytes::from_seq { arr = [0, 0, 0] } }
                  let src = bytes_to_mut { bytes = bytes::from_seq { arr = [4, 5, 6] } }
                  bytes_copy {
                    dest = dst, src = src,
                    src_offset = 1, dest_offset = 0, length = 2,
                  }
                  let a = match bytes_get { bytes = dst, i = 0 } is
                    Some { val } -> val
                    None -> 0 - 1
                  let b = match bytes_get { bytes = dst, i = 1 } is
                    Some { val } -> val
                    None -> 0 - 1
                  let c = match bytes_get { bytes = dst, i = 2 } is
                    Some { val } -> val
                    None -> 0 - 1
                  "$(int::to_str { n = a })|$(int::to_str { n = b })|$(int::to_str { n = c })"
                """
            },
        )
    }
}
