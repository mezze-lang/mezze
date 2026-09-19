package com.mezze.interpreter

import com.mezze.language.typechecker.validation.ElaborationInvariants
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * Task 0.2, the second gate — the same invariants applied to USER code.
 *
 * The typechecker-side gate ratchets the stdlib, which carries a large baseline
 * of impl bodies that are not solved in the module solve. This one asks a
 * stricter question of ordinary programs: after elaboration, does anything in a
 * user's own module still have a type the compiler never worked out?
 *
 * The answer, measured, is: sometimes. Each program states the gaps it has
 * TODAY, inline, so the gap is readable next to the code that produces it. With
 * six programs that is worth more than a separate baseline file.
 *
 * Two results are worth knowing on their own. A seq bound out of a match arm
 * still types as the literal `unknown` at `items.size {}` — the crash it used to
 * cause is gone, but the hole that caused it is not, and this is what A.2 turns
 * into a compile error. And a single chained call with an inline lambda leaves
 * SEVEN nodes without a type, which is the clearest measure here of how much the
 * compiler reconstructs rather than carries.
 *
 * `pair_up`'s parameters were listed here as untyped until the check was
 * corrected: a var-typed parameter is only a gap when the variable is bound
 * NOWHERE, and `{ a: 'a, b: 'a }` binds it in the declaration's own scheme.
 *
 * The programs cover what the elaboration plan is about — generics, ability
 * dispatch, containers of user types, match-bound receivers, chained calls —
 * rather than trying to be interesting programs.
 */
class UserCodeElaborationGateTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun findingsIn(source: String): List<ElaborationInvariants.Finding> {
        val result = MezzeTestHelper.typeCheck(source)
        val module = assertNotNull(result.typedModule, "typecheck produced no typed module")
        return ElaborationInvariants.check(module)
    }

    /**
     * [expected] is the gaps this program has TODAY, as `KIND where`. An extra
     * one fails (a regression); a missing one fails too (the list is stale and
     * would re-permit the gap). Variable names and locations are left out for
     * the same reason the stdlib baseline leaves them out: the solver mints them
     * fresh, so matching on them would churn.
     */
    private fun assertGaps(label: String, source: String, expected: List<String>) {
        val actual = findingsIn(source)
            .groupingBy { "${it.kind} ${it.where}" }.eachCount()
            .map { (key, count) -> if (count == 1) key else "$key x$count" }
            .sorted()
        val want = expected.sorted()
        if (actual != want) {
            fail(
                buildString {
                    appendLine("$label: elaboration gaps changed.")
                    appendLine("  expected: $want")
                    appendLine("  actual:   $actual")
                    appendLine()
                    appendLine("A NEW gap is a regression — fix it. A gap that DISAPPEARED is progress —")
                    appendLine("delete it from the expected list so it cannot come back unnoticed.")
                },
            )
        }
    }

    private fun assertElaboratesCleanly(label: String, source: String) =
        assertGaps(label, source, emptyList())

    @Test
    fun `a generic function over a user record`() = assertElaboratesCleanly(
        "generic over a record",
        """
        use std::collections::seq
        type P = { x: Int, y: Int }
        let first_or = { xs: [P], d: P } -> match xs.first {} is
          Some { val = p } -> p
          None -> d
        first_or { xs = [{ x = 1, y = 2 }], d = { x = 0, y = 0 } }
        """.trimIndent(),
    )

    @Test
    fun `a user ability dispatched through a type variable`() = assertGaps(
        "ability through a type variable",
        """
        ability Size for 'a where {
          size_of: { it: 'a } -> Int
        }
        type Box = { n: Int }
        impl Size for Box where {
          size_of = { it } -> it.n
        }
        let total: { b: Box } -> Int = { b } -> b.size_of {}
        total { b = { n = 7 } }
        """.trimIndent(),
        // CLEAN. The ability's type parameter is tied to Box by the call, because
        // the call site now types its callee from the call rather than leaving it
        // carrying the declaration's own variables.
        emptyList(),
    )

    @Test
    fun `a container of a user variant, compared by its own instance`() = assertGaps(
        "container of a user variant",
        """
        use std::collections::seq
        type T = | A { n: Int }, B { s: Str } |
        let count = { xs: [T] } -> xs.size {}
        count { xs = [A { n = 1 }, B { s = "x" }] }
        """.trimIndent(),
        // CLEAN. `xs.size {}` used to type as the literal `unknown` even though
        // `xs: [T]` is written out one line above; the operation's field-access
        // form is now typed from the call like any other callee.
        emptyList(),
    )

    @Test
    fun `a receiver bound in a match arm`() = assertGaps(
        "match-arm-bound receiver",
        """
        use std::collections::seq
        type W = | Wrap { items: [Int] } |
        let n = { w: W } -> match w is
          Wrap { items } -> items.size {}
        n { w = Wrap { items = [1, 2, 3] } }
        """.trimIndent(),
        // CLEAN. The residue of the CallIndirect crash is gone too: `items.size {}`
        // carries the type the call determines, not `unknown`.
        emptyList(),
    )

    @Test
    fun `a chained call whose intermediate type is only inferred`() = assertGaps(
        "inferred intermediate in a chain",
        """
        use std::collections::seq
        let evens = { xs: [Int] } -> xs.filter { p = { x } -> x % 2 == 0 }.size {}
        evens { xs = [1, 2, 3, 4] }
        """.trimIndent(),
        // CLEAN. This was seven open nodes for one line of code — the inline
        // lambda and both chained calls each left a type the solver never wrote
        // down. The chain is the hardest of these cells and it closes entirely.
        emptyList(),
    )

    @Test
    fun `a polymorphic helper applied at two different types`() = assertGaps(
        "one helper, two instantiations",
        """
        use std::collections::seq
        let pair_up: { a: 'a, b: 'a } -> ['a] = { a, b } -> [a, b]
        let ints = pair_up { a = 1, b = 2 }
        let strs = pair_up { a = "x", b = "y" }
        ints.size {} + strs.size {}
        """.trimIndent(),
        // CLEAN. `pair_up` is fully annotated, and its parameters used to reach the
        // typed AST as bare fresh variables anyway — one written `'a` arriving as
        // two TypeVars, the shape that let both call sites keep an open variable.
        // Each site now instantiates the callee at the types it is applied to.
        emptyList(),
    )

    /**
     * CLOSED. Kept because it was the last shape standing and it took three
     * wrong explanations to find, reduced to twelve lines.
     *
     * `init = { acc = empty {}, i = 0 }` says nothing about what the list holds.
     * The only thing that does is the `cons` inside the callback, whose type
     * `init` is supposed to give — and the accumulator's element comes out as
     * `{ value: Int, index: 'v }` with `index` open, although it is assigned
     * `acc.i` (which resolved to `Int` in the same record) and the declaration's
     * own return type says `Int` and is unified against by the final `reverse`.
     *
     * THE CAUSE, after three wrong guesses: `acc.i` is a field read whose receiver
     * is still a type VARIABLE when the constraint is generated, so it DEFERS in
     * the per-binding solve. `acc` is pinned later, by `init`. And
     * `collectGlobalConstraints` stripped every `HasField` before the global
     * solve — so nothing ever revisited the read, and its variable stayed open
     * forever. The global pass now re-solves them in COMPLETION mode.
     *
     * Ruled out by measurement along the way, so nobody re-checks: it is not two
     * reads of `acc.i` decoupling (the single-read variant behaved identically,
     * which is why it is still here beside this one); `fold_left`'s signature is
     * sound (`init`, `f`'s `acc`, `f`'s return and the result are ONE variable);
     * and inference was right all along — the unannotated form infers
     * `List { value: 'a, index: Int }` correctly.
     *
     * Both cells now measure clean. They stay as a regression guard: this shape
     * is where `enumerate`, and until recently `intersperse` and `flatten`, got
     * their rows in the stdlib baseline.
     */
    @Test
    fun `a fold whose accumulator element is fixed only inside the callback`() = assertGaps(
        "recursive accumulator, two reads",
        """
        use std::collections::list::{ reverse, fold_left, empty, cons }
        let go: { xs: List Int } -> List { value: Int, index: Int } = { xs } ->
          reverse { xs = (fold_left {
            xs = xs,
            init = { acc = empty {}, i = 0 },
            f = { acc, x } -> {
              acc = cons { head = { value = x, index = acc.i }, tail = acc.acc },
              i = acc.i + 1,
            },
          }).acc }
        """.trimIndent(),
        emptyList(),
    )

    @Test
    fun `the same fold with a single read of the field`() = assertGaps(
        "recursive accumulator, one read",
        """
        use std::collections::list::{ reverse, fold_left, empty, cons }
        let go2: { xs: List Int } -> List { value: Int, index: Int } = { xs } ->
          reverse { xs = (fold_left {
            xs = xs,
            init = { acc = empty {}, i = 0 },
            f = { acc, x } -> {
              acc = cons { head = { value = x, index = acc.i }, tail = acc.acc },
              i = 0,
            },
          }).acc }
        """.trimIndent(),
        // Identical to the two-read case, which is how the read-count theory was
        // ruled out — and identical again now that both are clean.
        emptyList(),
    )
}
