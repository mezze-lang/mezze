package com.mezze.language.typechecker

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Recursion in a type must have somewhere to STOP.
 *
 * Two refusals, both at the declaration:
 *
 *   - a cycle of pure ALIASES (`type Loop = Loop`) — V0709
 *   - recursion with NO BASE CASE (`type Endless = { next: Endless }`) — V0711
 *
 * MODELLED ON ROC, which states it most sharply: recursion is allowed only
 * behind a tag union with at least one non-recursive variant. Rust (`E0391`),
 * Haskell ("Cycle in type synonym declarations") and Elm refuse cyclic aliases
 * too, and all four point at their nominal construct, because a constructor is
 * what supplies the indirection. Generalised slightly here: a SEQUENCE is also
 * an escape, since the empty sequence inhabits `[T]` whatever `T` is.
 *
 * Both were accepted before. The second was the quieter one — the type checked
 * and the error arrived at CONSTRUCTION, as `Field 't' type mismatch: expected
 * Int, got Endless` pointing at the innermost literal.
 */
class RecursiveTypeSpecTest : VariantSpecBase() {

    // ===================================== cycles of aliases

    /** A self-referential alias. */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test fun `a self-referential alias is refused`() {
        rejects(
            """
            type Loop = Loop
            pub let f = { l: Loop } -> 1
            $MAIN
            """,
            "V0709",
            "A `type` is a SPELLING, so using it substitutes its definition and this " +
                "never ends. Accepted before, and every USE then failed with `expects " +
                "Loop but got Int` — the error 6.5 exists to move onto the declaration.",
        )
    }

    /** A mutual cycle. */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test fun `a mutual alias cycle is refused`() {
        rejects(
            """
            type A = B
            type B = A
            pub let f = { a: A } -> 1
            $MAIN
            """,
            "V0709",
            "Reported once for the cycle rather than once per participant, or two " +
                "declarations each blame the other and neither reads as the cause.",
        )
    }

    /** The message shows the cycle as a PATH. */
    @Test fun `the diagnosis names the cycle and what still works`() {
        val r = check(
            """
            type A = B
            type B = A
            $MAIN
            """.trimIndent()
        )
        val e = r.exceptionOrNull() as? TypeCheckError
        val msg = (e?.message ?: "") + "\n" + (e?.hints?.joinToString("\n") ?: "")
        assert(msg.contains("A → B → A")) {
            "the cycle must be shown as a path, not asserted; got:\n$msg"
        }
        assert(msg.contains("recursion DOES work")) {
            "the diagnosis must teach the boundary, or `cycle` reads as `no recursion " +
                "allowed`; got:\n$msg"
        }
    }

    // ===================================== no base case

    /** A record whose only field is itself. */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test fun `a record with no base case is refused`() {
        rejects(
            """
            type Endless = { next: Endless }
            pub let f = { e: Endless } -> 1
            $MAIN
            """,
            "V0711",
            "Every value would contain another, forever — the type has NO VALUES. " +
                "Before this it checked, and the author met a type mismatch about the " +
                "innermost literal when they tried to build one.",
        )
    }

    /** A variant whose every arm recurses — Roc's rule exactly. */
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @Test fun `a variant with no non-recursive arm is refused`() {
        rejects(
            """
            type Endless = | Node { t: Endless } |
            pub let f = { t: Endless } -> 1
            $MAIN
            """,
            "V0711",
            "Roc's rule: recursion is allowed behind a tag union only if at least one " +
                "arm does NOT recurse. `Node` is the only arm and it does.",
        )
    }

    /** And the hint is phrased for the shape that is wrong. */
    @Test fun `the hint fits the declaration's own shape`() {
        val variant = check(
            """
            type Endless = | Node { t: Endless } |
            $MAIN
            """.trimIndent()
        ).exceptionOrNull() as? TypeCheckError
        assert(variant?.hints?.any { it.contains("arm") } == true) {
            "a variant should be told to add an ARM; got: ${variant?.hints}"
        }

        val record = check(
            """
            type Endless = { next: Endless }
            $MAIN
            """.trimIndent()
        ).exceptionOrNull() as? TypeCheckError
        assert(record?.hints?.any { it.contains("Option") || it.contains("sequence") } == true) {
            "a record should be told to make the field optional or a sequence; " +
                "got: ${record?.hints}"
        }
    }

    // ===================================== what still works

    /** A variant with a non-recursive arm — the ordinary tree. */
    @Test fun `a variant with a base case is fine`() {
        accepts(
            """
            use std::int
            type Tree = | Leaf, Node { l: Tree, r: Tree } |
            pub let f = { t: Tree } -> 1
            $MAIN
            """,
            "`Leaf` ends the chain. This is the shape the refusals must not touch — " +
                "recursive types are the point of having variants.",
        )
    }

    /** A record recursing through `Option`. */
    @Test fun `a record recursing through Option is fine`() {
        accepts(
            """
            use std::int
            use std::option
            type Node = { n: Int, next: Option Node }
            pub let f = { v: Node } -> v.n
            $MAIN
            """,
            "`None` is the base case, one level down — so inhabitedness has to look " +
                "THROUGH the application, not just at the name.",
        )
    }

    /** A record recursing through a sequence. */
    @Test fun `a record recursing through a seq is fine`() {
        accepts(
            """
            use std::int
            type Tree = { kids: [Tree] }
            pub let f = { t: Tree } -> 1
            $MAIN
            """,
            "The EMPTY seq inhabits `[Tree]` whatever `Tree` is, so a sequence is an " +
                "escape in its own right. Roc's rule names tag unions; this is the same " +
                "idea for a container that can be empty.",
        )
    }

    /** Mutual recursion with a base case on one side. */
    @Test fun `mutual recursion with a base case is fine`() {
        accepts(
            """
            use std::int
            type Expr = | Lit, Call { args: [Expr], f: Fn } |
            type Fn = { body: Expr }
            pub let f = { e: Expr } -> 1
            $MAIN
            """,
            "`Expr` and `Fn` depend on each other, and `Lit` ends it. The fixpoint has " +
                "to settle across BOTH or legitimate mutual recursion is refused — the " +
                "most likely way to get this wrong.",
        )
    }

    /** A long alias chain that terminates is still fine. */
    /**
     * A chain that REACHES something is not a cycle — the walk has to stop
     * rather than report one. Written with record declarations now that a chain
     * of renames is itself refused (V0713): `type A = B` where `B` is another
     * alias is a second name, cycle or not. What this still pins is that the
     * cycle walk terminates, which is the only thing it was ever about.
     */
    @Test fun `a terminating alias chain is fine`() {
        accepts(
            """
            use std::int
            type Leaf = { v: Int }
            type Mid = { l: Leaf }
            type Top = { m: Mid }
            pub let f = { t: Top } -> t.m.l.v
            $MAIN
            """,
            "Only a CYCLE is refused. A chain of declarations that bottoms out in a " +
                "real shape is ordinary composition, and the walk must stop at `Leaf` " +
                "rather than report one.",
        )
    }
}
