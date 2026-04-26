package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for Channel implementations used by the Channel effect.
 * Migrated from V1 to V2 with unboxed values.
 *
 * Chan is the value type (like TVar for STM)
 * Channel is the effect (like STM)
 *
 * Channels provide typed communication between virtual threads using
 * Java's LinkedBlockingQueue for thread-safe blocking operations.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ChannelTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    // =============================================================================
    // Channel Creation Tests
    // =============================================================================

    @Test
    fun `test create unbounded channel`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                # Channel was created successfully if we can try_send
                Channel.try_send { ch = ch, value = 42 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test create buffered channel`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan_bounded, Channel, MockChannel}

            let ch = chan_bounded { cap = 10 }
            perform MockChannel in do
                # Channel was created successfully if we can try_send
                Channel.try_send { ch = ch, value = 42 }
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // Send/Receive Tests
    // =============================================================================

    @Test
    fun `test send and receive single value`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = 42 }
                Channel.recv { ch = ch }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test send and receive multiple values preserves order`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = 1 }
                Channel.send { ch = ch, value = 2 }
                Channel.send { ch = ch, value = 3 }
                Channel.send { ch = ch, value = 4 }
                Channel.send { ch = ch, value = 5 }

                let a = Channel.recv { ch = ch }
                let b = Channel.recv { ch = ch }
                let c = Channel.recv { ch = ch }
                let d = Channel.recv { ch = ch }
                let e = Channel.recv { ch = ch }

                # Encode values to verify order
                a * 10000 + b * 1000 + c * 100 + d * 10 + e
        """)
        assertEquals(12345L, result)
    }

    @Test
    fun `test send to closed channel fails`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.close { ch = ch }
                # try_send should return false on closed channel
                Channel.try_send { ch = ch, value = 42 }
        """)
        assertEquals(false, result)
    }

    // =============================================================================
    // Non-blocking Operations Tests
    // =============================================================================

    @Test
    fun `test try_send succeeds when channel has space`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.try_send { ch = ch, value = 42 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test try_send fails on closed channel`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.close { ch = ch }
                Channel.try_send { ch = ch, value = 42 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test try_recv returns Some when value available`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = 42 }
                match Channel.try_recv { ch = ch } is
                    Some { val } -> val
                    None -> -1
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test try_recv returns None when channel empty`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                match Channel.try_recv { ch = ch } is
                    Some { val } -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // Channel Close Tests
    // =============================================================================

    @Test
    fun `test close channel marks it closed`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                let before = Channel.is_closed { ch = ch }
                Channel.close { ch = ch }
                let after = Channel.is_closed { ch = ch }
                { before = before, after = after }
        """)
        val record = result as MezzeRecord
        assertEquals(false, record.getField("before"))
        assertEquals(true, record.getField("after"))
    }

    @Test
    fun `test is_closed returns correct value`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                let before = Channel.is_closed { ch = ch }
                Channel.close { ch = ch }
                let after = Channel.is_closed { ch = ch }
                { before = before, after = after }
        """)
        val record = result as MezzeRecord
        assertEquals(false, record.getField("before"))
        assertEquals(true, record.getField("after"))
    }

    @Test
    fun `test close is idempotent`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.close { ch = ch }
                Channel.close { ch = ch }
                Channel.close { ch = ch }
                Channel.is_closed { ch = ch }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test can receive remaining values from closed channel`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = 1 }
                Channel.send { ch = ch, value = 2 }
                Channel.close { ch = ch }

                # Should still receive queued values
                let a = Channel.recv { ch = ch }
                let b = Channel.recv { ch = ch }
                a * 10 + b
        """)
        assertEquals(12L, result)
    }

    // =============================================================================
    // Select Operations (recv_any) Tests
    // =============================================================================

    @Test
    fun `test recv_any returns first available value`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch1 = chan {}
            let ch2 = chan {}
            perform MockChannel in do
                # Send to second channel only
                Channel.send { ch = ch2, value = 42 }

                match Channel.recv_any_timeout { channels = [ch1, ch2], ms = 1000 } is
                    Some { val } -> val.index == 1 && val.value == 42
                    None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test recv_any with multiple channels having data`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch1 = chan {}
            let ch2 = chan {}
            perform MockChannel in do
                # Send to both channels
                Channel.send { ch = ch1, value = 1 }
                Channel.send { ch = ch2, value = 2 }

                # Should return first channel's value (index 0)
                match Channel.recv_any_timeout { channels = [ch1, ch2], ms = 1000 } is
                    Some { val } -> val.index == 0 && val.value == 1
                    None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test recv_any_timeout returns None on timeout`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch1 = chan {}
            let ch2 = chan {}
            perform MockChannel in do
                # Both channels empty, should timeout
                match Channel.recv_any_timeout { channels = [ch1, ch2], ms = 50 } is
                    Some { val } -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test recv_any_timeout returns Some when data available`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch1 = chan {}
            let ch2 = chan {}
            perform MockChannel in do
                Channel.send { ch = ch2, value = 99 }

                match Channel.recv_any_timeout { channels = [ch1, ch2], ms = 1000 } is
                    Some { val } -> val.index == 1 && val.value == 99
                    None -> False
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // MockChannel Tests
    // =============================================================================

    @Test
    fun `test mock channel send and recv`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = 42 }
                Channel.recv { ch = ch }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test mock recv_any_timeout returns None when empty`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch1 = chan {}
            let ch2 = chan {}
            perform MockChannel in do
                match Channel.recv_any_timeout { channels = [ch1, ch2], ms = 100 } is
                    Some { val } -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // Multi-threaded Tests with BlockingChannel
    // =============================================================================

    @Test
    fun `test producer consumer pattern`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, BlockingChannel}
            use std::effects::async::{Async, LoomAsync}

            let ch = chan {}
            perform LoomAsync in do
                perform BlockingChannel in do
                    # Producer sends 10 values
                    let producer = Async.spawn { f = {} -> do
                        Channel.send { ch = ch, value = 1 }
                        Channel.send { ch = ch, value = 2 }
                        Channel.send { ch = ch, value = 3 }
                        Channel.send { ch = ch, value = 4 }
                        Channel.send { ch = ch, value = 5 }
                        Channel.send { ch = ch, value = 6 }
                        Channel.send { ch = ch, value = 7 }
                        Channel.send { ch = ch, value = 8 }
                        Channel.send { ch = ch, value = 9 }
                        Channel.send { ch = ch, value = 10 }
                        {}
                    }

                    # Consumer receives 10 values
                    let a1 = Channel.recv { ch = ch }
                    let a2 = Channel.recv { ch = ch }
                    let a3 = Channel.recv { ch = ch }
                    let a4 = Channel.recv { ch = ch }
                    let a5 = Channel.recv { ch = ch }
                    let a6 = Channel.recv { ch = ch }
                    let a7 = Channel.recv { ch = ch }
                    let a8 = Channel.recv { ch = ch }
                    let a9 = Channel.recv { ch = ch }
                    let a10 = Channel.recv { ch = ch }

                    let _ = Async.await { p = producer }

                    # Sum should be 1+2+3+4+5+6+7+8+9+10 = 55
                    a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10
        """)
        assertEquals(55L, result)
    }

    @Test
    fun `test multiple producers single consumer`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, BlockingChannel}
            use std::effects::async::{Async, LoomAsync}

            let ch = chan {}
            perform LoomAsync in do
                perform BlockingChannel in do
                    # Producer 1 sends 1, 2
                    let p1 = Async.spawn { f = {} -> do
                        Channel.send { ch = ch, value = 1 }
                        Channel.send { ch = ch, value = 2 }
                        {}
                    }

                    # Producer 2 sends 10, 20
                    let p2 = Async.spawn { f = {} -> do
                        Channel.send { ch = ch, value = 10 }
                        Channel.send { ch = ch, value = 20 }
                        {}
                    }

                    # Producer 3 sends 100, 200
                    let p3 = Async.spawn { f = {} -> do
                        Channel.send { ch = ch, value = 100 }
                        Channel.send { ch = ch, value = 200 }
                        {}
                    }

                    # Consumer receives all 6 values
                    let v1 = Channel.recv { ch = ch }
                    let v2 = Channel.recv { ch = ch }
                    let v3 = Channel.recv { ch = ch }
                    let v4 = Channel.recv { ch = ch }
                    let v5 = Channel.recv { ch = ch }
                    let v6 = Channel.recv { ch = ch }

                    let _ = Async.await { p = p1 }
                    let _ = Async.await { p = p2 }
                    let _ = Async.await { p = p3 }

                    # Sum should be 1+2+10+20+100+200 = 333
                    v1 + v2 + v3 + v4 + v5 + v6
        """)
        assertEquals(333L, result)
    }

    @Test
    fun `test BlockingChannel send and recv with Loom`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, BlockingChannel}
            use std::effects::async::{Async, LoomAsync}

            let ch = chan {}
            perform LoomAsync in do
                perform BlockingChannel in do
                    # Spawn sender
                    let p = Async.spawn { f = {} -> do
                        Channel.send { ch = ch, value = 42 }
                        {}
                    }

                    # Receive
                    let received = Channel.recv { ch = ch }
                    let _ = Async.await { p = p }
                    received
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test channel blocks receiver when empty`() {
        // In this test, the receiver will block until the sender sends
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, BlockingChannel}
            use std::effects::async::{Async, LoomAsync}

            let ch = chan {}
            perform LoomAsync in do
                perform BlockingChannel in do
                    # Spawn sender that delays then sends
                    let sender = Async.spawn { f = {} -> do
                        Async.sleep { ms = 50 }
                        Channel.send { ch = ch, value = 42 }
                        {}
                    }

                    # Receiver will block until value is available
                    let received = Channel.recv { ch = ch }
                    let _ = Async.await { p = sender }
                    received
        """)
        assertEquals(42L, result)
    }

    // =============================================================================
    // Value Type Tests
    // =============================================================================

    @Test
    fun `test channel with string values`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = "hello" }
                Channel.recv { ch = ch }
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `test channel with record values`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = { x = 10, y = 20 } }
                let point = Channel.recv { ch = ch }
                point.x + point.y
        """)
        assertEquals(30L, result)
    }

    @Test
    fun `test channel with variant values`() {
        val result = eval("""
            use std::effects::concurrent::channel::{chan, Channel, MockChannel}

            type Message = | Ping, Pong { value: Int } |

            let ch = chan {}
            perform MockChannel in do
                Channel.send { ch = ch, value = Pong { value = 42 } }
                match Channel.recv { ch = ch } is
                    Ping -> 0
                    Pong { value } -> value
        """)
        assertEquals(42L, result)
    }
}
