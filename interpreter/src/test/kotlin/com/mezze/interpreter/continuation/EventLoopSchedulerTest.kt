package com.mezze.interpreter.continuation

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The task machine driven by a userland scheduler — continuations-and-swappable-async,
 * "A scheduler, whole". Everything here runs on ONE thread: tasks are BDSL
 * continuations the machine resumes, policy is the performer's `drive`, and the
 * interleaving is therefore DETERMINISTIC — asserted on the Console output order.
 * (`Mut` is hermetic and cannot share a row with `Async`, which is why the log is
 * stdout and not a mutable seq.)
 *
 *  - `EventLoop` (stdlib, no `drive`) is FIFO: two workers that yield between
 *    steps interleave a1 b1 a2 b2 a3 b3.
 *  - A userland performer with `drive = { runnable } -> runnable.last {}` is LIFO:
 *    the same program runs b to completion first. It has no native of its own.
 *  - `await` on a task's promise parks the awaiting task and resumes it with the
 *    value — through a nested await.
 *  - `park { predicate }` wakes when another task's progress makes it true.
 *  - Nothing runnable and nothing that can wake is a DEADLOCK, raised as an error
 *    that names the parked tasks and what they wait on; an EMPTY wait set is one.
 *  - Scope teardown: a boundary owns the tasks spawned under it; at exit the
 *    runnable ones finish, the parked ones are delivered `ParkCancelled`, and an
 *    inner boundary never touches an outer one's tasks.
 */
class EventLoopSchedulerTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
        com.mezze.language.typechecker.TypeChecker.clearCache()
    }

    /** Snippets are trimmed INDIVIDUALLY and joined — interpolating a trimmed snippet
     *  into an indented raw string leaves its first line indented and the rest not. */
    private fun program(vararg parts: String): String = parts.joinToString("\n") { it.trimIndent() }

    /** The harness evaluates the module's top-level expression (it does not call `main`). */
    private fun lines(source: String): List<String> =
        MezzeTestHelper.runWithOutput(source).output().trim().lines()

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private val imports = """
        use std::effects::async::{ Async, EventLoop, await, yield_now, park, is_done, spawn_task, inspect, settled_result,
                                   promise_id, ParkOn, WaitSet, ParkOutcome, PromiseState, AsyncError, CancelOutcome, RunQueue, Task }
        use std::effects::log::{ Console, println }
        use std::result
    """.trimIndent()

    /** Two workers, three steps each, a yield between steps; stdout is the interleaving. */
    private val twoWorkers = """
        let worker = { name } -> do
          println { msg = "${'$'}(name)1" }
          yield_now {}
          println { msg = "${'$'}(name)2" }
          yield_now {}
          println { msg = "${'$'}(name)3" }
          {}
        let program = {} -> do
          let a = Async.spawn { f = {} -> worker { name = "a" } }
          let b = Async.spawn { f = {} -> worker { name = "b" } }
          let ra = await { p = a }
          let rb = await { p = b }
          println { msg = "end" }
    """.trimIndent()

    @Test
    fun `EventLoop is FIFO - two yielding workers interleave`() = assertEquals(
        listOf("a1", "b1", "a2", "b2", "a3", "b3", "end"),
        lines(program(imports, twoWorkers, "perform Console, EventLoop in program {}")),
    )

    /** A userland scheduler: same ops as EventLoop, LIFO `drive`. No native of its own. */
    private val lifo = """
        impl Async for LifoLoop where {
          initialize_scope = {} -> {},
          finalize_scope = {} -> {},
          drive = { runnable } -> runnable.last {},
          spawn = { f } -> spawn_task { f = f },
          await = { p } -> match inspect { p = p } is
            PromiseCompleted { val } -> Ok { val = val }
            PromiseFailed { err } -> Err { error = err }
            PromisePending -> match cont.park { on = OnPromises { ids = [promise_id { p = p }] }, set = WaitDefault } is
              ParkReady { which } -> settled_result { p = p }
              ParkCancelled { reason } -> Err { error = AsyncCancelled { reason = reason } },
          cancel = { p, reason } -> CancelNotCancellable,
          yield_now = {} -> match cont.park { on = OnPredicates { fs = [{} -> True] }, set = WaitDefault } is
            ParkReady { which } -> {}
            ParkCancelled { reason } -> {},
          park = { predicate } -> match cont.park { on = OnPredicates { fs = [predicate] }, set = WaitDefault } is
            ParkReady { which } -> {}
            ParkCancelled { reason } -> {},
          park_until = { predicate, ms } -> match cont.park { on = OnPredicates { fs = [predicate] }, set = WaitDefault } is
            ParkReady { which } -> True
            ParkCancelled { reason } -> False,
        }
    """.trimIndent()

    @Test
    fun `a userland drive selector changes the order - LIFO runs b to completion first`() = assertEquals(
        listOf("b1", "b2", "b3", "a1", "a2", "a3", "end"),
        lines(program(imports, twoWorkers, lifo, "perform Console, LifoLoop in program {}")),
    )

    @Test
    fun `await parks the awaiting task and resumes it with the value - nested`() = assertEquals(
        42L,
        eval(program(imports, """
            perform EventLoop in do
              let inner = Async.spawn { f = {} -> 20 }
              let outer = Async.spawn { f = {} -> result::get_or { result = await { p = inner }, def = -1 } + 22 }
              result::get_or { result = await { p = outer }, def = -1 }
        """)),
    )

    @Test
    fun `park on a predicate another task's progress makes true`() = assertEquals(
        listOf("spawned", "flip", "after"),
        lines(program(imports, """
            perform Console, EventLoop in do
              let t = Async.spawn { f = {} -> do
                yield_now {}
                println { msg = "flip" }
                {} }
              println { msg = "spawned" }
              park { predicate = {} -> is_done { p = t } }
              println { msg = "after" }
        """)),
    )

    @Test
    fun `a task never awaited still completes before the perform returns`() = assertEquals(
        listOf("end", "late", "out"),
        lines(program(imports, """
            perform Console in do
              let r = perform EventLoop in do
                let t = Async.spawn { f = {} -> do
                  yield_now {}
                  println { msg = "late" }
                  {} }
                println { msg = "end" }
                1
              println { msg = "out" }
        """)),
    )

    @Test
    fun `a task parked forever is cancelled at scope teardown and sees ParkCancelled`() = assertEquals(
        listOf("end", "cancelled"),
        lines(program(imports, """
            perform Console, EventLoop in do
              let never = Async.spawn { f = {} -> do
                park { predicate = {} -> False }
                1 }
              let w = Async.spawn { f = {} -> match await { p = never } is
                Ok { val } -> println { msg = "ok" }
                Err { error } -> println { msg = "cancelled" } }
              println { msg = "end" }
        """)),
    )

    @Test
    fun `an inner boundary drives only its own tasks`() = assertEquals(
        listOf("u", "mid", "t"),
        lines(program(imports, """
            perform Console, EventLoop in do
              let t = Async.spawn { f = {} -> do
                yield_now {}
                yield_now {}
                println { msg = "t" }
                {} }
              let inner = perform EventLoop in do
                let u = Async.spawn { f = {} -> do
                  println { msg = "u" }
                  {} }
                await { p = u }
              println { msg = "mid" }
              await { p = t }
        """)),
    )

    @Test
    fun `nothing runnable and nothing that can wake is a deadlock, named`() {
        val e = assertThrows(Exception::class.java) {
            eval(program(imports, """
                perform EventLoop in do
                  park { predicate = {} -> False }
                  1
            """))
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("deadlock"), "the error names the condition: $msg")
        assertTrue(msg.contains("OnPredicates(1)"), "and what the task is parked on: $msg")
    }

    @Test
    fun `an empty wait set is unsatisfiable, so it is a deadlock too`() {
        val e = assertThrows(Exception::class.java) {
            eval(program("""
                use std::effects::async::{ ParkOn, WaitSet, ParkOutcome }
                effect Wait where { wait: {} -> Int <Wait>, }
                impl Wait for Nothing where {
                  initialize_scope = {} -> {},
                  finalize_scope = {} -> {},
                  wait = {} -> match cont.park { on = OnPredicates { fs = [] }, set = WaitDefault } is
                    ParkReady { which } -> which
                    ParkCancelled { reason } -> 0 - 1,
                }
                perform Nothing in Wait.wait {}
            """))
        }
        assertTrue((e.message ?: "").contains("OnPredicates(0)"), "names the empty set: ${e.message}")
    }
}
