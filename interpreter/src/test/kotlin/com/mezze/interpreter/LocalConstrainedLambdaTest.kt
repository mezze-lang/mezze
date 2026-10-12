package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `with`-constrained lambda LOCAL to a function body receives its dictionary
 * from each of its call sites, exactly as a top-level constrained function does.
 *
 * It did not. Only top-level declarations and impl operations were given
 * dictionary parameters, and a call through a local name injected nothing. The
 * body scan instead credited the local helper's need to the ENCLOSING function,
 * which grew a dictionary parameter its own callers never fill — so the helper
 * read an empty slot: "no dictionary was supplied … compiler defect". And with
 * one slot for the whole enclosing function, two uses of the helper at two
 * different types could never both be right.
 */
class LocalConstrainedLambdaTest {

    private val total = """
        ability Total for 'a where { total: { it: 'a } -> Int }
        type Ticket = { n: Int }
        type Order = { m: Int }
        impl Total for Ticket where { total = { it } -> it.n }
        impl Total for Order where { total = { it } -> it.m * 100 }
    """.trimIndent()

    private fun run(src: String): Any? = MezzeTestHelper.run(src.trimIndent())

    /** A receiver-less op: the type each call PRODUCES picks the impl. */
    @Test
    fun `a local static-op helper is resolved per call site`() = assertEquals(
        "0/",
        run("""
            use std::abilities::default::{ Default }
            let main = {} -> do
              let d: {} with Default 'a -> 'a = {} -> Default.default {}
              let n: Int = d {}
              let s: Str = d {}
              "$(n.to_str {})/$(s)"
            main {}
        """),
    )

    /** A receiver op, at two user types in one body — one slot could serve only one. */
    @Test
    fun `a local receiver helper is resolved per call site`() = assertEquals(
        207L,
        run("""
            $total
            let main = {} -> do
              let get: { x: 'a } with Total 'a -> Int = { x } -> x.total {}
              let t: Ticket = { n = 7 }
              let o: Order = { m = 2 }
              get { x = t } + get { x = o }
            main {}
        """),
    )

    /** The local helper forwards its dictionary to a TOP-LEVEL constrained function. */
    @Test
    fun `a local helper forwards its dictionary to a top-level callee`() = assertEquals(
        700L,
        run("""
            $total
            let dispatch: { x: 'a } with Total 'a -> Int = { x } -> x.total {}
            let main = {} -> do
              let via: { x: 'a } with Total 'a -> Int = { x } -> dispatch { x = x } * 100
              let t: Ticket = { n = 7 }
              via { x = t }
            main {}
        """),
    )

    /** Captures must survive: the helper reads a variable of the enclosing body. */
    @Test
    fun `a local helper keeps its captures`() = assertEquals(
        17L,
        run("""
            $total
            let main = {} -> do
              let base = 10
              let plus: { x: 'a } with Total 'a -> Int = { x } -> base + x.total {}
              let t: Ticket = { n = 7 }
              plus { x = t }
            main {}
        """),
    )
}
