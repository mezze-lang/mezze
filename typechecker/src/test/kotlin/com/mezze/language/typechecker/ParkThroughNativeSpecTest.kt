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
            pub let main = {} -> perform SystemTime, EventLoop in
              seq::for_each { xs = [1, 2], f = { x } -> yield_now {} }
            """,
            "V0513",
            "the native would swallow the park; the runtime detector is the backstop, this is the front door",
        )
    }

    @Test
    fun `the same program under LoomAsync is fine`() {
        accepts(
            """
            $imports
            pub let main = {} -> perform LoomAsync in
              seq::for_each { xs = [1, 2], f = { x } -> yield_now {} }
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
              let rs = seq::map { xs = ps, f = { x } -> result::get_or { result = await { p = x }, def = -1 } }
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
