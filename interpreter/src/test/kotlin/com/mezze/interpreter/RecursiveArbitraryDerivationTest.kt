package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * A recursive variant derives `Arbitrary`, and generation terminates.
 *
 * It used to derive nothing: bounding recursion seemed to need the `Sample` size
 * budget, which a derived body cannot name. So `Arbitrary.arbitrary {}` at a
 * `Tree` was a missing-impl error, and `mez sample` failed on any function over
 * one. The derived body bounds recursion with coin flips instead. A recursive
 * constructor is taken only when enough flips agree that each node has fewer
 * than one recursive child on average, so generation stops with probability 1.
 */
class RecursiveArbitraryDerivationTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val prelude = """
        use std::effects::sample::{ SeededSample }
        use std::abilities::arbitrary::{ Arbitrary }
    """.trimIndent()

    @Test
    fun `a binary tree generates both constructors and terminates`() {
        // 60 seeds: a run that never stopped would hang the test (the timeout fails it).
        val out = MezzeTestHelper.run("""
            $prelude
            type Tree = | Leaf, Node { left: Tree, v: Int, right: Tree } |
            let size = { t: Tree } -> match t is
              Leaf -> 0
              Node { left, v, right } -> 1 + size { t = left } + size { t = right }
            let draw = { seed: Int } -> perform SeededSample { seed = seed } in do
              let t: Tree = Arbitrary.arbitrary {}
              size { t = t }
            let go = { i: Int, leaves: Int, nodes: Int } ->
              if i == 60 then "$(leaves.to_str {}) $(nodes.to_str {})"
              else if draw { seed = i } == 0 then go { i = i + 1, leaves = leaves + 1, nodes = nodes }
              else go { i = i + 1, leaves = leaves, nodes = nodes + 1 }
            go { i = 0, leaves = 0, nodes = 0 }
        """.trimIndent()).toString()
        val (leaves, nodes) = out.split(" ").map { it.toInt() }
        assertTrue(leaves > 0 && nodes > 0, "both a Leaf and a Node must be generated: $out")
    }

    @Test
    fun `recursion through a seq terminates`() {
        val out = MezzeTestHelper.run("""
            $prelude
            type Rose = | Bud, Branch { kids: [Rose] } |
            let count = { r: Rose } -> match r is
              Bud -> 1
              Branch { kids } -> 1 + kids.fold { init = 0, f = { acc, x } -> acc + count { r = x } }
            let draw = { seed: Int } -> perform SeededSample { seed = seed } in do
              let r: Rose = Arbitrary.arbitrary {}
              count { r = r }
            let go = { i: Int, total: Int } -> if i == 40 then total else go { i = i + 1, total = total + draw { seed = i } }
            go { i = 0, total = 0 }
        """.trimIndent())
        assertTrue((out as Long) >= 40, "every draw is at least one node: $out")
    }

    @Test
    fun `a variant with no base case still does not derive`() {
        val e = runCatching {
            MezzeTestHelper.run("""
                $prelude
                type Endless = | Wrap { w: Endless } |
                let main = {} -> perform SeededSample { seed = 1 } in do
                  let e: Endless = Arbitrary.arbitrary {}
                  0
                main {}
            """.trimIndent())
        }.exceptionOrNull()
        assertTrue(e?.message?.contains("Endless") == true, "expected a missing-impl error naming Endless: ${e?.message}")
    }
}
