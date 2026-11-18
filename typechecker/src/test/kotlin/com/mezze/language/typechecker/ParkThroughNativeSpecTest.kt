package com.mezze.language.typechecker

import org.junit.jupiter.api.Test

/**
 * V0513 — until C4 (resumable natives): a callback that may PARK, handed to a native
 * that runs it synchronously, is refused at compile time. Measured before the rule:
 * `seq::for_each { f = { x } -> yield_now {} }` under `EventLoop` dropped the rest of
 * the callback and the program finished "normally".
 *
 * Decided from types, not names: the callee is a native wrapper, its row carries the
 * callback's effects (it RUNS the callback), and the module installs a cont-capable
 * performer of one of them. Each refusal has its accepting mirror.
 */
class ParkThroughNativeSpecTest : AbilityEffectSpecBase() {

    private val imports = """
        use std::effects::async::{ Async, EventLoop, LoomAsync, yield_now, spawn_task }
        use std::effects::time::{ Time, SystemTime }
        use std::collections::seq
    """

    @Test
    fun `a parking callback handed to a native HOF under EventLoop is refused`() {
        rejects(
            """
            $imports
            pub let main = {} -> perform SystemTime, EventLoop in do
              let m = seq::sort_by { xs = [1, 2], cmp = { a, b } -> do
                yield_now {}
                True }
              {}
            """,
            "V0513",
            "`sort_by` (a comparator native) is not resumable; the native would swallow the park",
        )
    }

    @Test
    fun `the same program under LoomAsync is fine`() {
        accepts(
            """
            $imports
            pub let main = {} -> perform LoomAsync in do
              let m = seq::sort_by { xs = [1, 2], cmp = { a, b } -> do
                yield_now {}
                True }
              {}
            """,
            "LoomAsync never parks: its yield_now is a thread yield inside the native",
        )
    }

    @Test
    fun `a callback free of the parking effect is fine under EventLoop`() {
        accepts(
            """
            $imports
            pub let main = {} -> perform SystemTime, EventLoop in
              seq::for_each { xs = [1, 2], f = { x } -> x + 1 }
            """,
            "no effect in the row, nothing to park on",
        )
    }

    /** Op-granular: `spawn` is an op of `Async`, but EventLoop's `spawn` never parks —
     *  only its `await`/`yield_now`/`park`/`park_until` mention `cont`. The first cut of
     *  the rule flagged this line (measured on the CLI) and told the user to rewrite a
     *  loop that was fine. */
    /** C4: these natives resume after a parking callback — no refusal, it just works. */
    @Test
    fun `a parking callback handed to a RESUMABLE native is fine - for_each, map, fold`() {
        accepts(
            """
            $imports
            use std::effects::async::{ await }
            use std::result
            pub let main = {} -> perform SystemTime, EventLoop in do
              seq::for_each { xs = [1, 2], f = { x } -> yield_now {} }
              let ps = seq::map { xs = [1, 2], f = { x } -> Async.spawn { f = {} -> x * 10 } }
              let rs = seq::map { xs = ps, f = { x } -> result::get_or { result = await { p = x }, def = -1 } }
              let total = seq::fold { xs = ps, init = 0, f = { acc, x } -> acc + result::get_or { result = await { p = x }, def = 0 } }
              {}
            """,
            "fold/map/for_each/filter/any/all/find hold their loop state on the heap when a callback suspends",
        )
    }

    @Test
    fun `iter stages and consumers are resumable too`() {
        accepts(
            """
            $imports
            use std::effects::async::{ await }
            use std::collections::iter
            use std::result
            pub let main = {} -> perform SystemTime, EventLoop in do
              let ps = seq::map { xs = [1, 2], f = { x } -> Async.spawn { f = {} -> x * 10 } }
              let total = iter::fold { it = iter::map { it = iter::from_seq { xs = ps }, f = { x } -> result::get_or { result = await { p = x }, def = 0 } }, init = 0, f = { acc, x } -> acc + x }
              {}
            """,
            "the consumer pulls through resumable cursors when the chain may suspend",
        )
    }

    /** The wrapper test follows direct-call hops: `min_by` → `min_by_with_cmp` → native. */
    @Test
    fun `a two-hop wrapper is seen through - min_by`() {
        rejects(
            """
            $imports
            pub let main = {} -> perform SystemTime, EventLoop in do
              let m = seq::min_by { xs = [1, 2], f = { x } -> do
                yield_now {}
                x }
              {}
            """,
            "V0513",
            "`min_by` reaches `__native_seq_min_by` through `min_by_with_cmp`; a parking key function would be swallowed",
        )
    }

    /** The method spelling: `xs.sort_by { … }` → inherent op → `seq::sort_by` → native. */
    @Test
    fun `the method-chain spelling is seen through - sort_by`() {
        rejects(
            """
            $imports
            pub let main = {} -> perform SystemTime, EventLoop in do
              let m = [1, 2].sort_by { cmp = { a, b } -> do
                yield_now {}
                True }
              {}
            """,
            "V0513",
            "the inherent op is one more hop to the same native",
        )
    }

    @Test
    fun `a callback that only reaches a non-parking op is fine - spawn in a map`() {
        accepts(
            """
            $imports
            pub let main = {} -> perform SystemTime, EventLoop in do
              let ps = seq::map { xs = [1, 2], f = { x } -> Async.spawn { f = {} -> x * 10 } }
              {}
            """,
            "`spawn` does not mention `cont` in EventLoop; the rule is per op, not per effect",
        )
    }

    @Test
    fun `a forwarder to a parking op is seen through - await in a map`() {
        rejects(
            """
            $imports
            use std::effects::async::{ await }
            use std::result
            pub let main = {} -> perform SystemTime, EventLoop in do
              let ps = seq::map { xs = [1, 2], f = { x } -> Async.spawn { f = {} -> x * 10 } }
              let m = seq::sort_by { xs = ps, cmp = { a, b } -> result::is_ok { result = await { p = a } } }
              {}
            """,
            "V0513",
            "`await { p }` is `Async.await { p }`; the forwarder's lowered body says which op",
        )
    }

    @Test
    fun `a callback a native DEFERS is fine - spawn_task runs it as a task`() {
        accepts(
            """
            $imports
            pub let main = {} -> perform SystemTime, EventLoop in do
              let p = spawn_task { f = {} -> yield_now {} }
              yield_now {}
            """,
            "`spawn_task: { f: {} -> 'a <...> } -> Promise 'a` — a pure arrow: the callback does not run during the call",
        )
    }

    @Test
    fun `a Mezze HOF with a parking callback is fine - it is BDSL, it suspends`() {
        accepts(
            """
            $imports
            let each = { xs, f } -> match xs is
              [] -> {}
              [x, ...rest] -> do
                f { x = x }
                each { xs = rest, f = f }
            pub let main = {} -> perform SystemTime, EventLoop in
              each { xs = [1, 2], f = { x } -> yield_now {} }
            """,
            "only natives cannot be suspended through",
        )
    }
}
