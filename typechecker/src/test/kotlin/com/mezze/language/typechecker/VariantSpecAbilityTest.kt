package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Variant specification, section 10: abilities and impls over variants.
 *
 * Two declarations share a spelling and are NOT the same thing:
 *
 *   impl <ability> for <type>       attaches behaviour to a named type
 *   impl <effect>  for <performer>  declares a handler, target is a bare tag
 *
 * An ABILITY target is a named type — a `type` alias, a `newtype`, or a builtin
 * name — optionally applied to type arguments. The arguments are ordinary types,
 * so `[{ k: Str, v: Str }]` is fine. An inline record shape, an inline group and
 * a bare tag are NOT ability targets.
 *
 * Dispatch is EQUALITY on the group, not inclusion: an impl is written for a
 * group, and a value of a narrower group has not said which group it belongs to.
 * ADOPTION is what makes that workable — it carries an anonymous value to a
 * nominal impl, and it is the same bridge for records as for variants.
 *
 * Because a receiver's type names one group and there is one impl per group,
 * dispatch cannot be ambiguous. Section 10 therefore needs no overlap check, no
 * specialization and no coherence pass — 8.3 pays for all of it.
 */
class VariantSpecAbilityTest : VariantSpecBase() {

    private val ability = "ability Describe for 'a where { describe: { it: 'a } -> Str }"

    // -------------------------------------------------- 10.1 named targets only

    @Test fun `an ability impl on a variant type alias`() {
        accepts(
            """
            $ability
            type Color = | Red, Green |
            impl Describe for Color where { describe = { it } -> "c" }
            pub let u = {} -> do
              let v = Red
              v.describe {}
            $MAIN
            """,
            "the main road: adoption gives v the identity Color, and the impl targets it",
        )
    }

    @Test fun `an ability impl on a record type alias`() {
        accepts(
            """
            $ability
            type Point = { x: Int }
            impl Describe for Point where { describe = { it } -> "p" }
            pub let u = {} -> do
              let p: Point = { x = 1 }
              p.describe {}
            $MAIN
            """,
            "the same rule for records — 10.1 is one rule for every kind of type",
        )
    }

    @Test fun `an ability impl on the Seq type`() {
        accepts(
            """
            $ability
            impl Describe for [ 'a ] where { describe = { it } -> "s" }
            $MAIN
            """,
            "[ 'a ] is a named type written in bracket syntax. The bracket shape does " +
                "not make it anonymous, and this is what keeps the stdlib's eight impls " +
                "on the list type legal",
        )
    }

    @Test fun `an ability impl on an anonymous group`() {
        rejects(
            """
            $ability
            impl Describe for | Red, Green | where { describe = { it } -> "c" }
            $MAIN
            """,
            "V0701",
            "a declaration-site restriction in the spirit of an orphan rule: an impl " +
                "must be attributable to a declared thing. Used NOWHERE in the codebase — " +
                "not in source, tests, or the compiler — so banning it costs nothing",
        )
    }

    @Test fun `an ability impl on an anonymous record`() {
        rejects(
            """
            $ability
            impl Describe for { x: Int } where { describe = { it } -> "r" }
            $MAIN
            """,
            "V0701",
            "same rule. Note the restriction is on SOURCE: the derive generator " +
                "synthesizes `impl Eq for { f1: T1, … }` for an anonymous shape holding a " +
                "newtype, and that must keep working",
        )
    }

    @Test fun `an ability impl on an open anonymous record`() {
        rejects(
            """
            $ability
            impl Describe for { x: Int, ... } where { describe = { it } -> "r" }
            $MAIN
            """,
            "V0701",
            "openness is not what 10.1 restricts — anonymity is",
        )
    }

    @Test fun `an ability impl on a bare tag`() {
        rejects(
            """
            $ability
            impl Describe for Red where { describe = { it } -> "r" }
            $MAIN
            """,
            "V0701",
            "a tag is a MEMBER of a group, not a type of its own, so there is nothing " +
                "an ability could usefully attach to: `impl Describe for Red` says " +
                "nothing `impl Describe for Color` does not say better. The performer " +
                "form that DOES target a bare tag is a different declaration (10.3)",
        )
    }

    // ------------------------------------------------- 10.5 one impl per group

    @Test fun `two impls of one ability for one group`() {
        rejects(
            """
            $ability
            type Color = | Red, Green |
            impl Describe for Color where { describe = { it } -> "a" }
            impl Describe for | Red, Green | where { describe = { it } -> "b" }
            $MAIN
            """,
            "V0701",
            "an alias is transparent (6.1), so these name ONE group and this is a " +
                "duplicate definition — the same error as writing `let x` twice. Today " +
                "both are accepted and the spelling silently decides which body runs",
        )
    }

    // ------------------------------------------------------- 10.4 / 10.8 / 10.9

    @Test fun `adoption carries a bare tag to a nominal impl`() {
        accepts(
            """
            $ability
            type Color = | Red, Green |
            impl Describe for Color where { describe = { it } -> "c" }
            pub let u = {} -> do
              let v = Red
              v.describe {}
            $MAIN
            """,
            "the load-bearing rule of the section: 10.1 makes impls nominal and 10.2 " +
                "makes dispatch exact, which together would force an annotation on every " +
                "receiver. Adoption is what removes that",
        )
    }

    @Test fun `the group named once in a signature, bare tags at the call`() {
        accepts(
            """
            $ability
            type Color = | Red, Green |
            impl Describe for Color where { describe = { it } -> "c" }
            pub let f = { c: Color } -> c.describe {}
            pub let u = {} -> f { c = Red }
            $MAIN
            """,
            "inclusion gets the value INTO the position; equality selects the impl once " +
                "it is there. The two never compete — this is the shape most code takes",
        )
    }

    @Test fun `an impl body must cover its closed group`() {
        rejects(
            """
            $ability
            type Color = | Red, Green |
            impl Describe for Color where { describe = { it } -> match it is
                Red -> "r" }
            pub let f = { c: Color } -> c.describe {}
            pub let u = {} -> f { c = Green }
            $MAIN
            """,
            "V0601",
            "`it` has exactly the target group, so section 5 applies with nothing added. " +
                "This is the identity-gated coverage bug reaching impl bodies, and it " +
                "crashes the interpreter with `exhaustiveness guarantee violated`",
        )
    }

    @Test fun `a generic group target instantiates`() {
        accepts(
            """
            $ability
            type Box 'a = | Full { val: 'a }, Empty |
            impl Describe for Box 'a where { describe = { it } -> "b" }
            pub let u = {} -> do
              let b: Box Int = Full { val = 1 }
              b.describe {}
            $MAIN
            """,
            "the route `impl Ord for Option 'a` takes; treating variants as groups " +
                "leaves generic impls exactly as they were",
        )
    }

    // ---------------------------------------------------- 10.3 / 10.11 effects

    @Test fun `an effect impl targets a performer tag`() {
        accepts(
            """
            effect Beep where { beep: { n: Int } -> {} }
            impl Beep for Loud where { beep = { n } -> {} }
            $MAIN
            """,
            "the performer form. `Loud` is a name, so this satisfies 10.1, and the impl " +
                "is also the tag's declaration — requiring `type Loud = | Loud |` first " +
                "would be ceremony. This is how every stdlib performer is written",
        )
    }

    @Test fun `performer selection is by the tag`() {
        accepts(
            """
            effect Beep where { beep: { n: Int } -> {} }
            impl Beep for Loud where { beep = { n } -> {} }
            impl Beep for Quiet where { beep = { n } -> {} }
            pub let u = {} -> perform Loud in Beep.beep { n = 1 }
            $MAIN
            """,
            "the performers of one effect are distinct tags, so every receiver has one " +
                "candidate. The whole performer mechanism needs only 10.2 and 10.3 — " +
                "nothing of its own, which is the best evidence the group model is right",
        )
    }
}
