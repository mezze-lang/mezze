package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested

/**
 * Tests for ability impls and inherent impls on newtypes in the stdlib.
 * Verifies Eq (reference identity) and method syntax for all concurrency/STM types.
 */
class NewtypeImplTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        com.mezze.language.typechecker.module.ModuleTypeChecker.clearCache()
    }

    private fun checkPasses(source: String, msg: String) {
        val result = typeChecker.checkProgram(parser.parse(source))
        assertTrue(result is TypeChecker.TypeCheckResult.Success, "$msg: $result")
    }

    // ========================================================================
    // Promise
    // ========================================================================

    @Nested
    inner class PromiseImpls {

        @Test
        fun `Promise has Eq`() {
            checkPasses("""
                use std::effects::async::{Async, LoomAsync, Promise}
                let main = {} -> perform LoomAsync in do
                    let p1 = Async.spawn {f = {} -> 42}
                    let p2 = p1
                    let same = p1 == p2
                    Async.await {p = p1}
            """.trimIndent(), "Promise Eq")
        }

        @Test
        fun `Promise await method`() {
            checkPasses("""
                use std::effects::async::{Async, LoomAsync}
                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 42}
                    p.await {}
            """.trimIndent(), "Promise .await")
        }

        @Test
        fun `Promise is_done method`() {
            checkPasses("""
                use std::effects::async::{Async, LoomAsync}
                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 42}
                    let done = p.is_done {}
                    Async.await {p = p}
            """.trimIndent(), "Promise .is_done")
        }

        @Test
        fun `Promise cancel method`() {
            checkPasses("""
                use std::effects::async::{Async, LoomAsync}
                let main = {} -> perform LoomAsync in do
                    let p = Async.spawn {f = {} -> 42}
                    p.cancel {}
                    42
            """.trimIndent(), "Promise .cancel")
        }
    }

    // ========================================================================
    // Chan
    // ========================================================================

    @Nested
    inner class ChannelImpls {

        @Test
        fun `Chan has Eq`() {
            checkPasses("""
                use std::effects::concurrent::channel::{Chan, chan}
                let ch1: Chan Int = chan {}
                let ch2 = ch1
                let same = ch1 == ch2
            """.trimIndent(), "Chan Eq")
        }

        @Test
        fun `Chan send and recv via effect`() {
            checkPasses("""
                use std::effects::concurrent::channel::{Chan, Channel, BlockingChannel, chan}
                let main = {} -> perform BlockingChannel in do
                    let ch: Chan Int = chan {}
                    Channel.send {ch = ch, value = 42}
                    Channel.recv {ch = ch}
            """.trimIndent(), "Chan send/recv via effect")
        }

        @Test
        fun `Chan close and is_closed via effect`() {
            checkPasses("""
                use std::effects::concurrent::channel::{Chan, Channel, BlockingChannel, chan}
                let main = {} -> perform BlockingChannel in do
                    let ch: Chan Int = chan {}
                    let closed = Channel.is_closed {ch = ch}
                    Channel.close {ch = ch}
            """.trimIndent(), "Chan close/is_closed via effect")
        }
    }

    // ========================================================================
    // WaitGroup
    // ========================================================================

    @Nested
    inner class WaitGroupImpls {

        @Test
        fun `WaitGroup has Eq`() {
            checkPasses("""
                use std::effects::concurrent::waitgroup::{WaitGroup, wait_group}
                let wg1 = wait_group {}
                let same = wg1 == wg1
            """.trimIndent(), "WaitGroup Eq")
        }

        @Test
        fun `WaitGroup done and wait methods`() {
            checkPasses("""
                use std::effects::concurrent::waitgroup::{WaitGroup, wait_group}
                let wg = wait_group {}
                let _ = wg.done {}
                let _ = wg.wait {}
            """.trimIndent(), "WaitGroup .done/.wait")
        }

        @Test
        fun `WaitGroup add via free function`() {
            checkPasses("""
                use std::effects::concurrent::waitgroup::{WaitGroup, wait_group, add}
                let wg = wait_group {}
                let _ = add {wg = wg, delta = 1}
                let _ = wg.wait {}
            """.trimIndent(), "WaitGroup add + .wait")
        }
    }

    // ========================================================================
    // Latch
    // ========================================================================

    @Nested
    inner class LatchImpls {

        @Test
        fun `Latch has Eq`() {
            checkPasses("""
                use std::effects::concurrent::latch::{Latch, latch}
                let l = latch {count = 1}
                let same = l == l
            """.trimIndent(), "Latch Eq")
        }

        @Test
        fun `Latch count_down and await methods`() {
            checkPasses("""
                use std::effects::concurrent::latch::{Latch, latch}
                let l = latch {count = 1}
                let _ = l.count_down {}
                let _ = l.await {}
            """.trimIndent(), "Latch .count_down/.await")
        }
    }

    // ========================================================================
    // Semaphore
    // ========================================================================

    @Nested
    inner class SemaphoreImpls {

        @Test
        fun `Semaphore has Eq`() {
            checkPasses("""
                use std::effects::concurrent::semaphore::{Semaphore, semaphore}
                let s = semaphore {permits = 3}
                let same = s == s
            """.trimIndent(), "Semaphore Eq")
        }

        @Test
        fun `Semaphore acquire and release methods`() {
            checkPasses("""
                use std::effects::concurrent::semaphore::{Semaphore, semaphore}
                let s = semaphore {permits = 3}
                let _ = s.acquire {}
                let _ = s.release {}
            """.trimIndent(), "Semaphore .acquire/.release")
        }
    }

    // ========================================================================
    // Mutex
    // ========================================================================

    @Nested
    inner class MutexImpls {

        @Test
        fun `Mutex has Eq`() {
            checkPasses("""
                use std::effects::concurrent::mutex::{Mutex, mutex}
                let m = mutex {}
                let same = m == m
            """.trimIndent(), "Mutex Eq")
        }

        @Test
        fun `Mutex lock and unlock methods`() {
            checkPasses("""
                use std::effects::concurrent::mutex::{Mutex, mutex}
                let m = mutex {}
                let _ = m.lock {}
                let _ = m.unlock {}
            """.trimIndent(), "Mutex .lock/.unlock")
        }
    }

    // ========================================================================
    // Atom
    // ========================================================================

    @Nested
    inner class AtomImpls {

        @Test
        fun `Atom has Eq`() {
            checkPasses("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a: Atom Int = atom {value = 42}
                let same = a == a
            """.trimIndent(), "Atom Eq")
        }

        @Test
        fun `Atom deref and reset methods`() {
            checkPasses("""
                use std::effects::concurrent::atom::{Atom, atom}
                let a = atom {value = 42}
                let v = a.deref {}
                let _ = a.reset {value = 100}
            """.trimIndent(), "Atom .deref/.reset")
        }
    }

    // ========================================================================
    // STM — TVar
    // ========================================================================

    @Nested
    inner class TVarImpls {

        @Test
        fun `TVar has Eq`() {
            checkPasses("""
                use std::effects::stm::{TVar, tvar}
                let v: TVar Int = tvar {init = 0}
                let same = v == v
            """.trimIndent(), "TVar Eq")
        }

        @Test
        fun `TVar read and write via STM effect`() {
            checkPasses("""
                use std::effects::stm::{STM, TVar, tvar, SequentialSTM}
                let main = {} -> perform SequentialSTM in do
                    let v = tvar {init = 0}
                    let val = STM.read {var = v}
                    STM.write {var = v, val = val + 1}
            """.trimIndent(), "TVar read/write via STM")
        }

        @Test
        fun `TVar deref method (non-transactional)`() {
            checkPasses("""
                use std::effects::stm::{TVar, tvar}
                let v = tvar {init = 42}
                let val = v.deref {}
            """.trimIndent(), "TVar .deref")
        }
    }

    // ========================================================================
    // CancellationToken
    // ========================================================================

    @Nested
    inner class CancellationTokenImpls {

        @Test
        fun `CancellationToken has Eq`() {
            checkPasses("""
                use std::effects::concurrent::cancellation::{CancellationToken, token}
                let t = token {}
                let same = t == t
            """.trimIndent(), "CancellationToken Eq")
        }

        @Test
        fun `CancellationToken cancel and is_cancelled methods`() {
            checkPasses("""
                use std::effects::concurrent::cancellation::{CancellationToken, token}
                let t = token {}
                let _ = t.cancel {reason = "done"}
                let cancelled = t.is_cancelled {}
            """.trimIndent(), "CancellationToken .cancel/.is_cancelled")
        }
    }

    // ========================================================================
    // User-defined newtype with Eq via not
    // ========================================================================

    @Nested
    inner class UserNewtypeEq {

        @Test
        fun `newtype Eq with neq via not`() {
            checkPasses("""
                use std::abilities::compare::{Eq}

                newtype UserId = UserId Int

                impl Eq for UserId where {
                    eq = {it, r} -> match it is
                        UserId {val = a} -> match r is
                            UserId {val = b} -> a == b,
                    neq = {it, r} -> it.eq {r = r}.not {}
                }

                let u1 = UserId {value = 42}
                let u2 = UserId {value = 42}
                let u3 = UserId {value = 99}
                let same = u1 == u2
                let diff = u1 != u3
            """.trimIndent(), "UserId Eq with neq via not")
        }
    }
}
