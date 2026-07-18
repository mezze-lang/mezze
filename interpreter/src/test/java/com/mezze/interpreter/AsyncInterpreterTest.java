package com.mezze.interpreter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Async effect implementations in V2 interpreter.
 *
 * Uses the user-facing API from std/effects/async.mz:
 * - Async effect with spawn, await, join, map, etc.
 * - CoopAsync for deterministic cooperative async
 * - LoomAsync for virtual thread async
 * - OSThreadAsync for OS thread async
 */
public class AsyncInterpreterTest {
    // ==================== Coop (Cooperative) Tests ====================
    // Uses CoopAsync for deterministic, single-threaded execution

    @Test
    void testCoopSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                result::get_or_else { result = Async.await {p = p}, def = -1 }
        """);
        assertEquals(42L, result);
    }

    @Test
    void testCoopSpawnMultipleTasks() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform CoopAsync in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let r1 = result::get_or_else { result = Async.await {p = p1}, def = -1 }
                let r2 = result::get_or_else { result = Async.await {p = p2}, def = -1 }
                r1 + r2
        """);
        assertEquals(30L, result);
    }

    @Test
    void testCoopJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform CoopAsync in do
                let r = join {
                    l = {} -> 10,
                    r = {} -> 20
                }
                result::get_or_else { result = r.l, def = 0 } + result::get_or_else { result = r.r, def = 0 }
        """);
        assertEquals(30L, result);
    }

    @Test
    void testCoopJoin3ThreeComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform CoopAsync in do
                let r = join3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                result::get_or_else { result = r.a, def = 0 } + result::get_or_else { result = r.b, def = 0 } + result::get_or_else { result = r.c, def = 0 }
        """);
        assertEquals(6L, result);
    }

    @Test
    void testCoopSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, is_done }

            perform CoopAsync in do
                Async.sleep {ms = 1}
                42
        """);
        assertEquals(42L, result);
    }

    @Test
    void testCoopIsDone() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform CoopAsync in do
                let p = Async.spawn {f = {} -> 42}
                let _ = result::get_or_else { result = Async.await {p = p}, def = -1 }
                is_done {p = p}
        """);
        assertEquals(true, result);
    }

    // ==================== Loom (Virtual Thread) Tests ====================
    // Uses LoomAsync for Project Loom virtual thread execution

    @Test
    void testLoomSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform LoomAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                result::get_or_else { result = Async.await {p = p}, def = -1 }
        """);
        assertEquals(42L, result);
    }

    @Test
    void testLoomJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform LoomAsync {} in do
                let r = join {
                    l = {} -> 10,
                    r = {} -> 20
                }
                result::get_or_else { result = r.l, def = 0 } + result::get_or_else { result = r.r, def = 0 }
        """);
        assertEquals(30L, result);
    }

    @Test
    void testLoomSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, LoomAsync, race, with_timeout, join, join3, is_done }
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in perform LoomAsync {} in do
                Async.sleep {ms = 10}
                42
        """);
        assertEquals(42L, result);
    }

    // ==================== OS Thread Tests ====================
    // Uses OSThreadAsync for platform thread execution

    @Test
    void testOsSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, OSThreadAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform OSThreadAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                result::get_or_else { result = Async.await {p = p}, def = -1 }
        """);
        assertEquals(42L, result);
    }

    @Test
    void testOsJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, OSThreadAsync, race, with_timeout, join, join3, is_done }
            use std::result

            perform OSThreadAsync {} in do
                let r = join {
                    l = {} -> 10,
                    r = {} -> 20
                }
                result::get_or_else { result = r.l, def = 0 } + result::get_or_else { result = r.r, def = 0 }
        """);
        assertEquals(30L, result);
    }

    @Test
    void testOsSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, OSThreadAsync, race, with_timeout, join, join3, is_done }
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in perform OSThreadAsync {} in do
                Async.sleep {ms = 10}
                42
        """);
        assertEquals(42L, result);
    }
}
