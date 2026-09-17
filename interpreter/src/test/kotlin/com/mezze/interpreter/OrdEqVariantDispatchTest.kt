package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * #13: a user `impl Ord`/`impl Eq for` a VARIANT must be dispatched by the
 * comparison/equality operators. The operand of `Low < High` desugars to
 * `Low.lt { r = High }`, but the variant literal reached emit carrying an
 * unresolved orphan type var, so dispatch fell through to the native
 * comparator — which THREW for Ord (`Cannot compare MezzeVariant`) and
 * silently used STRUCTURAL equality for Eq (ignoring the user impl).
 *
 * Fix (TypedASTCompiler): recover the concrete VariantType from the nullary
 * constructor (unambiguous ctor only) for the dispatch lookup. Routed through
 * the normal ability-op resolution, so a variant WITHOUT a user impl still
 * resolves to null and keeps its derived structural default.
 */
class OrdEqVariantDispatchTest {
    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private val ordImpl = """
        use std::abilities::compare::{ Ord, Hash }
        type Priority = | Low, Mid, High |
        let rank = { p: Priority } -> match p is
          Low -> 0
          Mid -> 1
          High -> 2
        impl Ord for Priority where {
          lt = { it, r } -> rank { p = it } < rank { p = r },
          le = { it, r } -> rank { p = it } <= rank { p = r },
          gt = { it, r } -> rank { p = it } > rank { p = r },
          ge = { it, r } -> rank { p = it } >= rank { p = r },
        }
    """.trimIndent()

    @Test
    fun `Ord impl on a variant dispatches for less-than (true case)`() {
        assertEquals(true, eval("$ordImpl\nLow < High"))
    }

    @Test
    fun `Ord impl on a variant dispatches for less-than (false case)`() {
        assertEquals(false, eval("$ordImpl\nHigh < Low"))
    }

    @Test
    fun `Ord impl on a variant dispatches for less-or-equal`() {
        assertEquals(true, eval("$ordImpl\nMid <= Mid"))
    }

    @Test
    fun `Ord impl on a variant dispatches for greater-than`() {
        assertEquals(true, eval("$ordImpl\nHigh > Low"))
    }

    @Test
    fun `Eq impl on a newtype dispatches to the user impl, not structural equals`() {
        // A variant DERIVES Eq (a hand-written impl on it is a duplicate), so the
        // legal home of a non-structural Eq is a newtype. The impl returns True for
        // ALL pairs; a structural fallback would give False for 1 vs 2 — so a True
        // result proves the impl dispatched.
        assertEquals(true, eval("""
            use std::abilities::compare::{ Eq, Hash }
            newtype Color = Int
            impl Hash for Color where {
              hash = { it } -> 0
            }
            impl Eq for Color where {
              eq = { it, r } -> True,
              neq = { it, r } -> False,
            }
            Color.(1) == Color.(2)
        """.trimIndent()))
    }

    @Test
    fun `a variant WITHOUT an Eq impl keeps structural equality — equal`() {
        assertEquals(true, eval("""
            type Color = | Red, Green |
            Red == Red
        """.trimIndent()))
    }

    @Test
    fun `a variant WITHOUT an Eq impl keeps structural equality — unequal`() {
        assertEquals(false, eval("""
            type Color = | Red, Green |
            Red == Green
        """.trimIndent()))
    }

    @Test
    fun `primitive comparison is unaffected`() {
        assertEquals(true, eval("1 < 2"))
        assertEquals(false, eval("2 < 1"))
        assertEquals(true, eval("1 == 1"))
        assertEquals(true, eval("\"a\" < \"b\""))
    }

    // #18 follow-up: a comparison inside a GENERIC `with Ord 'a` body (the
    // receiver is a still-generic `'a`, dispatched through a runtime dict)
    // must reach the user variant impl — not fall through to the native
    // comparator (`Cannot compare MezzeVariant`) or hand the dict slot an
    // empty record. The direct (non-HOF) form is what this fix covers: the
    // `inlineableOps` guard used to exclude `lt`/`gt`/`eq` from the VarType
    // dict-passing branch entirely, so a `with Ord 'a` helper never requested
    // a `__dict_lt` and its `a < b` crashed at runtime on a variant operand.

    @Test
    fun `comparison inside a generic with-Ord body dispatches on a variant`() {
        // `smaller` is generic over `'a with Ord 'a`; the `a < b` receiver is a
        // dict-param'd `'a`. Called with variant operands, it must use the user
        // `impl Ord for Priority`. High > Low so the smaller is Low.
        assertEquals("Low", eval("""
            $ordImpl
            let smaller : { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } ->
              if a < b then a else b
            (smaller { a = High, b = Low }).to_dbg {}
        """.trimIndent()))
    }

    @Test
    fun `comparison inside a generic with-Ord body — reversed operands`() {
        assertEquals("Low", eval("""
            $ordImpl
            let smaller : { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } ->
              if a < b then a else b
            (smaller { a = Low, b = High }).to_dbg {}
        """.trimIndent()))
    }

    @Test
    fun `greater-than inside a generic with-Ord body dispatches on a variant`() {
        assertEquals("High", eval("""
            $ordImpl
            let larger : { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } ->
              if a > b then a else b
            (larger { a = High, b = Low }).to_dbg {}
        """.trimIndent()))
    }

    @Test
    fun `equality inside a generic with-Eq body dispatches to the user impl`() {
        // The Eq impl reports ALL Colors equal; a structural fallback would say
        // 1 != 2. A generic `with Eq 'a` helper calling `a == b` must use the
        // impl — so Color.(1) vs Color.(2) comes back True.
        assertEquals(true, eval("""
            use std::abilities::compare::{ Eq, Hash }
            newtype Color = Int
            impl Hash for Color where {
              hash = { it } -> 0
            }
            impl Eq for Color where {
              eq = { it, r } -> True,
              neq = { it, r } -> False,
            }
            let same : { a: 'a, b: 'a } with Eq 'a -> Bool = { a, b } -> a == b
            same { a = Color.(1), b = Color.(2) }
        """.trimIndent()))
    }
}
