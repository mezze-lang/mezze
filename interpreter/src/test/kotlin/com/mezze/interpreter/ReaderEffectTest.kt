package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * #238 — Spec tests for the Reader 'a effect (typed ambient context,
 * algebraic-effect encoding of Haskell's Reader monad).
 *
 * Coverage:
 *   - Basic bind + ask
 *   - Sugar helpers (with_reader / ask aliases)
 *   - Shadowing + outer restore
 *   - Local override (Haskell `local`) via nested perform on transformed value
 *   - Value flow through deep call chain without parameter threading
 *   - Record carrier read at multiple sites + through deep call chain
 *   - Per-call-site binding isolation (two performs, two values)
 *   - Snapshot-at-spawn-time semantics across spawn boundary
 *   - Many concurrent spawns inheriting the same binding
 *   - Sibling reads after spawn site
 *   - Reader composed with Async in the same row
 *
 * Multi-carrier (`<Reader Str, Reader Int>` in the same row) is
 * still blocked on #283. Until that lands, multi-field context is
 * idiomatically expressed as a record carrier — exercised here.
 */
class ReaderEffectTest {

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // ------------------------------------------------------------------
    // Basic operations
    // ------------------------------------------------------------------

    @Test
    fun `bind and ask in same body`() {
        val source = """
            use std::effects::reader::{Reader, WithReader}

            perform WithReader { val = "req-7" } in do
              Reader.ask {}
        """
        assertEquals("req-7", eval(source))
    }

    @Test
    fun `module-level ask alias`() {
        val source = """
            use std::effects::reader::{Reader, WithReader, ask}

            perform WithReader { val = "via-alias" } in do
              ask {}
        """
        assertEquals("via-alias", eval(source))
    }

    @Test
    fun `with_reader sugar helper`() {
        val source = """
            use std::effects::reader::{Reader, with_reader}

            with_reader { val = "via-sugar", body = {} -> Reader.ask {} }
        """
        assertEquals("via-sugar", eval(source))
    }

    // ------------------------------------------------------------------
    // Shadowing + local override (Haskell `local`)
    // ------------------------------------------------------------------

    @Test
    fun `outer binding restored after inner block exits`() {
        val source = """
            use std::effects::reader::{Reader, WithReader}

            perform WithReader { val = "outer" } in do
              let inner_val = perform WithReader { val = "inner" } in do
                Reader.ask {}
              let after_inner = Reader.ask {}
              "$(inner_val):$(after_inner)"
        """
        assertEquals("inner:outer", eval(source))
    }

    @Test
    fun `local override via nested perform on transformed value`() {
        // Equivalent of Haskell: local (++ "!") $ ask
        val source = """
            use std::effects::reader::{Reader, WithReader}

            perform WithReader { val = "base" } in do
              let original = Reader.ask {}
              let modified = perform WithReader { val = "$(original)!" } in do
                Reader.ask {}
              let restored = Reader.ask {}
              "$(original):$(modified):$(restored)"
        """
        assertEquals("base:base!:base", eval(source))
    }

    // ------------------------------------------------------------------
    // Deeply-nested call chains
    // ------------------------------------------------------------------

    @Test
    fun `value flows through deep call chain without parameter threading`() {
        // Three helper functions: leaf reads, middle calls leaf, top
        // calls middle. None take an id parameter — it flows ambient.
        val source = """
            use std::effects::reader::{Reader, WithReader}

            let leaf : {} -> Str <Reader> = {} -> "leaf saw: $(Reader.ask {})"
            let middle : {} -> Str <Reader> = {} -> leaf {}
            let top : {} -> Str <Reader> = {} -> middle {}

            perform WithReader { val = "req-9" } in do
              top {}
        """
        assertEquals("leaf saw: req-9", eval(source))
    }

    // ------------------------------------------------------------------
    // Record carrier — the multi-field "combined value" pattern
    // ------------------------------------------------------------------

    @Test
    fun `record carrier read at multiple sites`() {
        val source = """
            use std::effects::reader::{Reader, WithReader}
            use std::int

            type Ctx = { request_id: Str, user_id: Int }

            perform WithReader { val = { request_id = "r-7", user_id = 42 } } in do
              let ctx_a : Ctx = Reader.ask {}
              let ctx_b : Ctx = Reader.ask {}
              "$(ctx_a.request_id):$(int::to_str { n = ctx_b.user_id })"
        """
        assertEquals("r-7:42", eval(source))
    }

    @Test
    fun `record carrier accessed via deep call chain`() {
        val source = """
            use std::effects::reader::{Reader, WithReader}
            use std::int

            type Ctx = { request_id: Str, user_id: Int }

            let format_log : { msg: Str } -> Str <Reader> = { msg } -> do
              let ctx : Ctx = Reader.ask {}
              "[$(ctx.request_id) user=$(int::to_str { n = ctx.user_id })] $(msg)"

            let process : {} -> Str <Reader> = {} -> format_log { msg = "ok" }

            perform WithReader { val = { request_id = "r-99", user_id = 7 } } in do
              process {}
        """
        assertEquals("[r-99 user=7] ok", eval(source))
    }

    // ------------------------------------------------------------------
    // Per-call-site isolation — two performs at different sites
    // ------------------------------------------------------------------

    @Test
    fun `two call sites with different bindings don't interfere`() {
        val source = """
            use std::effects::reader::{Reader, WithReader}

            let greet : {} -> Str <Reader> = {} -> "hi $(Reader.ask {})"

            do
              let first  = perform WithReader { val = "alice" } in greet {}
              let second = perform WithReader { val = "bob" }   in greet {}
              "$(first):$(second)"
        """
        assertEquals("hi alice:hi bob", eval(source))
    }

    // ------------------------------------------------------------------
    // Spawn-inheritance suite
    // ------------------------------------------------------------------

    @Test
    fun `spawn inherits parent's binding`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            perform SystemTime in perform LoomAsync in perform WithReader { val = "req-7" } in do
              let p = Async.spawn { f = {} -> Reader.ask {} }
              result::get_or_else { result = Async.await { p = p }, def = "missing" }
        """
        assertEquals("req-7", eval(source))
    }

    @Test
    fun `spawn from inner block captures inner value`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            perform SystemTime in perform LoomAsync in perform WithReader { val = "outer" } in do
              let p_outer = Async.spawn { f = {} -> Reader.ask {} }
              let p_inner = perform WithReader { val = "inner" } in do
                Async.spawn { f = {} -> Reader.ask {} }
              let outer_res = result::get_or_else { result = Async.await { p = p_outer }, def = "missing" }
              let inner_res = result::get_or_else { result = Async.await { p = p_inner }, def = "missing" }
              "$(outer_res):$(inner_res)"
        """
        assertEquals("outer:inner", eval(source))
    }

    @Test
    fun `spawn outlives bind via snapshot capture`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            perform SystemTime in perform LoomAsync in do
              let p = perform WithReader { val = "captured" } in do
                Async.spawn { f = {} -> do
                  Async.sleep { ms = 20 }
                  Reader.ask {}
                }
              result::get_or_else { result = Async.await { p = p }, def = "missing" }
        """
        assertEquals("captured", eval(source))
    }

    @Test
    fun `parent still reads binding after spawning a child`() {
        // The spawn doesn't disturb the parent's view of its own binding.
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            perform SystemTime in perform LoomAsync in perform WithReader { val = "shared" } in do
              let p = Async.spawn { f = {} -> Reader.ask {} }
              let parent_view_before_await = Reader.ask {}
              let child_view = result::get_or_else { result = Async.await { p = p }, def = "missing" }
              let parent_view_after_await = Reader.ask {}
              "$(parent_view_before_await):$(child_view):$(parent_view_after_await)"
        """
        assertEquals("shared:shared:shared", eval(source))
    }

    @Test
    fun `many concurrent spawns all inherit same binding`() {
        // Stress the snapshot inheritance: N children, all reading,
        // all should see the same value.
        val source = """
            use std::effects::async::{Async, LoomAsync, all}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result
            use std::collections::seq

            perform SystemTime in perform LoomAsync in perform WithReader { val = "shared" } in do
              let promises = seq::map {
                xs = [1, 2, 3, 4, 5],
                f = { x } -> Async.spawn { f = {} -> Reader.ask {} },
              }
              let results = all { ps = promises }
              let unwrapped = seq::map {
                xs = results,
                f = { x } -> result::get_or_else { result = x, def = "missing" },
              }
              seq::fold {
                xs = unwrapped,
                init = "",
                f = { acc, x } -> "$(acc)$(x),",
              }
        """
        assertEquals("shared,shared,shared,shared,shared,", eval(source))
    }

    // ------------------------------------------------------------------
    // Reader + another effect in the same row
    // ------------------------------------------------------------------

    @Test
    fun `Reader composes with Async row entries`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            let task : {} -> Str <Reader, Async, Time> = {} -> do
              Async.sleep { ms = 5 }
              Reader.ask {}

            perform SystemTime in perform LoomAsync in perform WithReader { val = "mixed" } in do
              let p = Async.spawn { f = task }
              result::get_or_else { result = Async.await { p = p }, def = "missing" }
        """
        assertEquals("mixed", eval(source))
    }

    // ------------------------------------------------------------------
    // Deep nested spawn trees — does the binding cross many spawn hops?
    // ------------------------------------------------------------------

    // Regression test for #284 — the named-function thunk no longer
    // gets auto-evaluated when read in value position (passed as spawn arg).
    @Test
    fun `REPRO 284 — named-fn nested spawn`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::result

            let grandchild : {} -> Str = {} -> "ok"

            let child : {} -> Str <Async> = {} ->
              result::get_or_else {
                result = Async.await { p = Async.spawn { f = grandchild } },
                def = "child-miss",
              }

            perform SystemTime in perform LoomAsync in
              result::get_or_else {
                result = Async.await { p = Async.spawn { f = child } },
                def = "root-miss",
              }
        """
        assertEquals("ok", eval(source))
    }

    // Probe: same shape but with inline lambda instead of named fn.
    // If this PASSES, the bug is specific to named-function references
    // when used as spawn arguments.
    @Test
    fun `PROBE — inline-lambda nested spawn`() {
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::result

            perform SystemTime in perform LoomAsync in
              result::get_or_else {
                result = Async.await { p = Async.spawn { f = {} ->
                  result::get_or_else {
                    result = Async.await { p = Async.spawn { f = {} -> "ok" } },
                    def = "child-miss",
                  }
                } },
                def = "root-miss",
              }
        """
        val result = try {
            eval(source).toString()
        } catch (e: Throwable) {
            "THREW: ${e.message?.take(400)}"
        }
        println("PROBE inline-lambda nested spawn: $result")
    }

    @Test
    fun `value crosses three nested spawn hops`() {
        // Parent → child → grandchild → great-grandchild, each on its own
        // virtual thread. Reader.ask {} at the leaf must still see the
        // value bound at the root, never having been threaded explicitly.
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            let great_grandchild : {} -> Str <Reader, Async, Time> = {} -> do
              Async.sleep { ms = 2 }
              let here = Reader.ask {}
              here

            let grandchild : {} -> Str <Reader, Async, Time> = {} -> do
              Async.sleep { ms = 2 }
              let p = Async.spawn { f = great_grandchild }
              result::get_or_else { result = Async.await { p = p }, def = "missing" }

            let child : {} -> Str <Reader, Async, Time> = {} -> do
              Async.sleep { ms = 2 }
              let p = Async.spawn { f = grandchild }
              result::get_or_else { result = Async.await { p = p }, def = "missing" }

            perform SystemTime in perform LoomAsync in perform WithReader { val = "deep-root" } in do
              let p = Async.spawn { f = child }
              result::get_or_else { result = Async.await { p = p }, def = "missing" }
        """
        assertEquals("deep-root", eval(source))
    }

    @Test
    fun `value crosses five nested spawn hops with inner shadowing`() {
        // Layers 1, 3, 5 read.
        // Layer 2 shadows with a different binding.
        // Layer 3 reads the SHADOWED value (because it ran under layer 2's binding).
        // Layer 4 shadows again with yet another binding.
        // Layer 5 reads the layer-4 binding.
        // Confirms each spawn snapshots the chain it was spawned under.
        val source = """
            use std::effects::async::{Async, LoomAsync}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result

            # layer5 only reads; no Async in body. Row is just <Reader>.
            let layer5 : {} -> Str <Reader> = {} -> Reader.ask {}

            let layer4 : {} -> Str <Async> = {} -> do
              # Shadow with "L4"; spawn layer5 under that.
              perform WithReader { val = "L4" } in do
                let p = Async.spawn { f = layer5 }
                result::get_or_else { result = Async.await { p = p }, def = "missing" }

            let layer3 : {} -> Str <Reader, Async> = {} -> do
              # Read here under whatever was inherited from layer2.
              let here = Reader.ask {}
              let p = Async.spawn { f = layer4 }
              let leaf = result::get_or_else { result = Async.await { p = p }, def = "missing" }
              "$(here)/$(leaf)"

            let layer2 : {} -> Str <Async> = {} -> do
              # Shadow with "L2"; spawn layer3 under that.
              perform WithReader { val = "L2" } in do
                let p = Async.spawn { f = layer3 }
                result::get_or_else { result = Async.await { p = p }, def = "missing" }

            let layer1 : {} -> Str <Reader, Async> = {} -> do
              let here = Reader.ask {}
              let p = Async.spawn { f = layer2 }
              let sub = result::get_or_else { result = Async.await { p = p }, def = "missing" }
              "$(here)/$(sub)"

            perform SystemTime in perform LoomAsync in perform WithReader { val = "ROOT" } in do
              let p = Async.spawn { f = layer1 }
              result::get_or_else { result = Async.await { p = p }, def = "missing" }
        """
        // L1 reads ROOT.
        // L2 shadows with L2.
        // L3 reads L2 (inherited), spawns L4.
        // L4 shadows with L4.
        // L5 reads L4.
        // → "ROOT/L2/L4"
        assertEquals("ROOT/L2/L4", eval(source))
    }

    @Test
    fun `fanout — one root binding, 10 grandchildren via 5 children`() {
        // Each child spawns 2 grandchildren; every leaf reads.
        // 10 leaves, all should see the root binding. Tests breadth.
        val source = """
            use std::effects::async::{Async, LoomAsync, all}
            use std::effects::time::{Time, SystemTime}
            use std::effects::reader::{Reader, WithReader}
            use std::result
            use std::collections::seq

            # leaf only reads; no Async. The spawn-site adds Async to the row.
            let leaf : {} -> Str <Reader> = {} -> Reader.ask {}

            let child : {} -> Str <Reader, Async> = {} -> do
              let p1 = Async.spawn { f = leaf }
              let p2 = Async.spawn { f = leaf }
              let r1 = result::get_or_else { result = Async.await { p = p1 }, def = "miss" }
              let r2 = result::get_or_else { result = Async.await { p = p2 }, def = "miss" }
              "$(r1)+$(r2)"

            perform SystemTime in perform LoomAsync in perform WithReader { val = "root" } in do
              let promises = seq::map {
                xs = [1, 2, 3, 4, 5],
                f = { x } -> Async.spawn { f = child },
              }
              let results = all { ps = promises }
              let unwrapped = seq::map {
                xs = results,
                f = { x } -> result::get_or_else { result = x, def = "miss" },
              }
              seq::fold {
                xs = unwrapped,
                init = "",
                f = { acc, x } -> "$(acc)$(x)|",
              }
        """
        // 5 children each yielding "root+root|" → 5 of them.
        assertEquals("root+root|root+root|root+root|root+root|root+root|", eval(source))
    }
}
