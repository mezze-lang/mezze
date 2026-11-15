package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * RECEIVERLESS DISPATCH, asserted by VALUE — the cell this area's bugs keep
 * coming from and the one with no runtime coverage until now.
 *
 * An operation with no `it` is chosen by the type the call PRODUCES. Every bug in
 * [[project_dictionary_not_arriving_family]] sat on that path (S-3's instance head
 * read off a receiver field that does not exist; S-10's monomorphizer rewriting
 * only `receiver.method`; S-11's prefix scan answering with the wrong variable's
 * slot; S-18's entry op resolved concretely while the frame held its dictionary),
 * and BUG 9 is the same shape one level up.
 *
 * WHY VALUE AND NOT ACCEPTANCE: every program here type-checks whether dispatch
 * is right or wrong. If the second call silently reuses the first impl's body the
 * program still compiles and still prints — just the wrong number. A `mez check`
 * cannot tell these apart, which is exactly how five of this area's six
 * dictionary bugs stayed silent. So each impl returns a DIFFERENT number and the
 * test asserts the exact list.
 */
class ReceiverlessDispatchMatrixTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun runLines(src: String): List<String> =
        MezzeTestHelper.runWithOutput(src).output.trim().lines()
            .map { it.trim() }.filter { it.isNotEmpty() }

    /** Several impls of a receiverless op, each selected by the ANNOTATED target. */
    @Test fun `a receiverless op runs the body of the type the call produces`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            type A = { tag: Int }
            type B = { label: Str }
            type C = { flag: Bool }

            ability Mk for 'a where { mk: {} -> 'a }
            impl Mk for A where { mk: {} -> A = {} -> { tag = 1 } }
            impl Mk for B where { mk: {} -> B = {} -> { label = "two" } }
            impl Mk for C where { mk: {} -> C = {} -> { flag = True } }

            let fromA: A = Mk.mk {}
            let fromB: B = Mk.mk {}
            let fromC: C = Mk.mk {}

            let main = {} -> perform Console in do
              println { msg = fromA.tag.to_str {} }
              println { msg = fromB.label }
              println { msg = fromC.flag.to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("1", "two", "True"), out,
            "three impls of ONE receiverless op, each chosen by the type its call " +
                "produces. Reading `1` three times is the first impl answering for all " +
                "of them — BUG 9's shape, and it type-checks.",
        )
    }

    /** The produced type arrives through a GENERIC HOP rather than at the call. */
    @Test fun `a receiverless op dispatches through a generic hop`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            type A = { tag: Int }
            type B = { tag: Int, extra: Str }

            ability Mk for 'a where { mk: {} -> 'a }
            impl Mk for A where { mk: {} -> A = {} -> { tag = 11 } }
            impl Mk for B where { mk: {} -> B = {} -> { tag = 22, extra = "b" } }

            ## the hop never mentions A or B — the CALLER's annotation decides
            pub let make: {} -> 'a with Mk 'a = {} -> Mk.mk {}

            let viaA: A = make {}
            let viaB: B = make {}

            let main = {} -> perform Console in do
              println { msg = viaA.tag.to_str {} }
              println { msg = viaB.tag.to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("11", "22"), out,
            "the hop is one generic function serving both targets, so its dictionary " +
                "comes from each CALL SITE. `11` twice is the hop pinned to whichever " +
                "target it saw first — S-10/S-11's shape.",
        )
    }

    /** A receiverless op whose impl body itself calls another receiverless op. */
    @Test fun `a receiverless op forwards to another receiverless op`() {
        val out = runLines(
            """
            use std::effects::log::{ println, Console }
            type A = { tag: Int }
            type B = { tag: Int }

            ability Seed for 'a where { seed: {} -> Int }
            impl Seed for A where { seed: {} -> Int = {} -> 100 }
            impl Seed for B where { seed: {} -> Int = {} -> 200 }

            ability Mk for 'a where { mk: {} -> 'a }
            impl Mk for A where { mk: {} -> A = {} -> { tag = Seed.seed {} + 1 } }
            impl Mk for B where { mk: {} -> B = {} -> { tag = Seed.seed {} + 2 } }

            let fromA: A = Mk.mk {}
            let fromB: B = Mk.mk {}

            let main = {} -> perform Console in do
              println { msg = fromA.tag.to_str {} }
              println { msg = fromB.tag.to_str {} }
            main {}
            """.trimIndent()
        )
        assertEquals(
            listOf("101", "202"), out,
            "each `mk` body calls a DIFFERENT receiverless op, and both must reach " +
                "their own impl: `Mk A`'s body wants `Seed A` (100+1) and `Mk B`'s wants " +
                "`Seed B` (200+2). A shared answer here is two dictionaries collapsing " +
                "onto one slot, which is S-11 exactly.",
        )
    }
}
