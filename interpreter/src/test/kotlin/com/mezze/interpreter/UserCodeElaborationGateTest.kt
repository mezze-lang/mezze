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
 * Three results are worth knowing on their own. A seq bound out of a match arm
 * still types as the literal `unknown` at `items.size {}` — the crash it used to
 * cause is gone, but the hole that caused it is not, and this is what A.2 turns
 * into a compile error. A polymorphic helper's parameters come out as bare fresh
 * variables, the same untyped-parameter shape the stdlib's impl bodies have. And
 * a single chained call with an inline lambda leaves SEVEN nodes without a type,
 * which is the clearest measure here of how much the compiler reconstructs
 * rather than carries.
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
        // The call `b.size_of {}` resolves, but a node in `total` keeps an open
        // variable: the ability's own type parameter never gets tied to Box here.
        listOf("UNBOUND_TYPE_VAR let:total"),
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
        // `xs.size {}` types as the literal `unknown`, even though `xs: [T]` is
        // written out one line above.
        listOf("UNBOUND_TYPE_VAR let:count"),
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
        // The residue of the CallIndirect crash: it runs now, but `items.size {}`
        // still carries `unknown`. A.2 is what makes this a compile error.
        listOf("UNBOUND_TYPE_VAR let:n"),
    )

    @Test
    fun `a chained call whose intermediate type is only inferred`() = assertGaps(
        "inferred intermediate in a chain",
        """
        use std::collections::seq
        let evens = { xs: [Int] } -> xs.filter { p = { x } -> x % 2 == 0 }.size {}
        evens { xs = [1, 2, 3, 4] }
        """.trimIndent(),
        // Seven open nodes for one line of code — the inline lambda and the two
        // chained calls each leave types the solver never wrote down.
        listOf("UNBOUND_TYPE_VAR let:evens x7"),
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
        // `pair_up` is fully annotated, yet its parameters reach the typed AST as
        // bare fresh variables — the same shape the stdlib's impl bodies have —
        // and both call sites keep an open variable.
        listOf(
            "UNTYPED_PARAM let:pair_up x2",
            "UNBOUND_TYPE_VAR let:ints",
            "UNBOUND_TYPE_VAR let:strs",
        ),
    )
}
