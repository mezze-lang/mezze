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
 * It DOES collide, and silently. Two abilities `Weigh` and `Price`, both
 * declaring `rank`, both constrained on one type variable: the program returns 6
 * where it must return 106, because `Weigh.rank` answered both calls and
 * `Price`'s implementation never ran. No error, no warning — the wrong answer.
 *
 * This is BUG 9, and the same class as BUG 1: a NAME used as an identity. Fixing
 * it is task C.2 — the dict slot has to be keyed on the ability, which means
 * threading ability identity through the prescan that mints the key and both call
 * sites that read it. The per-variable half of C.2 is already done and works (the
 * second test passes).
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
}
