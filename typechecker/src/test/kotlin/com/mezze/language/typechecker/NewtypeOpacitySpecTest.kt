package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A `newtype` is OPAQUE, not merely nominal.
 *
 * Its constructor is private to the module that declares it, for BOTH
 * construction and pattern matching:
 *
 *     # units.mz
 *     pub newtype Millis = Int
 *     pub let make = { n: Int } -> Millis.(n)     # fine, inside
 *
 *     # anywhere else
 *     Millis.(5)                                  # M0803
 *     match m is Millis.(n) -> n                   # M0803
 *
 * So another module can HOLD a `Millis`, pass it and return it, and can neither
 * make nor unmake one. The defining module decides what operations exist — which
 * is the whole value of the construct: `newtype Millis = Int` with a public
 * constructor would be a nominal type you could forge, and the invariant the
 * author wanted would last exactly as long as nobody wrote `Millis.(-1)`.
 *
 * Transparent INSIDE, opaque OUTSIDE is Roc's opaque-type semantics, and
 * Haskell's "do not export the constructor" pattern — except enforced by
 * default here rather than opt-in, so the safe thing is what you get for free.
 *
 * Within a module the payload is still reached only by MATCHING, never by field
 * access, even when the payload is a record. Opacity is not relaxed for the
 * shape that would make it convenient.
 */
class NewtypeOpacitySpecTest : VariantSpecBase() {

    // ===================================== opaque across a module boundary

    private fun givenUnits() = writeModule(
        "units",
        """
        use std::int
        pub newtype Millis = Int
        pub let make = { n: Int } -> Millis.(n)
        pub let get = { m: Millis } -> match m is
          Millis.(n) -> n
        """,
    )

    /** Another module may not CONSTRUCT one. */
    @Test fun `a newtype cannot be constructed outside its module`() {
        givenUnits()
        rejectsMain(
            """
            use std::int
            use units::{ Millis }
            pub let m = {} -> Millis.(5)
            $MAIN
            """.trimIndent(),
            "M0803",
            "If a caller can forge one, the invariant the author wrapped it for lasts " +
                "only until someone writes `Millis.(-1)`. The defining module hands out " +
                "the constructors it is willing to stand behind.",
        )
    }

    /** Nor DESTRUCTURE one — the other half, and the one easy to forget. */
    @Test fun `a newtype cannot be destructured outside its module`() {
        givenUnits()
        rejectsMain(
            """
            use std::int
            use units::{ Millis }
            pub let un = { m: Millis } -> match m is
              Millis.(n) -> n
            $MAIN
            """.trimIndent(),
            "M0803",
            "Reading the representation is as much a leak as writing it: a caller that " +
                "can unwrap depends on the payload's type, and the author can never " +
                "change it. Opacity that only blocked construction would be half a rule.",
        )
    }

    /** But HOLDING one is fine — opaque is not unusable. */
    @Test fun `a newtype may be passed around outside its module`() {
        givenUnits()
        acceptsMain(
            """
            use std::int
            use units::{ Millis, make, get }
            pub let round_trip = {} -> get { m = make { n = 5 } }
            $MAIN
            """.trimIndent(),
            "The point of the construct: a caller works through the operations the " +
                "module exports and never sees the representation. If this failed, " +
                "opacity would have made the type useless rather than safe.",
        )
    }

    /** And inside its own module the constructor is available. */
    @Test fun `a newtype is transparent inside its own module`() {
        accepts(
            """
            use std::int
            pub newtype Millis = Int
            pub let make = { n: Int } -> Millis.(n)
            pub let get = { m: Millis } -> match m is
              Millis.(n) -> n
            $MAIN
            """,
            "Transparent inside, opaque outside — Roc's rule. The author needs the " +
                "representation to write the operations; nobody else does.",
        )
    }

    // ===================================== opaque to field access, always

    /** Even a RECORD payload is reached only by matching. */
    @Test fun `a newtype over a record has no field access`() {
        rejects(
            """
            use std::int
            type P = { x: Int, y: Int }
            newtype Point = P
            pub let getx = { p: Point } -> p.x
            $MAIN
            """,
            "T0205",
            "The shape where relaxing opacity would be most convenient, and therefore " +
                "the one that decides whether the rule means anything. Allowing `p.x` " +
                "would make a newtype partly transparent, and then `p.x` working while " +
                "`p + p` does not needs explaining.",
        )
    }

    /** The match form is how you get in. */
    @Test fun `matching is how a record payload is read`() {
        accepts(
            """
            use std::int
            type P = { x: Int, y: Int }
            newtype Point = P
            pub let getx = { p: Point } -> match p is
              Point.(r) -> r.x
            $MAIN
            """,
            "One way in, stated once. The cost is a match per read; the benefit is that " +
                "the author can change the payload without changing every caller.",
        )
    }

    // ===================================== one name, one declaration

    /** A `type` and a `newtype` may not share a name. */
    @Test fun `a type and a newtype cannot share a name`() {
        rejects(
            """
            use std::int
            type Id = Int
            newtype Id = Int
            $MAIN
            """,
            "V0701",
            "They mean opposite things — a spelling and a distinct type — so a name " +
                "carrying both would make every use site ambiguous about which it got.",
        )
    }

    /** Two newtypes likewise. */
    @Test fun `two newtypes cannot share a name`() {
        rejects(
            """
            use std::int
            use std::str
            newtype Id = Int
            newtype Id = Str
            $MAIN
            """,
            "V0701",
            "The ordinary duplicate-declaration rule; pinned here because the newtype " +
                "constructor lives in its own namespace and could plausibly have been " +
                "missed by it.",
        )
    }
}
