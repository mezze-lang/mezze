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
 *  - `CoopAsync` (stdlib, no `drive`) is FIFO: two workers that yield between
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
class CoopAsyncSchedulerTest {

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
        use std::effects::async::{ Async, CoopAsync, await, yield_now, park, park_until, cancel, is_done, spawn_task, inspect, all, race,
                                   settled_result, promise_id, ParkOn, WaitSet, ParkOutcome, PromiseState, AsyncError,
                                   CancelReason, CancelOutcome, RunQueue, Task }
        use std::effects::log::{ Console, println }
        use std::collections::seq
        use std::int
        use std::effects::time::{ Time, SystemTime, MockTime }
        use std::datetime::{ test_clock_at, test_clock_advance, duration_millis, epoch }
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
    fun `CoopAsync is FIFO - two yielding workers interleave`() = assertEquals(
        listOf("a1", "b1", "a2", "b2", "a3", "b3", "end"),
        lines(program(imports, twoWorkers, "perform Console, SystemTime, CoopAsync in program {}")),
    )

    /** A userland scheduler: same ops as CoopAsync, LIFO `drive`. No native of its own. */
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
            perform SystemTime, CoopAsync in do
              let inner = Async.spawn { f = {} -> 20 }
              let outer = Async.spawn { f = {} -> result::get_or { result = await { p = inner }, def = -1 } + 22 }
              result::get_or { result = await { p = outer }, def = -1 }
        """)),
    )

    @Test
    fun `park on a predicate another task's progress makes true`() = assertEquals(
        listOf("spawned", "flip", "after"),
        lines(program(imports, """
            perform Console, SystemTime, CoopAsync in do
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
              let r = perform SystemTime, CoopAsync in do
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
            perform Console, SystemTime, CoopAsync in do
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
            perform Console, SystemTime, CoopAsync in do
              let t = Async.spawn { f = {} -> do
                yield_now {}
                yield_now {}
                println { msg = "t" }
                {} }
              let inner = perform SystemTime, CoopAsync in do
                let u = Async.spawn { f = {} -> do
                  println { msg = "u" }
                  {} }
                await { p = u }
              println { msg = "mid" }
              await { p = t }
        """)),
    )

    @Test
    fun `cancel wakes a parked task with ParkCancelled`() = assertEquals(
        listOf("cancel-sent", "woke"),
        lines(program(imports, """
            perform Console, SystemTime, CoopAsync in do
              let t = Async.spawn { f = {} -> do
                park { predicate = {} -> False }
                println { msg = "woke" }
                {} }
              yield_now {}
              let c = cancel { p = t, reason = CancelUserRequested { msg = "stop" } }
              println { msg = "cancel-sent" }
        """)),
    )

    /** The deadline is a PREDICATE performing `Time`; with `MockTime` the whole run is
     *  deterministic: a task advances the clock, the waiter times out. */
    @Test
    fun `park_until times out on a mock clock another task advances`() = assertEquals(
        listOf("advanced", "timed-out=False"),
        lines(program(imports, """
            let clock = test_clock_at { i = epoch {} }
            perform Console, MockTime { clock = clock }, CoopAsync in do
              let t = Async.spawn { f = {} -> do
                yield_now {}
                test_clock_advance { c = clock, d = duration_millis { n = 150 } }
                println { msg = "advanced" }
                {} }
              let r = park_until { predicate = {} -> False, ms = 100 }
              println { msg = "timed-out=${'$'}(r.to_dbg {})" }
        """)),
    )

    @Test
    fun `park_until answers True when the predicate wins`() = assertEquals(
        listOf("True"),
        lines(program(imports, """
            let clock = test_clock_at { i = epoch {} }
            perform Console, MockTime { clock = clock }, CoopAsync in do
              let t = Async.spawn { f = {} -> 1 }
              let r = park_until { predicate = {} -> is_done { p = t }, ms = 100 }
              println { msg = r.to_dbg {} }
        """)),
    )

    /** A comparator native is not resumable (C4 stops at Java's sort). The direct
     *  spelling is V0513 at compile time (`ParkThroughNativeSpecTest`); this reaches the
     *  native through a function VALUE, which the static rule cannot see, so the native
     *  itself names the error at once (measured before any of it: `after` never
     *  printed and the program finished). */
    @Test
    fun `a park inside a comparator just parks - the carrier blocks and the sort completes`() {
        val out = lines(program(imports, """
            use std::collections::seq
            let each = { xs, f } -> seq::sort_by { xs = xs, cmp = f }
            let via = { g } -> g { xs = [1, 2], f = { a, b } -> do
              println { msg = "before" }
              yield_now {}
              println { msg = "after" }
              True }
            perform Console, SystemTime, CoopAsync in do
              via { g = each }
              println { msg = "done" }
        """))
        assertTrue(out.contains("after"), "the comparator resumed after its park: $out")
        assertEquals("done", out.last(), "the sort completed: $out")
    }

    /** BUG 65: the stdlib's own helpers looped with native HOFs over `await`/`cancel`;
     *  under a cooperative scheduler that is a park through a native. Now Mezze loops. */
    @Test
    fun `await_all and race work under CoopAsync`() = assertEquals(
        listOf("all=[Ok { val = 1 }, Ok { val = 2 }]", "race=Ok { val = 10 }"),
        lines(program(imports, """
            perform Console, SystemTime, CoopAsync in do
              let a = Async.spawn { f = {} -> do
                yield_now {}
                1 }
              let b = Async.spawn { f = {} -> 2 }
              println { msg = "all=${'$'}((all { ps = [a, b] }).to_dbg {})" }
              let slow = Async.spawn { f = {} -> do
                park { predicate = {} -> False }
                99 }
              let fast = Async.spawn { f = {} -> 10 }
              println { msg = "race=${'$'}((race { ps = [slow, fast] }).to_dbg {})" }
        """)),
    )

    /** C4: the method-chain spelling (`ps.map { … await … }`) that reached the user as a
     *  raw ClassCastException before `map` was resumable. */
    @Test
    fun `await inside a map over promises works - the native resumes`() = assertEquals(
        listOf("[10, 20]"),
        lines(program(imports, """
            perform Console, SystemTime, CoopAsync in do
              let ps = [1, 2].map { f = { x } -> Async.spawn { f = {} -> x * 10 } }
              let rs = ps.map { f = { x } -> result::get_or { result = await { p = x }, def = -1 } }
              println { msg = rs.to_dbg {} }
        """)),
    )

    /** Two tasks each `map` with a yield per element: the loops interleave element by
     *  element, each resuming at its own index — the heap state is per native call. */
    @Test
    fun `two tasks yielding inside map interleave element by element`() = assertEquals(
        listOf("a1", "b10", "a2", "b20", "a3", "b30", "a=[1, 2, 3] b=[10, 20, 30]"),
        lines(program(imports, """
            let step = { name, x } -> do
              println { msg = "${'$'}(name)${'$'}(int::to_str { n = x })" }
              yield_now {}
              x
            perform Console, SystemTime, CoopAsync in do
              let a = Async.spawn { f = {} -> seq::map { xs = [1, 2, 3], f = { x } -> step { name = "a", x = x } } }
              let b = Async.spawn { f = {} -> seq::map { xs = [10, 20, 30], f = { x } -> step { name = "b", x = x } } }
              let ra = result::get_or { result = await { p = a }, def = [] }
              let rb = result::get_or { result = await { p = b }, def = [] }
              println { msg = "a=${'$'}(ra.to_dbg {}) b=${'$'}(rb.to_dbg {})" }
        """)),
    )

    @Test
    fun `fold, filter, any and find resume after an await inside their callback`() = assertEquals(
        listOf("fold=60", "filter=[20, 30]", "any=True", "find=Some { val = 30 }", "for_each=done"),
        lines(program(imports, """
            use std::collections::seq
            let settled = { p } -> result::get_or { result = await { p = p }, def = -1 }
            perform Console, SystemTime, CoopAsync in do
              let ps = seq::map { xs = [10, 20, 30], f = { x } -> Async.spawn { f = {} -> do
                yield_now {}
                x } }
              println { msg = "fold=${'$'}((seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + settled { p = x } }).to_str {})" }
              println { msg = "filter=${'$'}((seq::filter { xs = ps, p = { x } -> settled { p = x } > 15 }).map { f = { x } -> settled { p = x } }.to_dbg {})" }
              println { msg = "any=${'$'}((seq::any { xs = ps, p = { x } -> settled { p = x } == 20 }).to_dbg {})" }
              println { msg = "find=${'$'}((seq::find { xs = ps, p = { x } -> settled { p = x } == 30 }).map { f = { val } -> settled { p = val } }.to_dbg {})" }
              seq::for_each { xs = ps, f = { x } -> do
                let _ = settled { p = x }
                {} }
              println { msg = "for_each=done" }
        """)),
    )

    /** `sort_by` (a comparator native) is not resumable: the comparator's park is
     *  reported BY THE NATIVE, by name and at once (OrderCallbacks), here through a
     *  function VALUE the static rule cannot see. */
    @Test
    fun `an await inside a comparator through a function value just works`() = assertEquals(
        listOf("2"),
        lines(program(imports, """
            use std::collections::seq
            perform Console, SystemTime, CoopAsync in do
              let ps = [1, 2].map { f = { x } -> Async.spawn { f = {} -> x * 10 } }
              let sorter = { xs, cmp } -> seq::sort_by { xs = xs, cmp = cmp }
              let via = { g } -> g { xs = ps, cmp = { a, b } -> result::is_ok { result = await { p = a } } }
              let rs = via { g = sorter }
              println { msg = (seq::size { xs = rs }).to_str {} }
        """)),
    )

    /** C4 for iter: a SINK's callback may await (its row is `<...>`), and the consumer pulls
     *  through the cursor chain while it does. Stage callbacks (`map`, `filter`, `take_while`)
     *  are pure by main's rule — an effect belongs to the arrow that performs it, never to data
     *  — so the awaits live in the sinks: fold, any, all, find. */
    @Test
    fun `iter sinks resume after awaits in their callbacks - fold, any, all, find`() = assertEquals(
        listOf("fold=60", "any=True", "all=True", "find=30"),
        lines(program(imports, """
            use std::collections::iter
            let settled = { p } -> result::get_or { result = await { p = p }, def = -1 }
            perform Console, SystemTime, CoopAsync in do
              let ps = seq::map { xs = [10, 20, 30], f = { x } -> Async.spawn { f = {} -> do
                yield_now {}
                x } }
              let it = {} -> iter::from_seq { xs = ps }
              println { msg = "fold=${'$'}((iter::fold { it = it {}, init = 0, f = { acc, x } -> acc + settled { p = x } }).to_str {})" }
              println { msg = "any=${'$'}((iter::any { it = it {}, p = { x } -> settled { p = x } > 25 }).to_dbg {})" }
              println { msg = "all=${'$'}((iter::all { it = it {}, p = { x } -> settled { p = x } > 5 }).to_dbg {})" }
              println { msg = "find=${'$'}(match iter::find { it = it {}, p = { x } -> settled { p = x } == 30 } is
                Some { val } -> (settled { p = val }).to_str {}
                None -> "none")" }
        """)),
    )

    @Test
    fun `two tasks yielding inside iter for_each interleave`() = assertEquals(
        listOf("a1", "b1", "a2", "b2", "end"),
        lines(program(imports, """
            use std::collections::iter
            let worker = { name } -> iter::for_each { it = iter::range { start = 1, end = 3 }, f = { x } -> do
              println { msg = "${'$'}(name)${'$'}(int::to_str { n = x })" }
              yield_now {} }
            perform Console, SystemTime, CoopAsync in do
              let a = Async.spawn { f = {} -> worker { name = "a" } }
              let b = Async.spawn { f = {} -> worker { name = "b" } }
              let _ = await { p = a }
              let _ = await { p = b }
              println { msg = "end" }
        """)),
    )

    /** C4 for the rest of seq and for list, on one shared indexed driver. */
    @Test
    fun `the remaining seq natives and the list natives resume after an await`() = assertEquals(
        listOf("fi=Some { val = 1 }", "red=Some { val = 60 }", "cnt=2",
               "part=[20, 30]/[10]", "tw=[10]", "dw=[20, 30]", "fm=[10, 20, 30]",
               "scan=[0, 10, 30, 60]", "lmap=[11, 21, 31]", "lfold=60", "lfind=Some { val = 20 }", "lany=True"),
        lines(program(imports, """
            use std::collections::list
            let settled = { p } -> result::get_or { result = await { p = p }, def = -1 }
            perform Console, SystemTime, CoopAsync in do
              let ps = seq::map { xs = [10, 20, 30], f = { x } -> Async.spawn { f = {} -> do
                yield_now {}
                x } }
              println { msg = "fi=${'$'}((seq::find_index { xs = ps, p = { x } -> settled { p = x } == 20 }).to_dbg {})" }
              println { msg = "red=${'$'}((seq::reduce { xs = ps.map { f = { x } -> settled { p = x } }, f = { acc, x } -> acc + x }).to_dbg {})" }
              println { msg = "cnt=${'$'}((seq::count { xs = ps, p = { x } -> settled { p = x } > 15 }).to_str {})" }
              let part = seq::partition { xs = ps, p = { x } -> settled { p = x } > 15 }
              println { msg = "part=${'$'}((part.pass.map { f = { x } -> settled { p = x } }).to_dbg {})/${'$'}((part.fail.map { f = { x } -> settled { p = x } }).to_dbg {})" }
              println { msg = "tw=${'$'}((seq::take_while { xs = ps, p = { x } -> settled { p = x } < 15 }).map { f = { x } -> settled { p = x } }.to_dbg {})" }
              println { msg = "dw=${'$'}((seq::drop_while { xs = ps, p = { x } -> settled { p = x } < 15 }).map { f = { x } -> settled { p = x } }.to_dbg {})" }
              println { msg = "fm=${'$'}((seq::flat_map { xs = ps, f = { x } -> [settled { p = x }] }).to_dbg {})" }
              println { msg = "scan=${'$'}((seq::scan { xs = ps, init = 0, f = { acc, x } -> acc + settled { p = x } }).to_dbg {})" }
              let l = list::from_seq { xs = ps }
              println { msg = "lmap=${'$'}((list::to_seq { xs = list::map { xs = l, f = { x } -> settled { p = x } + 1 } }).to_dbg {})" }
              println { msg = "lfold=${'$'}((list::fold_left { xs = l, init = 0, f = { acc, x } -> acc + settled { p = x } }).to_str {})" }
              println { msg = "lfind=${'$'}((list::find { xs = l, p = { x } -> settled { p = x } == 20 }).map { f = { val } -> settled { p = val } }.to_dbg {})" }
              println { msg = "lany=${'$'}((list::any { xs = l, p = { x } -> settled { p = x } == 30 }).to_dbg {})" }
        """)),
    )

    /** Phase B: a task dispatches against the handlers in effect WHERE IT WAS SPAWNED,
     *  not where it happens to be driven. Before Phase B this printed "A" — the task
     *  spawned under `perform B` was driven by the outer boundary under A. */
    @Test
    fun `a task keeps the handlers it was spawned under`() = assertEquals(
        listOf("B", "A"),
        lines(program(imports, """
            effect Tag where { tag: {} -> Str <Tag>, }
            impl Tag for A where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              tag = {} -> "A",
            }
            impl Tag for B where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              tag = {} -> "B",
            }
            perform Console, SystemTime, CoopAsync, A in do
              let t = perform B in Async.spawn { f = {} -> do
                yield_now {}
                Tag.tag {} }
              println { msg = result::get_or { result = await { p = t }, def = "?" } }
              println { msg = Tag.tag {} }
        """)),
    )

    @Test
    fun `two tasks under different inner performers each see their own`() = assertEquals(
        listOf("x=X", "y=Y"),
        lines(program(imports, """
            effect Tag where { tag: {} -> Str <Tag>, }
            impl Tag for X where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              tag = {} -> "X",
            }
            impl Tag for Y where {
              initialize_scope = {} -> {},
              finalize_scope = {} -> {},
              tag = {} -> "Y",
            }
            perform Console, SystemTime, CoopAsync in do
              let x = perform X in Async.spawn { f = {} -> do
                yield_now {}
                yield_now {}
                "x=${'$'}(Tag.tag {})" }
              let y = perform Y in Async.spawn { f = {} -> do
                yield_now {}
                "y=${'$'}(Tag.tag {})" }
              println { msg = result::get_or { result = await { p = x }, def = "?" } }
              println { msg = result::get_or { result = await { p = y }, def = "?" } }
        """)),
    )

    /** C1 hole found through sort_by's stdlib wrapper: an indirect call rewritten to the
     *  SCALAR ABI lost its `maySuspend` flag, so a park inside `if cmp { … } then …`
     *  was dropped — with no native involved at all. */
    @Test
    fun `a park inside an if condition through a scalar indirect call propagates`() = assertEquals(
        listOf("-1"),
        lines(program(imports, """
            let wrap = { cmp } -> { a, b } -> if cmp { a = a, b = b } then 0 - 1 else 1
            perform Console, SystemTime, CoopAsync in do
              let p = Async.spawn { f = {} -> 10 }
              let w = wrap { cmp = { a, b } -> result::is_ok { result = await { p = a } } }
              let r = w { a = p, b = p }
              println { msg = r.to_str {} }
        """)),
    )

    /** A plain predicate that never becomes true is NOT reported as a deadlock any more: in an open
     *  system a foreign thread may change the state, so the machine re-checks with backoff (and
     *  prints one diagnostic after the grace period). A deadline bounds it here. */
    @Test
    fun `a plain predicate that never holds is waited for until its deadline, not declared a deadlock`() = assertEquals(
        false,
        eval(program(imports, """
            perform SystemTime, CoopAsync in park_until { predicate = {} -> False, ms = 60 }
        """)),
    )

    @Test
    fun `an empty wait set is unsatisfiable, so it is the one deadlock the machine reports`() {
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
