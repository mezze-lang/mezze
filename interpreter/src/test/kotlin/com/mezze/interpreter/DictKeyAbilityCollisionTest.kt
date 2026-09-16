package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * C.2 — dictionary parameters are identified by METHOD NAME (plus, since the
 * per-var work, the type variable): `__dict_eq@a`. The plan says they should be
 * identified by (ability hash, type variable) instead, because a name is not an
 * identity — the same rule BUG 1 established for dispatch.
 *
 * It DOES collide, and silently: `Weigh.rank {…} + Price.rank {…}` returns 6
 * where it must return 106, because `Weigh.rank` answered both calls and Price's
 * implementation never ran. No error, no warning — the wrong answer. BUG 9, and
 * the same class as BUG 1: a NAME used as an identity.
 *
 * HALF FIXED. With a CONCRETE receiver it is now right: the typechecker knew the
 * ability per call site all along (`MethodCallInfo.abilityName`) and emit simply
 * dropped it, leaving the compiler to re-resolve against an index keyed on the
 * operation name. Emit now resolves the impl and writes it out by hash.
 *
 * With a GENERIC receiver it is still wrong, and that half is task C.2: the
 * dictionary slot is keyed `rank@a` — method name plus type variable, no ability
 * — so `Weigh.rank@a` and `Price.rank@a` are one slot. The per-VARIABLE half of
 * that keying already works, which the second test pins.
 */
class DictKeyAbilityCollisionTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    @Test
    @Disabled("BUG 9 / task C.2: dict slots are keyed on the method NAME, so two abilities declaring the same op name collide and one silently answers for both — see known-bugs.md")
    fun `two abilities declaring the same op name, both constrained on one type var`() {
        // `Weigh.rank` and `Price.rank` are different abilities with the same op
        // name. `describe` needs both for 'a. Keyed on the name alone, the two
        // dict slots are one slot.
        assertEquals(
            106L,
            MezzeTestHelper.run(
                """
                ability Weigh for 'a where {
                  rank: { it: 'a } -> Int
                }
                ability Price for 'a where {
                  rank: { it: 'a } -> Int
                }
                type Item = { n: Int }
                impl Weigh for Item where {
                  rank = { it } -> it.n
                }
                impl Price for Item where {
                  rank = { it } -> it.n + 100
                }
                let both: { x: 'a } with Weigh 'a, Price 'a -> Int = { x } ->
                  Weigh.rank { it = x } + Price.rank { it = x }
                both { x = { n = 3 } }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the same op name on two DIFFERENT type variables`() {
        // One ability, two variables. The per-var keys (`__dict_rank@a`,
        // `__dict_rank@b`) exist for exactly this; the test pins that they work.
        assertEquals(
            5L,
            MezzeTestHelper.run(
                """
                ability Weigh for 'a where {
                  rank: { it: 'a } -> Int
                }
                type A = { n: Int }
                type B = { s: Str }
                impl Weigh for A where {
                  rank = { it } -> it.n
                }
                impl Weigh for B where {
                  rank = { it } -> 4
                }
                let pair: { x: 'a, y: 'b } with Weigh 'a, Weigh 'b -> Int = { x, y } ->
                  Weigh.rank { it = x } + Weigh.rank { it = y }
                pair { x = { n = 1 }, y = { s = "z" } }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `two abilities with the same op name, CONCRETE receiver`() {
        // The same collision without the generic wrapper. This one is FIXED: the
        // typechecker knew the ability per call site all along
        // (`MethodCallInfo.abilityName`), and emit now resolves the impl and
        // writes it out by hash instead of leaving the compiler to re-resolve
        // against an index keyed on the operation NAME.
        assertEquals(
            106L,
            MezzeTestHelper.run(
                """
                ability Weigh for 'a where {
                  rank: { it: 'a } -> Int
                }
                ability Price for 'a where {
                  rank: { it: 'a } -> Int
                }
                type Item = { n: Int }
                impl Weigh for Item where {
                  rank = { it } -> it.n
                }
                impl Price for Item where {
                  rank = { it } -> it.n + 100
                }
                let concrete: { x: Item } -> Int = { x } ->
                  Weigh.rank { it = x } + Price.rank { it = x }
                concrete { x = { n = 3 } }
                """.trimIndent(),
            ),
        )
    }
}
