package com.mezze.interpreter.repro

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Minimal repros for the suspected #84 closure-capture bug found while
 * landing #83 (delete_recursive in std::effects::filesystem).
 *
 * Each test narrows one axis:
 *  - baseline: does seq::fold closure work at all in a simple case?
 *  - with pub let param: does the callback see the enclosing pub let's arg?
 *  - with recursion: does self-referencing pub let close over its own param?
 *  - with records: does destructuring in the callback confuse things?
 *  - with effects: does an effectful body inside the callback matter?
 *
 * If baseline passes and one narrower case fails, we've isolated the axis.
 */
class ClosureCaptureReproTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(src: String): Any = MezzeTestHelper.run(src)

    /** Baseline: fold closure captures an outer local let-binding. */
    @Test
    fun `baseline — fold closure captures outer local let`() {
        val code = """
            use std::collections::seq
            let base = 100
            seq::fold {
              xs = [1, 2, 3],
              init = 0,
              f = { acc, x } -> acc + base + x
            }
        """.trimIndent()
        // 0 + (100+1) + (100+2) + (100+3) = 306
        assertEquals(306L, eval(code))
    }

    /** Does fold callback close over a pub let's parameter? */
    @Test
    fun `fold closure captures pub let parameter`() {
        val code = """
            use std::collections::seq
            pub let f : { base: Int } -> Int = { base } ->
              seq::fold {
                xs = [1, 2, 3],
                init = 0,
                f = { acc, x } -> acc + base + x
              }
            f { base = 100 }
        """.trimIndent()
        // Same as baseline, but base comes from pub let param.
        assertEquals(306L, eval(code))
    }

    /** Same as above but the closure references a Str parameter and
     *  interpolates. Concatenates results in the accumulator. */
    @Test
    fun `fold closure captures Str pub let parameter`() {
        val code = """
            use std::str
            use std::collections::seq
            pub let f : { prefix: Str } -> Str = { prefix } ->
              seq::fold {
                xs = ["a", "b", "c"],
                init = "",
                f = { acc, x } -> "${'$'}(acc)${'$'}(prefix)/${'$'}(x);"
              }
            f { prefix = "/d" }
        """.trimIndent()
        assertEquals("/d/a;/d/b;/d/c;", eval(code))
    }

    /** Recursive pub let — does the recursive call see its own param? */
    @Test
    fun `recursive pub let sees its own parameter`() {
        val code = """
            use std::collections::seq
            pub let sum_down : { n: Int } -> Int = { n } ->
              match n <= 0 is
                True -> 0
                False -> n + sum_down { n = n - 1 }
            sum_down { n = 5 }
        """.trimIndent()
        // 5+4+3+2+1 = 15
        assertEquals(15L, eval(code))
    }

    /** Recursive pub let where recursion is invoked from a fold callback. */
    @Test
    fun `recursive pub let called from fold callback`() {
        val code = """
            use std::collections::seq
            pub let inc : { n: Int } -> Int = { n } -> n + 1
            pub let f : { base: Int } -> Int = { base } ->
              seq::fold {
                xs = [10, 20, 30],
                init = 0,
                f = { acc, x } -> acc + inc { n = x } + base
              }
            f { base = 1 }
        """.trimIndent()
        // 0 + (11+1) + (21+1) + (31+1) = 66
        assertEquals(66L, eval(code))
    }

    /** SELF-recursive pub let called from its own fold callback,
     *  with param closure — this is the exact filesystem shape. */
    @Test
    fun `self-recursive pub let called from own fold callback with param closure`() {
        val code = """
            use std::collections::seq
            pub let sum_tree : { depth: Int } -> Int = { depth } ->
              match depth <= 0 is
                True -> 1
                False -> seq::fold {
                  xs = [1, 2],
                  init = 0,
                  f = { acc, x } -> acc + sum_tree { depth = depth - 1 }
                }
            sum_tree { depth = 3 }
        """.trimIndent()
        // Each level: sum over 2 elements of the recursive result.
        // depth 0 -> 1
        // depth 1 -> 1+1 = 2
        // depth 2 -> 2+2 = 4
        // depth 3 -> 4+4 = 8
        assertEquals(8L, eval(code))
    }

    /** Minimal: does a lambda that closes over a Str pub let param
     *  and interpolates it just work outside any HOF? */
    @Test
    fun `plain lambda captures Str pub let param via interpolation`() {
        val code = """
            pub let f : { prefix: Str } -> Str = { prefix } ->
              (({ x: Str } -> "${'$'}(prefix)/${'$'}(x)") { x = "a" })
            f { prefix = "/root" }
        """.trimIndent()
        assertEquals("/root/a", eval(code))
    }

    /** Same closure pattern, but concat via str::concat instead of
     *  interpolation. Isolates: is the bug in interpolation, or in
     *  the closure capture itself? */
    @Test
    fun `plain lambda captures Str pub let param via str concat`() {
        val code = """
            use std::str
            pub let f : { prefix: Str } -> Str = { prefix } ->
              (({ x: Str } -> str::concat { l = prefix, r = x }) { x = "a" })
            f { prefix = "/root" }
        """.trimIndent()
        assertEquals("/roota", eval(code))
    }

    /** Same, but the outer lambda directly interpolates prefix
     *  without any nested lambda. This should trivially work. */
    @Test
    fun `pub let body interpolates Str param directly`() {
        val code = """
            pub let f : { prefix: Str } -> Str = { prefix } -> "${'$'}(prefix)/x"
            f { prefix = "/root" }
        """.trimIndent()
        assertEquals("/root/x", eval(code))
    }

    /** Fold callback whose `x` is a record — does destructuring `x.name`
     *  or accessing `x.field` inside the callback while also referencing
     *  the outer param work? */
    @Test
    fun `fold callback with record x and outer param`() {
        val code = """
            use std::collections::seq
            pub let concat_all : { prefix: Str } -> Str = { prefix } ->
              seq::fold {
                xs = [{ name = "a" }, { name = "b" }, { name = "c" }],
                init = "",
                f = { acc, x } -> "${'$'}(acc)${'$'}(prefix)/${'$'}(x.name)"
              }
            concat_all { prefix = "/root" }
        """.trimIndent()
        assertEquals("/root/a/root/b/root/c", eval(code))
    }
}
