package com.mezze.interpreter.v2;

import com.mezze.language.typechecker.ModuleResolver;
import com.mezze.language.typechecker.TypeChecker;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runtime tests for ability impls and inherent impls on stdlib newtypes.
 * These tests verify that Eq (reference identity) and method syntax
 * actually execute correctly through the Truffle interpreter.
 */
@Execution(ExecutionMode.SAME_THREAD)
class NewtypeImplRuntimeTest {

    @BeforeEach
    void setUp() {
        ModuleResolver.clearCache();
        TypeChecker.clearCache();
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache();
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearTypeCheckingStack();
    }

    // ========================================================================
    // Atom
    // ========================================================================

    @Nested
    class AtomRuntime {

        @Test
        void atomEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a = atom {value = 42}
                a == a
                """);
            assertEquals(true, result);
        }

        @Test
        void atomEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a1 = atom {value = 42}
                let a2 = atom {value = 42}
                a1 == a2
                """);
            assertEquals(false, result);
        }

        @Test
        void atomNeq() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a1 = atom {value = 1}
                let a2 = atom {value = 1}
                a1 != a2
                """);
            assertEquals(true, result);
        }

        @Test
        void atomDerefMethod() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a = atom {value = 99}
                a.deref {}
                """);
            assertEquals(99L, result);
        }

        @Test
        void atomResetMethod() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a = atom {value = 10}
                let _ = a.reset {value = 20}
                a.deref {}
                """);
            assertEquals(20L, result);
        }

        @Test
        void atomSwapMethod() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a = atom {value = 5}
                let _ = a.swap {f = {current} -> current + 10}
                a.deref {}
                """);
            assertEquals(15L, result);
        }
    }

    // ========================================================================
    // WaitGroup
    // ========================================================================

    @Nested
    class WaitGroupRuntime {

        @Test
        void waitGroupEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::waitgroup::{WaitGroup, wait_group}
                let wg = wait_group {}
                wg == wg
                """);
            assertEquals(true, result);
        }

        @Test
        void waitGroupEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::waitgroup::{WaitGroup, wait_group}
                let wg1 = wait_group {}
                let wg2 = wait_group {}
                wg1 == wg2
                """);
            assertEquals(false, result);
        }

        @Test
        void waitGroupDoneAndWaitMethods() {
            // add 1, done 1, then wait should return immediately
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::waitgroup::{WaitGroup, wait_group, add}
                let wg = wait_group {}
                let _ = add {wg = wg, delta = 1}
                let _ = wg.done {}
                let _ = wg.wait {}
                42
                """);
            assertEquals(42L, result);
        }
    }

    // ========================================================================
    // Latch
    // ========================================================================

    @Nested
    class LatchRuntime {

        @Test
        void latchEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::latch::{Latch, latch}
                let l = latch {count = 1}
                l == l
                """);
            assertEquals(true, result);
        }

        @Test
        void latchEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::latch::{Latch, latch}
                let l1 = latch {count = 1}
                let l2 = latch {count = 1}
                l1 == l2
                """);
            assertEquals(false, result);
        }

        @Test
        void latchCountDownAndAwaitMethods() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::latch::{Latch, latch}
                let l = latch {count = 1}
                let _ = l.count_down {}
                let _ = l.await {}
                100
                """);
            assertEquals(100L, result);
        }

        @Test
        void latchGetCountMethod() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::latch::{Latch, latch}
                let l = latch {count = 3}
                let _ = l.count_down {}
                l.get_count {}
                """);
            assertEquals(2L, result);
        }
    }

    // ========================================================================
    // Semaphore
    // ========================================================================

    @Nested
    class SemaphoreRuntime {

        @Test
        void semaphoreEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::semaphore::{Semaphore, semaphore}
                let s = semaphore {permits = 3}
                s == s
                """);
            assertEquals(true, result);
        }

        @Test
        void semaphoreEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::semaphore::{Semaphore, semaphore}
                let s1 = semaphore {permits = 3}
                let s2 = semaphore {permits = 3}
                s1 == s2
                """);
            assertEquals(false, result);
        }

        @Test
        void semaphoreAcquireAndReleaseMethods() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::semaphore::{Semaphore, semaphore}
                let s = semaphore {permits = 2}
                let _ = s.acquire {}
                let _ = s.release {}
                77
                """);
            assertEquals(77L, result);
        }
    }

    // ========================================================================
    // Mutex
    // ========================================================================

    @Nested
    class MutexRuntime {

        @Test
        void mutexEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::mutex::{Mutex, mutex}
                let m = mutex {}
                m == m
                """);
            assertEquals(true, result);
        }

        @Test
        void mutexEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::mutex::{Mutex, mutex}
                let m1 = mutex {}
                let m2 = mutex {}
                m1 == m2
                """);
            assertEquals(false, result);
        }

        @Test
        void mutexLockAndUnlockMethods() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::mutex::{Mutex, mutex}
                let m = mutex {}
                let _ = m.lock {}
                let _ = m.unlock {}
                55
                """);
            assertEquals(55L, result);
        }
    }

    // ========================================================================
    // CancellationToken
    // ========================================================================

    @Nested
    class CancellationTokenRuntime {

        @Test
        void cancellationTokenEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::cancellation::{CancellationToken, token}
                let t = token {}
                t == t
                """);
            assertEquals(true, result);
        }

        @Test
        void cancellationTokenEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::cancellation::{CancellationToken, token}
                let t1 = token {}
                let t2 = token {}
                t1 == t2
                """);
            assertEquals(false, result);
        }

        @Test
        void cancellationTokenCancelAndIsCancelledMethods() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::cancellation::{CancellationToken, token}
                let t = token {}
                let _ = t.cancel {reason = "done"}
                t.is_cancelled {}
                """);
            assertEquals(true, result);
        }

        @Test
        void cancellationTokenNotCancelledInitially() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::cancellation::{CancellationToken, token}
                let t = token {}
                t.is_cancelled {}
                """);
            assertEquals(false, result);
        }
    }

    // ========================================================================
    // TVar
    // ========================================================================

    @Nested
    class TVarRuntime {

        @Test
        void tvarEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::stm::{TVar, tvar}
                let v = tvar {init = 0}
                v == v
                """);
            assertEquals(true, result);
        }

        @Test
        void tvarEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::stm::{TVar, tvar}
                let v1 = tvar {init = 0}
                let v2 = tvar {init = 0}
                v1 == v2
                """);
            assertEquals(false, result);
        }

        @Test
        void tvarDerefMethod() {
            Object result = MezzeTestHelper.run("""
                use std::effects::stm::{TVar, tvar}
                let v = tvar {init = 42}
                v.deref {}
                """);
            assertEquals(42L, result);
        }
    }

    // ========================================================================
    // Chan
    // ========================================================================

    @Nested
    class ChanRuntime {

        @Test
        void chanEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::channel::{Chan, chan}
                let ch: Chan Int = chan {}
                ch == ch
                """);
            assertEquals(true, result);
        }

        @Test
        void chanEqDifferentReferences() {
            Object result = MezzeTestHelper.run("""
                use std::effects::concurrent::channel::{Chan, chan}
                let ch1: Chan Int = chan {}
                let ch2: Chan Int = chan {}
                ch1 == ch2
                """);
            assertEquals(false, result);
        }
    }

    // ========================================================================
    // Promise (requires LoomAsync perform block)
    // ========================================================================

    @Nested
    class PromiseRuntime {

        @Test
        void promiseEqSameReference() {
            Object result = MezzeTestHelper.run("""
                use std::effects::async::{Async, LoomAsync, Promise}
                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 42}
                    let same = p == p
                    let _ = Async.await {p = p}
                    same
                main {}
                """);
            assertEquals(true, result);
        }

        @Test
        void promiseAwaitMethod() {
            Object result = MezzeTestHelper.run("""
                use std::effects::async::{Async, LoomAsync, Promise}
                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 42}
                    p.await {}
                main {}
                """);
            assertEquals(42L, result);
        }

        @Test
        void promiseIsDoneMethod() {
            Object result = MezzeTestHelper.run("""
                use std::effects::async::{Async, LoomAsync, Promise}
                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 99}
                    let _ = Async.await {p = p}
                    p.is_done {}
                main {}
                """);
            assertEquals(true, result);
        }
    }
}
