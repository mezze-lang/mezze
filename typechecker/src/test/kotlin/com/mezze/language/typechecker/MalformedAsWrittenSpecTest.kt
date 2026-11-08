package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * A module that is not well formed AS WRITTEN is answered for what is written,
 * not for what the solver then made of it.
 *
 * Only the FIRST collected error is reported, and the checks that read just the
 * parsed module and the registry — does this name resolve, did this constructor
 * get the right number of arguments, can this type be built at all — used to
 * run at the END, beside the ones that need a substitution. So a program whose
 * annotation named an unimported type was told:
 *
 *     Cannot use '+' with Int, PubId. To enable this, implement:
 *     impl NumAdd PubId for Int ...
 *
 * advice to write an impl for a type that does not exist. Nothing was wrong with
 * the solver — it reasoned faithfully about a type it had been handed and
 * reported what it found. The real diagnostic was sitting further down the list.
 *
 * Rust reports E0412 "cannot find type" and does not go on to complain about the
 * operator. `validateAsWritten` now runs right after the registry is built and
 * returns before the solver is asked anything, so the module never reaches
 * inference with a name that does not resolve.
 *
 * The second subject here is `newtype T = T`, which the PARSER used to claim was
 * old syntax — "drop the leading `T`", which leaves `newtype T =` and advises
 * nothing. Only the APPLIED spelling `newtype X = X Int` is that legacy form; a
 * bare repeat has no payload for the dropped constructor to have wrapped, so it
 * is a type that wraps itself, and the honest answer is that no value of it can
 * be built. Rust says the same with E0072.
 */
class MalformedAsWrittenSpecTest : VariantSpecBase() {

    // ========================= the real cause wins over the consequence

    @Test fun `an unresolved type name is reported, not the arithmetic it breaks`() {
        writeModule("sib", "use std::int\npub type PubId = Int")
        rejectsMain(
            """
            use std::int
            pub let u = { i: PubId } -> i + 1
            $MAIN
            """,
            "T0213",
            "`PubId` is declared in a sibling module and NOT imported here, so it " +
                "resolves to nothing. This used to report V0402 and suggest " +
                "`impl NumAdd PubId` — an impl for a type that does not exist.",
        )
    }

    /**
     * NOT an ordering case, and recorded as such: a method call on a name that
     * does not resolve produces no competing diagnostic, so this passes with the
     * checks at either end of the pipeline. It pins that the answer is the same
     * however the bad name is USED, which is worth having and is a different
     * claim from the one above.
     */
    @Test fun `an unresolved type name is reported however it is used`() {
        writeModule("sib", "use std::int\npub type PubId = Int")
        rejectsMain(
            """
            use std::int
            pub let u = { i: PubId } -> i.to_str {}
            $MAIN
            """,
            "T0213",
            "The name is answered before anything is inferred about it.",
        )
    }

    @Test fun `a bad arity is reported, not the arithmetic it breaks`() {
        rejects(
            """
            use std::int
            use std::option
            pub let u = { o: Option Int Str } -> o + 1
            $MAIN
            """,
            "V0705",
            "`+` on an `Option` is its own error, and with the arity check running " +
                "last that one came first — so the author was told to implement " +
                "arithmetic for a type whose arity was the actual mistake.",
        )
    }

    @Test fun `an ability in a type position is reported, not the arithmetic it breaks`() {
        rejects(
            """
            use std::int
            use std::abilities::compare::{ Eq }
            pub let u = { x: Eq } -> x + 1
            $MAIN
            """,
            "V0712",
            "Reporting `Cannot use '+' with Eq` would be answering a question " +
                "about a thing that is not a type.",
        )
    }

    /** The guard that matters: a real inference error must still report as one. */
    @Test fun `a genuine arithmetic error is still an arithmetic error`() {
        rejects(
            """
            use std::int
            pub let u = { s: Str } -> s + 1
            $MAIN
            """,
            "V0402",
            "Everything here is well formed as written — `Str` resolves, takes no " +
                "arguments, is a type. Moving the written-form checks earlier must " +
                "not swallow the errors that need the solver.",
        )
    }

    /** And a well-formed module must still reach the solver at all. */
    @Test fun `a well-formed module still type-checks`() {
        accepts(
            """
            use std::int
            use std::option
            type Id = Int
            pub let u = { i: Id, o: Option Int } -> i + 1
            $MAIN
            """,
            "The returning-early path must not return early on a healthy module.",
        )
    }

    // ============================== a newtype that wraps itself (V0711)

    @Test fun `a newtype that wraps itself cannot be built`() {
        rejects(
            """
            newtype T = T
            $MAIN
            """,
            "V0711",
            "To build a `T` you need a `T`, so there is no base case. The parser " +
                "used to call this old syntax and advise dropping the leading " +
                "`T` — leaving `newtype T =`, which is not a fix and not even a " +
                "program.",
        )
    }

    @Test fun `mutually self-wrapping newtypes cannot be built`() {
        rejects(
            """
            newtype A = B
            newtype B = A
            $MAIN
            """,
            "V0711",
            "The same reason one link further out, which is why inhabitedness is " +
                "a fixpoint rather than a local check.",
        )
    }

    /** The genuine legacy form keeps its own message. */
    @Test fun `the old redundant-constructor newtype form is still refused as such`() {
        val r = check(
            """
            use std::int
            newtype UserId = UserId Int
            $MAIN
            """.trimIndent(),
        )
        assert(r.isFailure) { "old syntax must be refused" }
        val msg = r.exceptionOrNull()?.message ?: ""
        assert(msg.contains("no longer accepted")) {
            "the APPLIED spelling is the legacy form and keeps the rewrite hint; got: $msg"
        }
    }

    /** An alias that is its own definition is a CYCLE, which is a different fault. */
    @Test fun `a self-referential alias is a cycle, not an uninhabited type`() {
        rejects(
            """
            type T = T
            $MAIN
            """,
            "V0709",
            "An alias EXPANDS, so naming itself never terminates — the fault is in " +
                "the expansion, before anything asks whether a value exists. A " +
                "newtype does not expand, so for it the only question is whether " +
                "one can be built.",
        )
    }

    /** Recursion with a base case stays legal, for both. */
    @Test fun `a newtype recursive through a seq is accepted`() {
        accepts(
            """
            newtype T = [T]
            $MAIN
            """,
            "An empty seq is the base case, so a `T` can be built. Pinned because " +
                "the uninhabited check has to tell this apart from `newtype T = T`.",
        )
    }
}
