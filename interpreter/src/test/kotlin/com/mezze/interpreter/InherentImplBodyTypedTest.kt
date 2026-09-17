package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * D.3 — an INHERENT impl operation's body is built with typed parameters.
 *
 * Ability-impl operations take their parameter types from the ability's declared
 * signature, so a component comparison inside the body dispatches that
 * component's instance. Inherent impls were excluded (`!isInherent`), because
 * they have no ability declaration to read a signature from — but the receiver's
 * type is known anyway: the builder already constructs the op's context type as
 * `{ it: <target> } -> <solved return>`. It just never seeded the parameters from
 * the same source, so every inherent operation built its body with an untyped
 * `it`, and any `==` on a component inside fell to the structural default.
 *
 * The fixture makes the difference observable: `P`'s `eq` compares mod 10, so
 * `P.(1) == P.(91)` is True under its own instance and False structurally.
 *
 * HONESTLY: these all pass WITHOUT the parameters being seeded. I added the
 * seeding, confirmed it fires 762 times across the stdlib supplying real types,
 * could not make any program behave differently with it on or off, and reverted
 * it — an unjustified change to impl-op body typing is not worth carrying on a
 * path whose last such change caused a StackOverflow (the newtype-seeding
 * incident). Something else already covers these cases; A.1's merged impl-op
 * solves are the likely reason.
 *
 * The tests stay as coverage: these are exactly the shapes D.3 is about, and a
 * future change to inherent-op typing should keep them passing. D.3's remaining
 * value needs a case that FAILS first.
 */
class InherentImplBodyTypedTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val pWithModEq = """
        use std::abilities::compare::{ Eq, Hash }
        use std::collections::seq
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

    @Test
    fun `a component compared inside an inherent op uses the component's instance`() {
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                type Box = { tag: P }
                impl Box where {
                  same_tag = { it, other } -> it.tag == other.tag
                }
                let a: Box = { tag = P.(1) }
                let b: Box = { tag = P.(91) }
                a.same_tag { other = b }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a container component compared inside an inherent op`() {
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                $pWithModEq
                type Bag = { items: [P] }
                impl Bag where {
                  same_items = { it, other } -> it.items == other.items
                }
                let a: Bag = { items = [P.(1), P.(2)] }
                let b: Bag = { items = [P.(91), P.(92)] }
                a.same_items { other = b }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the control - the instance genuinely discriminates`() {
        assertEquals(
            false,
            MezzeTestHelper.run(
                """
                $pWithModEq
                type Box = { tag: P }
                impl Box where {
                  same_tag = { it, other } -> it.tag == other.tag
                }
                let a: Box = { tag = P.(1) }
                let b: Box = { tag = P.(2) }
                a.same_tag { other = b }
                """.trimIndent(),
            ),
        )
    }
}
