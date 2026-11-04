package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Variant specification, section 5: matching, plus the unreachable-arm rules.
 *
 * Arms must cover the scrutinee's group. A CLOSED group needs every element and
 * no wildcard; an OPEN group cannot be enumerated, so it needs one. A GUARDED
 * arm covers nothing, because a guard can fail — that single fact drives four
 * of the rules here and is the reason the sound form (5.12) stays writable.
 *
 * Unlike section 2, a reject-only assertion IS evidence in most of this file:
 * the failure mode is over-permissiveness, and the controls that pin it are the
 * sibling cases that must be ACCEPTED with the arm added.
 */
class VariantSpecMatchingTest : VariantSpecBase() {

    // ---------------------------------------------------------------- closed

    @Test fun `closed, every element covered`() {
        accepts(
            "pub let f = { c: | Red, Pink | } -> match c is\n  Red -> 1\n  Pink -> 2\n$MAIN",
            "CONTROL for the whole file: full coverage must pass, else every rejection " +
                "below is measuring a broken match path rather than a coverage rule",
        )
    }

    @Test fun `closed, an element has no arm`() {
        rejects(
            """
            pub let f = { c: | Red, Pink | } -> match c is
              Red -> 1
            pub let boom = {} -> f { c = Pink }
            $MAIN
            """,
            "V0601",
            "Pink has no arm and the call supplies one — this is the stuck call, and it " +
                "crashes the interpreter with `typechecker exhaustiveness guarantee violated`",
        )
    }

    @Test fun `closed, fully covered, plus a wildcard`() {
        rejects(
            "pub let f = { c: | Red, Pink | } -> match c is\n" +
                "  Red -> 1\n  Pink -> 2\n  _ -> 3\n$MAIN",
            "V0603",
            "the group is exhausted, so the wildcard can never run. One shape of 9.7",
        )
    }

    @Test fun `an arm for a name outside the group`() {
        rejects(
            "pub let f = { c: | Red, Pink | } -> match c is\n" +
                "  Red -> 1\n  Pink -> 2\n  Blue -> 3\n$MAIN",
            "V0704",
            "Blue is not a member and the group is closed, so no arm can introduce it",
        )
    }

    // ------------------------------------------------------------------ open

    @Test fun `open, every named element covered, no wildcard`() {
        rejects(
            "pub let f = { c: | Red, Pink, ... | } -> match c is\n  Red -> 1\n  Pink -> 2\n$MAIN",
            "V0601",
            "an open group cannot be enumerated, so covering its named part is not " +
                "coverage — a tag from the tail has nowhere to go",
        )
    }

    @Test fun `open, covered plus a wildcard`() {
        accepts(
            "pub let f = { c: | Red, Pink, ... | } -> match c is\n" +
                "  Red -> 1\n  Pink -> 2\n  _ -> 3\n$MAIN",
            "CONTROL: the wildcard is what makes an open group coverable",
        )
    }

    @Test fun `open, an arm for an unnamed element`() {
        rejects(
            "pub let f = { c: | Red, Pink, ... | } -> match c is\n" +
                "  Red -> 1\n  Pink -> 2\n  Blue -> 3\n  _ -> 4\n$MAIN",
            "V0704",
            "Blue is not in the named part, so naming it in an arm claims a membership " +
                "the group does not state; the tail is reached by `_`, not by name",
        )
    }

    // ------------------------------------------------------ no annotation

    @Test fun `no annotation, closed from the arms`() {
        accepts(
            "pub let f = { c } -> match c is\n  Red -> 1\n  Pink -> 2\n$MAIN",
            "the arms define { c: | Red, Pink | }",
        )
    }

    @Test fun `no annotation, open from a wildcard`() {
        accepts(
            "pub let g = { c } -> match c is\n  Red -> 1\n  _ -> 2\n$MAIN",
            "a wildcard makes the inferred group open: { c: | Red, ... | }",
        )
    }

    /** 5.5 — arms widen a bound value, and the widened value fits less. */
    @Test fun `an arm outside a bound value's group is extra`() {
        rejects(
            "pub let u = {} -> do\n  let v = Red\n  match v is\n    Red -> 1\n    Pink -> 2\n$MAIN",
            "V0704",
            "A bound value's group is FIXED at the binding: `let v = Red` makes it " +
                "| Red |, and the Pink arm is an extra arm on a group that does not hold " +
                "Pink.\n\nArms widen an UNANNOTATED PARAMETER, where there is no group yet " +
                "and the arms are the only evidence (5.9). A binding already has one. An " +
                "earlier draft of this suite had arms widening a bound value too, which " +
                "contradicted 4.1 — a bound value keeps its group and each use is checked " +
                "— and the two could not both hold.",
        )
    }

    @Test fun `a bound value keeps its group through a match`() {
        accepts(
            """
            pub let only_red = { c: | Red | } -> 1
            pub let u = {} -> do
              let v = Red
              let n = match v is
                Red -> 1
              only_red { c = v }
            $MAIN
            """,
            "The mirror of the test above: with no extra arm, v is still | Red | after " +
                "the match and fits a | Red | position. Nothing widened it, which is the " +
                "point — a match READS a value, it does not retype it.",
        )
    }

    // ---------------------------------------------------------------- guards

    @Test fun `a guarded arm contributes nothing to coverage`() {
        rejects(
            """
            pub let f = { c, n: Int } -> match c is
              Red if n > 0 -> 1
              Pink if n > 0 -> 2
            pub let boom = {} -> f { c = Red, n = 0 - 5 }
            $MAIN
            """,
            "V0601",
            "both guards can fail, so nothing is covered — and `boom` supplies the " +
                "arguments that make it fall through",
        )
    }

    @Test fun `a guard reading the tag's own payload is still a guard`() {
        rejects(
            "pub let f = { c: | Red { n: Int }, Pink | } -> match c is\n" +
                "  Red { n = v } if v > 0 -> 1\n  Pink -> 2\n$MAIN",
            "V0601",
            "reading the payload does not make the guard total; v <= 0 has no arm",
        )
    }

    @Test fun `a guarded arm plus an unguarded one for the same tag`() {
        accepts(
            "pub let f = { c: | Red { n: Int }, Pink | } -> match c is\n" +
                "  Red { n = v } if v > 0 -> 1\n  Red { n = _ } -> 2\n  Pink -> 3\n$MAIN",
            "CONTROL, and it is load-bearing: the sound form is expressible, so the " +
                "guard rule never makes a program unwritable — only more explicit",
        )
    }

    @Test fun `a guarded wildcard is not a fallback`() {
        rejects(
            "pub let f = { c, n: Int } -> match c is\n  Red -> 1\n  _ if n > 0 -> 2\n$MAIN",
            "V0601",
            "a guarded wildcard covers nothing AND leaves the inferred group open, so " +
                "the match has no fallback at all",
        )
    }

    @Test fun `complementary guards do not count`() {
        rejects(
            "pub let f = { c: | Red { n: Int }, Pink | } -> match c is\n" +
                "  Red { n = v } if v > 0 -> 1\n  Red { n = v } if v <= 0 -> 2\n  Pink -> 3\n$MAIN",
            "V0601",
            "CHOSEN: a guarded arm never counts. Proving this pair total needs a solver, " +
                "and a syntactic special case for `> 0` / `<= 0` invites `> 0` / `< 1` next",
        )
    }

    // -------------------------------------------------- unreachable arms (9.7)

    @Test fun `a named arm after a wildcard is unreachable`() {
        rejects(
            "pub let f = { c: | Red, Pink | } -> match c is\n  Red -> 1\n  _ -> 2\n  Pink -> 3\n$MAIN",
            "V0603",
            "the wildcard already took Pink",
        )
    }

    @Test fun `a guard makes a same-looking wildcard reachable`() {
        accepts(
            """
            pub let bright = { c: | Red, Pink | } -> True
            pub let f = { c: | Red, Pink | } -> match c is
              Red if bright { c = c } -> 1
              Pink -> 3
              _ -> 2
            $MAIN
            """,
            "every named tag appears in an arm, exactly as in the case above — but the " +
                "guard can fail, so a non-bright Red falls to the wildcard. THE case that " +
                "forbids a syntactic unreachability check: 9.7 is an error, and an error " +
                "that misfires rejects a correct program",
        )
    }

    @Test fun `a duplicate arm is unreachable`() {
        rejects(
            "pub let f = { c: | Red, Pink | } -> match c is\n  Red -> 1\n  Red -> 2\n  Pink -> 3\n$MAIN",
            "V0603",
            "the second Red can never run",
        )
    }

    // ------------------------------------------------- payloads and nesting

    @Test fun `a payload pattern`() {
        accepts(
            "pub let f = { c: | Red { n: Int }, Pink | } -> match c is\n" +
                "  Red { n = _ } -> 1\n  Pink -> 2\n$MAIN",
            "binding or ignoring a payload does not change coverage",
        )
    }

    @Test fun `a recursive group with a missing arm`() {
        rejects(
            """
            type Tree = | Leaf, Node { l: Tree, r: Tree } |
            pub let f = { t: Tree } -> match t is
              Leaf -> 1
            $MAIN
            """,
            "V0601",
            "recursion in the payload does not exempt the group from coverage",
        )
    }

    @Test fun `an or-pattern with a shared binding`() {
        accepts(
            "pub let f = { c: | A { v: Int }, B { v: Int } | } -> match c is\n" +
                "  A { v = n } | B { v = n } -> n\n$MAIN",
            "one arm may cover two tags when both bind the same name at the same type",
        )
    }

    @Test fun `a nested constructor pattern selects, it does not narrow`() {
        accepts(
            "pub let f = { c: | Outer { inner: | A, B | } | } -> match c is\n" +
                "  Outer { inner = A } -> 1\n  Outer { inner = B } -> 2\n$MAIN",
            "each arm SELECTS one inner tag; it must not narrow the payload's group to " +
                "that tag, or the second arm contradicts the first",
        )
    }

    @Test fun `coverage across a product of two groups`() {
        accepts(
            """
            pub let f = { a: | Red, Pink |, b: | Up, Down | } ->
              match { x = a, y = b } is
                { x = Red,  y = Up }   -> 1
                { x = Red,  y = Down } -> 2
                { x = Pink, y = Up }   -> 3
                { x = Pink, y = Down } -> 4
            $MAIN
            """,
            "all four combinations are present, so the product is covered. Blocks a core " +
                "shape — a state machine over (state, event) — when it fails",
        )
    }

    @Test fun `an arm outside a payload's group is extra, at depth`() {
        rejects(
            """
            pub let a = {} -> do
              let v = Some { val = Red }
              match v is
                Some { val = Red } -> 1
                Some { val = Pink } -> 2
                None -> 3
            $MAIN
            """,
            // T0201: the user sees a FIELD mismatch on `val`, whose inner cause
            // is the membership failure (V0704). Three wrappers in that chain
            // were carrying the inner code by the end of this work and a fourth
            // still flattens it; the outer tag is defensible either way, since
            // the fault at the outer level genuinely is a field mismatch.
            "T0201",
            "Same rule as at the top level, one level down: `val`'s group is | Red | " +
                "from the literal, so the Pink arm is extra. A payload is a record and " +
                "its fields are ordinary types, so nothing about depth changes the " +
                "answer.\n\nThis test previously expected the two arms to JOIN into " +
                "| Red, Pink |, which is what widening a bound value would have implied.",
        )
    }
}
