package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BUG 11 — a GENERIC `with Ord 'b` body compares a newtype by its WRAPPED order.
 *
 * Found 2026-09-16 while probing C.3's evidence selection. The probe was looking
 * for a wrong ref being picked among several; what it found instead is that even
 * a SINGLE constraint gets this wrong.
 *
 * `B` wraps an Int and its `Ord` is deliberately REVERSED, so `B.(1) < B.(3)` is
 * False. Every concrete path agrees. Only the generic one does not:
 *
 * | path                                                  | answer |
 * |-------------------------------------------------------|--------|
 * | `B.(1) < B.(3)` — operator, concrete                   | false  |
 * | `B.(1).lt { r = B.(3) }` — method, concrete            | false  |
 * | `let f: { x: B, y: B } -> Bool = …` — concrete annot.  | false  |
 * | `[B].min {}` — through seq (BUG 7's fix)               | 3      |
 * | `let g: { x: 'b, y: 'b } with Ord 'b -> Bool = …`      | **true** |
 *
 * This is silent wrongness, not a crash — the worst kind, and the same shape as
 * BUG 7 and BUG 10. A newtype is erased to its wrapped value at runtime, so the
 * primitive comparison agrees with the WRAPPED type's ordering and quietly
 * contradicts the one the author wrote.
 *
 * WHERE IT COMES FROM. Compiling the impl op shows the generic's `<` lowered to
 * `CallIndirectIR(ReadFieldIR(x, "lt"), { it = x, r = y })` — a DYNAMIC field
 * read named `lt` off `x`, which at runtime is a raw long. The compiler emits
 * that shape when the receiver has no type, and the receiver has no type because
 * of `TypedAstBuilder`'s binding fallback:
 *
 *     else structuralBindings[name] ?: MezzeType.VarType(TypeVar.freshWithName(name))
 *
 * When neither the solver nor the structural read supplies a type, the binding
 * gets a FRESH VARIABLE — and a fresh variable is indistinguishable from a
 * legitimately polymorphic one, so nothing downstream can tell that the compiler
 * simply did not know. Instrumenting that site shows real cases reaching it with
 * `solver=[] structural=[]`.
 *
 * That fallback is exactly what task **A.2** removes ("builder reads types by
 * NodeId; a miss is an ElaborationError, never a fresh var"). This is its first
 * demonstrated silent-wrongness case, and should be its acceptance test. The
 * structural read is additionally gated on `typingImplOpBody`, which is false on
 * this path, so both halves need attention.
 *
 * The concrete cases below are enabled and guard what already works.
 */
class GenericNewtypeOrdTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    /** `B` wraps an Int; its Ord is REVERSED, so `lt` is `>` on the payload. */
    private val reversedNewtype = """
        use std::abilities::compare::{ Ord }
        newtype B = Int
        impl Ord for B where {
          lt = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x > y,
          le = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x >= y,
          gt = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x < y,
          ge = { it, r } -> match it is
            B.(x) -> match r is
              B.(y) -> x <= y
        }
    """.trimIndent()

    // ── What already works ────────────────────────────────────────────────

    @Test
    fun `the operator on two concrete newtypes uses the impl`() {
        assertEquals(false, MezzeTestHelper.run("$reversedNewtype\nB.(1) < B.(3)"))
    }

    @Test
    fun `the method call on two concrete newtypes uses the impl`() {
        assertEquals(false, MezzeTestHelper.run("$reversedNewtype\nB.(1).lt { r = B.(3) }"))
    }

    @Test
    fun `a concretely annotated function uses the impl`() {
        assertEquals(
            false,
            MezzeTestHelper.run(
                """
                $reversedNewtype
                let f: { x: B, y: B } -> Bool = { x, y } -> x < y
                f { x = B.(1), y = B.(3) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `seq min over the newtype uses the impl`() {
        // Reversed Ord, so the minimum is the LARGEST wrapped value.
        assertEquals(
            3L,
            MezzeTestHelper.run(
                """
                $reversedNewtype
                use std::collections::seq
                let bs: [B] = [B.(1), B.(3)]
                match bs.min {} is
                  Some { val = v } -> match v is
                    B.(n) -> n
                  None -> 0
                """.trimIndent(),
            ),
        )
    }

    // ── BUG 11 ────────────────────────────────────────────────────────────

    @Test
    fun `a generic with one Ord constraint uses the impl`() {
        assertEquals(
            false,
            MezzeTestHelper.run(
                """
                $reversedNewtype
                let only_b: { x: 'b, y: 'b } with Ord 'b -> Bool = { x, y } -> x < y
                only_b { x = B.(1), y = B.(3) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a generic with two Ord constraints uses each variable's impl`() {
        assertEquals(
            false,
            MezzeTestHelper.run(
                """
                $reversedNewtype
                newtype A = Int
                impl Ord for A where {
                  lt = { it, r } -> match it is
                    A.(x) -> match r is
                      A.(y) -> x < y,
                  le = { it, r } -> match it is
                    A.(x) -> match r is
                      A.(y) -> x <= y,
                  gt = { it, r } -> match it is
                    A.(x) -> match r is
                      A.(y) -> x > y,
                  ge = { it, r } -> match it is
                    A.(x) -> match r is
                      A.(y) -> x >= y
                }
                let cmp_b: { seed: 'a, x: 'b, y: 'b } with Ord 'a, Ord 'b -> Bool =
                  { seed, x, y } -> x < y
                cmp_b { seed = A.(0), x = B.(1), y = B.(3) }
                """.trimIndent(),
            ),
        )
    }
}
