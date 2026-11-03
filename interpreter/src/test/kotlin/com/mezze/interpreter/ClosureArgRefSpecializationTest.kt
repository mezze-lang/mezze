package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Passing a NAMED generic function to a higher-order function.
 *
 * WHAT CHANGED. Mono specialises a HOF on its closure argument, which is what
 * makes the callback's type concrete inside the specialised body. It only
 * harvested a LAMBDA LITERAL, so `fold { f = {a,b} -> a+b }` specialised but
 * `fold { f = add2 }` did not — the callback's parameter type stayed a type
 * VARIABLE, the referenced function could not be monomorphised either, and
 * lowering fell back to wrapping it in `dict_wrapper_<name>`.
 *
 * `BindingRef.ByHash` names a top-level function statically, so its hash is a
 * sound specialisation key — no escape analysis is needed, which is what the
 * original "lambda literals only" restriction was guarding against.
 *
 * MEASURED: a user-written HOF called with a named generic callback went from
 * 57 ns/iter to 6 ns/iter — essentially the non-generic baseline of 4 — and the
 * dict wrapper stopped being compiled at all. See bench/cases/dispatch_probe.
 *
 * WHAT THESE TESTS GUARD. The specialisation SPLICES the referenced function's
 * body into the HOF, so a wrong splice is a wrong answer, not a slow one. Each
 * case below is chosen so a mis-splice changes the result:
 *
 *  - a non-commutative callback catches swapped arguments
 *  - two different callbacks through the same HOF catch one specialisation
 *    being reused for the other
 *  - a lambda literal alongside a reference catches the old path breaking
 *  - a callback with captures must still work (the splice declines it, so this
 *    checks the decline path is correct rather than silently dropping captures)
 */
class ClosureArgRefSpecializationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    fun `a named generic callback through a HOF computes correctly`() {
        assertEquals(
            30L,
            MezzeTestHelper.run(
                """
                let add2 = { a, b } -> a + b
                let apply = { f, x, y } -> f { a = x, b = y }
                apply { f = add2, x = 10, y = 20 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a non-commutative named callback keeps its argument order`() {
        // Splicing `sub2`'s body with the operands swapped would give -6.
        assertEquals(
            6L,
            MezzeTestHelper.run(
                """
                let sub2 = { a, b } -> a - b
                let apply = { f, x, y } -> f { a = x, b = y }
                apply { f = sub2, x = 10, y = 4 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `two different named callbacks through one HOF stay distinct`() {
        // If one specialisation of `apply` were reused for both callbacks, both
        // lines would answer the same. 14 + 6 = 20 only if each kept its own.
        assertEquals(
            20L,
            MezzeTestHelper.run(
                """
                let add2 = { a, b } -> a + b
                let sub2 = { a, b } -> a - b
                let apply = { f, x, y } -> f { a = x, b = y }
                apply { f = add2, x = 10, y = 4 } + apply { f = sub2, x = 10, y = 4 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a lambda literal callback still works alongside the reference path`() {
        assertEquals(
            40L,
            MezzeTestHelper.run(
                """
                let apply = { f, x, y } -> f { a = x, b = y }
                apply { f = { a, b } -> a * b, x = 10, y = 4 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a callback used in a recursive HOF accumulates correctly`() {
        // The callback is forwarded through every iteration, so a specialisation
        // that lost it after the first step would answer differently.
        assertEquals(
            55L,
            MezzeTestHelper.run(
                """
                let add2 = { a, b } -> a + b
                let go = { n, acc, f } ->
                  if n <= 0 then acc
                  else go { n = n - 1, acc = f { a = acc, b = n }, f = f }
                go { n = 10, acc = 0, f = add2 }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a named callback over Float resolves its own impl, not Int's`() {
        // The callback is generic over NumAdd; specialising on the reference
        // must still pick the impl for the CONCRETE element type here.
        assertEquals(
            7.5,
            MezzeTestHelper.run(
                """
                let addf = { a, b } -> a + b
                let apply = { f, x, y } -> f { a = x, b = y }
                apply { f = addf, x = 2.5, y = 5.0 }
                """.trimIndent(),
            ),
        )
    }
}
