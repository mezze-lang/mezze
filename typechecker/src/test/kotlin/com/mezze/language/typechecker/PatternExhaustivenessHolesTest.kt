package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Match-exhaustiveness holes found by the multi-agent soundness hunt (#203), now
 * FIXED. Two axes were unsound and are now checked by MatchValidator:
 *
 *  - Nested-variant payloads: `Wrap { inner = X }` covers X but not Y
 *    (`nestedVariantExhaustivenessErrors`, commit 6003125b6).
 *  - Seq/list length: a match must cover every length. Exhaustive iff a catch-all
 *    (`_`/var/`[...all]`/`[..._]`) OR (`[]` plus a one-leading-element open-cons
 *    `[x, ...rest]`/`[x, ..._]`). Fixed-length patterns (`[a]`, `[a, b]`) cover
 *    only that one length (`seqExhaustivenessErrors`).
 *
 * Each `is rejected` test pins a real non-exhaustive program that used to
 * type-check and crash at runtime. The `is accepted` tests pin the boundary
 * cases a fix must not break (they must stay total).
 */
class PatternExhaustivenessHolesTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── KNOWN HOLES (currently accepted; should be rejected) ─────────────

    @Test fun `nested variant field non-exhaustive is rejected`() {
        // Wrap { inner = X } covers X but not Y — crashes on Wrap { inner = Y }.
        // FIXED #203: nested-variant exhaustiveness now checked.
        val e = err("""
            type Inner = | X, Y |
            type Outer = | Wrap { inner: Inner } |
            let f = { o: Outer } ->
              match o is
                Wrap { inner = X } -> 1
        """)
        assertNotNull(e, "#203: nested variant `inner` missing `Y` must be rejected")
        assertTrue(e!!.contains("Y"), "error should name the missing inner constructor Y: $e")
    }

    @Test fun `nested variant covering both inner constructors across arms is accepted`() {
        // Two arms, each pinning inner to one ctor — together exhaustive.
        assertNull(err("""
            type Inner = | X, Y |
            type Outer = | Wrap { inner: Inner } |
            let f = { o: Outer } ->
              match o is
                Wrap { inner = X } -> 1
                Wrap { inner = Y } -> 2
        """))
    }

    @Test fun `multi-constructor outer with one nested miss is rejected`() {
        // Both outer ctors covered, but B's nested `k` misses `Off`.
        assertNotNull(err("""
            type Switch = | On, Off |
            type Box = | Plain, Tagged { k: Switch } |
            let f = { b: Box } ->
              match b is
                Plain -> 0
                Tagged { k = On } -> 1
        """), "#203: outer covered but Tagged's `k` missing `Off`")
    }

    @Test fun `nested variant field with a scalar sibling is rejected`() {
        assertNotNull(err("""
            type Choice = | A, B |
            type Pair = | P { l: Choice, r: Int } |
            let f = { p: Pair } ->
              match p is
                P { l = A, r = n } -> n
        """), "#203: nested variant `l` missing `B` must be rejected")
    }

    @Test fun `seq match without an empty arm is rejected`() {
        // `[x, ...rest]` covers length >= 1 but not the empty seq. FIXED #203.
        val e = err("""
            let head = { xs: [Int] } ->
              match xs is
                [x, ...rest] -> x
        """)
        assertNotNull(e, "#203: seq match missing `[]` must be rejected")
        assertTrue(e!!.contains("[]"), "error should name the missing empty seq: $e")
    }

    @Test fun `fixed-length seq pattern treated as total is rejected`() {
        // `[a, b]` covers only length 2 — misses `[]` and every other length. FIXED #203.
        assertNotNull(err("""
            let both = { xs: [Int] } ->
              match xs is
                [a, b] -> a + b
        """), "#203: a single fixed-length seq pattern is not exhaustive")
    }

    // ── boundary cases that MUST stay accepted (a fix must not break these) ─

    @Test fun `nested variant fully covered is accepted`() {
        assertNull(err("""
            type Inner = | X, Y |
            type Outer = | Wrap { inner: Inner } |
            let f = { o: Outer } ->
              match o is
                Wrap { inner = X } -> 1
                Wrap { inner = Y } -> 2
        """))
    }

    @Test fun `nested variant with a wildcard payload is accepted`() {
        assertNull(err("""
            type Inner = | X, Y |
            type Outer = | Wrap { inner: Inner } |
            let f = { o: Outer } ->
              match o is
                Wrap { inner = _ } -> 1
        """))
    }

    @Test fun `nested variant binding the whole inner value is accepted`() {
        assertNull(err("""
            type Inner = | X, Y |
            type Outer = | Wrap { inner: Inner } |
            let f = { o: Outer } ->
              match o is
                Wrap { inner = i } -> 1
        """))
    }

    @Test fun `seq match with empty and cons arms is accepted`() {
        assertNull(err("""
            let head = { xs: [Int] } ->
              match xs is
                [] -> 0
                [x, ...rest] -> x
        """))
    }

    // ── #203 seq-length exhaustiveness: the length rule in detail ────────

    @Test fun `seq match with a wildcard catch-all is accepted`() {
        assertNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [a, b] -> a + b
                _ -> 0
        """))
    }

    @Test fun `seq match with a whole-seq underscore-rest is accepted`() {
        // `[..._]` binds nothing and matches any length — a catch-all.
        assertNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [..._] -> 0
        """))
    }

    @Test fun `seq match with a whole-seq named rest is accepted`() {
        assertNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [...all] -> 0
        """))
    }

    @Test fun `seq match empty plus underscore-cons is accepted`() {
        assertNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [] -> 0
                [x, ..._] -> x
        """))
    }

    @Test fun `two refutable-head cons arms covering the element type are accepted`() {
        // `[Some x, ...t]` + `[None, ...t]` jointly cover every non-empty seq; the
        // length check must not reject this idiom (it reasons about length only).
        assertNull(err("""
            use std::collections::seq
            let f = { xs: [Option Int] } ->
              match xs is
                [] -> 0
                [Some { val = x }, ...t] -> x
                [None, ...t] -> 0
        """))
    }

    @Test fun `empty plus a two-element open cons still misses length one`() {
        // `[a, b, ...rest]` covers length >= 2, so length 1 is uncovered.
        assertNotNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [] -> 0
                [a, b, ...rest] -> a + b
        """), "#203: `[a, b, ...rest]` covers len>=2, missing length 1")
    }

    @Test fun `only a guarded cons arm is not exhaustive`() {
        // A guard can fail at runtime, so `[x, ...rest] if ...` covers nothing on
        // its own — even with `[]`, some non-empty seq falls through.
        assertNotNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [] -> 0
                [x, ...rest] if x > 0 -> x
        """), "#203: a guarded cons arm can't be relied on for coverage")
    }

    @Test fun `list type gets the same length exhaustiveness check`() {
        // The check applies to List 'a as well as Seq 'a.
        assertNotNull(err("""
            let f = { xs: List Int } ->
              match xs is
                [a, b] -> a + b
        """), "#203: fixed-length pattern on a List is not exhaustive")
    }

    // ── #102: the ELEMENT axis ───────────────────────────────────────────
    //
    // The length check above reasons about how MANY elements a seq has. It said
    // nothing about WHICH elements the open-cons heads accept, and a seq long
    // enough that no fixed-length arm applies is matched only through a head. So
    // `[]` + `[Some x, ...rest]` covered every length and still fell off the end
    // of the match on `[None]` — it type-checked and died at runtime with
    // "no variant tag matched — typechecker exhaustiveness guarantee violated".

    @Test fun `a refutable open-cons head that misses a constructor is rejected`() {
        val e = err("""
            use std::collections::seq
            let f = { xs: [Option Int] } ->
              match xs is
                [] -> 0
                [Some { val = x }, ...rest] -> x
        """)
        assertNotNull(e, "#102: `[Some x, ...rest]` does not cover `[None]`")
        assertTrue(e!!.contains("None"), "error should name the missing element constructor: $e")
    }

    @Test fun `a user variant element with an uncovered constructor is rejected`() {
        val e = err("""
            type Tok = | Word { s: Str }, Space, Punct |
            let f = { xs: [Tok] } ->
              match xs is
                [] -> 0
                [Word { s = w }, ...rest] -> 1
                [Space, ...rest] -> 2
        """)
        assertNotNull(e, "#102: `Punct` is never covered by a head")
        assertTrue(e!!.contains("Punct"), "error should name Punct: $e")
    }

    @Test fun `heads covering every constructor across arms stay accepted`() {
        // The idiom the length check was careful not to break, now also carried
        // through the element check: jointly total heads are total.
        assertNull(err("""
            type Tok = | Word { s: Str }, Space, Punct |
            let f = { xs: [Tok] } ->
              match xs is
                [] -> 0
                [Word { s = w }, ...rest] -> 1
                [Space, ...rest] -> 2
                [Punct, ...rest] -> 3
        """))
    }

    @Test fun `an irrefutable head covers every element`() {
        assertNull(err("""
            use std::collections::seq
            let f = { xs: [Option Int] } ->
              match xs is
                [] -> 0
                [h, ...rest] -> 1
        """))
    }

    @Test fun `a catch-all arm settles the element axis too`() {
        // A trailing `_` matches any seq whatever its elements, so a refutable
        // head alongside it is fine.
        assertNull(err("""
            use std::collections::seq
            let f = { xs: [Option Int] } ->
              match xs is
                [Some { val = x }, ...rest] -> x
                other -> 0
        """))
    }

    @Test fun `a non-enumerable element type is not judged`() {
        // `[1, ...rest]` does not cover `[2]`, but deciding when a set of literal
        // heads is total is the non-enumerable-primitive question, not this one.
        // Pinned so the conservatism is a recorded decision, not an accident.
        assertNull(err("""
            let f = { xs: [Int] } ->
              match xs is
                [] -> 0
                [1, ...rest] -> 1
        """))
    }

    @Test fun `a guarded head does not count toward element coverage`() {
        // The guard can fail, so `[None, ...t] if …` covers no element; `None` is
        // still missing. Same rule the length axis already applies.
        val e = err("""
            use std::collections::seq
            let f = { xs: [Option Int] } ->
              match xs is
                [] -> 0
                [Some { val = x }, ...rest] -> x
                [None, ...rest] if 1 > 0 -> 0
        """)
        assertNotNull(e, "#102: a guarded arm can't be relied on for element coverage")
        assertTrue(e!!.contains("None"), "error should name None: $e")
    }
}
