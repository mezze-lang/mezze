package com.mezze.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression tests at the intersection of #246 and #285:
 *
 *   - #246: two effects declaring an op with the same simple name must be
 *     disambiguated by (effect_hash, op_name), not by op_name alone.
 *   - #285: on a spawned task thread, unhandled ops now chain-dispatch
 *     against the inherited parent handler chain via
 *     `OpCallNode.dispatchOpOnChain` (and `MezzeSuspend.forceWithoutFiber`).
 *
 * The join of those two rules is what this file exercises: when the op
 * runs inside `Async.spawn { f = ... }`, resolution goes through the
 * per-thread chain — a *different* code path than the top-level driver
 * covered by [EffectOpNameClashBugTest]. If someone regresses the
 * hash-tagged filter on that path (e.g. drops `effectHash` from
 * `HandlerChainEntry`, or falls back to string field lookup), these
 * tests fail with silent value corruption — a matching-named op from
 * the wrong effect gets invoked.
 *
 * Each test compares to a Bool so we don't rely on Int-to-Str coercion
 * in interpolation (Mezze requires an explicit `int::to_str`).
 */
class EffectOpNameClashChainDispatchTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ------------------------------------------------------------------
    // Baseline — sanity that distinct names still work through the chain.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `distinct op names through spawn chain — baseline`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              read_a: {} -> Str <E1>
            }
            effect E2 where {
              read_b: {} -> Int <E2>
            }
            impl E1 for H1 where {
              read_a = {} -> "from_E1"
            }
            impl E2 for H2 where {
              read_b = {} -> 42
            }

            perform SystemTime in
            perform LoomAsync in
            perform H1 in
            perform H2 in do
              let p = Async.spawn { f = {} -> do
                let a = E1.read_a {}
                let b = E2.read_b {}
                a == "from_E1" && b == 42
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    // ------------------------------------------------------------------
    // Core: same op simple-name in two effects, both called from inside
    // a spawn'd task. Must route to the correct handler by effect hash.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `same op simple name across two effects — reachable from spawned task`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              shared: {} -> Str <E1>
            }
            effect E2 where {
              shared: {} -> Int <E2>
            }
            impl E1 for H1 where {
              shared = {} -> "from_E1"
            }
            impl E2 for H2 where {
              shared = {} -> 99
            }

            perform SystemTime in
            perform LoomAsync in
            perform H1 in
            perform H2 in do
              let p = Async.spawn { f = {} -> do
                let a = E1.shared {}
                let b = E2.shared {}
                a == "from_E1" && b == 99
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    /**
     * Flipped chain order: H2 inside, H1 outside. The chain walk is
     * innermost-first, so if the filter drops the effect hash the
     * innermost `shared` field wins on both call sites — corruption.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `same op simple name — flipped chain order from spawned task`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              shared: {} -> Str <E1>
            }
            effect E2 where {
              shared: {} -> Int <E2>
            }
            impl E1 for H1 where {
              shared = {} -> "from_E1"
            }
            impl E2 for H2 where {
              shared = {} -> 99
            }

            perform SystemTime in
            perform LoomAsync in
            perform H2 in
            perform H1 in do
              let p = Async.spawn { f = {} -> do
                let a = E1.shared {}
                let b = E2.shared {}
                a == "from_E1" && b == 99
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    /**
     * Same op name AND same return type in both effects — this is the
     * strong form. If dispatch accidentally works because the types
     * differ, the previous tests could pass while this one fails.
     * Both call sites must route by effect identity.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `same op simple name AND same return type — spawned task`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              read: {} -> Str <E1>
            }
            effect E2 where {
              read: {} -> Str <E2>
            }
            impl E1 for H1 where {
              read = {} -> "value_from_E1"
            }
            impl E2 for H2 where {
              read = {} -> "value_from_E2"
            }

            perform SystemTime in
            perform LoomAsync in
            perform H1 in
            perform H2 in do
              let p = Async.spawn { f = {} -> do
                let a = E1.read {}
                let b = E2.read {}
                a == "value_from_E1" && b == "value_from_E2"
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    // ------------------------------------------------------------------
    // Breadth: 3-way clash from a spawned task.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `three effects with same op simple name — spawned task`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              fetch: {} -> Str <E1>
            }
            effect E2 where {
              fetch: {} -> Int <E2>
            }
            effect E3 where {
              fetch: {} -> Bool <E3>
            }
            impl E1 for H1 where {
              fetch = {} -> "alpha"
            }
            impl E2 for H2 where {
              fetch = {} -> 7
            }
            impl E3 for H3 where {
              fetch = {} -> True
            }

            perform SystemTime in
            perform LoomAsync in
            perform H1 in
            perform H2 in
            perform H3 in do
              let p = Async.spawn { f = {} -> do
                let a = E1.fetch {}
                let b = E2.fetch {}
                let c = E3.fetch {}
                a == "alpha" && b == 7 && c == True
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    // ------------------------------------------------------------------
    // Nested spawn: a spawned task itself spawns. Chain snapshot must
    // preserve hash tags across both hops.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `same op name survives a nested spawn hop`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              shared: {} -> Str <E1>
            }
            effect E2 where {
              shared: {} -> Int <E2>
            }
            impl E1 for H1 where {
              shared = {} -> "from_E1"
            }
            impl E2 for H2 where {
              shared = {} -> 99
            }

            perform SystemTime in
            perform LoomAsync in
            perform H1 in
            perform H2 in do
              let p_outer = Async.spawn { f = {} -> do
                let p_inner = Async.spawn { f = {} -> do
                  let a = E1.shared {}
                  let b = E2.shared {}
                  a == "from_E1" && b == 99
                }
                result::get_or { result = await { p = p_inner }, def = False }
              }
              result::get_or { result = await { p = p_outer }, def = False }
        """
        assertEquals(true, eval(source))
    }

    // ------------------------------------------------------------------
    // Perform-inside-spawn where the enclosing perform does NOT handle
    // the clashing op. This exercises the #285 unhandled-op → chain
    // dispatch fallback specifically. If chain dispatch loses the hash
    // tag, we get corruption at the fallback site.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `perform-inside-spawn falls through to chain — correct effect wins`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            effect E1 where {
              shared: {} -> Str <E1>
            }
            effect E2 where {
              shared: {} -> Int <E2>
            }
            impl E1 for H1 where {
              shared = {} -> "from_E1"
            }
            impl E2 for H2 where {
              shared = {} -> 99
            }

            perform SystemTime in
            perform LoomAsync in
            perform H1 in
            perform H2 in do
              let p = Async.spawn { f = {} ->
                # Enclose the two `shared` calls in a perform that handles
                # NEITHER — forcing the driver's unhandled-op branch on
                # the spawned thread to chain-dispatch (that's the #285
                # fix path). If that dispatch drops the effect hash, one
                # of these two lookups misroutes.
                perform WithReader { val = "inert" } in do
                  let a = E1.shared {}
                  let b = E2.shared {}
                  a == "from_E1" && b == 99
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    // ------------------------------------------------------------------
    // OSThreadAsync mirror of the core test — the two Async impls
    // (Loom vs platform-thread) go through slightly different task
    // boundaries; both should honor (effect_hash, op_name).
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Shadowing: same effect, two nested `perform`s of the SAME handler
    // type but different closure values. Innermost must win, and it must
    // still win when called from a spawned task via chain dispatch. If
    // the chain filter drops effect hash equality by accident it could
    // pick the outer handler, since both entries carry the same hash.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `same effect performed twice — innermost wins from spawned task`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            perform SystemTime in
            perform LoomAsync in
            perform WithReader { val = "outer" } in do
              perform WithReader { val = "inner" } in do
                let p = Async.spawn { f = {} -> do
                  let v = Reader.ask {}
                  v == "inner"
                }
                result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    // ------------------------------------------------------------------
    // Same-name-as-a-builtin: user effect declares an op named `spawn`.
    // Async.spawn calls must NOT route to it, and E.spawn must NOT route
    // to Async's handler. Both effects live in the same chain at once.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `user effect named spawn does not collide with Async spawn`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect Fake where {
              spawn: {} -> Int <Fake>
            }
            impl Fake for FakeH where {
              spawn = {} -> 12345
            }

            perform SystemTime in
            perform LoomAsync in
            perform FakeH in do
              let p = Async.spawn { f = {} -> do
                let n = Fake.spawn {}
                n == 12345
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    // ------------------------------------------------------------------
    // Transitive: a handler for E1 internally calls E2's same-named op.
    // The chain walk must not confuse the two dispatches even under
    // re-entry.
    // ------------------------------------------------------------------

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `handler calls other same-named op transitively from spawned task`() {
        val source = """
            use std::effects::async::{ Async, LoomAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              tag: {} -> Str <E1, E2>
            }
            effect E2 where {
              tag: {} -> Str <E2>
            }
            impl E2 for H2 where {
              tag = {} -> "E2"
            }
            impl E1 for H1 where {
              # E1.tag internally asks E2.tag then decorates. If the
              # chain walk misroutes, we'd either loop or read E1.tag's
              # own literal from a wrong branch.
              tag = {} -> do
                let inner = E2.tag {}
                "E1($(inner))",
            }

            perform SystemTime in
            perform LoomAsync in
            perform H2 in
            perform H1 in do
              let p = Async.spawn { f = {} -> do
                let v = E1.tag {}
                v == "E1(E2)"
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `same op simple name across two effects — OSThread task`() {
        val source = """
            use std::effects::async::{ Async, OSThreadAsync, await }
            use std::effects::time::{Time, SystemTime}
            use std::result

            effect E1 where {
              shared: {} -> Str <E1>
            }
            effect E2 where {
              shared: {} -> Int <E2>
            }
            impl E1 for H1 where {
              shared = {} -> "from_E1"
            }
            impl E2 for H2 where {
              shared = {} -> 99
            }

            perform SystemTime in
            perform OSThreadAsync in
            perform H1 in
            perform H2 in do
              let p = Async.spawn { f = {} -> do
                let a = E1.shared {}
                let b = E2.shared {}
                a == "from_E1" && b == 99
              }
              result::get_or { result = await { p = p }, def = False }
        """
        assertEquals(true, eval(source))
    }
}
