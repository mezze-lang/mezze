package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Ord must NOT be structurally auto-derived for composite types (variant /
 * record / list / seq). The runtime Direct{LessThan,GreaterThan,Le,Ge}Node only
 * specialize long/double/String/Frac and THROW on anything else — so
 * auto-deriving Ord for a variant/record type-checked a program that crashed at
 * runtime (`a < b` on a `Color`). Found by the multi-agent soundness hunt.
 *
 * Contrast Eq: DirectEqualNode has an Objects.equals fallback reaching
 * MezzeVariant/MezzeRecord, so `==` on a variant is sound and STAYS derivable.
 *
 * The fix: `<`/`<=`/`>`/`>=` on a user variant/record now require an explicit
 * `impl Ord` (as newtypes already do); primitives still compare directly.
 */
class OrdDerivationSoundnessTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    // ── the hole: `<` on a composite type must be REJECTED ───────────────

    @Test fun `less-than on a payload-less variant is rejected`() {
        assertNotNull(err("""
            type Color = | Red, Green, Blue |
            let f = {} -> do
              let a: Color = Red
              let b: Color = Green
              a < b
        """), "`<` on a variant with no Ord impl must be rejected (runtime would crash)")
    }

    @Test fun `less-than on an all-primitive record is rejected`() {
        assertNotNull(err("""
            type Pt = { x: Int, y: Int }
            let f = {} -> do
              let a: Pt = { x = 1, y = 2 }
              let b: Pt = { x = 3, y = 4 }
              a < b
        """))
    }

    @Test fun `greater-than on a variant is rejected`() {
        assertNotNull(err("""
            type Color = | Red, Green, Blue |
            let f = { a: Color, b: Color } -> a > b
        """))
    }

    @Test fun `less-than-or-equal on a record is rejected`() {
        assertNotNull(err("""
            type Pt = { x: Int, y: Int }
            let f = { a: Pt, b: Pt } -> a <= b
        """))
    }

    // NOTE: `<` on a Seq/List (`[Color] < [Color]`) routes through a separate
    // native compare (__native_seq_compare), NOT DirectLessThanNode, so its
    // soundness is a distinct question (element Ord vs whole-seq compare) —
    // deliberately not asserted here; see task #201 notes.

    // ── HOLE C: an OPEN record's Ord obligation must not be silently dropped ──
    // A closed record `{ x: Int }` was already rejected above. An OPEN record
    // `{ x: Int, ... }` carries a rowVar, so it is not `isConcrete` — the ability
    // obligation deferred as "not yet concrete" and was then PERMANENTLY skipped
    // in validateAbilityConstraints (the same defer-drop family as #213). The fix
    // closes open records before the concreteness test: extra hidden fields can
    // never supply a structural-ability impl the written shape lacks, so testing
    // the closed shape is sound. `<` on an open record is now rejected too.

    @Test fun `less-than on an OPEN record is rejected (HOLE C)`() {
        assertNotNull(err("""
            let f: { r: { a: Int, ... } } -> Bool = { r } -> r < r
        """), "an open record has no Ord impl; its obligation must not be dropped just because the row is open")
    }

    @Test fun `less-than on a nested OPEN record field is rejected`() {
        // The inner open record `{ b: Int, ... }` is what `<` is applied to; its
        // Ord obligation must survive being nested inside another open record.
        assertNotNull(err("""
            let f: { r: { a: { b: Int, ... }, ... } } -> Bool = { r } -> r.a < r.a
        """))
    }

    @Test fun `greater-than-or-equal on an OPEN record is rejected`() {
        assertNotNull(err("""
            let f: { r: { a: Int, b: Str, ... } } -> Bool = { r } -> r >= r
        """))
    }

    @Test fun `equality on an OPEN record is still accepted`() {
        // Eq stays derivable for records (DirectEqualNode's structural fallback),
        // whether the record is open or closed — closing only strips the row, so
        // the visible fields (all Eq-able) still derive. Must not over-reject.
        assertNull(err("""
            let f: { r: { a: Int, ... } } -> Bool = { r } -> r == r
        """), "== on an open record stays sound; closing the row must not break it")
    }

    // ── still sound: Eq on a variant works ───────────────────────────────

    @Test fun `equality on a payload-less variant is still accepted`() {
        assertNull(err("""
            type Color = | Red, Green, Blue |
            let f = { a: Color, b: Color } -> a == b
        """), "== on a variant stays derivable (DirectEqualNode has a structural fallback)")
    }

    @Test fun `equality on an all-primitive record is still accepted`() {
        assertNull(err("""
            type Pt = { x: Int, y: Int }
            let f = { a: Pt, b: Pt } -> a == b
        """))
    }

    // ── still works: primitives compare, explicit impl allows it ─────────

    @Test fun `less-than on primitives still works`() {
        assertNull(err("let f = { a: Int, b: Int } -> a < b"))
    }

    @Test fun `less-than on strings still works`() {
        assertNull(err("let f = { a: Str, b: Str } -> a < b"))
    }

    @Test fun `an explicit Ord impl makes a variant comparable`() {
        // With a user impl, `<` on the variant is fine — the impl provides the
        // runtime path. Ord's methods are lt/le/gt/ge with params { it, r }.
        assertNull(err("""
            use std::abilities::compare::{ Ord }
            type Color = | Red, Green, Blue |
            impl Ord for Color where {
              lt = { it, r } -> True,
              le = { it, r } -> True,
              gt = { it, r } -> False,
              ge = { it, r } -> False
            }
            let f = { a: Color, b: Color } -> a.lt { r = b }
        """))
    }
}
