package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * The IMPL TARGET axis: what an ability impl may target, and what coherence
 * refuses. Every target below was probed before being written down.
 */
class AbilityEffectSpecImplTargetTest : AbilityEffectSpecBase() {

    private fun target(targetType: String, body: String, value: String) = """
        type Box = { v: Int }
        newtype Meters = Int
        type Colour = |
          ColRed {},
          ColBlue {},
        |
        ability Bump for 'a where { bump: { it: 'a } -> Int }
        impl Bump for $targetType where { bump = { it } -> $body }
        let use_it: {} -> Int = {} -> Bump.bump { it = $value }
        $MAIN
    """

    @Test fun `a primitive is a legal target`() =
        accepts(target("Int", "it + 1", "1"), "Int")

    @Test fun `Str is a legal target`() =
        accepts(target("Str", "7", "\"hi\""), "Str")

    @Test fun `a record alias is a legal target`() =
        accepts(target("Box", "it.v", "{ v = 1 }"), "a named record")

    @Test fun `a newtype is a legal target`() =
        accepts(target("Meters", "7", "Meters.(3)"), "a newtype")

    @Test fun `a variant is a legal target`() =
        accepts(target("Colour", "7", "ColRed {}"), "a variant")

    @Test fun `a seq of builtins is a legal target`() =
        accepts(target("[Int]", "7", "[1, 2]"), "`[Int]`, i.e. Seq Int")

    @Test fun `an applied type is a legal target`() =
        accepts(target("Option Int", "7", "Some { val = 1 }"), "a full type application")

    @Test
    fun `two impls at different applications of one constructor are legal`() {
        // `impl Box Int` and `impl Box Str` once collided on a shared target
        // hash and the second overwrote the first. Dispatch by value is guarded
        // separately in the interpreter suite.
        accepts(
            """
            ability Bump for 'a where { bump: { it: 'a } -> Int }
            impl Bump for Option Int where { bump = { it } -> 111 }
            impl Bump for Option Str where { bump = { it } -> 222 }
            let use_it: {} -> Int = {} -> Bump.bump { it = Some { val = 1 } }
            $MAIN
            """,
            "distinct applications are distinct targets",
        )
    }

    @Test
    fun `an unnamed record is not a legal target`() {
        rejects(
            """
            ability Bump for 'a where { bump: { it: 'a } -> Int }
            impl Bump for { v: Int } where { bump = { it } -> it.v }
            $MAIN
            """,
            "V0701",
            "an impl must target a NAMED type; records are structural",
        )
    }

    @Test
    fun `two impls of one ability for one target are refused`() {
        rejects(
            """
            ability Bump for 'a where { bump: { it: 'a } -> Int }
            impl Bump for Int where { bump = { it } -> 1 }
            impl Bump for Int where { bump = { it } -> 2 }
            $MAIN
            """,
            "V0407",
            "coherence: one impl per (ability, target)",
        )
    }

    // ------------------------------------------------- the impl's own contract

    @Test
    fun `an impl missing a declared op is refused`() {
        rejects(
            """
            type Box = { v: Int }
            ability Bump for 'a where {
              bump: { it: 'a } -> Int,
              other: { it: 'a } -> Int
            }
            impl Bump for Box where { bump = { it } -> it.v }
            $MAIN
            """,
            "V0404",
            "every declared op must be implemented",
        )
    }

    @Test
    fun `an impl with an op the ability never declared is refused`() {
        rejects(
            """
            type Box = { v: Int }
            ability Bump for 'a where { bump: { it: 'a } -> Int }
            impl Bump for Box where { bump = { it } -> it.v, extra = { it } -> 9 }
            $MAIN
            """,
            "V0405",
            "an impl may not add ops the ability does not declare",
        )
    }

    @Test
    fun `an impl whose op returns the wrong type is refused`() {
        rejects(
            """
            type Box = { v: Int }
            ability Bump for 'a where { bump: { it: 'a } -> Int }
            impl Bump for Box where { bump = { it } -> "nope" }
            $MAIN
            """,
            "T0201",
            "the op's declared return type is the contract",
        )
    }
}
