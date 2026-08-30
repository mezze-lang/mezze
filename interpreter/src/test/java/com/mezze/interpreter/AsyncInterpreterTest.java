package com.mezze.interpreter;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Async effect implementations in V2 interpreter.
 *
 * Uses the user-facing API from std/effects/async.mz:
 * - Async effect with spawn, await, join, map, etc.
 * - LoomAsync for deterministic cooperative async
 * - LoomAsync for virtual thread async
 * - OSThreadAsync for OS thread async
 */
public class AsyncInterpreterTest {
    // ==================== Loom (Virtual Thread) Tests ====================

    @Test
    void testLoomSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{ Async, LoomAsync, await, is_done, join, join3, race, sleep, with_timeout }
            use std::result

            perform LoomAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                result::get_or { result = await {p = p}, def = -1 }
        """);
        assertEquals(42L, result);
    }

    @Test
    void testLoomJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{ Async, LoomAsync, await, is_done, join, join3, race, sleep, with_timeout }
            use std::result

            perform LoomAsync {} in do
                let r = join {
                    l = {} -> 10,
                    r = {} -> 20
                }
                result::get_or { result = r.l, def = 0 } + result::get_or { result = r.r, def = 0 }
        """);
        assertEquals(30L, result);
    }

    @Test
    void testLoomSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{ Async, LoomAsync, await, is_done, join, join3, race, sleep, with_timeout }
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in perform LoomAsync {} in do
                sleep {ms = 10}
                42
        """);
        assertEquals(42L, result);
    }

    // ==================== OS Thread Tests ====================
    // Uses OSThreadAsync for platform thread execution

    @Test
    void testOsSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{ Async, OSThreadAsync, await, is_done, join, join3, race, sleep, with_timeout }
            use std::result

            perform OSThreadAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                result::get_or { result = await {p = p}, def = -1 }
        """);
        assertEquals(42L, result);
    }

    @Test
    void testOsJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{ Async, OSThreadAsync, await, is_done, join, join3, race, sleep, with_timeout }
            use std::result

            perform OSThreadAsync {} in do
                let r = join {
                    l = {} -> 10,
                    r = {} -> 20
                }
                result::get_or { result = r.l, def = 0 } + result::get_or { result = r.r, def = 0 }
        """);
        assertEquals(30L, result);
    }

    @Test
    void testOsSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{ Async, OSThreadAsync, await, is_done, join, join3, race, sleep, with_timeout }
            use std::effects::time::{Time, SystemTime}

            perform SystemTime in perform OSThreadAsync {} in do
                sleep {ms = 10}
                42
        """);
        assertEquals(42L, result);
    }
}
