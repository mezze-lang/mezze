package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * KNOWN OPEN HOLES in match exhaustiveness, found by the multi-agent soundness
 * hunt (task #203). These programs TYPE-CHECK today but are non-exhaustive and
 * crash at runtime on the uncovered input. Exhaustiveness analysis
 * (MatchValidator.validateParsedExhaustiveness) currently only checks the
 * TOP-LEVEL constructor set — it does not recurse into a constructor's payload
 * (nested variant fields), and it does not reason about seq/list length at all.
 *
 * The `holeStillOpen` tests assert the CURRENT (buggy) behavior so the hole is
 * documented and tracked; when the fix lands, flip each to `assertNotNull(err)`.
 * The `alreadySound` tests pin the boundary cases that must keep working.
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

    @Test fun `HOLE seq match without an empty arm is currently accepted`() {
        assertNull(err("""
            let head = { xs: [Int] } ->
              match xs is
                [x, ...rest] -> x
        """), "KNOWN HOLE #203: seq-length exhaustiveness not checked yet")
    }

    @Test fun `HOLE fixed-length seq pattern treated as total is currently accepted`() {
        assertNull(err("""
            let both = { xs: [Int] } ->
              match xs is
                [a, b] -> a + b
        """), "KNOWN HOLE #203: seq-length exhaustiveness not checked yet")
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
}
