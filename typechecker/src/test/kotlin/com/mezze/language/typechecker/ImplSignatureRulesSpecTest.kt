package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * WHAT MAY AN IMPL CHANGE about the signature it implements?
 *
 * The rules, measured rather than assumed, for both kinds of impl:
 *
 * | dimension | ability impl | effect performer |
 * |---|---|---|
 * | param type | INVARIANT — T0201 | INVARIANT — T0201/V0901 |
 * | param NAME | INVARIANT — T0201 | **UNCHECKED — BUG 37** |
 * | param arity | INVARIANT — T0201 | — |
 * | return type | COVARIANT: narrower yes, wider T0201 | must match — T0201 |
 * | effects | may be NARROWED; widening is V0421+V0510 | may be WIDENED, and the perform site answers for it (T0210) |
 * | `with` constraints | may be ADDED; unmet at the call is V0402 | — |
 *
 * Two of those are worth stating in words because they are not the obvious
 * answers. An impl may **add** a `with` constraint the ability never declared —
 * `std/collections/list.mz` does exactly that (`eq: { it: List 'a, r: List 'a }
 * with Eq 'a -> Bool`), and the obligation lands on the caller. And an effect
 * performer may **add** effects its op's declaration does not mention; they are
 * tracked, and a `perform` site that does not handle them is T0210.
 *
 * The one gap is the parameter NAME on a performer's op — see BUG 37 and the
 * break test below.
 */
class ImplSignatureRulesSpecTest : VariantSpecBase() {

    // ────────────────────────────────────────────────── ability impls

    @Test fun `an ability impl may not change a param type`() {
        rejects(
            """
            ability Op for 'a where { f: { it: 'a, n: Int } -> Int }
            type T = { x: Int }
            impl Op for T where { f: { it: T, n: Str } -> Int = { it, n } -> 1 }
            pub let run: {} -> Int = {} -> do
              let t: T = { x = 0 }
              t.f { n = 1 }
            $MAIN
            """,
            "T0201",
            "Params are INVARIANT. The message names the whole expected record, " +
                "`expected {it: T, n: Int}`, rather than the one field — which is " +
                "what makes the rename and arity cases below report identically.",
        )
    }

    @Test fun `an ability impl may not rename a param`() {
        rejects(
            """
            ability Op for 'a where { f: { it: 'a, n: Int } -> Int }
            type T = { x: Int }
            impl Op for T where { f: { it: T, m: Int } -> Int = { it, m } -> m }
            pub let run: {} -> Int = {} -> do
              let t: T = { x = 0 }
              t.f { n = 1 }
            $MAIN
            """,
            "T0201",
            "THE ABILITY SIDE OF BUG 37, and the reason that bug is a gap rather " +
                "than a design choice: the identical rename on a performer's op is " +
                "accepted.",
        )
    }

    @Test fun `an ability impl may not add a param`() {
        rejects(
            """
            ability Op for 'a where { f: { it: 'a, n: Int } -> Int }
            type T = { x: Int }
            impl Op for T where { f: { it: T, n: Int, extra: Int } -> Int = { it, n, extra } -> n }
            pub let run: {} -> Int = {} -> do
              let t: T = { x = 0 }
              t.f { n = 1 }
            $MAIN
            """,
            "T0201",
            "Arity is part of the record type, so an extra field is the same " +
                "mismatch as a wrong one.",
        )
    }

    @Test fun `an ability impl may return NARROWER than declared`() {
        accepts(
            """
            ability Op for 'a where { f: { it: 'a } -> | A, B, C | }
            type T = { x: Int }
            impl Op for T where { f: { it: T } -> | A, B | = { it } -> A }
            $MAIN
            """,
            "Return is COVARIANT. Promising fewer constructors than the ability " +
                "does is safe — every caller matched against the wider set.",
        )
    }

    @Test fun `an ability impl may NOT return wider than declared`() {
        rejects(
            """
            ability Op for 'a where { f: { it: 'a } -> | A, B | }
            type T = { x: Int }
            impl Op for T where { f: { it: T } -> | A, B, C | = { it } -> C }
            $MAIN
            """,
            "T0201",
            "THE MIRROR that makes the covariance claim mean something. Without " +
                "it, an implementation with no variance rule at all — rigid " +
                "equality — would pass the narrower test by refusing both.",
        )
    }

    @Test fun `an ability impl may not perform an effect the ability declares pure`() {
        rejects(
            """
            effect Log where { emit: { msg: Str } -> {} <Log> }
            impl Log for Silent where { emit = { msg } -> {} }
            ability Op for 'a where { f: { it: 'a } -> Int }
            type T = { x: Int }
            impl Op for T where { f: { it: T } -> Int = { it } -> do
              Log.emit { msg = "x" }
              1 }
            pub let run: {} -> Int <*> = {} -> perform Silent in do
              let t: T = { x = 0 }
              t.f {}
            $MAIN
            """,
            "V0421",
            "Effects may be NARROWED, never widened. A caller reading `-> Int` " +
                "with no row would otherwise perform Log without naming it.",
        )
    }

    @Test fun `an ability impl may leave a declared effect unused`() {
        accepts(
            """
            effect Log where { emit: { msg: Str } -> {} <Log> }
            impl Log for Silent where { emit = { msg } -> {} }
            ability Op for 'a where { f: { it: 'a } -> Int <Log> }
            type T = { x: Int }
            impl Op for T where { f: { it: T } -> Int <Log> = { it } -> 1 }
            pub let run: {} -> Int <*> = {} -> perform Silent in do
              let t: T = { x = 0 }
              t.f {}
            $MAIN
            """,
            "The narrowing direction, which must stay open: the ability PERMITS " +
                "Log and this impl simply does not use it.",
        )
    }

    @Test fun `an ability impl may ADD a with constraint`() {
        accepts(
            """
            ability Show for 'a where { show: { it: 'a } -> Int }
            ability Box for 'a where { unbox: { it: 'a } -> Int }
            type W 'a = { inner: 'a }
            impl Show for W 'a where {
              show: { it: W 'a } with Box 'a -> Int = { it } -> it.inner.unbox {}
            }
            type T = { x: Int }
            impl Box for T where { unbox: { it: T } -> Int = { it } -> it.x }
            pub let run: {} -> Int = {} -> do
              let w: W T = { inner = { x = 7 } }
              w.show {}
            $MAIN
            """,
            "NOT the obvious answer, and the stdlib depends on it: " +
                "`std/collections/list.mz` writes `eq: { it: List 'a, r: List 'a } " +
                "with Eq 'a -> Bool` against an ability declaring no constraint. " +
                "The obligation moves to the caller.",
        )
    }

    @Test fun `an added constraint is enforced at the call`() {
        rejects(
            """
            ability Show for 'a where { show: { it: 'a } -> Int }
            ability Box for 'a where { unbox: { it: 'a } -> Int }
            type W 'a = { inner: 'a }
            impl Show for W 'a where {
              show: { it: W 'a } with Box 'a -> Int = { it } -> it.inner.unbox {}
            }
            type U = { y: Int }
            pub let run: {} -> Int = {} -> do
              let w: W U = { inner = { y = 7 } }
              w.show {}
            $MAIN
            """,
            "V0402",
            "What keeps the addition sound — `U` has no Box impl, so the " +
                "constraint the IMPL added is refused at the USE SITE.",
        )
    }

    // ────────────────────────────────────────────── effect performers

    @Test fun `a performer may not change an op's return type`() {
        rejects(
            """
            effect E where { op: { n: Int } -> Int <E> }
            impl E for H where { op = { n } -> "s" }
            pub let run: {} -> Int <*> = {} -> perform H in E.op { n = 1 }
            $MAIN
            """,
            "T0201",
            "The return is checked against the op's declaration.",
        )
    }

    @Test fun `a performer may not change an op's param type`() {
        rejects(
            """
            effect E where { op: { n: Int } -> Int <E> }
            impl E for H where { op: { n: Str } -> Int = { n } -> 1 }
            pub let run: {} -> Int <*> = {} -> perform H in E.op { n = 1 }
            $MAIN
            """,
            "T0201",
            "The param TYPE is checked — which is what makes the unchecked NAME " +
                "below a gap rather than a decision.",
        )
    }

    @Test fun `BREAK - a performer may rename an op's params`() {
        accepts(
            """
            effect E where { op: { a: Int, b: Int } -> Int <E> }
            impl E for H where { op = { y, x } -> x }
            pub let run: {} -> Int <*> = {} -> perform H in E.op { a = 10, b = 20 }
            $MAIN
            """,
            "BREAK — known-bugs BUG 37. The op declares `{ a, b }` and the " +
                "performer destructures `{ y, x }`. MEASURED: `Check passed`, " +
                "`pub run : {} -> Int <*>`, then at the first arithmetic " +
                "`error[I0004]: cannot multiply a function and an Int`. Declared " +
                "Int, produced a FUNCTION. The one-parameter form yields the whole " +
                "argument record instead. The ability test above shows the same " +
                "rename IS checked for an ability impl.",
        )
    }

    @Test fun `a performer with the declared param names works`() {
        accepts(
            """
            effect E where { op: { a: Int, b: Int } -> Int <E> }
            impl E for H where { op = { a, b } -> a }
            pub let run: {} -> Int <*> = {} -> perform H in E.op { a = 10, b = 20 }
            $MAIN
            """,
            "THE CONTROL for the break above — measured at runtime as 10, so the " +
                "break's wrong value is the rename and not the shape of the probe.",
        )
    }

    @Test fun `a performer may ADD effects, and the perform site answers for them`() {
        accepts(
            """
            effect Log where { emit: { msg: Str } -> {} <Log> }
            impl Log for Silent where { emit = { msg } -> {} }
            effect E where { op: { n: Int } -> Int <E> }
            impl E for H where { op = { n } -> do
              Log.emit { msg = "x" }
              n }
            pub let run: {} -> Int <*> = {} -> perform Silent in perform H in E.op { n = 1 }
            $MAIN
            """,
            "The OPPOSITE of the ability rule, and deliberate: a performer is " +
                "where effects get interpreted, so it may use others. " +
                "`ExprPerformConstraints` reads `performerAddedEffects` for exactly " +
                "this.",
        )
    }

    @Test fun `a performer's added effect must be handled`() {
        rejects(
            """
            effect Log where { emit: { msg: Str } -> {} <Log> }
            impl Log for Silent where { emit = { msg } -> {} }
            effect E where { op: { n: Int } -> Int <E> }
            impl E for H where { op = { n } -> do
              Log.emit { msg = "x" }
              n }
            pub let run: {} -> Int <*> = {} -> perform H in E.op { n = 1 }
            $MAIN
            """,
            "T0210",
            "What keeps the widening sound — `<*>` claims everything is handled, " +
                "and H's added Log is not.",
        )
    }

    // ──────────────────────────────── VARIANT INCLUSION, the shared cause
    //
    // Round 1 only changed a param to an UNRELATED type (Int -> Str), which any
    // rule refuses — and both kinds do, T0201. Variance is a different
    // question, and it is where the performer side leaks.
    //
    // The cause is one thing: a performer's op signature is checked by
    // UNIFICATION, and variant rows UNIFY BY MERGING. So a narrower param or a
    // wider return unifies happily and nothing complains. It is the same root
    // cause that makes `PerformValidator.areAssociatedTypesCompatible` load
    // bearing — that is the one place in the effect path that asks the subset
    // question unification will not, and it asks it only about `having` types.
    // An op's parameter and return types have no equivalent.
    //
    // Abilities survive the same probe by a DIFFERENT mechanism: an impl
    // method's body is exhaustiveness-checked against the DECLARED type, so
    // omitting a constructor is V0601 before variance ever matters.

    @Test fun `an ability impl narrowing a param is caught by exhaustiveness`() {
        rejects(
            """
            ability Op for 'a where { f: { it: 'a, n: | A, B, C | } -> Int }
            type T = { x: Int }
            impl Op for T where {
              f: { it: T, n: | A, B | } -> Int = { it, n } -> match n is
                A -> 1
                B -> 2
            }
            pub let run: {} -> Int = {} -> do
              let t: T = { x = 0 }
              t.f { n = C }
            $MAIN
            """,
            "V0601",
            "The narrower ANNOTATION does not constrain — unification merges the " +
                "rows — but the body is exhaustiveness-checked against the " +
                "ability's declared `| A, B, C |`, so the missing arm is caught: " +
                "\"Missing case: C\". Sound by a different route than the type " +
                "check.",
        )
    }

    @Test fun `BREAK - a performer narrowing an op param is accepted`() {
        accepts(
            """
            effect E where { op: { n: | A, B, C | } -> Int <E> }
            impl E for H where {
              op: { n: | A, B | } -> Int = { n } -> match n is
                A -> 1
                B -> 2
            }
            pub let run: {} -> Int <*> = {} -> perform H in E.op { n = C }
            $MAIN
            """,
            "BREAK — known-bugs BUG 37. The same shape the ability test above " +
                "refuses with V0601. MEASURED: check passes, then " +
                "`error[I0004]: Non-exhaustive match: no variant tag matched — " +
                "typechecker exhaustiveness guarantee violated`. The runtime says " +
                "the quiet part out loud: a guarantee was violated, not a program " +
                "was wrong.",
        )
    }

    @Test fun `BREAK - a performer widening an op return is accepted`() {
        accepts(
            """
            effect E where { op: {} -> | A, B | <E> }
            impl E for H where { op: {} -> | A, B, C | = {} -> C }
            pub let run: {} -> Int <*> = {} -> perform H in do
              let r = E.op {}
              match r is
                A -> 1
                B -> 2
            $MAIN
            """,
            "BREAK — the other direction, same cause. The op promises " +
                "`| A, B |`, the performer returns `C`, and a caller matching " +
                "exactly the two declared constructors gets the same I0004 " +
                "exhaustiveness violation. The ABILITY return IS checked (see the " +
                "wider-return test above, T0201), because an ability impl's type " +
                "is compared by `ImplValidator` rather than left to unification.",
        )
    }

    @Test fun `a performer widening an op param is fine`() {
        accepts(
            """
            effect E where { op: { n: | A, B | } -> Int <E> }
            impl E for H where {
              op: { n: | A, B, C | } -> Int = { n } -> match n is
                A -> 1
                B -> 2
                C -> 3
            }
            pub let run: {} -> Int <*> = {} -> perform H in E.op { n = A }
            $MAIN
            """,
            "THE SOUND DIRECTION, and the reason a fix cannot simply demand " +
                "equality: accepting MORE than the declaration promises is " +
                "contravariance, and is safe. Only the narrowing direction is the " +
                "hole.",
        )
    }
}
