package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * Record specification, §11.13: coverage multiplies across a record's fields.
 *
 * This is what "a record is a product" actually means. §02 counts tags; the same
 * counting over a product counts combinations. So a match over a record of two
 * `Bool` fields is exhaustive at four arms, and a missing combination is a
 * coverage error naming that combination.
 *
 * **This is the rule that unblocks variant 5.16.** That rule is this same
 * program and is recorded as rejected today — "two variants cannot be matched
 * together at all, which rules out the ordinary state-machine shape over
 * (state, event)". The blocker was a RECORD rule, not a variant one, which is
 * why it sat open through the whole variant pass.
 *
 * Cost: coverage over a product is exponential in the field count, so the
 * checker has to report ONE missing combination usefully rather than enumerate
 * 2^n of them.
 */
class RecordSpecCoverageTest : VariantSpecBase() {

    /** 11.13 — all four combinations present, so no wildcard is needed. */
    @Test fun `all combinations of two fields is exhaustive`() {
        accepts(
            """
            use std::int
            pub let f = { r: { x: Bool, y: Bool } } -> match r is
              { x = True,  y = True  } -> 1
              { x = True,  y = False } -> 2
              { x = False, y = True  } -> 3
              { x = False, y = False } -> 4
            $MAIN
            """,
            "Four arms over two Bool fields is the whole product, so the match is complete " +
                "and a wildcard would be unreachable (5.3). This is the state-machine shape " +
                "over (state, event) that 5.16 records as rejected today.",
        )
    }

    /** 11.13 — and a missing combination is a coverage error. */
    @Test fun `a missing combination is refused`() {
        rejects(
            """
            use std::int
            pub let f = { r: { x: Bool, y: Bool } } -> match r is
              { x = True,  y = True  } -> 1
              { x = True,  y = False } -> 2
              { x = False, y = True  } -> 3
            $MAIN
            """,
            "V0601",
            "`{ x = False, y = False }` is unhandled, and the diagnostic should name that " +
                "combination rather than listing the three that are covered. The code is V0601 (the match does not cover all cases) and not V0602 (a record PATTERN does not mention every field) — the two are different failures and the site picks the coverage one, correctly. Without this " +
                "the acceptance above would only show that the checker ignores records.",
        )
    }

    /** 11.13 — a wildcard still rescues an incomplete match. */
    @Test fun `a wildcard covers the remaining combinations`() {
        accepts(
            """
            use std::int
            pub let f = { r: { x: Bool, y: Bool } } -> match r is
              { x = True,  y = True } -> 1
              { x = _,     y = _    } -> 2
            $MAIN
            """,
            "The ordinary escape, and the control for the rejection above: the same " +
                "incomplete enumeration becomes complete when the rest is written.",
        )
    }

    /** 11.13 — the product recurses into a variant-typed field. */
    @Test fun `coverage multiplies over a variant-typed field`() {
        accepts(
            """
            use std::int
            type Light = | Red, Green |
            pub let f = { r: { light: Light, on: Bool } } -> match r is
              { light = Red,   on = True  } -> 1
              { light = Red,   on = False } -> 2
              { light = Green, on = True  } -> 3
              { light = Green, on = False } -> 4
            $MAIN
            """,
            "The case 5.16 is actually about: a record whose fields are GROUPS. The product " +
                "is over each field's own coverage — two tags times two tags — so the same " +
                "four arms complete it.",
        )
    }
}
