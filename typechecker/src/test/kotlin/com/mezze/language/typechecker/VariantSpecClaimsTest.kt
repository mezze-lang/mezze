package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Variant specification, section 8 and the claim rules: the same tag in two
 * places.
 *
 * 8.3 IS THE LOAD-BEARING RULE OF THE SPECIFICATION. Within a module a tag
 * belongs to exactly ONE named group, and it makes no difference whether that
 * name arrived by declaration or by import.
 *
 * The rule is asymmetric on purpose, and the asymmetry is the part that is easy
 * to get wrong — an earlier draft had inline groups claiming too, which made
 * fourteen other rules in the specification illegal:
 *
 *   two NAMED groups claiming one tag ............ refused (adoption has no answer)
 *   an INLINE group holding a claimed tag ........ refused (contradicts a decision)
 *   two INLINE groups sharing a tag .............. always fine (neither claims)
 *   an open group's TAIL ......................... claims nothing
 *
 * It is also what protects Bool, Option and Result without a reservation list:
 * those groups are simply always in scope, so the ordinary rule refuses a second
 * claimant. See VariantSpecInclusionTest for the builtin cases.
 */
class VariantSpecClaimsTest : VariantSpecBase() {

    // ------------------------------------------------ inline groups never claim

    @Test fun `two inline groups may share a tag`() {
        accepts(
            """
            pub let f = { c: | Red, Pink | } -> 1
            pub let g = { c: | Red, Blue | } -> 2
            pub let u = {} -> f { c = Red } + g { c = Red }
            $MAIN
            """,
            "neither inline group claims Red, because nothing could ever ADOPT an " +
                "inline group — adoption needs a name. THE control for the asymmetry: " +
                "if this fails, 8.3 has been over-generalized and a dozen other rules " +
                "in the suite are illegal",
        )
    }

    @Test fun `one bound value may reach two inline groups`() {
        accepts(
            """
            pub let f = { c: | Red, Pink | } -> 1
            pub let g = { c: | Red, Blue | } -> 2
            pub let u = {} -> do
              let v = Red
              f { c = v } + g { c = v }
            $MAIN
            """,
            "same as above with the value named; naming changes nothing (4.1)",
        )
    }

    // ------------------------------------------------- named groups do claim

    @Test fun `two named groups may not claim one tag`() {
        rejects(
            "type T = | Red, Pink |\ntype U = | Red, Blue |\n$MAIN",
            "V0701",
            "a bare Red would have two candidate groups and adoption would have no " +
                "answer. This is the well-definedness condition for 1.1",
        )
    }

    @Test fun `an inline group may not contradict a named claim`() {
        rejects(
            "type T = | Red, Pink |\npub let f = { c: | Red | } -> 1\n$MAIN",
            "V0701",
            "once T claims Red the module has decided what Red's group is, and writing " +
                "| Red | contradicts that decision. The error belongs on THIS line, not " +
                "at the call that later fails — which is what 6.3 settles",
        )
    }

    @Test fun `two inline groups with different payloads for one tag`() {
        rejects(
            """
            pub let fp = { c: | Red { n: Int } | } -> 1
            pub let gp = { c: | Red { s: Str } | } -> 2
            $MAIN
            """,
            "V0701",
            "8.2: getting one name to carry two payloads in a module needs two groups " +
                "claiming it, and the error should arrive at the second signature rather " +
                "than as a payload mismatch somewhere later",
        )
    }

    // ----------------------------------------------- duplicates within a group

    @Test fun `a repeated tag in an inline group`() {
        rejects(
            "pub let f = { c: | Red, Red | } -> 1\n$MAIN",
            "V0701",
            "a group is a set, so the repeat has no effect — and writing something with " +
                "no effect is a mistake about intent (9.8)",
        )
    }

    @Test fun `a repeated tag in a type declaration`() {
        rejects(
            "type T = | Red, Pink, Red |\n$MAIN",
            "V0701",
            "same rule as above, asked of a declaration instead of an annotation",
        )
    }

    @Test fun `one name may not appear twice with different payloads`() {
        rejects(
            "pub let f = { c: | Red, Red { val: Int } | } -> 1\n$MAIN",
            "V0701",
            "identity is the NAME (9.2), so this is one tag written twice. Worse than " +
                "accepting it: today the group collapses to | Red { val: Int } | and the " +
                "nullary Red is silently DISCARDED, so the signature declares two members " +
                "and accepts one",
        )
    }

    // ------------------------------------------------------------- notation

    @Test fun `an undeclared type name in an annotation`() {
        rejects(
            "pub let f = { c: Pink } -> 1\n$MAIN",
            "T0213",
            "a bare name with no | | is a type name, not a group, so an undeclared one " +
                "is unknown and the error belongs on this line. Otherwise it becomes an " +
                "uninhabited opaque type: declarable, uncallable, and the diagnostic " +
                "lands at every call site except the one with the typo",
        )
    }

    @Test fun `an undeclared type name inside a list`() {
        rejects(
            "pub let g = { xs: [Pink] } -> 1\n$MAIN",
            "T0213",
            "the notation trap: [Pink] is a list of an undeclared Pink, where " +
                "[| Pink |] was meant — and it used to typecheck",
        )
    }

    // --------------------------------- the control experiment for coverage

    @Test fun `naming a group must not change whether coverage is checked - inline`() {
        rejects(
            "pub let f = { c: | Red, Pink | } -> match c is\n  Red -> 1\n$MAIN",
            "V0601",
            "6.2a. Read with the next test: identical group, identical single arm, and " +
                "the only difference is whether the group has a NAME. If this one is " +
                "accepted while the next is refused, checking is gated on a declared " +
                "identity rather than on the group's contents — the cleanest statement " +
                "of that bug in the whole suite",
        )
    }

    @Test fun `naming a group must not change whether coverage is checked - named`() {
        rejects(
            "type T = | Red, Pink |\npub let f = { c: T } -> match c is\n  Red -> 1\n$MAIN",
            "V0601",
            "6.2b, the other half. This one passes today, which is what makes the pair " +
                "evidence rather than a bare assertion",
        )
    }
}
