package com.mezze.interpreter.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
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

    @BeforeEach
    void setup() {
        // Clear caches before each test
        com.mezze.language.typechecker.ModuleResolver.clearCache();
    }

    // ==================== Coop (Cooperative) Tests ====================
    // Uses CoopAsync for deterministic, single-threaded execution

    @Test
    void testCoopSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """);
        assertEquals(42L, result);
    }

    @Test
    void testCoopSpawnMultipleTasks() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let p1 = Async.spawn {f = {} -> 10}
                let p2 = Async.spawn {f = {} -> 20}
                let r1 = Async.await {p = p1}
                let r2 = Async.await {p = p2}
                r1 + r2
        """);
        assertEquals(30L, result);
    }

    @Test
    void testCoopJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let r = Async.join {
                    left = {} -> 10,
                    right = {} -> 20
                }
                r.left + r.right
        """);
        assertEquals(30L, result);
    }

    @Test
    void testCoopJoin3ThreeComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let r = Async.join3 {
                    a = {} -> 1,
                    b = {} -> 2,
                    c = {} -> 3
                }
                r.a + r.b + r.c
        """);
        assertEquals(6L, result);
    }

    @Test
    void testCoopSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                Async.sleep {ms = 1}
                42
        """);
        assertEquals(42L, result);
    }

    @Test
    void testCoopIsDone() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, CoopAsync}
            use std::control::continuation

            perform CoopAsync {cont = continuation::new {}} in do
                let p = Async.spawn {f = {} -> 42}
                let _ = Async.await {p = p}
                Async.is_done {p = p}
        """);
        assertEquals(true, result);
    }

    // ==================== Loom (Virtual Thread) Tests ====================
    // Uses LoomAsync for Project Loom virtual thread execution

    @Disabled("Needs TruffleContext multi-thread support")
    @Test
    void testLoomSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """);
        assertEquals(42L, result);
    }

    @Disabled("Needs TruffleContext multi-thread support")
    @Test
    void testLoomJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync {} in do
                let r = Async.join {
                    left = {} -> 10,
                    right = {} -> 20
                }
                r.left + r.right
        """);
        assertEquals(30L, result);
    }

    @Test
    void testLoomSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, LoomAsync}

            perform LoomAsync {} in do
                Async.sleep {ms = 10}
                42
        """);
        assertEquals(42L, result);
    }

    // ==================== OS Thread Tests ====================
    // Uses OSThreadAsync for platform thread execution

    @Disabled("Needs TruffleContext multi-thread support")
    @Test
    void testOsSpawnAndAwait() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, OSThreadAsync}

            perform OSThreadAsync {} in do
                let p = Async.spawn {f = {} -> 42}
                Async.await {p = p}
        """);
        assertEquals(42L, result);
    }

    @Disabled("Needs TruffleContext multi-thread support")
    @Test
    void testOsJoinTwoComputations() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, OSThreadAsync}

            perform OSThreadAsync {} in do
                let r = Async.join {
                    left = {} -> 10,
                    right = {} -> 20
                }
                r.left + r.right
        """);
        assertEquals(30L, result);
    }

    @Test
    void testOsSleep() {
        Object result = MezzeTestHelper.run("""
            use std::effects::async::{Async, OSThreadAsync}

            perform OSThreadAsync {} in do
                Async.sleep {ms = 10}
                42
        """);
        assertEquals(42L, result);
    }
}
