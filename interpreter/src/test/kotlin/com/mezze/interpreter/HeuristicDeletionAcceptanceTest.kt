package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Task 0.3 — the acceptance criteria for deleting the compiler's guess-back
 * heuristics.
 *
 * `TypedASTCompiler` carries a set of functions that reconstruct a type the
 * solver did not write down: each covers one shape that would otherwise reach
 * dispatch with nothing to dispatch on. Task A.4 deletes them once elaboration
 * carries types properly. Deleting one today would be a blind change — the
 * heuristics are named in comments and nothing says which program stops working
 * when a given one goes.
 *
 * This file is that mapping, one test per heuristic, each named after it. Every
 * test runs a program whose ANSWER depends on the right instance being chosen —
 * a newtype whose `eq` deliberately ignores a field, so a structural fallback
 * gives a different, observable result rather than quietly working. Delete a
 * heuristic, run the test named after it, and you learn whether elaboration now
 * covers its shape.
 *
 * Some shapes are also covered by the container and element-instance suites.
 * That overlap is intentional: the value here is the explicit one-to-one map, so
 * A.4 can proceed heuristic by heuristic instead of deleting and hoping.
 */
class HeuristicDeletionAcceptanceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /**
     * A newtype whose Eq compares ONLY `x`, so `P.(1)` and `P.(99)` are equal by
     * its own instance and different by any structural fallback. Every program
     * below ends in a comparison that answers True only if P's instance was the
     * one used.
     */
    private val pWithPartialEq = """
        use std::abilities::compare::{ Eq, Ord, Hash }
        pub newtype P = Int
        impl Hash for P where {
          hash = { it } -> match it is P.(a) -> a % 10
        }
        impl Eq for P where {
          eq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 == b % 10,
          neq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 != b % 10
        }
    """.trimIndent()

    private fun assertTrue(source: String) =
        assertEquals(true, MezzeTestHelper.run(source))

    @Test
    fun `control - the fixture's instance actually discriminates`() {
        // Every test below asserts True, which is only evidence if P's `eq` is not
        // simply always True and a structural comparison would not also say True.
        // Directly at the newtype, where no heuristic is involved:
        //   P.(1) vs P.(91)  → P's eq says equal (1 == 91 mod 10), structural says not
        //   P.(1) vs P.(2)   → both say not equal
        assertTrue("$pWithPartialEq\nP.(1) == P.(91)")
        assertEquals(false, MezzeTestHelper.run("$pWithPartialEq\nP.(1) == P.(2)"))
    }

    // ── TypedASTCompiler.resolveVariantReceiverType ──────────────────────────

    @Test
    fun `resolveVariantReceiverType - a variant construct used directly as a receiver`() {
        // The receiver is a payload-carrying construct whose own type never
        // solved. The heuristic recovers the declared variant AND binds its
        // parameter from the actual payload (`W P`, not a bare `W 'a`), which is
        // what lets the element's dict be selected as well as the impl.
        assertTrue(
            """
            $pWithPartialEq
            type W = | Wrap { p: P } |
            Wrap { p = P.(1) } == Wrap { p = P.(91) }
            """.trimIndent(),
        )
    }

    @Test
    fun `resolveVariantReceiverType - Option and Result have no typed declaration behind them`() {
        // Some/Ok take the other branch: they are compiler-known synthetic
        // variants, so the heuristic rebuilds the nominal application by hand.
        assertTrue(
            """
            $pWithPartialEq
            let a: Option P = Some { val = P.(1) }
            let b: Option P = Some { val = P.(91) }
            a == b
            """.trimIndent(),
        )
    }

    // ── TypedASTCompiler.extractConcreteElementTypeFromExpr ──────────────────

    @Test
    fun `extractConcreteElementTypeFromExpr - a literal whose element type did not solve`() {
        // The seq's TYPE has an unresolved element variable; the ELEMENTS
        // themselves carry concrete types, and the heuristic reads the element
        // type back off them.
        assertTrue(
            """
            $pWithPartialEq
            [P.(1), P.(2)] == [P.(91), P.(92)]
            """.trimIndent(),
        )
    }

    // ── TypedASTCompiler structuralElem (the nested-container binding) ───────

    @Test
    fun `a nested container binds the INNER container's instance - structuralElem DELETED`() {
        // The flat pile of candidate types mixes levels, and first-match lands one
        // level too deep: `[[P]]` bound P's eq instead of eq-for-`[P]`, so the
        // comparator was handed a P where it expected a seq. This is the shape
        // that forced dict selection to go by structure rather than by bag.
        assertTrue(
            """
            $pWithPartialEq
            [[P.(1)], [P.(2)]] == [[P.(91)], [P.(92)]]
            """.trimIndent(),
        )
    }

    // ── TypedASTCompiler structuralConstraintTypes ──────────────────────────

    @Test
    fun `structuralConstraintTypes - an impl whose TARGET is a container pattern`() {
        // Reached through a record field, where the op's declared arrow is
        // laundered (its params are variables named after the record's fields), so
        // the only surviving structure is the impl's target pattern `['a]`. The
        // field's actual type has to be unified against THAT.
        assertTrue(
            """
            $pWithPartialEq
            type Box = { items: [P] }
            let b1: Box = { items = [P.(1), P.(2)] }
            let b2: Box = { items = [P.(91), P.(92)] }
            b1.items == b2.items
            """.trimIndent(),
        )
    }

    // ── TypedASTCompiler.bindConstraintArgsViaArg ───────────────────────────

    @Test
    fun `bindConstraintArgsViaArg - the constraint is on the KEY, not the container`() {
        // `min_by` carries `with Ord 'b` on the key function's RESULT. The module
        // substitution never solves that generalized 'b, and guessing from the
        // argument types finds the container first — where `['a]` falsely matches
        // the blanket container impl. The binding has to come from unifying the
        // callee's parameter record against the actual argument.
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                type R = { k: Int, tag: Int }
                let rows: [R] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }, { k = 2, tag = 20 }]
                match rows.min_by { f = { x } -> x.k } is
                  Some { val = r } -> r.k
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    // ── TypedASTCompiler.recoverLambdaReturnType ────────────────────────────

    @Test
    fun `recoverLambdaReturnType - the key function is the identity`() {
        // `{ x } -> x` returns its own parameter, so the lambda's stamped return
        // type is a fresh variable and the key type has to be recovered from the
        // parameter instead. Fixed earlier in this effort; the shape stays here so
        // A.4 knows which program proves the recovery is no longer needed.
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                use std::collections::seq
                let xs: [Int] = [3, 1, 2]
                match xs.min_by { f = { x } -> x } is
                  Some { val = v } -> v
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    // ── TypedASTCompiler.containerElementType ───────────────────────────────

    @Test
    fun `containerElementType - the container arrives in its OTHER representation`() {
        // A container is either a dedicated node (`ListType`) or
        // `TypeApplication("List", args)`. Only the first was handled, so
        // `List P == List P` produced no element type at all, element-first dict
        // resolution could not descend, and no element dict was injected. This is
        // the shape task B.1 removes by making one representation canonical.
        assertTrue(
            """
            $pWithPartialEq
            use std::collections::list::{ from_seq }
            let a: List P = from_seq { xs = [P.(1), P.(2)] }
            let b: List P = from_seq { xs = [P.(91), P.(92)] }
            a == b
            """.trimIndent(),
        )
    }

    // ── TypedASTCompiler.specBoundReceiverType / resolveDictFromSpecTypeArgs ─

    @Test
    fun `a receiver typed by the impl's OWN variable inside a spec - specBoundReceiverType DELETED`() {
        // This case still has to work, but the heuristic named in the original
        // title is GONE (A.4). `specBoundReceiverType` bound such a receiver
        // through the instance head, `Result 'a 'e` against `Result P Str`.
        //
        // Measured deletable: with it neutralised, BOTH gates stay green, so
        // something upstream already pins this receiver — C.1's per-spec evidence
        // is the likely reason, since it landed after the heuristic was written.
        // The test stays as the guard that the case keeps working without it.
        assertTrue(
            """
            $pWithPartialEq
            let a: Result P Str = Ok { val = P.(1) }
            let b: Result P Str = Ok { val = P.(91) }
            a == b
            """.trimIndent(),
        )
    }

    @Test
    fun `resolveDictFromSpecTypeArgs - the last resort inside a spec still picks the element`() {
        // The final fallback expands the spec's type arguments element-first so the
        // ELEMENT's impl is preferred over the container's. A seq of the wrapped
        // type inside a Result exercises both levels at once.
        assertTrue(
            """
            $pWithPartialEq
            let a: Result [P] Str = Ok { val = [P.(1), P.(2)] }
            let b: Result [P] Str = Ok { val = [P.(91), P.(92)] }
            a == b
            """.trimIndent(),
        )
    }
}
