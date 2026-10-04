package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Can a container be generated INLINE at a concrete element type — needing no
 * dictionary, and so sidestepping S-3 entirely?
 */
class InlineContainerProbeTest {
    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private val imports = """
        use std::effects::sample::{ Sample, SeededSample, list_of, choose, size, resize }
        use std::abilities::arbitrary::{ Arbitrary }
        use std::collections::seq
        use std::option::{ get_or }
        use std::result::{ is_ok }
    """.trimIndent()

    private fun show(label: String, body: String) {
        System.err.println("=== $label ===")
        try {
            System.err.println("   OK -> " + MezzeTestHelper.run("$imports\n\n$body"))
        } catch (e: Exception) {
            System.err.println("   FAILED -> " + (e.message ?: "").lines().first().take(180))
        }
    }

    /** THE question: list_of at a CONCRETE element type. */
    @Test fun `I1 list_of with a concrete element`() {
        show("I1  list_of { g = {} -> Arbitrary.arbitrary {} } pinned to [Int]", """
            perform SeededSample { seed = 3 } in do
              let xs: [Int] = list_of { g = {} -> Arbitrary.arbitrary {} }
              seq::size { xs = xs }
        """.trimIndent())
    }

    /** A record whose seq field is generated inline — the derived-body shape. */
    @Test fun `I2 a record built with an inline seq field`() {
        show("I2  { xs = list_of {...}, n = arbitrary {} } against a record type", """
            type Tags = { xs: [Int], n: Int }
            let count: Tags -> Int = { xs, n } -> seq::size { xs = xs } + n

            perform SeededSample { seed = 3 } in do
              let t = { xs = list_of { g = {} -> Arbitrary.arbitrary {} }, n = Arbitrary.arbitrary {} }
              count { xs = t.xs, n = t.n }
        """.trimIndent())
    }

    /** Inline Option, at a concrete element type — no impl for Option needed. */
    @Test fun `I4 an inline Option field`() {
        show("I4  if choose == 0 then None else Some { val = arbitrary {} }", """
            type Maybe = { o: Option Int }
            let unwrap: Maybe -> Int = { o } -> get_or { opt = o, def = 0 - 1 }

            perform SeededSample { seed = 3 } in do
              let m = { o = if choose { bound = 4 } == 0 then None else Some { val = Arbitrary.arbitrary {} } }
              unwrap { o = m.o }
        """.trimIndent())
    }

    /** Inline Result, same idea. */
    @Test fun `I5 an inline Result field`() {
        show("I5  Ok/Err inline", """
            type Outcome = { r: Result Int Str }
            let ok: Outcome -> Bool = { r } -> is_ok { result = r }

            perform SeededSample { seed = 3 } in do
              let o = { r = if choose { bound = 3 } == 0
                              then Err { error = Arbitrary.arbitrary {} }
                              else Ok { val = Arbitrary.arbitrary {} } }
              ok { r = o.r }
        """.trimIndent())
    }

    /** The RECURSION shape: budget read + lowered, at a concrete type. */
    @Test fun `I3 a recursive variant with an explicit budget`() {
        show("I3  recursive Tree bounded by size/resize", """
            type Tree = | Leaf { v: Int }, Node { l: Tree, r: Tree } |

            let count: { t: Tree } -> Int = { t } -> match t is
              Leaf { v = _ } -> 1
              Node { l, r } -> count { t = l } + count { t = r }

            let gen_tree: {} -> Tree <Sample> = {} ->
              if size {} == 0n
                then Leaf { v = Arbitrary.arbitrary {} }
                else resize { n = size {} / 2n, call = {} ->
                  if choose { bound = 2 } == 0
                    then Leaf { v = Arbitrary.arbitrary {} }
                    else Node { l = gen_tree {}, r = gen_tree {} } }

            perform SeededSample { seed = 5 } in count { t = gen_tree {} } 
        """.trimIndent())
    }
}
