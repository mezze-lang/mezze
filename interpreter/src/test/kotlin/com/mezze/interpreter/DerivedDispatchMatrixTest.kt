package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * DERIVED impls in the dispatch matrix — the third axis, and the one S-17 is
 * about: "a constrained op FORWARDING to a constrained function loses the
 * dictionary when the element's instance is DERIVED".
 *
 * A derived impl has no written body to read, so a wrong dictionary here cannot
 * be spotted by eye. And a reflexive comparison hides it completely: `x == x` is
 * True whichever `Eq` ran. So every case below compares values that DIFFER in
 * exactly one nested place, where the answer is True only if the inner impl was
 * actually consulted.
 *
 * This is also why S-17 could not be pinned by a standalone test earlier: only
 * the well-known abilities derive, and a second `impl Eq for ['a]` cannot coexist
 * with the stdlib's under coherence. Comparing a NESTED user type is the shape
 * that reaches a derived instance without declaring one.
 */
class DerivedDispatchMatrixTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun runLines(src: String): List<String> =
        MezzeTestHelper.runWithOutput(src).output.trim().lines()
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** A derived `Eq` must reach the NESTED type's derived `Eq`, not compare shallowly. */
    @Test fun `a derived impl reaches the nested type's derived impl`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            type Inner = { n: Int }
            type Outer = { i: Inner, label: Str }

            let x: Outer = { i = { n = 1 }, label = "same" }
            let y: Outer = { i = { n = 2 }, label = "same" }
            let z: Outer = { i = { n = 1 }, label = "same" }

            let main = {} -> perform Console in do
              println { msg = (x == z).to_str {} }
              println { msg = (x == y).to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("True", "False"), out,
            "the two differ ONLY at `i.n`, so `False` is produced only if the nested " +
                "`Eq Inner` actually ran. A shallow or misdirected comparison answers " +
                "True twice, and nothing in the types says so.",
        )
    }

    /** A container's derived `Eq` must reach the ELEMENT's derived `Eq`. */
    @Test fun `a container's derived impl reaches the element's`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            type Inner = { n: Int }
            type Outer = { i: Inner, label: Str }

            let x: Outer = { i = { n = 1 }, label = "same" }
            let y: Outer = { i = { n = 2 }, label = "same" }
            let z: Outer = { i = { n = 1 }, label = "same" }

            let main = {} -> perform Console in do
              println { msg = ([x, z] == [z, x]).to_str {} }
              println { msg = ([x, z] == [x, y]).to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("True", "False"), out,
            "the seq `Eq` must consult the ELEMENT's, which must consult the nested " +
                "one. This is the two-hop version of the case above, and the hop is " +
                "where S-17 said the dictionary goes missing.",
        )
    }

    /** A derived VARIANT `Eq` must reach its payload's derived `Eq`. */
    @Test fun `a derived variant impl reaches its payload's`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            type Inner = { n: Int }
            type Wrapped = | Full { v: Inner }, Empty |

            let p: Wrapped = Full { v = { n = 1 } }
            let q: Wrapped = Full { v = { n = 2 } }
            let r: Wrapped = Full { v = { n = 1 } }
            let e: Wrapped = Empty

            let main = {} -> perform Console in do
              println { msg = (p == r).to_str {} }
              println { msg = (p == q).to_str {} }
              println { msg = (p == e).to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("True", "False", "False"), out,
            "a variant's derived `Eq` compares the TAG and then the PAYLOAD with the " +
                "payload type's own impl. `p == q` is False only if the payload was " +
                "compared; `p == e` is False on the tag alone, so it is the control " +
                "that says a True/True/False reading means the payload was skipped.",
        )
    }

    /**
     * A WRITTEN element impl, reached through a derived container — the sharpest
     * cell, because the written impl DISAGREES with structural equality, so the
     * answer says which one ran.
     *
     * It has to be a NEWTYPE. A closed record gets `Eq` DERIVED, and a written one
     * beside it is a duplicate impl (coherence refuses it) — so for a record there
     * is no way to make the two disagree, and therefore no way to tell them apart.
     * `DerivedImplGenerator.eqImpl` returns null for a newtype, an alias, and an
     * OPEN record or variant; those are the shapes where a written impl is the only
     * impl.
     *
     * The compiler also requires `Eq` and `Hash` together — "they are one decision:
     * a hash container buckets by hash and settles ties by equality" — so the
     * parity `Eq` needs the matching parity `Hash`.
     */
    @Test fun `a written element impl is reached through a derived container`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            use std::abilities::compare::{ Eq, Hash }
            newtype Parity = Int

            ## DELIBERATELY not structural: two Parity values are equal when their
            ## parities match, so `Parity.(1) == Parity.(3)` is True.
            impl Eq for Parity where {
              eq:  { it: Parity, r: Parity } -> Bool = { it, r } -> match it is
                Parity.(a) -> match r is Parity.(b) -> (a % 2) == (b % 2),
              neq: { it: Parity, r: Parity } -> Bool = { it, r } -> match it is
                Parity.(a) -> match r is Parity.(b) -> (a % 2) != (b % 2)
            }
            impl Hash for Parity where {
              hash: { it: Parity } -> Int = { it } -> match it is Parity.(a) -> a % 2
            }

            let one: Parity = Parity.(1)
            let three: Parity = Parity.(3)
            let two: Parity = Parity.(2)

            let main = {} -> perform Console in do
              println { msg = (one == three).to_str {} }
              println { msg = (one == two).to_str {} }
              println { msg = ([one] == [three]).to_str {} }
              println { msg = ([one] == [two]).to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("True", "False", "True", "False"), out,
            "`Parity.(1) == Parity.(3)` is True ONLY under the written impl — a " +
                "structural comparison says False. So the last two lines prove the " +
                "seq's derived `Eq` reached the element's WRITTEN impl instead of " +
                "comparing the wrapped Ints: the silent substitution S-17 is about, " +
                "and what the ordered-collections memory warns a derived instance can " +
                "quietly do to someone's notion of equality.",
        )
    }
}
