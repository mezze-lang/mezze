package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * `type` specification: what an alias may NAME, and what an impl on one may do.
 *
 * The governing rule is ONE SHAPE, ONE NAME, so that inference has exactly one
 * answer — `let x = { name = "Ada", age = 32 }` can only adopt `Person` while
 * `Person` is the only name for that shape. TypeAliasTransparencyTest pins the
 * rule itself and the transparency that follows from it. This file is the IMPL
 * question that sits on top: given an alias that is allowed, may it carry one?
 *
 * After the rule, exactly one alias kind can be declared AND cannot carry an
 * impl, so V0706 has exactly one subject left: the FUNCTION alias.
 *
 *   - a record or variant declaration keeps an IDENTITY, so `impl Norm for
 *     Point2` dispatches and is allowed
 *   - a primitive, seq-of-builtins, newtype or applied-name alias is refused at
 *     the DECLARATION by V0713, so no impl on one can exist to ask about
 *   - a FUNCTION alias is allowed — a function type is genuinely nameless, so
 *     naming it is what `type` is for — but `FunctionType` has no identity
 *     field, so the name does not survive to a use site and an impl on it would
 *     be silently dead. That is V0706.
 *
 * A WRONG TURN worth recording, because the record of it matters more now than
 * when I wrote it. I first refused the primitive ALIAS itself, having measured
 * that the stdlib and examples contain none. The measurement was right and the
 * conclusion was premature: TypeAliasTransparencyTest pinned the alias as
 * intended behaviour in 13 cases, and a corpus of USES was never going to show
 * a feature's own test file — so I retracted and refused only the impl.
 *
 * The decision later went the other way, on a stronger argument than the one I
 * had: a primitive alias is ERASED before inference finishes — measured, even on
 * an annotated return type — so it never reaches a signature, a diagnostic or a
 * hover, and cannot carry an impl. It is a comment the compiler spell-checks.
 * What made my first attempt wrong was the evidence I offered, not the
 * conclusion; a pinning test meant "someone decided this, go ask them", and
 * asking is what eventually settled it.
 */
class TypeAliasSpecTest : VariantSpecBase() {

    // ===================== V0706's one remaining subject: a function alias

    /**
     * The name cannot reach the use site, so the impl would never run. Unlike
     * the primitive case this is NOT redundant — a function alias is a real,
     * allowed abbreviation; it simply cannot be a dispatch target.
     */
    @Test fun `an impl on a function alias is refused`() {
        rejects(
            """
            use std::int
            type Handler = { n: Int } -> Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Handler where { norm = { it } -> 1 }
            $MAIN
            """,
            "V0706",
            "Refused where it is WRITTEN, not at the use site that is surprised by " +
                "it. `FunctionType` has no identity field, so `Handler` is lost " +
                "between the impl and the receiver.",
        )
    }

    /** An INHERENT impl on one, by the same reasoning. */
    @Test fun `an inherent impl on a function alias is refused`() {
        rejects(
            """
            use std::int
            type Handler = { n: Int } -> Int
            impl Handler where { twice = { it: Handler } -> 2 }
            $MAIN
            """,
            "V0706",
            "The inherent path resolves aliases first and reaches the same answer; " +
                "InherentImplOwnershipSpecTest pins that half of it.",
        )
    }

    /** And the diagnosis names `newtype`, which is what the author meant. */
    @Test fun `the refusal points at newtype`() {
        val r = check(
            """
            use std::int
            type Handler = { n: Int } -> Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Handler where { norm = { it } -> 1 }
            $MAIN
            """.trimIndent()
        )
        val msg = (r.exceptionOrNull() as? TypeCheckError)?.let {
            it.message + "\n" + it.hints.joinToString("\n")
        } ?: ""
        assert(msg.contains("newtype Handler = { n: Int } -> Int")) {
            "the fix must be spelled out, not implied; got:\n$msg"
        }
    }

    /**
     * The hint names the REAL type, not a description of it.
     *
     * It first said "a function type", which produced `newtype H = a function
     * type` — a hint that reads as a fix and cannot be pasted. Worse than no
     * hint. Now the annotation the author would have written is rendered.
     */
    @Test fun `the hint renders the underlying type as code`() {
        val e = check(
            """
            use std::int
            type Handler = { n: Int } -> Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Handler where { norm = { it } -> 1 }
            $MAIN
            """.trimIndent()
        ).exceptionOrNull() as? TypeCheckError
        val hints = e?.hints?.joinToString("\n") ?: ""
        assert(hints.contains("`impl … for { n: Int } -> Int`")) {
            "the impl hint must be pasteable; got:\n$hints"
        }
        assert(hints.contains("`newtype Handler = { n: Int } -> Int`")) {
            "the newtype hint must be pasteable; got:\n$hints"
        }
    }

    /** And both routes the hint offers actually work. */
    @Test fun `both routes the hint offers are real`() {
        accepts(
            """
            use std::int
            type Handler = { n: Int } -> Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for ({ n: Int } -> Int) where { norm = { it } -> 1 }
            $MAIN
            """,
            "Route one: implement for the underlying type. A hint that named an " +
                "impossible impl would be worse than silence.",
        )
        accepts(
            """
            use std::int
            newtype Handler = { n: Int } -> Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Handler where { norm = { it } -> 1 }
            $MAIN
            """,
            "Route two: a newtype, which carries its own impls.",
        )
    }

    // ========================================= newtype is the way to do it

    /** `newtype` does what the alias looked like it was doing. */
    @Test fun `a newtype of a primitive is the distinct type`() {
        accepts(
            """
            use std::int
            newtype Id = Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Id where { norm = { it } -> 1 }
            pub let u = { i: Id } -> i.norm {}
            $MAIN
            """,
            "Nominal, so the impl is FOUND. This is the test that makes V0713 a " +
                "redirection rather than a removal.",
        )
    }

    /** And two newtypes of one payload stay distinct. */
    @Test fun `two newtypes of the same primitive are distinct`() {
        rejects(
            """
            use std::int
            newtype Meters = Int
            newtype Feet = Int
            pub let takes = { m: Meters } -> 1
            pub let u = { f: Feet } -> takes { m = f }
            $MAIN
            """,
            "T0201",
            "What an author writing `type Meters = Int` was reaching for and could " +
                "never have got: two ALIASES of `Int` interchanged freely, which is " +
                "why they are now refused outright rather than merely barred from " +
                "carrying impls.",
        )
    }

    /**
     * The argument that used to permit two aliases of one primitive, turned
     * around. It was "they interchange freely and so cannot compete for
     * dispatch" — true, and the reason they were legal together. Taken
     * seriously it says the names mean nothing at all, which is a reason to
     * refuse each of them rather than to allow both.
     */
    @Test fun `two aliases of one primitive are now each refused`() {
        for (decl in listOf("type Meters = Int", "type Feet = Int")) {
            rejects(
                """
                use std::int
                $decl
                $MAIN
                """,
                "V0713",
                "Refused individually, so the pair never arises.",
            )
        }
    }

    // ========================================= what a `type` may still name

    /** A record declaration is the case `type` is FOR. */
    @Test fun `a record alias is still allowed`() {
        accepts(
            """
            use std::int
            type Point2 = { x: Int, y: Int }
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Point2 where { norm = { it } -> it.x + it.y }
            pub let u = { p: Point2 } -> p.norm {}
            $MAIN
            """,
            "Keeps an identity, so the name reaches dispatch — and it names a shape " +
                "that had no name. Both reasons the primitive case lacks.",
        )
    }

    /** A variant declaration too. */
    @Test fun `a variant alias is still allowed`() {
        accepts(
            """
            use std::int
            type Color = | Red, Green |
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Color where { norm = { it } -> 1 }
            pub let u = { c: Color } -> c.norm {}
            $MAIN
            """,
            "Same reasoning as a record: a VariantType carries its identity.",
        )
    }

    /** A function alias — declarable, just not a dispatch target. */
    @Test fun `a function alias is still allowed`() {
        accepts(
            """
            use std::int
            type Handler = { n: Int } -> Int
            pub let run = { h: Handler } -> h { n = 1 }
            pub let go = {} -> run { h = { n } -> n + 1 }
            $MAIN
            """,
            "A function type is nameless, so this is a first name, and the whole " +
                "value of it is the abbreviation at the signatures that use it.",
        )
    }

    @Test fun `a generic record alias is still allowed`() {
        accepts(
            """
            use std::int
            type Box 'a = { v: 'a }
            pub let u = { b: Box Int } -> b.v
            $MAIN
            """,
            "A record declaration with parameters is still a record declaration.",
        )
    }

    /**
     * A seq alias needs its element to be a type the author NAMED. `[Row]`
     * names a collection of their own things; `[{ k: Str, v: Str }]` names a
     * collection of a shape that itself has no name, which is backwards — the
     * element is what wanted naming.
     */
    @Test fun `a seq alias over a named element is allowed and over an anonymous one is not`() {
        accepts(
            """
            use std::int
            use std::str
            type Row = { k: Str, v: Str }
            type Rows = [Row]
            pub let count = { r: Rows } -> 1
            $MAIN
            """,
            "Name the element, then the collection. Two declarations, each naming " +
                "something that had no name.",
        )
        rejects(
            """
            use std::str
            type Rows = [{ k: Str, v: Str }]
            $MAIN
            """,
            "V0713",
            "The inner shape has no name while the collection does. Refused so the " +
                "author names the row, which is the thing they will want to talk " +
                "about anyway.",
        )
    }
}
