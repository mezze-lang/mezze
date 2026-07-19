package com.mezze.interpreter.concurrency

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/**
 * Tests for the `Chan 'a` newtype and its free-function operations.
 *
 * Post-#115: `Chan` is a plain newtype carrying `<Async>` on every
 * operation. The old `effect Channel` (with BlockingChannel/MockChannel
 * impls) is gone; all tests run under `perform LoomAsync in do`.
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
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                # Channel was created successfully if we can try_send
                try_send { ch = ch, val = 42 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test create buffered channel`() {
        val result = eval("""
            use std::effects::async::channel::{chan_bounded, send, recv, try_send, try_recv, close, is_closed}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan_bounded { cap = 10 }
            perform SystemTime in perform LoomAsync in do
                # Channel was created successfully if we can try_send
                try_send { ch = ch, val = 42 }
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // Send/Receive Tests
    // =============================================================================

    @Test
    fun `test send and receive single value`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = 42 }
                recv { ch = ch }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test send and receive multiple values preserves order`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = 1 }
                send { ch = ch, val = 2 }
                send { ch = ch, val = 3 }
                send { ch = ch, val = 4 }
                send { ch = ch, val = 5 }

                let a = recv { ch = ch }
                let b = recv { ch = ch }
                let c = recv { ch = ch }
                let d = recv { ch = ch }
                let e = recv { ch = ch }

                # Encode values to verify order
                a * 10000 + b * 1000 + c * 100 + d * 10 + e
        """)
        assertEquals(12345L, result)
    }

    @Test
    fun `test send to closed channel fails`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                close { ch = ch }
                # try_send should return false on closed channel
                try_send { ch = ch, val = 42 }
        """)
        assertEquals(false, result)
    }

    // =============================================================================
    // Non-blocking Operations Tests
    // =============================================================================

    @Test
    fun `test try_send succeeds when channel has space`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                try_send { ch = ch, val = 42 }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test try_send fails on closed channel`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                close { ch = ch }
                try_send { ch = ch, val = 42 }
        """)
        assertEquals(false, result)
    }

    @Test
    fun `test try_recv returns Some when value available`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = 42 }
                match try_recv { ch = ch } is
                    Some { val } -> val
                    None -> -1
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test try_recv returns None when channel empty`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                match try_recv { ch = ch } is
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
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                let before = is_closed { ch = ch }
                close { ch = ch }
                let after = is_closed { ch = ch }
                { before = before, after = after }
        """)
        val record = result as MezzeRecord
        assertEquals(false, record.getField("before"))
        assertEquals(true, record.getField("after"))
    }

    @Test
    fun `test is_closed returns correct value`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                let before = is_closed { ch = ch }
                close { ch = ch }
                let after = is_closed { ch = ch }
                { before = before, after = after }
        """)
        val record = result as MezzeRecord
        assertEquals(false, record.getField("before"))
        assertEquals(true, record.getField("after"))
    }

    @Test
    fun `test close is idempotent`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                close { ch = ch }
                close { ch = ch }
                close { ch = ch }
                is_closed { ch = ch }
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test can receive remaining values from closed channel`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = 1 }
                send { ch = ch, val = 2 }
                close { ch = ch }

                # Should still receive queued values
                let a = recv { ch = ch }
                let b = recv { ch = ch }
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
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch1 = chan {}
            let ch2 = chan {}
            perform SystemTime in perform LoomAsync in do
                # Send to second channel only
                send { ch = ch2, val = 42 }

                match recv_any_timeout { channels = [ch1, ch2], ms = 1000 } is
                    Some { val } -> val.index == 1 && val.val == 42
                    None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test recv_any with multiple channels having data`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch1 = chan {}
            let ch2 = chan {}
            perform SystemTime in perform LoomAsync in do
                # Send to both channels
                send { ch = ch1, val = 1 }
                send { ch = ch2, val = 2 }

                # Should return first channel's value (index 0)
                match recv_any_timeout { channels = [ch1, ch2], ms = 1000 } is
                    Some { val } -> val.index == 0 && val.val == 1
                    None -> False
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test recv_any_timeout returns None on timeout`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch1 = chan {}
            let ch2 = chan {}
            perform SystemTime in perform LoomAsync in do
                # Both channels empty, should timeout
                match recv_any_timeout { channels = [ch1, ch2], ms = 50 } is
                    Some { val } -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    @Test
    fun `test recv_any_timeout returns Some when data available`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch1 = chan {}
            let ch2 = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch2, val = 99 }

                match recv_any_timeout { channels = [ch1, ch2], ms = 1000 } is
                    Some { val } -> val.index == 1 && val.val == 99
                    None -> False
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // Single-threaded sanity tests
    // =============================================================================

    @Test
    fun `test single-threaded send and recv`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = 42 }
                recv { ch = ch }
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test recv_any_timeout returns None when all channels empty`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch1 = chan {}
            let ch2 = chan {}
            perform SystemTime in perform LoomAsync in do
                match recv_any_timeout { channels = [ch1, ch2], ms = 100 } is
                    Some { val } -> False
                    None -> True
        """)
        assertEquals(true, result)
    }

    // =============================================================================
    // Multi-threaded tests under LoomAsync
    // =============================================================================

    @Test
    fun `test producer consumer pattern`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                    # Producer sends 10 values
                    let producer = Async.spawn { f = {} -> do
                        send { ch = ch, val = 1 }
                        send { ch = ch, val = 2 }
                        send { ch = ch, val = 3 }
                        send { ch = ch, val = 4 }
                        send { ch = ch, val = 5 }
                        send { ch = ch, val = 6 }
                        send { ch = ch, val = 7 }
                        send { ch = ch, val = 8 }
                        send { ch = ch, val = 9 }
                        send { ch = ch, val = 10 }
                        {}
                    }

                    # Consumer receives 10 values
                    let a1 = recv { ch = ch }
                    let a2 = recv { ch = ch }
                    let a3 = recv { ch = ch }
                    let a4 = recv { ch = ch }
                    let a5 = recv { ch = ch }
                    let a6 = recv { ch = ch }
                    let a7 = recv { ch = ch }
                    let a8 = recv { ch = ch }
                    let a9 = recv { ch = ch }
                    let a10 = recv { ch = ch }

                    let _ = await { p = producer }

                    # Sum should be 1+2+3+4+5+6+7+8+9+10 = 55
                    a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10
        """)
        assertEquals(55L, result)
    }

    @Test
    fun `test multiple producers single consumer`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                    # Producer 1 sends 1, 2
                    let p1 = Async.spawn { f = {} -> do
                        send { ch = ch, val = 1 }
                        send { ch = ch, val = 2 }
                        {}
                    }

                    # Producer 2 sends 10, 20
                    let p2 = Async.spawn { f = {} -> do
                        send { ch = ch, val = 10 }
                        send { ch = ch, val = 20 }
                        {}
                    }

                    # Producer 3 sends 100, 200
                    let p3 = Async.spawn { f = {} -> do
                        send { ch = ch, val = 100 }
                        send { ch = ch, val = 200 }
                        {}
                    }

                    # Consumer receives all 6 values
                    let v1 = recv { ch = ch }
                    let v2 = recv { ch = ch }
                    let v3 = recv { ch = ch }
                    let v4 = recv { ch = ch }
                    let v5 = recv { ch = ch }
                    let v6 = recv { ch = ch }

                    let _ = await { p = p1 }
                    let _ = await { p = p2 }
                    let _ = await { p = p3 }

                    # Sum should be 1+2+10+20+100+200 = 333
                    v1 + v2 + v3 + v4 + v5 + v6
        """)
        assertEquals(333L, result)
    }

    @Test
    fun `test channel send and recv across spawned tasks`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                    # Spawn sender
                    let p = Async.spawn { f = {} -> do
                        send { ch = ch, val = 42 }
                        {}
                    }

                    # Receive
                    let received = recv { ch = ch }
                    let _ = await { p = p }
                    received
        """)
        assertEquals(42L, result)
    }

    @Test
    fun `test channel blocks receiver when empty`() {
        // In this test, the receiver will block until the sender sends
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                    # Spawn sender that delays then sends
                    let sender = Async.spawn { f = {} -> do
                        sleep { ms = 50 }
                        send { ch = ch, val = 42 }
                        {}
                    }

                    # Receiver will block until value is available
                    let received = recv { ch = ch }
                    let _ = await { p = sender }
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
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = "hello" }
                recv { ch = ch }
        """)
        assertEquals("hello", result)
    }

    @Test
    fun `test channel with record values`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = { x = 10, y = 20 } }
                let point = recv { ch = ch }
                point.x + point.y
        """)
        assertEquals(30L, result)
    }

    @Test
    fun `test channel with variant values`() {
        val result = eval("""
            use std::effects::async::channel::{chan, send, recv, try_send, try_recv, close, is_closed, recv_any, recv_any_timeout}
            use std::effects::async::{ Async, LoomAsync, await, sleep }
            use std::effects::time::{Time, SystemTime}

            type Message = | Ping, Pong { value: Int } |

            let ch = chan {}
            perform SystemTime in perform LoomAsync in do
                send { ch = ch, val = Pong { value = 42 } }
                match recv { ch = ch } is
                    Ping -> 0
                    Pong { value } -> value
        """)
        assertEquals(42L, result)
    }
}
