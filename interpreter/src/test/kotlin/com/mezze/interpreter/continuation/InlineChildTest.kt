package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The inline-child fast path of the carrier machine: a task that awaits exactly ONE promise whose
 * task has not started, while that task is the only runnable one, runs it on its own carrier with no
 * handoff (spawn+await 788 → 203 ns, folds with await 790 → 205). These pin what the fast path must
 * not change: results when the inlined task itself parks (its park and resume use the shared
 * carrier), and the scheduler's order when it does NOT apply (another task is runnable, so FIFO
 * `drive` order must hold — the fast path only ever takes the sole runnable).
 */
class InlineChildTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())
    /** Printed output; the harness evaluates the module's top-level expression. */
    private fun lines(source: String): List<String> =
        MezzeTestHelper.runWithOutput(source.trimIndent()).output().trim().lines()

    private val imports = """
        use std::effects::async::{ Async, CoopAsync, await, yield_now }
        use std::effects::log::{ Console, println }
        use std::result
    """

    @Test
    fun `an inlined child that parks twice still completes on the shared carrier`() = assertEquals(
        listOf("child-1", "child-2", "child-3", "sum=42"),
        lines("""
            $imports
            perform Console, CoopAsync in do
              let p = Async.spawn { f = {} -> do
                println { msg = "child-1" }
                yield_now {}
                println { msg = "child-2" }
                yield_now {}
                println { msg = "child-3" }
                40 + 2 }
              let v = result::get_or { result = await { p = p }, def = 0 - 1 }
              println { msg = "sum=$(v.to_str {})" }
              v
        """),
    )

    @Test
    fun `with another task runnable the FIFO order holds - the fast path takes only a sole runnable`() = assertEquals(
        listOf("a", "b", "got=2"),
        lines("""
            $imports
            perform Console, CoopAsync in do
              let pa = Async.spawn { f = {} -> do
                println { msg = "a" }
                1 }
              let pb = Async.spawn { f = {} -> do
                println { msg = "b" }
                2 }
              let v = result::get_or { result = await { p = pb }, def = 0 - 1 }
              println { msg = "got=$(v.to_str {})" }
              v
        """),
    )

    @Test
    fun `an inlined child that spawns and awaits its own child nests again`() = assertEquals(
        7L,
        eval("""
            $imports
            perform CoopAsync in do
              let outer = Async.spawn { f = {} -> do
                let inner = Async.spawn { f = {} -> 3 + 4 }
                result::get_or { result = await { p = inner }, def = 0 - 1 } }
              result::get_or { result = await { p = outer }, def = 0 - 1 }
        """),
    )

    @Test
    fun `an inlined child that fails fails its promise, and the awaiter sees Err`() = assertEquals(
        "Err",
        eval("""
            $imports
            perform CoopAsync in do
              let p = Async.spawn { f = {} -> do
                let z = 0
                10 / z }
              match await { p = p } is
                Ok { val } -> "Ok"
                Err { error } -> "Err"
        """),
    )
}
