package com.funk.language.interpreter.concurrency

import com.funk.language.interpreter.stdlib.concurrent.ChannelImplementations
import com.funk.language.interpreter.stdlib.concurrent.FunkChannel
import com.funk.language.interpreter.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for Channel implementations used by the Chan effect.
 *
 * Channels provide typed communication between virtual threads using
 * Java's LinkedBlockingQueue for thread-safe blocking operations.
 */
class ChannelTest {

    private lateinit var context: MutableMap<String, FunkValue>

    @BeforeEach
    fun setUp() {
        context = mutableMapOf()
        ChannelImplementations.registerAll(context)
    }

    // =============================================================================
    // Channel Creation Tests
    // =============================================================================

    @Test
    fun `test create unbounded channel`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap())))

        assertTrue(channel is FunkChannel, "Should return a FunkChannel")
        assertFalse((channel as FunkChannel).isClosed(), "New channel should not be closed")
    }

    @Test
    fun `test create buffered channel`() {
        val makeBuffered = context["__native_channel_make_buffered"] as NativeFunkFunction

        val channel = makeBuffered.implementation(listOf(FunkRecord(mapOf("capacity" to FunkInt(10)))))

        assertTrue(channel is FunkChannel, "Should return a FunkChannel")
        assertFalse((channel as FunkChannel).isClosed(), "New channel should not be closed")
    }

    @Test
    fun `test buffered channel rejects non-positive capacity`() {
        val makeBuffered = context["__native_channel_make_buffered"] as NativeFunkFunction

        assertThrows(FunkException::class.java) {
            makeBuffered.implementation(listOf(FunkRecord(mapOf("capacity" to FunkInt(0)))))
        }

        assertThrows(FunkException::class.java) {
            makeBuffered.implementation(listOf(FunkRecord(mapOf("capacity" to FunkInt(-1)))))
        }
    }

    // =============================================================================
    // Send/Receive Tests
    // =============================================================================

    @Test
    fun `test send and receive single value`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val recv = context["__native_channel_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        // Send a value
        send.implementation(listOf(FunkRecord(mapOf(
            "ch" to channel,
            "value" to FunkInt(42)
        ))))

        // Receive the value
        val received = recv.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        assertEquals(FunkInt(42), received)
    }

    @Test
    fun `test send and receive multiple values preserves order`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val recv = context["__native_channel_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        // Send multiple values
        for (i in 1..5) {
            send.implementation(listOf(FunkRecord(mapOf(
                "ch" to channel,
                "value" to FunkInt(i.toLong())
            ))))
        }

        // Receive values in order
        for (i in 1..5) {
            val received = recv.implementation(listOf(FunkRecord(mapOf("ch" to channel))))
            assertEquals(FunkInt(i.toLong()), received)
        }
    }

    @Test
    fun `test send to closed channel throws`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val close = context["__native_channel_close"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        // Close the channel
        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        // Attempt to send should throw
        assertThrows(FunkException::class.java) {
            send.implementation(listOf(FunkRecord(mapOf(
                "ch" to channel,
                "value" to FunkInt(42)
            ))))
        }
    }

    // =============================================================================
    // Non-blocking Operations Tests
    // =============================================================================

    @Test
    fun `test try_send succeeds when channel has space`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val trySend = context["__native_channel_try_send"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        val result = trySend.implementation(listOf(FunkRecord(mapOf(
            "ch" to channel,
            "value" to FunkInt(42)
        ))))

        assertEquals(FunkBool(true), result)
    }

    @Test
    fun `test try_send fails on closed channel`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val trySend = context["__native_channel_try_send"] as NativeFunkFunction
        val close = context["__native_channel_close"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel
        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        val result = trySend.implementation(listOf(FunkRecord(mapOf(
            "ch" to channel,
            "value" to FunkInt(42)
        ))))

        assertEquals(FunkBool(false), result)
    }

    @Test
    fun `test try_recv returns Some when value available`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val tryRecv = context["__native_channel_try_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        // Send a value first
        send.implementation(listOf(FunkRecord(mapOf(
            "ch" to channel,
            "value" to FunkInt(42)
        ))))

        val result = tryRecv.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        assertTrue(result is FunkVariant, "Should return a variant")
        assertEquals("Some", (result as FunkVariant).constructor)
        val inner = (result.value as FunkRecord).fields["val"]
        assertEquals(FunkInt(42), inner)
    }

    @Test
    fun `test try_recv returns None when channel empty`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val tryRecv = context["__native_channel_try_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        val result = tryRecv.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        assertTrue(result is FunkVariant, "Should return a variant")
        assertEquals("None", (result as FunkVariant).constructor)
    }

    // =============================================================================
    // Channel Close Tests
    // =============================================================================

    @Test
    fun `test close channel marks it closed`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val close = context["__native_channel_close"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        assertFalse(channel.isClosed())

        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        assertTrue(channel.isClosed())
    }

    @Test
    fun `test close is idempotent`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val close = context["__native_channel_close"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        // Close multiple times - should not throw
        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))
        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))
        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        assertTrue(channel.isClosed())
    }

    @Test
    fun `test can receive remaining values from closed channel`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val close = context["__native_channel_close"] as NativeFunkFunction
        val recv = context["__native_channel_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        // Send values before closing
        send.implementation(listOf(FunkRecord(mapOf("ch" to channel, "value" to FunkInt(1)))))
        send.implementation(listOf(FunkRecord(mapOf("ch" to channel, "value" to FunkInt(2)))))

        // Close the channel
        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        // Should still be able to receive the queued values
        assertEquals(FunkInt(1), recv.implementation(listOf(FunkRecord(mapOf("ch" to channel)))))
        assertEquals(FunkInt(2), recv.implementation(listOf(FunkRecord(mapOf("ch" to channel)))))
    }

    // =============================================================================
    // Multi-threaded Tests
    // =============================================================================

    @Test
    fun `test producer consumer pattern`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val recv = context["__native_channel_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        val itemCount = 100
        val received = AtomicInteger(0)
        val sum = AtomicInteger(0)
        val producerDone = CountDownLatch(1)
        val consumerDone = CountDownLatch(1)

        // Producer thread
        Thread.startVirtualThread {
            for (i in 1..itemCount) {
                send.implementation(listOf(FunkRecord(mapOf(
                    "ch" to channel,
                    "value" to FunkInt(i.toLong())
                ))))
            }
            producerDone.countDown()
        }

        // Consumer thread
        Thread.startVirtualThread {
            for (i in 1..itemCount) {
                val value = recv.implementation(listOf(FunkRecord(mapOf("ch" to channel)))) as FunkInt
                sum.addAndGet(value.value.toInt())
                received.incrementAndGet()
            }
            consumerDone.countDown()
        }

        assertTrue(producerDone.await(5, TimeUnit.SECONDS), "Producer should complete")
        assertTrue(consumerDone.await(5, TimeUnit.SECONDS), "Consumer should complete")

        assertEquals(itemCount, received.get())
        assertEquals((1..itemCount).sum(), sum.get())
    }

    @Test
    fun `test multiple producers single consumer`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val recv = context["__native_channel_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        val producerCount = 5
        val itemsPerProducer = 20
        val totalItems = producerCount * itemsPerProducer
        val received = AtomicInteger(0)
        val producersDone = CountDownLatch(producerCount)
        val consumerDone = CountDownLatch(1)

        // Multiple producer threads
        repeat(producerCount) { producerId ->
            Thread.startVirtualThread {
                for (i in 1..itemsPerProducer) {
                    send.implementation(listOf(FunkRecord(mapOf(
                        "ch" to channel,
                        "value" to FunkInt((producerId * 100 + i).toLong())
                    ))))
                }
                producersDone.countDown()
            }
        }

        // Single consumer thread
        Thread.startVirtualThread {
            for (i in 1..totalItems) {
                recv.implementation(listOf(FunkRecord(mapOf("ch" to channel))))
                received.incrementAndGet()
            }
            consumerDone.countDown()
        }

        assertTrue(producersDone.await(5, TimeUnit.SECONDS), "All producers should complete")
        assertTrue(consumerDone.await(5, TimeUnit.SECONDS), "Consumer should complete")

        assertEquals(totalItems, received.get())
    }

    @Test
    fun `test channel blocks receiver when empty`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val recv = context["__native_channel_recv"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        val receivedValue = AtomicReference<FunkValue?>()
        val receiveStarted = CountDownLatch(1)
        val receiveCompleted = CountDownLatch(1)

        // Start receiver thread (will block waiting for value)
        Thread.startVirtualThread {
            receiveStarted.countDown()
            val value = recv.implementation(listOf(FunkRecord(mapOf("ch" to channel))))
            receivedValue.set(value)
            receiveCompleted.countDown()
        }

        // Wait for receiver to start
        assertTrue(receiveStarted.await(1, TimeUnit.SECONDS))

        // Give it a moment to confirm it's blocking
        Thread.sleep(100)
        assertNull(receivedValue.get(), "Receiver should be blocking")

        // Now send a value
        send.implementation(listOf(FunkRecord(mapOf(
            "ch" to channel,
            "value" to FunkInt(42)
        ))))

        // Receiver should complete
        assertTrue(receiveCompleted.await(1, TimeUnit.SECONDS), "Receiver should complete after send")
        assertEquals(FunkInt(42), receivedValue.get())
    }

    // =============================================================================
    // Display String Tests
    // =============================================================================

    @Test
    fun `test channel display string shows state`() {
        val makeChannel = context["__native_channel_make"] as NativeFunkFunction
        val send = context["__native_channel_send"] as NativeFunkFunction
        val close = context["__native_channel_close"] as NativeFunkFunction

        val channel = makeChannel.implementation(listOf(FunkRecord(emptyMap()))) as FunkChannel

        assertTrue(channel.getDisplayString().contains("open"))
        assertTrue(channel.getDisplayString().contains("0"))

        // Add items
        send.implementation(listOf(FunkRecord(mapOf("ch" to channel, "value" to FunkInt(1)))))
        send.implementation(listOf(FunkRecord(mapOf("ch" to channel, "value" to FunkInt(2)))))

        assertTrue(channel.getDisplayString().contains("2"))

        // Close
        close.implementation(listOf(FunkRecord(mapOf("ch" to channel))))

        assertTrue(channel.getDisplayString().contains("closed"))
    }
}
