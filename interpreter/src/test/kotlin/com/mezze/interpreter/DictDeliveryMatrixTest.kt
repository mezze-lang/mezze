package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * HOW A DICTIONARY REACHES A BODY, asserted by VALUE — the second axis of the
 * dispatch matrix. The first ([ReceiverlessDispatchMatrixTest]) varies how the
 * IMPL is chosen; this one fixes that and varies the DELIVERY ROUTE: resolved at
 * the call site, forwarded from an enclosing op, carried through a callback, or
 * carried through a container element.
 *
 * The sweep probed these routes at the TYPE level and they held. That is a weaker
 * statement than it looks: a misdirected dictionary type-checks — it is a
 * dictionary of the right ABILITY, just the wrong IMPL — so only a value tells
 * them apart. Today's forwarding bug (`a.fwd { x = b }` answering the receiver's
 * `Show A`) type-checked before and after the fix.
 *
 * Each impl returns a distinct number, and the test asserts the exact list.
 */
class DictDeliveryMatrixTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun runLines(src: String): List<String> =
        MezzeTestHelper.runWithOutput(src).output.trim().lines()
            .map { it.trim() }.filter { it.isNotEmpty() }

    private val shows = """
        use std::effects::log::{ println, Console }
        type A = { a: Int }
        type B = { b: Int }
        ability Show for 'x where { show: { it: 'x } -> Int }
        impl Show for A where { show: { it: A } -> Int = { it } -> 1 }
        impl Show for B where { show: { it: B } -> Int = { it } -> 2 }
        let a: A = { a = 0 }
        let b: B = { b = 0 }
    """.trimIndent()

    /** Through a CALLBACK parameter: the constraint is delivered into a lambda. */
    @Test fun `a constraint reaches a callback's body`() {
        val out = runLines(
            """
            $shows
            pub let apply: { f: { v: 'x } -> Int, v: 'x } -> Int = { f, v } -> f { v = v }
            pub let useA: { v: 'x } with Show 'x -> Int = { v } -> v.show {}

            let main = {} -> perform Console in do
              println { msg = apply { f = useA, v = a }.to_str {} }
              println { msg = apply { f = useA, v = b }.to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("1", "2"), out,
            "one constrained function passed TWICE as a callback, at two different " +
                "types. Each application must carry its own dictionary; a shared one " +
                "prints the same number twice.",
        )
    }

    /** Through a SEQ ELEMENT: the element type decides, not the container. */
    @Test fun `a constraint reaches through a container element`() {
        val out = runLines(
            """
            $shows
            pub let sum_shows: { xs: ['x] } with Show 'x -> Int = { xs } ->
              xs.fold { init = 0, f = { acc, x } -> acc + x.show {} }

            let main = {} -> perform Console in do
              println { msg = sum_shows { xs = [a, a, a] }.to_str {} }
              println { msg = sum_shows { xs = [b, b] }.to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("3", "4"), out,
            "the ELEMENT's impl must run, not the container's: 3x`Show A`=3 and " +
                "2x`Show B`=4. Resolving against the container is the " +
                "`impl Ord for ['a]`-instead-of-the-element shape that " +
                "`resolveDictOp`'s element-first ordering exists for.",
        )
    }

    /** TWO live dictionaries of DIFFERENT abilities in one body. */
    @Test fun `two dictionaries stay on their own slots`() {
        val out = runLines(
            """
            $shows
            ability Tag for 'x where { tag: { it: 'x } -> Int }
            impl Tag for A where { tag: { it: A } -> Int = { it } -> 10 }
            impl Tag for B where { tag: { it: B } -> Int = { it } -> 20 }

            pub let mix: { p: 'p, q: 'q } with Show 'p, Tag 'q -> Int = { p, q } ->
              p.show {} * 100 + q.tag {}

            let main = {} -> perform Console in do
              println { msg = mix { p = a, q = b }.to_str {} }
              println { msg = mix { p = b, q = a }.to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("120", "210"), out,
            "`Show p` and `Tag q` are two slots and must not cross: (1*100)+20=120 " +
                "then (2*100)+10=210. A crossed pair reads 110 or 220 — BUG 9's " +
                "collapse, and the reason every resolution is ability-qualified.",
        )
    }

    /** One ability at TWO class args, both used in one body. */
    @Test fun `one ability at two class args keeps both impls`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            type Src = { n: Int }
            ability Make 'b for 'a where { make: { it: 'a } -> 'b }
            impl Make Int for Src where { make: { it: Src } -> Int = { it } -> 111 }
            impl Make Str for Src where { make: { it: Src } -> Str = { it } -> "222" }

            let s: Src = { n = 1 }
            let main = {} -> perform Console in do
              let i: Int = s.make {}
              let t: Str = s.make {}
              println { msg = i.to_str {} }
              println { msg = t }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("111", "222"), out,
            "the class arg is in the RETURN, reachable from no argument, so dispatch " +
                "must ask what the call PRODUCES — BUG 39's shape, which broke `Into` " +
                "for every target but one. Both lines reading 111 is that bug.",
        )
    }
}
