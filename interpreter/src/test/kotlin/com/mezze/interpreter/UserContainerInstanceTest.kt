package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * A USER container (a tree, a box, a pair) must honour its elements' instances
 * exactly like the stdlib ones: derived Eq is compositional through user
 * variants/records, and a user `impl Ord … with Ord 'a` dispatches the element
 * Ord inside stdlib operations (`sort`, `contains`, `<` on seqs of trees).
 * The element is a NEWTYPE whose Eq/Ord look only at `x` — a structural compare
 * would disagree on `y`.
 */
class UserContainerInstanceTest {
    private val head = """
        use std::collections::seq
        use std::collections::list
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype P = { x: Int, y: Int }
        let px: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> x
        impl Hash for P where {
          hash = { it } -> px { p = it }
        }
        impl Eq for P where { eq = { it, r } -> px { p = it } == px { p = r }, neq = { it, r } -> if px { p = it } == px { p = r } then False else True, }
        impl Ord for P where { lt = { it, r } -> px { p = it } < px { p = r }, le = { it, r } -> px { p = it } <= px { p = r }, gt = { it, r } -> px { p = it } > px { p = r }, ge = { it, r } -> px { p = it } >= px { p = r }, }
        type Tree = | Node { l: Tree, v: P, r: Tree }, Leaf |
        let leaf: { x: Int, y: Int } -> Tree = { x, y } -> Node { l = Leaf, v = P.({ x = x, y = y }), r = Leaf }
        type Box 'a = { v: 'a }
        type Pair 'a 'b = { a: 'a, b: 'b }
    """.trimIndent()
    private fun run(body: String) = MezzeTestHelper.run("$head\n$body")

    // derived Eq through a recursive user variant
    @Test fun treeEq()        { assertEquals(true,  run("let main = {} -> leaf { x = 1, y = 10 } == leaf { x = 1, y = 99 }\nmain {}")) }
    @Test fun treeEqFalse()   { assertEquals(false, run("let main = {} -> leaf { x = 1, y = 10 } == leaf { x = 2, y = 10 }\nmain {}")) }
    @Test fun treeNestedEq()  { assertEquals(true,  run("let main = {} -> Node { l = leaf { x = 1, y = 1 }, v = P.({ x = 5, y = 0 }), r = Leaf } == Node { l = leaf { x = 1, y = 2 }, v = P.({ x = 5, y = 9 }), r = Leaf }\nmain {}")) }
    @Test fun seqOfTreesEq()  { assertEquals(true,  run("let main = {} -> [ leaf { x = 1, y = 10 } ] == [ leaf { x = 1, y = 99 } ]\nmain {}")) }
    @Test fun seqContainsTree() { assertEquals(true, run("let main = {} -> seq::contains { xs = [ leaf { x = 1, y = 10 } ], x = leaf { x = 1, y = 99 } }\nmain {}")) }
    @Test fun listOfTreesEq() { assertEquals(true,  run("let main = {} -> list::from_seq { xs = [ leaf { x = 1, y = 10 } ] } == list::from_seq { xs = [ leaf { x = 1, y = 99 } ] }\nmain {}")) }

    // derived Eq through GENERIC user records
    @Test fun boxEq()         { assertEquals(true,  run("let mk: { x: Int, y: Int } -> Box P = { x, y } -> { v = P.({ x = x, y = y }) }\nlet main = {} -> mk { x = 1, y = 10 } == mk { x = 1, y = 99 }\nmain {}")) }
    @Test fun pairEq()        { assertEquals(true,  run("let mk: { x: Int, y: Int, s: Str } -> Pair P Str = { x, y, s } -> { a = P.({ x = x, y = y }), b = s }\nlet main = {} -> mk { x = 1, y = 10, s = \"k\" } == mk { x = 1, y = 99, s = \"k\" }\nmain {}")) }
    @Test fun pairEqFalseOnB(){ assertEquals(false, run("let mk: { x: Int, y: Int, s: Str } -> Pair P Str = { x, y, s } -> { a = P.({ x = x, y = y }), b = s }\nlet main = {} -> mk { x = 1, y = 10, s = \"k\" } == mk { x = 1, y = 10, s = \"z\" }\nmain {}")) }
    @Test fun seqOfBoxesEq()  { assertEquals(true,  run("let mk: { x: Int, y: Int } -> Box P = { x, y } -> { v = P.({ x = x, y = y }) }\nlet main = {} -> [ mk { x = 1, y = 10 } ] == [ mk { x = 1, y = 99 } ]\nmain {}")) }

    // a user Ord on the container dispatching the element Ord inside stdlib ops
    private val ordHead = """
        impl Ord for Tree where {
          lt = { it, r } -> match it is
            Leaf -> match r is
              Leaf -> False
              Node { l = _, v = _, r = _ } -> True
            Node { l = _, v = a, r = _ } -> match r is
              Leaf -> False
              Node { l = _, v = b, r = _ } -> a < b,
          le = { it, r } -> if r < it then False else True,
          gt = { it, r } -> r < it,
          ge = { it, r } -> if it < r then False else True,
        }
    """.trimIndent()
    private fun runO(body: String) = run("$ordHead\n$body")
    @Test fun treeLt()        { assertEquals(true,  runO("let main = {} -> leaf { x = 1, y = 0 } < leaf { x = 2, y = 0 }\nmain {}")) }
    @Test fun seqOfTreesLt()  { assertEquals(true,  runO("let main = {} -> [ leaf { x = 1, y = 0 } ] < [ leaf { x = 2, y = 0 } ]\nmain {}")) }
    @Test fun sortTrees()     { assertEquals(1L,    runO("let main = {} -> match seq::first { xs = seq::sort { xs = [ leaf { x = 3, y = 0 }, leaf { x = 1, y = 0 }, leaf { x = 2, y = 0 } ] } } is\n  Some { val = t } -> match t is\n    Node { l = _, v = v, r = _ } -> px { p = v }\n    Leaf -> 0 - 1\n  None -> 0 - 1\nmain {}")) }
    @Test fun minTree()       { assertEquals(1L,    runO("let main = {} -> match seq::min { xs = [ leaf { x = 3, y = 0 }, leaf { x = 1, y = 0 } ] } is\n  Some { val = t } -> match t is\n    Node { l = _, v = v, r = _ } -> px { p = v }\n    Leaf -> 0 - 1\n  None -> 0 - 1\nmain {}")) }
}
