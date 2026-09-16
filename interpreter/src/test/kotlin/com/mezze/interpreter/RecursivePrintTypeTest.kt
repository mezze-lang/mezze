package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BUG 12 — FIXED. A value used to render differently depending on how DEEP it
 * sat in a recursive type.
 *
 * `PrintTypeBuilder` cut recursion with `PrintType.Dynamic`:
 *
 *     if (!building.add(name)) return PrintType.Dynamic
 *
 * and `Dynamic` makes the printer fall back to rendering from the runtime value
 * alone. For most shapes that is indistinguishable. It is NOT for the four types
 * whose rendering cannot be recovered from the value: a newtype is erased to its
 * payload, `Nat` and `Int` are both Long, `Frac` is an ordinary two-field record,
 * and a one-character `Str` is indistinguishable from a `Char`. Those are exactly
 * the cases `PrintType` exists to disambiguate.
 *
 * So below the cut, `Money.(7)` prints as `7`.
 *
 * This also falsifies `PrintType`'s own doc, which claims:
 *
 *     "TOTAL BY CONSTRUCTION: there is deliberately no Opaque / fallback case."
 *     "[Dynamic] is NOT a fallback for concrete types: the builder emits it ONLY
 *      for a genuine VarType … Nat/Frac/Newtype/Char can only reach here behind
 *      an unconstrained type variable — i.e. never as an actual runtime value."
 *
 * The VarType claim is true and was tested (a generic `'a` carrying a newtype
 * renders correctly, because the call is monomorphized and the builder sees the
 * concrete type). The recursion cut is the second emitter, it fires for a
 * CONCRETE type, and the value below it is very much real.
 *
 * The fix is not a wider `Dynamic`. The cut now carries a BACK-REFERENCE the
 * printer follows — a cyclic TYPE still has a finite SHAPE — so no position
 * degrades to value-only rendering. `PrintType.Ref(name)` marks the cycle and
 * `PrintType.Recursive(defs, root)` carries the definitions it resolves against;
 * the wrapper appears only when a cycle was actually hit, so every
 * non-recursive shape is byte-identical to before. `InspectRenderingGoldenTest`
 * confirms that across its 617 pinned assertion sites.
 */
class RecursivePrintTypeTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val recursiveTree = """
        newtype Money = Int
        type Tree = | Node { child: Tree, amt: Money }, Leaf |
    """.trimIndent()

    @Test
    fun `a newtype ABOVE the recursion cut renders as a newtype`() {
        assertEquals(
            "Node { child = Leaf, amt = Money.(5) }",
            MezzeTestHelper.run(
                """
                $recursiveTree
                let t: Tree = Node { child = Leaf, amt = Money.(5) }
                t.to_dbg {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a newtype BELOW the recursion cut renders the same way`() {
        // The SAME value, one level deeper. Nothing about `Money.(7)` changed;
        // only its depth in the type did.
        assertEquals(
            "Node { child = Node { child = Leaf, amt = Money.(7) }, amt = Money.(5) }",
            MezzeTestHelper.run(
                """
                $recursiveTree
                let t: Tree = Node { child = Node { child = Leaf, amt = Money.(7) }, amt = Money.(5) }
                t.to_dbg {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the VarType half of the Dynamic claim holds - a generic carrying a newtype`() {
        // PrintType's doc says Dynamic is only reached behind an unconstrained
        // type variable. That half is TRUE and is pinned here so a future change
        // to monomorphization cannot quietly break it: the call is specialized,
        // so the builder sees `Money` and never emits Dynamic.
        assertEquals(
            "Money.(5)",
            MezzeTestHelper.run(
                """
                newtype Money = Int
                let show_it: { x: 'a } -> Str = { x } -> x.to_dbg {}
                show_it { x = Money.(5) }
                """.trimIndent(),
            ),
        )
    }
}
