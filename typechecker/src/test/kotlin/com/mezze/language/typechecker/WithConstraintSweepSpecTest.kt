package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The `with` CLAUSE as its own matrix.
 *
 * The ability sweep (27 shapes, 2026-11-13) probed what a `with` constraint
 * DELIVERS — through a callback, through a seq element, through an intermediary.
 * It did not probe the clause itself. This is that matrix: what may be written in
 * one, and what each spelling means. Everything here was measured with
 * `mez check` on 2026-11-15 before it was written down.
 *
 * Arity is next door in [WithConstraintAritySpecTest] (V0705, "every parameter");
 * a fully concrete constraint is in [ConcreteWithConstraintSpecTest] (V0408).
 * What is left is the clause's other degrees of freedom: repetition, ORDER, one
 * ability at two different class args, where the constrained variable may be
 * bound, and whether the declared signature survives.
 *
 * Why order has a test: the dictionary a constraint resolves to travels in a
 * named parameter slot, and those slots are collected from a SET and sorted.
 * Nothing in the clause's meaning depends on the order it is written in, so the
 * two orders must produce the same signature — which is the kind of thing that
 * holds until someone keys a map on position.
 */
class WithConstraintSweepSpecTest : VariantSpecBase() {

    private val abilities = """
        type Src = { n: Int }
        ability Weigh for 'a where { weigh: { it: 'a } -> Int }
        impl Weigh for Src where {
          weigh: { it: Src } -> Int = { it } -> 7
        }
        ability Make 'b for 'a where { make: { it: 'a } -> 'b }
        impl Make Int for Src where {
          make: { it: Src } -> Int = { it } -> 111
        }
        impl Make Str for Src where {
          make: { it: Src } -> Str = { it } -> "222"
        }
    """.trimIndent()

    /** The declared type of `p`, as the module exports it. */
    private fun typeOfP(src: String): MezzeType {
        val r = check(src)
        if (r.isFailure) throw AssertionError("expected this to check:\n$src\n\n${r.exceptionOrNull()?.message}")
        return r.getOrNull()?.getVariableType("p")
            ?: throw AssertionError("no declared type for `p`:\n$src")
    }

    /** The type of `p`'s `x` parameter field. */
    private fun paramFieldX(src: String): MezzeType {
        val fn = typeOfP(src) as? MezzeType.FunctionType
            ?: throw AssertionError("`p` is not a function:\n$src")
        val rec = fn.paramType as? MezzeType.RecordType
            ?: throw AssertionError("`p`'s parameter is not a record: ${fn.paramType}")
        return rec.fields["x"] ?: throw AssertionError("no `x` field in ${fn.paramType}")
    }

    @Test fun `the same constraint written twice is accepted`() {
        accepts(
            """
            $abilities
            pub let p: { x: 'a } with Make Int 'a, Make Int 'a -> Int = { x } -> x.make {}
            $MAIN
            """,
            "A repeated constraint asks for nothing a single one does not. Refusing " +
                "it would be a style rule, and this is a type system.",
        )
    }

    @Test fun `one ability at two different class args, both used`() {
        accepts(
            """
            $abilities
            pub let p: { x: 'a } with Make Int 'a, Make Str 'a -> Str = { x } -> do
              let i: Int = x.make {}
              let s: Str = x.make {}
              s
            $MAIN
            """,
            "Two constraints on ONE ability differing only in the class arg, and " +
                "both ops called — the shape BUG 39 was about (a class param in the " +
                "RETURN is reachable from no argument, so dispatch has to ask what " +
                "the call PRODUCES). `Into` was broken for every target but one.",
        )
    }

    @Test fun `the order two constraints are written in does not change the type`() {
        val ab = """
            $abilities
            pub let p: { x: 'a } with Weigh 'a, Make Int 'a -> Int = { x } -> x.weigh {}
            $MAIN
        """
        val ba = """
            $abilities
            pub let p: { x: 'a } with Make Int 'a, Weigh 'a -> Int = { x } -> x.weigh {}
            $MAIN
        """
        val tyAB = typeOfP(ab) as MezzeType.FunctionType
        val tyBA = typeOfP(ba) as MezzeType.FunctionType
        assertTrue(
            tyAB.abilityConstraints.map { it.abilityName }.toSet() ==
                tyBA.abilityConstraints.map { it.abilityName }.toSet(),
            "the two orders must carry the SAME constraints, and they do not:\n" +
                "  as written A,B: ${tyAB.abilityConstraints.map { it.abilityName }}\n" +
                "  as written B,A: ${tyBA.abilityConstraints.map { it.abilityName }}\n" +
                "Dict slots are collected from a set and sorted, so order is not " +
                "supposed to reach the type — but that is an invariant, not a given.",
        )
        assertTrue(
            tyAB.paramType == tyBA.paramType && tyAB.returnType == tyBA.returnType,
            "same clause, two orders, different arrow: ${tyAB.paramType} -> ${tyAB.returnType} " +
                "vs ${tyBA.paramType} -> ${tyBA.returnType}",
        )
    }

    @Test fun `the constrained variable may be bound by a callback's own arrow`() {
        accepts(
            """
            $abilities
            pub let p: { k: { x: 'b } -> 'b } with Weigh 'b -> Int = { k } -> 0
            $MAIN
            """,
            "`'b` is introduced by a PARAMETER's function type, not by the outer " +
                "arrow's own parameters. The constraint still has somewhere to come " +
                "from, so it is not the T0215 case (a type no caller can choose).",
        )
    }

    @Test fun `the constrained variable used at two types in the body is refused`() {
        rejects(
            """
            $abilities
            pub let p: { x: 'a } with Weigh 'a -> Int = { x } -> do
              let a: Int = x
              let b: Str = x
              1
            $MAIN
            """,
            "T0201",
            "A constraint does not loosen the variable it constrains: `'a` is still " +
                "ONE type, and the body cannot read it as two.",
        )
    }

    @Test fun `an unknown type name inside a constraint is refused`() {
        rejects(
            """
            $abilities
            pub let p: { x: Int } with Make Int Nope -> Int = { x } -> 1
            $MAIN
            """,
            "T0213",
            "`Nope` names no type. This has to stay distinct from the \"no impl\" " +
                "diagnostic — see [ConcreteWithConstraintSpecTest], whose V0408 " +
                "would otherwise send the author off to implement an ability for a " +
                "type that does not exist.",
        )
    }

    @Test fun `a receiver-only constraint keeps the type variable the author wrote`() {
        val ty = paramFieldX(
            """
            $abilities
            pub let p: { x: 'a } with Weigh 'a -> Int = { x } -> x.weigh {}
            $MAIN
            """
        )
        assertTrue(
            ty is MezzeType.VarType,
            "the author wrote `{ x: 'a }` and the declared type says `x: $ty`. " +
                "Exactly one `impl Weigh` is in scope, and a unique impl is not a " +
                "reason to solve a variable the author quantified.",
        )
    }

    @Test fun `a class-param constraint keeps it too, and does not depend on how many impls are in scope`() {
        val oneImpl = """
            type Src = { n: Int }
            type Other = { m: Int }
            ability Make 'b for 'a where { make: { it: 'a } -> 'b }
            impl Make Int for Src where {
              make: { it: Src } -> Int = { it } -> 111
            }
            pub let p: { x: 'a } with Make Int 'a -> Int = { x } -> x.make {}
            $MAIN
        """
        val twoImpls = """
            type Src = { n: Int }
            type Other = { m: Int }
            ability Make 'b for 'a where { make: { it: 'a } -> 'b }
            impl Make Int for Src where {
              make: { it: Src } -> Int = { it } -> 111
            }
            impl Make Int for Other where {
              make: { it: Other } -> Int = { it } -> 222
            }
            pub let p: { x: 'a } with Make Int 'a -> Int = { x } -> x.make {}
            $MAIN
        """
        val withOne = paramFieldX(oneImpl)
        val withTwo = paramFieldX(twoImpls)

        // MEASURED before the fix: `x: Src` with one impl in scope and `x: 'a`
        // with two. The two programs differ ONLY in an impl for an unrelated
        // type, so adding one WIDENED a published signature — and a published
        // signature is content-addressed, so the hash moved with it.
        assertTrue(
            withTwo is MezzeType.VarType,
            "two impls in scope and `x: $withTwo` — the baseline this compares " +
                "against is itself wrong, so read the whole test.",
        )
        assertTrue(
            withOne is MezzeType.VarType,
            "the author wrote `{ x: 'a }` and got `x: $withOne` because exactly ONE " +
                "`impl Make Int` is in scope. Adding `impl Make Int for Other` — " +
                "an impl this declaration never mentions — changes it back to " +
                "`x: $withTwo`. A written annotation is not a hint: a unique impl " +
                "is a fact about scope, and scope must not decide an exported type.",
        )
    }
}
