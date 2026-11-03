package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Variant specification, sections 1 and 2: what group an expression has, and
 * when one group fits another.
 *
 * The model: a variant is a RECORD WITH A TAG, and the tag belongs to a GROUP.
 * A group is a set of tags; `G` fits a position expecting `H` iff `G ⊆ H`.
 * Membership is by NAME — `Red` and `Red { val: Int }` are the same tag with
 * two payloads — and once a name matches, the payloads must unify.
 *
 * The `widening` block is the important part of this file. Inclusion is a
 * DIRECTION, and an implementation with no direction at all — one that unifies
 * groups by equality — refuses both ways and so passes every "this is refused"
 * test in the section. The only assertions that can tell the two apart are the
 * ones that require a widening to be ACCEPTED, so those are written first and
 * referenced from the rejections they justify.
 */
class VariantSpecInclusionTest : VariantSpecBase() {

    // ======================================================== 1. what group

    /** 1.1 — a tag adopts the in-scope alias whose group contains it. */
    @Test fun `a tag adopts an in-scope alias`() {
        val t = printedType(
            """
            type T = | Red, Pink |
            pub let v = {} -> Red
            $MAIN
            """,
            "v",
        )
        assertTrue(
            t.contains("T"),
            "a bare Red with T = | Red, Pink | in scope must adopt T, since the printed " +
                "type is the whole reason adoption exists (9.1); got $t",
        )
    }

    /** 1.2 — with nothing claiming it, a tag is its own one-element group. */
    @Test fun `an unclaimed tag is its own one-element group`() {
        val t = printedType("pub let v = {} -> Blue\n$MAIN", "v")
        assertTrue(
            t.contains("Blue"),
            "nothing claims Blue, so its group is | Blue | — and that is exactly when " +
                "| Blue | is also legal to write (8.3); got $t",
        )
    }

    /** 1.3 — the builtin groups are always in scope, so their tags adopt. */
    @Test fun `Some adopts Option`() {
        val t = printedType("pub let v = {} -> Some { val = 5 }\n$MAIN", "v")
        assertTrue(t.contains("Option"), "Some must adopt Option; got $t")
    }

    @Test fun `True adopts Bool`() {
        val t = printedType("pub let v = {} -> True\n$MAIN", "v")
        assertTrue(t.contains("Bool"), "True must adopt Bool; got $t")
    }

    /**
     * 1.3 — and the protection of the builtins is 8.3 doing its ordinary job,
     * not a reservation list: those groups are simply always in scope.
     */
    @Test fun `a user group may not claim Bool's tags`() {
        rejects(
            "type MyBool = | True, False |\n$MAIN",
            null,
            "Bool claims True and False, so a second claimant is refused by 8.3",
        )
    }

    @Test fun `an inline group may not claim a builtin tag`() {
        rejects(
            "pub let f = { c: | True | } -> 1\n$MAIN",
            null,
            "writing | True | contradicts Bool's claim on True — the inline half of 8.3, " +
                "and the reason a reservation list is not enough",
        )
    }

    @Test fun `a reshaped builtin tag is refused`() {
        rejects(
            "pub let g = {} -> False { val = 6 }\n$MAIN",
            null,
            "identity is the NAME (9.2), so a payload does not buy a second False",
        )
    }

    /** 1.4 — adoption reaches patterns, so coverage is judged against the group. */
    @Test fun `one arm defines the group when nothing is in scope`() {
        val t = printedType("pub let f = { c } -> match c is\n  Red -> 1\n$MAIN", "f")
        assertTrue(t.contains("Red"), "the single arm defines { c: | Red | }; got $t")
    }

    @Test fun `adoption applies to patterns`() {
        rejects(
            """
            type T = | Red, Pink |
            pub let f = { c } -> match c is
              Red -> 1
            $MAIN
            """,
            "V0601",
            "the pattern's Red adopts T, so Pink is a missing case",
        )
    }

    /** 1.5 — a multi-element literal unions its elements. */
    @Test fun `a list literal unions its elements`() {
        val t = printedType("pub let xs = {} -> [Red, Pink]\n$MAIN", "xs")
        assertTrue(
            t.contains("Red") && t.contains("Pink"),
            "[Red, Pink] is a list of | Red, Pink |. Taking only the first element makes " +
                "the type order-dependent, and it leaks into mutable containers; got $t",
        )
    }

    /** 1.6 — branches and arms union correctly. */
    @Test fun `if branches union`() {
        val t = printedType("pub let f = { n: Int } -> if n > 0 then Red else Pink\n$MAIN", "f")
        assertTrue(t.contains("Red") && t.contains("Pink"), "got $t")
    }

    @Test fun `match arms union`() {
        val t = printedType(
            "pub let f = { n: Int } -> match n is\n  0 -> Red\n  _ -> Pink\n$MAIN", "f")
        assertTrue(t.contains("Red") && t.contains("Pink"), "got $t")
    }

    /** 1.7 — the first arm fixes the group; a later arm must fit it. */
    @Test fun `arms may not span two aliases`() {
        rejects(
            """
            type T = | Red, Pink |
            type U = | Green, Blue |
            pub let f = { c } -> match c is
              Red -> 1
              Green -> 2
            $MAIN
            """,
            "V0704",
            "the first arm adopts T, and Green is not in T",
        )
    }

    // ============================================= 2. widening — the mirrors
    //
    // Every rejection below this point is only evidence because these pass.
    // An implementation that unifies groups by equality refuses both
    // directions and would satisfy the rejections alone.

    @Test fun `widening a written one-element group`() {
        accepts(
            """
            pub let f = { c: | Red, Pink | } -> 1
            pub let u = { c: | Red | } -> f { c = c }
            $MAIN
            """,
            "| Red | ⊆ | Red, Pink |, so it fits — rule 2.1, and the single most " +
                "load-bearing acceptance in the suite: without it, every rejection in " +
                "this section is satisfied by plain equality",
        )
    }

    @Test fun `widening a written two-element group`() {
        accepts(
            """
            pub let f = { c: | Red, Pink, Blue | } -> 1
            pub let u = { c: | Red, Pink | } -> f { c = c }
            $MAIN
            """,
            "| Red, Pink | ⊆ | Red, Pink, Blue |. Widening is not special to " +
                "one-element groups",
        )
    }

    @Test fun `widening across two added tags`() {
        accepts(
            """
            pub let f = { c: | Red, Pink, Blue, Green | } -> 1
            pub let u = { c: | Red, Pink | } -> f { c = c }
            $MAIN
            """,
            "the size of the gap is not a rule",
        )
    }

    @Test fun `equal written groups fit`() {
        accepts(
            """
            pub let f = { c: | Red, Pink | } -> 1
            pub let u = { c: | Red, Pink | } -> f { c = c }
            $MAIN
            """,
            "CONTROL: equality must pass, else the parameter path is down and the " +
                "widening cases above are measuring nothing",
        )
    }

    @Test fun `a bare literal fits a wider group`() {
        accepts(
            """
            pub let f = { c: | Red, Pink, Blue | } -> 1
            pub let u = {} -> f { c = Red }
            $MAIN
            """,
            "CONTROL: a literal has no fixed group yet, so this passes even under " +
                "equality — it isolates whether the WRITTEN cases are what fail",
        )
    }

    @Test fun `an inferred return group fits a wider group`() {
        accepts(
            """
            pub let mk = {} -> Red
            pub let f = { c: | Red, Pink, Blue | } -> 1
            pub let u = {} -> f { c = mk {} }
            $MAIN
            """,
            "CONTROL: an inferred group is still flexible at the call, so this also " +
                "passes under equality",
        )
    }

    @Test fun `a written group fits an open target`() {
        accepts(
            """
            pub let f = { c: | Red, Pink, ... | } -> 1
            pub let u = { c: | Red, Pink | } -> f { c = c }
            $MAIN
            """,
            "closed flows to open: an unbounded group contains everything (9.5)",
        )
    }

    // ============================================= 2. inclusion — rejections

    /** 2.1 / 2.2 — the direction. Paired with the widening cases above. */
    @Test fun `a superset does not fit`() {
        rejects(
            """
            pub let f = { c: | Red | } -> 1
            pub let u = { c: | Red, Pink | } -> f { c = c }
            $MAIN
            """,
            "T0201",
            "f cannot handle Pink. Only evidence alongside `widening a written " +
                "one-element group`, which must accept the other direction",
        )
    }

    @Test fun `an inferred superset does not fit`() {
        rejects(
            """
            pub let only_red = { c } -> match c is
              Red -> 1
            pub let u = { c: | Red, Pink | } -> only_red { c = c }
            $MAIN
            """,
            "T0201",
            "the single arm infers { c: | Red | }, which cannot receive a Pink",
        )
    }

    /** 2.4 — an open group is unbounded, so anything fits it. */
    @Test fun `an open group accepts an unnamed tag`() {
        accepts(
            """
            pub let f = { c: | Pink, ... | } -> 1
            pub let u = {} -> f { c = Red }
            $MAIN
            """,
            "Red arrives through the tail",
        )
    }

    /** 2.5 — membership is by name; then the payload must unify. */
    @Test fun `a payload that does not unify is refused`() {
        rejects(
            """
            pub let f = { c: | Red { n: Int }, Pink | } -> 1
            pub let u = {} -> f { c = Red { s = "x" } }
            $MAIN
            """,
            "T0201",
            "Red IS the member — this is a payload mismatch on a tag that is present, " +
                "not a missing member, and the diagnostic should say so",
        )
    }

    /** 2.6 — the unbounded tail never rescues a payload mismatch. */
    @Test fun `the open tail does not rescue a payload mismatch`() {
        rejects(
            """
            pub let f = { c: | Red, ... | } -> 1
            pub let u = {} -> f { c = Red { val = 5 } }
            $MAIN
            """,
            "T0201",
            "the named part claims Red, so its payload must unify and the tail is " +
                "never consulted — otherwise a payload-carrying Red becomes a second Red",
        )
    }

    /** 2.7 — a generic payload instantiates. */
    @Test fun `a generic payload instantiates`() {
        accepts(
            """
            pub let f = { c: | Red { val: 'a }, Green | } -> 1
            pub let u = {} -> f { c = Red { val = 5 } }
            $MAIN
            """,
            "'a := Int",
        )
    }

    /** 2.8 / 3.4 — inclusion is deep, and an immutable element is read-only. */
    @Test fun `inclusion reaches a container element`() {
        accepts(
            """
            pub let wide = { ys: [| Red, Pink |] } -> 1
            pub let u = {} -> wide { ys = [Red] }
            $MAIN
            """,
            "a list cannot be written through, so its elements only travel outward",
        )
    }

    /** 2.9 — one parameter cannot be two disjoint groups. */
    @Test fun `one parameter cannot be two disjoint groups`() {
        rejects(
            """
            pub let f = { c: | Red, Pink | } -> 1
            pub let g = { c: | Red, Blue | } -> 2
            pub let u = { c } -> f { c = c } + g { c = c }
            $MAIN
            """,
            "T0201",
            "c would have to be a subset of both, and | Red | is not what either " +
                "signature says",
        )
    }

    /** 6.1 — an alias is transparent for unification. */
    @Test fun `an alias is transparent`() {
        accepts(
            """
            type T = | Red, Pink |
            pub let f = { c: T } -> 1
            pub let u = {} -> f { c = Red }
            $MAIN
            """,
            "T names a group; a bare Red adopts it and fits",
        )
    }
}
