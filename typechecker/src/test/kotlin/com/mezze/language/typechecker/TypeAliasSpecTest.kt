package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * `type` specification: a SPELLING, never a new type.
 *
 * A `type` is transparent everywhere — `type Id = Int` lets any `Int` pass where
 * an `Id` is asked for, and back again. That is the whole of what it is, and it
 * decides what it may be used for:
 *
 *   - For a RECORD or VARIANT it earns its keep. The type keeps an IDENTITY, so
 *     the name survives to dispatch (`impl Norm for Point2` works) and the name
 *     stands for something long.
 *   - For a bare PRIMITIVE the alias itself is fine and SUPPORTED: it reads as
 *     documentation in a signature, and it INHERITS the primitive's impls, which
 *     TypeAliasTransparencyTest pins (`type MyInt = Int` already has `Eq` and
 *     arithmetic, with no impl written). What is refused is declaring an IMPL on
 *     it — `IntType` has nowhere to record "this was called Id", so the alias
 *     name never reaches dispatch and the impl would be silently dead.
 *
 * `newtype` is the construct for a distinct type, and in Mezze it costs one
 * line — which is why `type` can afford to be only a spelling. Rust's users
 * reach for alias tricks because its newtype pattern costs real boilerplate.
 *
 * A WRONG TURN worth recording. I first refused the primitive ALIAS itself,
 * having measured that the stdlib and examples contain none. The measurement was
 * right and the conclusion was not: TypeAliasTransparencyTest pins the alias as
 * intended behaviour in 13 cases, and a corpus of USES was never going to show a
 * feature's own test file. Only the impl is refused.
 */
class TypeAliasSpecTest : VariantSpecBase() {

    // ========================================= a primitive alias is refused

    /** An IMPL on a transparent alias of a primitive can never be found. */
    @Test fun `an impl on a primitive alias is refused`() {
        rejects(
            """
            use std::int
            type Id = Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Id where { norm = { it } -> 1 }
            $MAIN
            """,
            "V0706",
            "Refused where it is WRITTEN, not at the use site that is surprised by " +
                "it — the same instinct as an undeclared type name being an error on " +
                "its own line. Without this the author gets a type that silently is " +
                "`Int`, and an impl on it that silently never runs.",
        )
    }

    /** Every primitive, since the reason is about the representation. */
    @Test fun `an impl on any primitive alias is refused`() {
        for (prim in listOf("Str", "Float", "Bool", "Char")) {
            rejects(
                """
                type Alias$prim = $prim
                ability Norm$prim for 'a where { norm$prim: { it: 'a } -> Bool }
                impl Norm$prim for Alias$prim where { norm$prim = { it } -> True }
                $MAIN
                """,
                "V0706",
                "`$prim` has no identity field either — the rule is about what the type " +
                    "node can carry, not about `Int` specifically.",
            )
        }
    }

    /** And the diagnosis names `newtype`, which is what the author meant. */
    @Test fun `the refusal points at newtype`() {
        val r = check(
            """
            use std::int
            type Id = Int
            ability Norm for 'a where { norm: { it: 'a } -> Int }
            impl Norm for Id where { norm = { it } -> 1 }
            $MAIN
            """.trimIndent()
        )
        val msg = (r.exceptionOrNull() as? TypeCheckError)?.let {
            it.message + "\n" + it.hints.joinToString("\n")
        } ?: ""
        assert(msg.contains("newtype Id = Int")) {
            "the fix must be spelled out, not implied; got:\n$msg"
        }
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
            "Nominal, so the impl is FOUND — the exact thing the alias could not do. " +
                "This is the test that makes the refusal above a redirection rather " +
                "than a removal.",
        )
    }

    /** And two newtypes of one payload stay distinct, unlike two aliases. */
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
            "What an author wanting `type Meters = Int` was reaching for. Two ALIASES " +
                "of `Int` interchange freely and are legal together precisely because " +
                "they mean nothing; two newtypes do not.",
        )
    }

    // ========================================= compound aliases stay

    /** A record alias is the case `type` is FOR. */
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
            "Keeps an identity, so the name reaches dispatch — and it stands for a " +
                "shape worth naming. Both reasons the primitive case lacks.",
        )
    }

    /** A variant alias too. */
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

    /** A seq alias shortens real text, so it stays — impls on it are 11.x's business. */
    @Test fun `a seq alias is still allowed`() {
        accepts(
            """
            use std::int
            use std::str
            type Rows = [{ k: Str, v: Str }]
            pub let count = { r: Rows } -> 1
            $MAIN
            """,
            "`[{ k: Str, v: Str }]` is worth a name; `Int` is not. The rule is about " +
                "a BARE primitive, so this is unaffected.",
        )
    }

    /** A function alias likewise. */
    @Test fun `a function alias is still allowed`() {
        accepts(
            """
            use std::int
            type Handler = { n: Int } -> Int
            pub let run = { h: Handler } -> h { n = 1 }
            $MAIN
            """,
            "Shortens a real signature. Nothing about the primitive rule reaches it.",
        )
    }

    /** A generic record alias — 11.14's case, unaffected. */
    @Test fun `a generic record alias is still allowed`() {
        accepts(
            """
            use std::int
            type Box 'a = { v: 'a }
            pub let get = { b: Box Int } -> b.v
            $MAIN
            """,
            "The parameterised form is a record alias and keeps every property of one.",
        )
    }

    /** Two aliases of one primitive remain legal — nothing adopts an `Int`. */
    @Test fun `two aliases of one primitive coexist`() {
        accepts(
            """
            use std::int
            type Id = Int
            type Count = Int
            pub let f = { i: Id, c: Count } -> i + c
            $MAIN
            """,
            "The one-shape-one-name claim (11.5) deliberately does not reach a " +
                "primitive: a bare `4` adopts nothing, so the ambiguity 11.5 exists to " +
                "prevent cannot arise. And they add freely, because both ARE `Int`.",
        )
    }
}
