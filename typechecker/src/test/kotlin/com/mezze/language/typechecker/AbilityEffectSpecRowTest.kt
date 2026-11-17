package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * The EFFECT ROW axis: laundering, the `*` marker, and the handler rules.
 * Rows are exact and `<...>` is a row VARIABLE, not a wildcard.
 */
class AbilityEffectSpecRowTest : AbilityEffectSpecBase() {

    private val tick = """
        effect Tick where { tick: {} -> Int <Tick>, }
        impl Tick for T1 where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          tick = {} -> 1,
        }
    """.trimIndent()

    private val ex = """
        effect Ex where { throw: { e: Str } -> 'a <Ex>, }
    """.trimIndent()

    @Test
    fun `a pure callback slot refuses an effectful function`() {
        rejects(
            """
            $tick
            let apply_pure: { f: {} -> Int } -> Int = { f } -> f {}
            let leak: {} -> Int <Tick> = {} -> apply_pure { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "T0201",
            "a pure slot is pure; this is the laundering the row must refuse",
        )
    }

    @Test
    fun `a callback slot declaring an open row accepts an effectful function`() {
        // The mirror: `<...>` is a PERMISSION, so the same program is fine once
        // the slot asks for one. Without this the rule above is weak evidence.
        accepts(
            """
            $tick
            let apply_any: { f: {} -> Int <...> } -> Int <...> = { f } -> f {}
            let ok: {} -> Int <Tick> = {} -> apply_any { f = {} -> Tick.tick {} }
            $MAIN
            """,
            "`<...>` is a row variable the caller's effects bind to",
        )
    }

    @Test
    fun `a function annotated pure may not perform`() {
        rejects(
            """
            $tick
            let lies: {} -> Int = {} -> Tick.tick {}
            $MAIN
            """,
            "T0210",
            "the annotation is a claim the body must honour",
        )
    }

    @Test
    fun `a body that performs AND handles needs the star marker`() {
        rejects(
            """
            $ex
            impl Ex for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> abort { val = "s" },
            }
            let use_it: {} -> Str = {} -> perform Stop {} in Ex.throw { e = "x" }
            $MAIN
            """,
            "T0210",
            "an annotation with no `*` hides that the body handles what it performs",
        )
    }

    @Test
    fun `the star marker spelling is a bare star`() {
        accepts(
            """
            $ex
            impl Ex for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> abort { val = "s" },
            }
            let use_it: {} -> Str <*> = {} -> perform Stop {} in Ex.throw { e = "x" }
            $MAIN
            """,
            "`<*>`; `<*Ex>` is a parse error",
        )
    }

    @Test
    fun `an abort payload must match the perform block's type`() {
        rejects(
            """
            $ex
            impl Ex for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> abort { val = 0 },
            }
            let use_it: {} -> Str <*> = {} -> perform Stop {} in Ex.throw { e = "x" }
            $MAIN
            """,
            "T0201",
            "the abort payload becomes the block's value, so the types must agree",
        )
    }

    // -------------------------------------------------------- handler rules

    @Test
    fun `resume in tail position is accepted`() {
        accepts(
            """
            effect Ex2 where { throw: { e: Str } -> Int <Ex2>, }
            impl Ex2 for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> resume { val = 1 },
            }
            $MAIN
            """,
            "the designed position",
        )
    }

    @Test
    fun `resume used as a value is refused`() {
        rejects(
            """
            effect Ex2 where { throw: { e: Str } -> Int <Ex2>, }
            impl Ex2 for Stop where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              throw = { e } -> resume { val = 1 } + 1,
            }
            $MAIN
            """,
            "V0503",
            "`resume` is the identity function, so arithmetic on it lands on the wrong value",
        )
    }

    @Test
    fun `abort inside initialize_scope is refused`() {
        rejects(
            """
            effect Ex2 where { throw: { e: Str } -> Int <Ex2>, }
            impl Ex2 for Stop where {
              initialize_scope = {} -> abort { val = 1 },
              finalize_scope = {} -> {},
              throw = { e } -> 1,
            }
            $MAIN
            """,
            "V0503",
            "the scope hooks are not op bodies; the op loop continues past them",
        )
    }

    // ------------------------------------------------- effects vs abilities

    @Test
    fun `two effects may declare the same op name`() {
        accepts(
            """
            effect A1 where { go: {} -> Int <A1>, }
            effect A2 where { go: {} -> Int <A2>, }
            impl A1 for P1 where {
              initialize_scope = {} -> {}, finalize_scope = {} -> {}, go = {} -> 11,
            }
            impl A2 for P2 where {
              initialize_scope = {} -> {}, finalize_scope = {} -> {}, go = {} -> 22,
            }
            let use_it: {} -> Int <*> = {} -> perform P1 {}, P2 {} in A1.go {}
            $MAIN
            """,
            "an effect op is always called qualified, so there is nothing to disambiguate",
        )
    }

    @Test
    fun `two abilities may NOT declare the same op name`() {
        // The asymmetry with the rule above, and its reason: an ability op is
        // reachable receiver-style, where the name alone would have to choose.
        rejects(
            """
            ability Weigh for 'a where { rank: { it: 'a } -> Int }
            ability Price for 'a where { rank: { it: 'a } -> Int }
            $MAIN
            """,
            "V0701",
            "`x.rank {}` could not pick, so the name belongs to one ability",
        )
    }
}
