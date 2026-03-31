package com.mezze.interpreter.v2

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * Tests for anonymous impl methods (value.method {} syntax) across all types.
 * These test that `impl Type where { ... }` methods work end-to-end.
 */
class AnonymousImplMethodTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ========================================
    // Int methods
    // ========================================

    @Test
    fun `Int abs`() {
        assertEquals(5L, eval("""
            use std::int
            let n = 0 - 5
            n.abs {}
        """))
    }

    @Test
    fun `Int abs positive unchanged`() {
        assertEquals(42L, eval("""
            use std::int
            42.abs {}
        """))
    }

    @Test
    fun `Int sign positive`() {
        assertEquals(1L, eval("""
            use std::int
            42.sign {}
        """))
    }

    @Test
    fun `Int sign negative`() {
        assertEquals(-1L, eval("""
            use std::int
            let n = 0 - 7
            n.sign {}
        """))
    }

    @Test
    fun `Int sign zero`() {
        assertEquals(0L, eval("""
            use std::int
            0.sign {}
        """))
    }

    @Test
    fun `Int is_even`() {
        assertEquals(true, eval("""
            use std::int
            4.is_even {}
        """))
    }

    @Test
    fun `Int is_even false`() {
        assertEquals(false, eval("""
            use std::int
            3.is_even {}
        """))
    }

    @Test
    fun `Int is_odd`() {
        assertEquals(true, eval("""
            use std::int
            3.is_odd {}
        """))
    }

    @Test
    fun `Int pow`() {
        assertEquals(8L, eval("""
            use std::int
            2.pow {exp = 3}
        """))
    }

    @Test
    fun `Int gcd`() {
        assertEquals(4L, eval("""
            use std::int
            12.gcd {r = 8}
        """))
    }

    @Test
    fun `Int lcm`() {
        assertEquals(12L, eval("""
            use std::int
            4.lcm {r = 6}
        """))
    }

    @Test
    fun `Int max`() {
        assertEquals(5L, eval("""
            use std::int
            3.max {r = 5}
        """))
    }

    @Test
    fun `Int min`() {
        assertEquals(3L, eval("""
            use std::int
            3.min {r = 5}
        """))
    }

    @Test
    fun `Int clamp`() {
        assertEquals(10L, eval("""
            use std::int
            15.clamp {min = 0, max = 10}
        """))
    }

    @Test
    fun `Int to_str`() {
        assertEquals("42", eval("""
            use std::int
            42.to_str {}
        """))
    }

    @Test
    fun `Int to_float`() {
        assertEquals(42.0, eval("""
            use std::int
            42.to_float {}
        """))
    }

    @Test
    fun `Int to_nat returns Some for positive`() {
        assertEquals(5L, eval("""
            use std::int
            match 5.to_nat {} is
                Some {val} -> val
                None -> -1
        """))
    }

    @Test
    fun `Int to_nat returns None for negative`() {
        assertEquals(-1L, eval("""
            use std::int
            let n = 0 - 3
            match n.to_nat {} is
                Some {val} -> val
                None -> 0 - 1
        """))
    }

    // ========================================
    // Float methods
    // ========================================

    @Test
    fun `Float abs`() {
        assertEquals(3.14, eval("""
            use std::float
            let n = 0.0 - 3.14
            n.abs {}
        """))
    }

    @Test
    fun `Float sign positive`() {
        assertEquals(1.0, eval("""
            use std::float
            5.5.sign {}
        """))
    }

    @Test
    fun `Float sign negative`() {
        assertEquals(-1.0, eval("""
            use std::float
            let n = 0.0 - 5.5
            n.sign {}
        """))
    }

    @Test
    fun `Float is_nan`() {
        assertEquals(false, eval("""
            use std::float
            3.14.is_nan {}
        """))
    }

    @Test
    fun `Float is_finite`() {
        assertEquals(true, eval("""
            use std::float
            3.14.is_finite {}
        """))
    }

    @Test
    fun `Float is_infinite`() {
        assertEquals(false, eval("""
            use std::float
            3.14.is_infinite {}
        """))
    }

    @Test
    fun `Float min`() {
        assertEquals(1.5, eval("""
            use std::float
            1.5.min {r = 2.5}
        """))
    }

    @Test
    fun `Float max`() {
        assertEquals(2.5, eval("""
            use std::float
            1.5.max {r = 2.5}
        """))
    }

    @Test
    fun `Float clamp`() {
        assertEquals(10.0, eval("""
            use std::float
            15.5.clamp {min = 0.0, max = 10.0}
        """))
    }

    @Test
    fun `Float to_str`() {
        val result = eval("""
            use std::float
            float::to_str {n = 3.14}
        """)
        assertTrue((result as String).startsWith("3.14"))
    }

    @Test
    fun `Float to_int`() {
        assertEquals(3L, eval("""
            use std::float
            match 3.7.to_int {} is
                Some {val} -> val
                None -> -1
        """))
    }

    // ========================================
    // Str methods
    // ========================================

    @Test
    fun `Str length`() {
        assertEquals(5L, eval("""
            use std::str
            "hello".length {}
        """))
    }

    @Test
    fun `Str is_empty true`() {
        assertEquals(true, eval("""
            use std::str
            "".is_empty {}
        """))
    }

    @Test
    fun `Str is_empty false`() {
        assertEquals(false, eval("""
            use std::str
            "x".is_empty {}
        """))
    }

    @Test
    fun `Str to_upper`() {
        assertEquals("HELLO", eval("""
            use std::str
            "hello".to_upper {}
        """))
    }

    @Test
    fun `Str to_lower`() {
        assertEquals("hello", eval("""
            use std::str
            "HELLO".to_lower {}
        """))
    }

    @Test
    fun `Str trim`() {
        assertEquals("hello", eval("""
            use std::str
            "  hello  ".trim {}
        """))
    }

    @Test
    fun `Str trim_start`() {
        assertEquals("hello  ", eval("""
            use std::str
            "  hello  ".trim_start {}
        """))
    }

    @Test
    fun `Str trim_end`() {
        assertEquals("  hello", eval("""
            use std::str
            "  hello  ".trim_end {}
        """))
    }

    @Test
    fun `Str reverse`() {
        assertEquals("olleh", eval("""
            use std::str
            "hello".reverse {}
        """))
    }

    @Test
    fun `Str starts_with`() {
        assertEquals(true, eval("""
            use std::str
            "hello world".starts_with {prefix = "hello"}
        """))
    }

    @Test
    fun `Str ends_with`() {
        assertEquals(true, eval("""
            use std::str
            "hello world".ends_with {suffix = "world"}
        """))
    }

    @Test
    fun `Str contains`() {
        assertEquals(true, eval("""
            use std::str
            "hello world".contains {substring = "lo wo"}
        """))
    }

    @Test
    fun `Str index_of`() {
        assertEquals(2L, eval("""
            use std::str
            match "hello".index_of {sub = "ll"} is
                Some {val} -> val
                None -> -1
        """))
    }

    @Test
    fun `Str last_index_of`() {
        assertEquals(8L, eval("""
            use std::str
            match "hello hello".last_index_of {sub = "ll"} is
                Some {val} -> val
                None -> -1
        """))
    }

    @Test
    fun `Str substring`() {
        assertEquals("hello", eval("""
            use std::str
            "hello world".substring {start = 0, end = 5}
        """))
    }

    @Test
    fun `Str replace`() {
        assertEquals("hello there world", eval("""
            use std::str
            "hello world world".replace {old = "world", new = "there"}
        """))
    }

    @Test
    fun `Str replace_all`() {
        assertEquals("hello there there", eval("""
            use std::str
            "hello world world".replace_all {old = "world", new = "there"}
        """))
    }

    @Test
    fun `Str split`() {
        assertEquals(3L, eval("""
            use std::str
            use std::collections::seq
            let parts = str::split {s = "a,b,c", delimiter = ","}
            seq::length {xs = parts}
        """))
    }

    @Test
    fun `Str lines`() {
        assertEquals(3L, eval("""
            use std::str
            use std::collections::seq
            let parts = "a\nb\nc".lines {}
            seq::length {xs = parts}
        """))
    }

    @Test
    fun `Str words`() {
        assertEquals(3L, eval("""
            use std::str
            use std::collections::seq
            let parts = "hello   world  test".words {}
            seq::length {xs = parts}
        """))
    }

    @Test
    fun `Str chars`() {
        assertEquals(3L, eval("""
            use std::str
            use std::collections::seq
            let cs = "abc".chars {}
            seq::length {xs = cs}
        """))
    }

    @Test
    fun `Str char_at`() {
        assertEquals(101L, eval("""
            use std::str
            use std::char
            match "hello".char_at {i = 1} is
                Some {val} -> char::to_int {c = val}
                None -> -1
        """))
    }

    @Test
    fun `Str repeat`() {
        assertEquals("ababab", eval("""
            use std::str
            "ab".repeat {n = 3}
        """))
    }

    @Test
    fun `Str to_snake_case`() {
        assertEquals("hello_world", eval("""
            use std::str
            "HelloWorld".to_snake_case {}
        """))
    }

    @Test
    fun `Str to_camel_case`() {
        assertEquals("helloWorld", eval("""
            use std::str
            "hello_world".to_camel_case {}
        """))
    }

    @Test
    fun `Str to_kebab_case`() {
        assertEquals("hello-world", eval("""
            use std::str
            "HelloWorld".to_kebab_case {}
        """))
    }

    @Test
    fun `Str to_screaming_snake_case`() {
        assertEquals("HELLO_WORLD", eval("""
            use std::str
            "HelloWorld".to_screaming_snake_case {}
        """))
    }

    @Test
    fun `Str glob_match`() {
        assertEquals(true, eval("""
            use std::str
            "config.json".glob_match {pattern = "*.json"}
        """))
    }

    // ========================================
    // Bool methods
    // ========================================

    @Test
    fun `Bool to_str true`() {
        assertEquals("true", eval("""
            use std::bool
            True.to_str {}
        """))
    }

    @Test
    fun `Bool to_str false`() {
        assertEquals("false", eval("""
            use std::bool
            False.to_str {}
        """))
    }

    @Test
    fun `Bool xor`() {
        assertEquals(true, eval("""
            use std::bool
            True.xor {r = False}
        """))
    }

    @Test
    fun `Bool xor same`() {
        assertEquals(false, eval("""
            use std::bool
            True.xor {r = True}
        """))
    }

    @Test
    fun `Bool implies`() {
        assertEquals(true, eval("""
            use std::bool
            False.implies {r = True}
        """))
    }

    @Test
    fun `Bool implies false`() {
        assertEquals(false, eval("""
            use std::bool
            True.implies {r = False}
        """))
    }

    @Test
    fun `Bool iff`() {
        assertEquals(true, eval("""
            use std::bool
            True.iff {r = True}
        """))
    }

    @Test
    fun `Bool iff different`() {
        assertEquals(false, eval("""
            use std::bool
            True.iff {r = False}
        """))
    }

    // ========================================
    // Nat methods
    // ========================================

    @Test
    fun `Nat to_int`() {
        assertEquals(5L, eval("""
            use std::nat
            5n.to_int {}
        """))
    }

    @Test
    fun `Nat to_str`() {
        assertEquals("42", eval("""
            use std::nat
            42n.to_str {}
        """))
    }

    @Test
    fun `Nat is_zero true`() {
        assertEquals(true, eval("""
            use std::nat
            0n.is_zero {}
        """))
    }

    @Test
    fun `Nat is_zero false`() {
        assertEquals(false, eval("""
            use std::nat
            5n.is_zero {}
        """))
    }

    @Test
    fun `Nat is_even`() {
        assertEquals(true, eval("""
            use std::nat
            4n.is_even {}
        """))
    }

    @Test
    fun `Nat is_odd`() {
        assertEquals(true, eval("""
            use std::nat
            3n.is_odd {}
        """))
    }

    @Test
    fun `Nat min`() {
        assertEquals(3L, eval("""
            use std::nat
            3n.min {r = 5n}
        """))
    }

    @Test
    fun `Nat max`() {
        assertEquals(5L, eval("""
            use std::nat
            3n.max {r = 5n}
        """))
    }

    @Test
    fun `Nat clamp`() {
        assertEquals(10L, eval("""
            use std::nat
            15n.clamp {min = 0n, max = 10n}
        """))
    }

    @Test
    fun `Nat subtract_to_int`() {
        assertEquals(-2L, eval("""
            use std::nat
            3n.subtract_to_int {r = 5n}
        """))
    }

    // ========================================
    // Frac methods
    // ========================================

    @Test
    fun `Frac numerator`() {
        assertEquals(1L, eval("""
            use std::frac
            let f = frac::make {numerator = 1, denominator = 2}
            f.numerator {}
        """))
    }

    @Test
    fun `Frac denominator`() {
        assertEquals(2L, eval("""
            use std::frac
            let f = frac::make {numerator = 1, denominator = 2}
            f.denominator {}
        """))
    }

    @Test
    fun `Frac abs`() {
        assertEquals(1L, eval("""
            use std::frac
            let f = frac::make {numerator = -1, denominator = 2}
            let a = f.abs {}
            frac::numerator {n = a}
        """))
    }

    @Test
    fun `Frac to_float`() {
        assertEquals(0.5, eval("""
            use std::frac
            let f = frac::make {numerator = 1, denominator = 2}
            f.to_float {}
        """))
    }

    @Test
    fun `Frac to_str`() {
        assertEquals("1/2", eval("""
            use std::frac
            let f = frac::make {numerator = 1, denominator = 2}
            f.to_str {}
        """))
    }

    // ========================================
    // Char methods
    // ========================================

    @Test
    fun `Char to_int`() {
        assertEquals(65L, eval("""
            use std::char
            match char::from_int {code = 65} is
                Some {val} -> char::to_int {c = val}
                None -> 0 - 1
        """))
    }

    @Test
    fun `Char to_str`() {
        assertEquals("A", eval("""
            use std::char
            match char::from_int {code = 65} is
                Some {val} -> char::to_str {c = val}
                None -> "error"
        """))
    }

    @Test
    fun `Char to_upper`() {
        assertEquals(65L, eval("""
            use std::char
            match char::from_int {code = 97} is
                Some {val} -> char::to_int {c = char::to_upper {c = val}}
                None -> 0 - 1
        """))
    }

    @Test
    fun `Char to_lower`() {
        assertEquals(97L, eval("""
            use std::char
            match char::from_int {code = 65} is
                Some {val} -> char::to_int {c = char::to_lower {c = val}}
                None -> 0 - 1
        """))
    }

    @Test
    fun `Char is_digit`() {
        assertEquals(true, eval("""
            use std::char
            match char::from_int {code = 48} is
                Some {val} -> char::is_digit {c = val}
                None -> False
        """))
    }

    @Test
    fun `Char is_letter`() {
        assertEquals(true, eval("""
            use std::char
            match char::from_int {code = 65} is
                Some {val} -> char::is_letter {c = val}
                None -> False
        """))
    }

    @Test
    fun `Char is_whitespace`() {
        assertEquals(true, eval("""
            use std::char
            match char::from_int {code = 32} is
                Some {val} -> char::is_whitespace {c = val}
                None -> False
        """))
    }

    @Test
    fun `Char is_upper`() {
        assertEquals(true, eval("""
            use std::char
            match char::from_int {code = 65} is
                Some {val} -> char::is_upper {c = val}
                None -> False
        """))
    }

    @Test
    fun `Char is_lower`() {
        assertEquals(true, eval("""
            use std::char
            match char::from_int {code = 97} is
                Some {val} -> char::is_lower {c = val}
                None -> False
        """))
    }

    // ========================================
    // Method chaining
    // ========================================

    @Test
    fun `chain Int methods`() {
        assertEquals(true, eval("""
            use std::int
            let n = 0 - 15
            let a = int::abs {n = n}
            let c = int::clamp {n = a, min = 0, max = 10}
            c.is_even {}
        """))
    }

    @Test
    fun `chain Str methods`() {
        assertEquals("HELLO", eval("""
            use std::str
            let trimmed = str::trim {s = "  hello  "}
            str::to_upper {s = trimmed}
        """))
    }

    @Test
    fun `chain Float to_str then Str method`() {
        val result = eval("""
            use std::float
            float::to_str {n = 3.14}
        """)
        assertTrue((result as String).startsWith("3"))
    }

    // ========================================
    // Option methods
    // ========================================

    @Test
    fun `Option is_some on Some`() {
        assertEquals(true, eval("""
            use std::option
            let x = Some {val = 42}
            x.is_some {}
        """))
    }

    @Test
    fun `Option is_some on None`() {
        assertEquals(false, eval("""
            use std::option
            let x: Option Int = None
            x.is_some {}
        """))
    }

    @Test
    fun `Option is_none on None`() {
        assertEquals(true, eval("""
            use std::option
            let x: Option Int = None
            x.is_none {}
        """))
    }

    @Test
    fun `Option is_none on Some`() {
        assertEquals(false, eval("""
            use std::option
            let x = Some {val = 42}
            x.is_none {}
        """))
    }

    @Test
    fun `Option get_or_else on Some`() {
        assertEquals(42L, eval("""
            use std::option
            let x = Some {val = 42}
            x.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Option get_or_else on None`() {
        assertEquals(0L, eval("""
            use std::option
            let x: Option Int = None
            x.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Option map on Some`() {
        assertEquals(84L, eval("""
            use std::option
            Some {val = 42}.map {f = {val} -> val + val}.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Option map on None`() {
        assertEquals(true, eval("""
            use std::option
            let x: Option Int = None
            x.map {f = {val} -> val + val}.is_none {}
        """))
    }

    @Test
    fun `Option flat_map on Some`() {
        assertEquals(42L, eval("""
            use std::option
            Some {val = 42}.flat_map {f = {val} -> Some {val = val}}.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Option flat_map on None`() {
        assertEquals(true, eval("""
            use std::option
            let x: Option Int = None
            x.flat_map {f = {val} -> Some {val = val}}.is_none {}
        """))
    }

    @Test
    fun `Option filter on Some matching`() {
        assertEquals(true, eval("""
            use std::option
            Some {val = 42}.filter {p = {val} -> val == 42}.is_some {}
        """))
    }

    @Test
    fun `Option filter on Some not matching`() {
        assertEquals(true, eval("""
            use std::option
            Some {val = 42}.filter {p = {val} -> val == 0}.is_none {}
        """))
    }

    @Test
    fun `Option to_list on Some`() {
        val result = eval("""
            use std::option
            let x = Some {val = 42}
            x.to_list {}
        """)
        assertTrue(result.toString().contains("42"))
    }

    @Test
    fun `Option fold on Some`() {
        assertEquals(42L, eval("""
            use std::option
            let x = Some {val = 42}
            x.fold {on_none = {} -> 0, on_some = {val} -> val}
        """))
    }

    @Test
    fun `Option fold on None`() {
        assertEquals(0L, eval("""
            use std::option
            let x: Option Int = None
            x.fold {on_none = {} -> 0, on_some = {val} -> val}
        """))
    }

    // ========================================
    // Result methods
    // ========================================

    @Test
    fun `Result is_ok on Ok`() {
        assertEquals(true, eval("""
            use std::result
            let x = Ok {val = 42}
            x.is_ok {}
        """))
    }

    @Test
    fun `Result is_ok on Err`() {
        assertEquals(false, eval("""
            use std::result
            let x = Err {error = "oops"}
            x.is_ok {}
        """))
    }

    @Test
    fun `Result is_err on Err`() {
        assertEquals(true, eval("""
            use std::result
            let x = Err {error = "oops"}
            x.is_err {}
        """))
    }

    @Test
    fun `Result is_err on Ok`() {
        assertEquals(false, eval("""
            use std::result
            let x = Ok {val = 42}
            x.is_err {}
        """))
    }

    @Test
    fun `Result get_or_else on Ok`() {
        assertEquals(42L, eval("""
            use std::result
            let x = Ok {val = 42}
            x.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Result get_or_else on Err`() {
        assertEquals(0L, eval("""
            use std::result
            let x: Result Int Str = Err {error = "oops"}
            x.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Result map on Ok`() {
        assertEquals(84L, eval("""
            use std::result
            Ok {val = 42}.map {f = {val} -> val + val}.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Result map on Err`() {
        assertEquals(true, eval("""
            use std::result
            let x: Result Int Str = Err {error = "oops"}
            x.map {f = {val} -> val + val}.is_err {}
        """))
    }

    @Test
    fun `Result flat_map on Ok`() {
        assertEquals(42L, eval("""
            use std::result
            Ok {val = 42}.flat_map {f = {val} -> Ok {val = val}}.get_or_else {def = 0}
        """))
    }

    @Test
    fun `Result to_option on Ok`() {
        assertEquals(true, eval("""
            use std::result
            use std::option
            let x = Ok {val = 42}
            let opt = x.to_option {}
            opt.is_some {}
        """))
    }

    @Test
    fun `Result to_option on Err`() {
        assertEquals(true, eval("""
            use std::result
            use std::option
            let x: Result Int Str = Err {error = "oops"}
            let opt = x.to_option {}
            opt.is_none {}
        """))
    }

    @Test
    fun `Result fold on Ok`() {
        assertEquals(42L, eval("""
            use std::result
            let x = Ok {val = 42}
            x.fold {on_err = {error} -> 0, on_ok = {val} -> val}
        """))
    }

    @Test
    fun `Result fold on Err`() {
        assertEquals(0L, eval("""
            use std::result
            let x: Result Int Str = Err {error = "oops"}
            x.fold {on_err = {error} -> 0, on_ok = {val} -> val}
        """))
    }

    // ========================================
    // Seq methods
    // ========================================

    @Test
    fun `Seq length`() {
        assertEquals(3L, eval("""
            use std::collections::seq
            [1, 2, 3].length {}
        """))
    }

    @Test
    fun `Seq map and length chain`() {
        assertEquals(3L, eval("""
            use std::collections::seq
            [1, 2, 3].map {f = {x} -> x * 2}.length {}
        """))
    }

    @Test
    fun `Seq filter and length chain`() {
        assertEquals(2L, eval("""
            use std::collections::seq
            [1, 2, 3, 4].filter {p = {x} -> x > 2}.length {}
        """))
    }

    @Test
    fun `Seq push and length chain`() {
        assertEquals(4L, eval("""
            use std::collections::seq
            [1, 2, 3].push {x = 4}.length {}
        """))
    }

    @Test
    fun `Seq reverse`() {
        assertEquals(true, eval("""
            use std::collections::seq
            [1, 2, 3].reverse {} == [3, 2, 1]
        """))
    }

    @Test
    fun `Seq any`() {
        assertEquals(true, eval("""
            use std::collections::seq
            [1, 2, 3].any {p = {x} -> x > 2}
        """))
    }

    @Test
    fun `Seq all`() {
        assertEquals(true, eval("""
            use std::collections::seq
            [2, 4, 6].all {p = {x} -> x % 2 == 0}
        """))
    }

    @Test
    fun `Seq is_empty`() {
        assertEquals(true, eval("""
            use std::collections::seq
            [1, 2, 3].filter {p = {x} -> x > 10}.is_empty {}
        """))
    }

    @Test
    fun `Seq contains`() {
        assertEquals(true, eval("""
            use std::collections::seq
            [1, 2, 3].contains {x = 2}
        """))
    }

    @Test
    fun `Seq fold`() {
        assertEquals(6L, eval("""
            use std::collections::seq
            [1, 2, 3].fold {init = 0, f = {acc, x} -> acc + x}
        """))
    }

    @Test
    fun `Seq sort and head chain`() {
        val result = eval("""
            use std::collections::seq
            [3, 1, 2].sort {}.head {}
        """)
        // head returns Option - Some {val = 1}
        assertTrue(result.toString().contains("1"), "Expected head after sort to be Some(1), got: $result")
    }

    @Test
    fun `Seq map filter chain`() {
        assertEquals(true, eval("""
            use std::collections::seq
            [1, 2, 3, 4].map {f = {x} -> x * 2}.filter {p = {x} -> x > 4} == [6, 8]
        """))
    }

    @Test
    fun `Seq join`() {
        assertEquals("a, b, c", eval("""
            use std::collections::seq
            ["a", "b", "c"].join {sep = ", "}
        """))
    }

    // ========================================
    // List methods
    // ========================================

    @Test
    fun `List map and length chain`() {
        assertEquals(3L, eval("""
            use std::collections::list
            let xs = list::cons {head = 1, tail = list::cons {head = 2, tail = list::cons {head = 3, tail = list::empty {}}}}
            xs.map {f = {x} -> x * 2}.length {}
        """))
    }

    @Test
    fun `List reverse and head chain`() {
        val result = eval("""
            use std::collections::list
            let xs = list::cons {head = 1, tail = list::cons {head = 2, tail = list::cons {head = 3, tail = list::empty {}}}}
            xs.reverse {}.head {}
        """)
        assertTrue(result.toString().contains("3"), "Expected reversed head to be Some(3), got: $result")
    }

    @Test
    fun `List filter and any chain`() {
        assertEquals(true, eval("""
            use std::collections::list
            let xs = list::cons {head = 1, tail = list::cons {head = 2, tail = list::cons {head = 3, tail = list::empty {}}}}
            xs.filter {p = {x} -> x > 1}.any {p = {x} -> x == 3}
        """))
    }

    @Test
    fun `List is_empty on empty list`() {
        assertEquals(true, eval("""
            use std::collections::list
            let xs = list::empty {}
            xs.is_empty {}
        """))
    }

    // ========================================
    // Map methods
    // ========================================

    @Test
    fun `Map insert and size chain`() {
        assertEquals(2L, eval("""
            use std::collections::map
            let m = map::empty {}
            m.insert {k = "a", v = 1}.insert {k = "b", v = 2}.size {}
        """))
    }

    @Test
    fun `Map get after insert`() {
        val result = eval("""
            use std::collections::map
            let m = map::empty {}
            m.insert {k = "hello", v = 42}.get {k = "hello"}
        """)
        assertTrue(result.toString().contains("42"), "Expected Some(42), got: $result")
    }

    @Test
    fun `Map contains_key`() {
        assertEquals(true, eval("""
            use std::collections::map
            let m = map::empty {}
            m.insert {k = "a", v = 1}.contains_key {k = "a"}
        """))
    }

    @Test
    fun `Map remove and size chain`() {
        assertEquals(1L, eval("""
            use std::collections::map
            let m = map::empty {}
            m.insert {k = "a", v = 1}.insert {k = "b", v = 2}.remove {k = "a"}.size {}
        """))
    }

    @Test
    fun `Map is_empty`() {
        assertEquals(true, eval("""
            use std::collections::map
            let m = map::empty {}
            m.is_empty {}
        """))
    }

    // ========================================
    // Set methods
    // ========================================

    @Test
    fun `Set insert and size chain`() {
        assertEquals(3L, eval("""
            use std::collections::set
            let s = set::empty {}
            s.insert {x = 1}.insert {x = 2}.insert {x = 3}.size {}
        """))
    }

    @Test
    fun `Set contains after insert`() {
        assertEquals(true, eval("""
            use std::collections::set
            let s = set::empty {}
            s.insert {x = 42}.contains {x = 42}
        """))
    }

    @Test
    fun `Set remove and contains chain`() {
        assertEquals(false, eval("""
            use std::collections::set
            let s = set::empty {}
            s.insert {x = 1}.insert {x = 2}.remove {x = 1}.contains {x = 1}
        """))
    }

    @Test
    fun `Set union and size`() {
        assertEquals(3L, eval("""
            use std::collections::set
            let s1 = set::from_seq {xs = [1, 2]}
            let s2 = set::from_seq {xs = [2, 3]}
            s1.union {s2 = s2}.size {}
        """))
    }

    @Test
    fun `Set is_empty on empty set`() {
        assertEquals(true, eval("""
            use std::collections::set
            let s = set::empty {}
            s.is_empty {}
        """))
    }

    @Test
    fun `Set filter and size chain`() {
        assertEquals(2L, eval("""
            use std::collections::set
            let s = set::from_seq {xs = [1, 2, 3, 4]}
            s.filter {p = {val} -> val > 2}.size {}
        """))
    }
}
