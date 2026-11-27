package com.mezze.language.typechecker

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The typed half of the pattern rules (decided 2026-11-26/27):
 *
 *  - an unused binding says how to write it instead, by where it sits: a record
 *    field -> leave it out, end with `...`; one positional value -> `_`; a list's
 *    tail -> `...` without a name. `_` and `...` themselves bind nothing.
 *  - an arm an earlier unconditional arm already covers is V0603, a hard error.
 *  - an OPEN callback type `{ a: Int, ... } -> R` means the callee passes at least
 *    `a`, maybe more: the callback may use only `a`, and says "maybe more" with `...`.
 */
class PatternSpecTest : VariantSpecBase() {

    private fun warnings(src: String): List<String> {
        val r = check(src)
        assertTrue(r.isSuccess, "must type-check: ${r.exceptionOrNull()?.message}\n$src")
        return r.getOrThrow().warnings.map { it.message }
    }

    private fun warns(src: String, vararg fragments: String) {
        val w = warnings(src)
        assertTrue(w.any { m -> fragments.all { it in m } }, "expected a warning with ${fragments.toList()}; got $w")
    }

    private fun silent(src: String) {
        val w = warnings(src).filter { "is never used" in it }
        assertTrue(w.isEmpty(), "expected no unused-binding warning; got $w")
    }

    // ── unused bindings, by slot ────────────────────────────────────────────

    @Test fun `an unused record field - leave it out and end with dots`() = warns("""
        let f = { o: Option Int } -> match o is
          Some { val } -> 0
          None -> 1
    """, "`val` is never used", "Leave the field out", "...")

    @Test fun `an unused positional element - write underscore`() = warns("""
        let f = { xs: [Int] } -> match xs is
          [first, b] -> first
          _ -> 0
    """, "`b` is never used", "Write `_`")

    @Test fun `an unused list tail - write dots without a name`() = warns("""
        let f = { xs: [Int] } -> match xs is
          [first, ...rest] -> first
          [] -> 0
    """, "`rest` is never used", "without a name")

    @Test fun `a used list tail is silent - it is a list`() = silent("""
        let len: { xs: [Int] } -> Int = { xs } -> match xs is
          [first, ...rest] -> first + len { xs = rest }
          [] -> 0
    """)

    @Test fun `an unused string hole - write underscore`() = warns("""
        let f = { s: Str } -> match s is
          "api/$(rest)" -> 1
          _ -> 0
    """, "`rest` is never used", "Write `_`")

    @Test fun `wildcards and dots bind nothing and are never warned about`() = silent("""
        let f = { xs: [Int], s: Str, o: Option Int } -> do
          let a = match xs is
            [_, b, ...] -> b
            [..., last] -> last
            _ -> 0
          let c = match s is
            "api/$(_)" -> 1
            _ -> 0
          let d = match o is
            Some { ... } -> 1
            None -> 0
          a + c + d
    """)

    @Test fun `an unused field in a let destructuring - leave it out and end with dots`() = warns("""
        let f = { r: { a: Int, b: Int } } -> do
          let { a, b } = r
          a
    """, "`b` is never used", "Leave the field out")

    // ── unreachable arms (V0603) ────────────────────────────────────────────

    @Test fun `a payload arm after one that takes every payload`() = rejects("""
        let f = { o: Option Int } -> match o is
          Some { val } -> val
          Some { val = x } -> x
          None -> 0
    """, "V0603", "the first arm already matches every Some")

    @Test fun `an exact list after an open one that covers it`() = rejects("""
        let f = { xs: [Int] } -> match xs is
          [first, ...] -> first
          [a, b] -> a + b
          [] -> 0
    """, "V0603", "[first, ...] matches every list of 1 or more")

    @Test fun `a list after a middle rest that covers it`() = rejects("""
        let f = { xs: [Int] } -> match xs is
          [first, ..., last] -> first + last
          [a, b, c] -> a + b + c
          _ -> 0
    """, "V0603", "[first, ..., last] matches every list of 2 or more")

    @Test fun `a literal string after a hole that covers it`() = rejects("""
        let f = { s: Str } -> match s is
          "api/$(_)" -> 1
          "api/users" -> 2
          _ -> 0
    """, "V0603", "every string starting with api/ is taken by the first arm")

    @Test fun `two holes with the same shape`() = rejects("""
        let f = { s: Str } -> match s is
          "api/$(rest)" -> rest
          "api/$(_)" -> "never"
          _ -> "other"
    """, "V0603", "both match every string starting with api/")

    @Test fun `a more specific payload first is live`() = accepts("""
        let f = { o: Option Int } -> match o is
          Some { val = 1 } -> 10
          Some { val } -> val
          None -> 0
    """, "Some { val = 1 } does not cover Some { val }")

    @Test fun `an exact list first, an open one after, is live`() = accepts("""
        let f = { xs: [Int] } -> match xs is
          [a, b] -> a + b
          [first, ...] -> first
          [] -> 0
    """, "lengths 1 and 3+ still reach the second arm")

    @Test fun `a guarded arm covers nothing`() = accepts("""
        let f = { o: Option Int } -> match o is
          Some { val } if val > 0 -> val
          Some { val } -> 0 - val
          None -> 0
    """, "the guard can fail, so the second arm is live")

    @Test fun `different string prefixes are live`() = accepts("""
        let f = { s: Str } -> match s is
          "api/$(_)" -> 1
          "web/$(_)" -> 2
          _ -> 0
    """, "no prefix covers the other")

    // ── open callback types ─────────────────────────────────────────────────

    private val apply1 = "let apply1: { f: { a: Int, ... } -> Int } -> Int = { f } -> f { a = 1 }"

    @Test fun `a callback that uses only the guaranteed field and says maybe-more`() = accepts("""
        $apply1
        let g = {} -> apply1 { f = { a, ... } -> a }
    """, "{ a, ... } takes a and accepts whatever else the callee passes")

    @Test fun `a callback that needs a field the type does not guarantee`() = rejects("""
        $apply1
        let g = {} -> apply1 { f = { a, b } -> a + b }
    """, "T0211", "apply1 never passes b — this used to return a partial function where Int was promised")

    @Test fun `a closed callback pattern against an open type`() = rejects("""
        $apply1
        let g = {} -> apply1 { f = { a } -> a }
    """, "T0211", "the callee may pass more fields; a closed pattern claims the exact shape")

    @Test fun `an annotated definition needs no repeated dots - its annotation is its signature`() = accepts("""
        let my_func: { x: Int, y: Int, ... } -> Int = { x, y } -> x + y
        let g = {} -> my_func { x = 1, y = 0, z = "" }
    """, "the open annotation already says callers may pass more")

    @Test fun `an annotated definition still cannot name a field its open type lacks`() = rejects("""
        let my_func: { x: Int, ... } -> Int = { x, w } -> x + w
    """, "T0211", "w is not in the open type; callers may pass only x")

    @Test fun `a closed callback type takes exactly its fields`() = accepts("""
        let apply2: { f: { a: Int, b: Int } -> Int } -> Int = { f } -> f { a = 1, b = 2 }
        let g = {} -> apply2 { f = { a, b } -> a + b }
    """, "a closed callback type: the lambda names exactly those fields")
}
