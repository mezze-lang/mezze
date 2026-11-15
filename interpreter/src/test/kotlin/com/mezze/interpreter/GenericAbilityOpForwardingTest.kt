package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * An ability operation carrying its OWN type parameter, FORWARDED to another such
 * operation — the half of BUG 42 that direct dispatch did not cover.
 *
 * ```mezze
 * direct: { it: A, x: 'p } with Show 'p -> Int = { it, x } -> x.show {}
 * fwd:    { it: A, x: 'p } with Show 'p -> Int = { it, x } -> it.direct { x = x }
 * ```
 *
 * `a.direct { x = b }` was right and `a.fwd { x = b }` answered 1 — `Show A`, the
 * RECEIVER's impl, where `Show B` (2) is correct. Silently wrong, on the
 * interpreter AND the monomorphised build.
 *
 * TWO causes, and the first is why instrumenting beat reasoning. `fwd` compiled
 * with `params=[it, x]` — NO `__dict_show` to forward at all:
 *
 *   - `dictMethodsForHash` for an impl op came from SCANNING THE BODY for method
 *     calls on a VarType receiver. `direct` calls `x.show {}` and is found; `fwd`
 *     calls `it.direct {…}` on a CONCRETE receiver, so the scan sees nothing.
 *     Phase 2 does not rescue it either — it matches `BindingRef.ByHash` callees
 *     and an ability-op method call is not one. A DECLARED `with` is a
 *     requirement regardless of what the body does with it, so the declared set
 *     now decides parameters too.
 *   - and the call site has to PREFER that slot. Forwarding was the last resort
 *     there, reached only when nothing resolved — but the concrete pile holds the
 *     receiver, so `show` always resolved, to the wrong impl. The pile cannot see
 *     what the caller's type variable is bound to; only the enclosing slot can.
 *
 * WHY A RUNTIME TEST: every one of these type-checks either way, before and
 * after. Only running them tells the dictionaries apart — the same reason
 * `GenericAbilityOpRuntimeTest` and `ClassParamDispatchRuntimeTest` exist.
 */
class GenericAbilityOpForwardingTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val src = """
        use std::effects::log::{ println, Console }
        type A = { a: Int }
        type B = { b: Str }
        type C = { c: Bool }

        ability Show for 'a where { show: { it: 'a } -> Int }
        impl Show for A where { show: { it: A } -> Int = { it } -> 1 }
        impl Show for B where { show: { it: B } -> Int = { it } -> 2 }
        impl Show for C where { show: { it: C } -> Int = { it } -> 3 }

        ability Tag for 'a where { tag: { it: 'a } -> Int }
        impl Tag for A where { tag: { it: A } -> Int = { it } -> 10 }
        impl Tag for B where { tag: { it: B } -> Int = { it } -> 20 }

        ability Many for 'w where {
          uno:  { it: 'w, x: 'p } with Show 'p -> Int,
          both: { it: 'w, x: 'p, y: 'q } with Show 'p, Tag 'q -> Int,
        }
        impl Many for A where {
          uno:  { it: A, x: 'p } with Show 'p -> Int = { it, x } -> x.show {} * 1000,
          both: { it: A, x: 'p, y: 'q } with Show 'p, Tag 'q -> Int = { it, x, y } -> x.show {} + y.tag {},
        }

        ability Fwd for 'w where { fwd: { it: 'w, x: 'p } with Show 'p -> Int }
        impl Fwd for A where {
          fwd: { it: A, x: 'p } with Show 'p -> Int = { it, x } -> it.uno { x = x } + 7,
        }

        ability Fwd2 for 'w where { fwd2: { it: 'w, x: 'p } with Show 'p -> Int }
        impl Fwd2 for A where {
          fwd2: { it: A, x: 'p } with Show 'p -> Int = { it, x } -> it.fwd { x = x } * 2,
        }

        let a: A = { a = 0 }
        let b: B = { b = "s" }
        let c: C = { c = True }

        let main = {} -> perform Console in do
          println { msg = a.uno { x = a }.to_str {} }
          println { msg = a.uno { x = b }.to_str {} }
          println { msg = a.uno { x = c }.to_str {} }
          println { msg = a.both { x = c, y = b }.to_str {} }
          println { msg = a.fwd { x = b }.to_str {} }
          println { msg = a.fwd2 { x = b }.to_str {} }
        main {}
    """.trimIndent()

    @Test
    fun `an operation's own type parameter reaches a forwarded operation`() {
        val out = MezzeTestHelper.runWithOutput(src).output
        assertEquals(
            listOf("1000", "2000", "3000", "23", "2007", "4014"),
            out.trim().lines().map { it.trim() }.filter { it.isNotEmpty() },
            "each instantiation must reach ITS OWN dictionary, through a forward as " +
                "well as directly. `fwd` read 2007 only if `uno` saw `Show B`; reading " +
                "1007 is the RECEIVER's `Show A` arriving instead. `fwd2` (4014) is the " +
                "same forward one level deeper, and `both` (23) is two own parameters " +
                "under two different bounds in one operation.",
        )
    }
}
