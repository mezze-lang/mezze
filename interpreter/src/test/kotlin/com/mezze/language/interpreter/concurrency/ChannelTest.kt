package com.mezze.language.interpreter.concurrency

import com.mezze.language.interpreter.stdlib.concurrent.ChannelImplementations
import com.mezze.language.interpreter.stdlib.concurrent.MezzeChannel
import com.mezze.language.interpreter.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for Channel implementations used by the Channel effect.
 *
 * Chan is the value type (like TVar for STM)
 * Channel is the effect (like STM)
 *
 * Channels provide typed communication between virtual threads using
 * Java's LinkedBlockingQueue for thread-safe blocking operations.
 */
class ChannelTest {

    private lateinit var context: MutableMap<String, MezzeValue>

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
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap())))

        assertTrue(channel is MezzeChannel, "Should return a MezzeChannel")
        assertFalse((channel as MezzeChannel).isClosed(), "New channel should not be closed")
    }

    @Test
    fun `test create buffered channel`() {
        val makeBuffered = context["__native_channel_make_buffered"] as NativeMezzeFunction

        val channel = makeBuffered.implementation(listOf(MezzeRecord(mapOf("capacity" to MezzeInt(10)))))

        assertTrue(channel is MezzeChannel, "Should return a MezzeChannel")
        assertFalse((channel as MezzeChannel).isClosed(), "New channel should not be closed")
    }

    @Test
    fun `test buffered channel rejects non-positive capacity`() {
        val makeBuffered = context["__native_channel_make_buffered"] as NativeMezzeFunction

        assertThrows(MezzeException::class.java) {
            makeBuffered.implementation(listOf(MezzeRecord(mapOf("capacity" to MezzeInt(0)))))
        }

        assertThrows(MezzeException::class.java) {
            makeBuffered.implementation(listOf(MezzeRecord(mapOf("capacity" to MezzeInt(-1)))))
        }
    }

    // =============================================================================
    // Send/Receive Tests
    // =============================================================================

    @Test
    fun `test send and receive single value`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recv = context["__native_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Send a value
        send.implementation(listOf(MezzeRecord(mapOf(
            "ch" to channel,
            "value" to MezzeInt(42)
        ))))

        // Receive the value
        val received = recv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        assertEquals(MezzeInt(42), received)
    }

    @Test
    fun `test send and receive multiple values preserves order`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recv = context["__native_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Send multiple values
        for (i in 1..5) {
            send.implementation(listOf(MezzeRecord(mapOf(
                "ch" to channel,
                "value" to MezzeInt(i.toLong())
            ))))
        }

        // Receive values in order
        for (i in 1..5) {
            val received = recv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
            assertEquals(MezzeInt(i.toLong()), received)
        }
    }

    @Test
    fun `test send to closed channel throws`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val close = context["__native_channel_close"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Close the channel
        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        // Attempt to send should throw
        assertThrows(MezzeException::class.java) {
            send.implementation(listOf(MezzeRecord(mapOf(
                "ch" to channel,
                "value" to MezzeInt(42)
            ))))
        }
    }

    // =============================================================================
    // Non-blocking Operations Tests
    // =============================================================================

    @Test
    fun `test try_send succeeds when channel has space`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val trySend = context["__native_channel_try_send"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val result = trySend.implementation(listOf(MezzeRecord(mapOf(
            "ch" to channel,
            "value" to MezzeInt(42)
        ))))

        assertEquals(MezzeBool(true), result)
    }

    @Test
    fun `test try_send fails on closed channel`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val trySend = context["__native_channel_try_send"] as NativeMezzeFunction
        val close = context["__native_channel_close"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        val result = trySend.implementation(listOf(MezzeRecord(mapOf(
            "ch" to channel,
            "value" to MezzeInt(42)
        ))))

        assertEquals(MezzeBool(false), result)
    }

    @Test
    fun `test try_recv returns Some when value available`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val tryRecv = context["__native_channel_try_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Send a value first
        send.implementation(listOf(MezzeRecord(mapOf(
            "ch" to channel,
            "value" to MezzeInt(42)
        ))))

        val result = tryRecv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        assertTrue(result is MezzeVariant, "Should return a variant")
        assertEquals("Some", (result as MezzeVariant).constructor)
        val inner = (result.value as MezzeRecord).fields["val"]
        assertEquals(MezzeInt(42), inner)
    }

    @Test
    fun `test try_recv returns None when channel empty`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val tryRecv = context["__native_channel_try_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val result = tryRecv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        assertTrue(result is MezzeVariant, "Should return a variant")
        assertEquals("None", (result as MezzeVariant).constructor)
    }

    // =============================================================================
    // Channel Close Tests
    // =============================================================================

    @Test
    fun `test close channel marks it closed`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val close = context["__native_channel_close"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        assertFalse(channel.isClosed())

        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        assertTrue(channel.isClosed())
    }

    @Test
    fun `test is_closed returns correct value`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val close = context["__native_channel_close"] as NativeMezzeFunction
        val isClosed = context["__native_channel_is_closed"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val beforeClose = isClosed.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
        assertEquals(MezzeBool(false), beforeClose)

        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        val afterClose = isClosed.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
        assertEquals(MezzeBool(true), afterClose)
    }

    @Test
    fun `test close is idempotent`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val close = context["__native_channel_close"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Close multiple times - should not throw
        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        assertTrue(channel.isClosed())
    }

    @Test
    fun `test can receive remaining values from closed channel`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val close = context["__native_channel_close"] as NativeMezzeFunction
        val recv = context["__native_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Send values before closing
        send.implementation(listOf(MezzeRecord(mapOf("ch" to channel, "value" to MezzeInt(1)))))
        send.implementation(listOf(MezzeRecord(mapOf("ch" to channel, "value" to MezzeInt(2)))))

        // Close the channel
        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        // Should still be able to receive the queued values
        assertEquals(MezzeInt(1), recv.implementation(listOf(MezzeRecord(mapOf("ch" to channel)))))
        assertEquals(MezzeInt(2), recv.implementation(listOf(MezzeRecord(mapOf("ch" to channel)))))
    }

    // =============================================================================
    // Select Operations (recv_any) Tests
    // =============================================================================

    @Test
    fun `test recv_any returns first available value`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recvAny = context["__native_channel_recv_any"] as NativeMezzeFunction

        val ch1 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch2 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Send to second channel only
        send.implementation(listOf(MezzeRecord(mapOf("ch" to ch2, "value" to MezzeInt(42)))))

        val result = recvAny.implementation(listOf(MezzeRecord(mapOf(
            "channels" to MezzeArrayList(mutableListOf(ch1, ch2))
        )))) as MezzeRecord

        assertEquals(MezzeInt(1), result.fields["index"])  // Index 1 = ch2
        assertEquals(MezzeInt(42), result.fields["value"])
    }

    @Test
    fun `test recv_any with multiple channels having data`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recvAny = context["__native_channel_recv_any"] as NativeMezzeFunction

        val ch1 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch2 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Send to both channels
        send.implementation(listOf(MezzeRecord(mapOf("ch" to ch1, "value" to MezzeInt(1)))))
        send.implementation(listOf(MezzeRecord(mapOf("ch" to ch2, "value" to MezzeInt(2)))))

        // Should return first channel's value (index 0)
        val result = recvAny.implementation(listOf(MezzeRecord(mapOf(
            "channels" to MezzeArrayList(mutableListOf(ch1, ch2))
        )))) as MezzeRecord

        assertEquals(MezzeInt(0), result.fields["index"])
        assertEquals(MezzeInt(1), result.fields["value"])
    }

    @Test
    fun `test recv_any throws on empty channel list`() {
        val recvAny = context["__native_channel_recv_any"] as NativeMezzeFunction

        assertThrows(MezzeException::class.java) {
            recvAny.implementation(listOf(MezzeRecord(mapOf(
                "channels" to MezzeArrayList(mutableListOf())
            ))))
        }
    }

    @Test
    fun `test recv_any_timeout returns None on timeout`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val recvAnyTimeout = context["__native_channel_recv_any_timeout"] as NativeMezzeFunction

        val ch1 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch2 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        // Both channels empty, should timeout
        val result = recvAnyTimeout.implementation(listOf(MezzeRecord(mapOf(
            "channels" to MezzeArrayList(mutableListOf(ch1, ch2)),
            "ms" to MezzeInt(50)  // 50ms timeout
        )))) as MezzeVariant

        assertEquals("None", result.constructor)
    }

    @Test
    fun `test recv_any_timeout returns Some when data available`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recvAnyTimeout = context["__native_channel_recv_any_timeout"] as NativeMezzeFunction

        val ch1 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch2 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        send.implementation(listOf(MezzeRecord(mapOf("ch" to ch2, "value" to MezzeInt(99)))))

        val result = recvAnyTimeout.implementation(listOf(MezzeRecord(mapOf(
            "channels" to MezzeArrayList(mutableListOf(ch1, ch2)),
            "ms" to MezzeInt(1000)
        )))) as MezzeVariant

        assertEquals("Some", result.constructor)
        val inner = (result.value as MezzeRecord).fields["val"] as MezzeRecord
        assertEquals(MezzeInt(1), inner.fields["index"])
        assertEquals(MezzeInt(99), inner.fields["value"])
    }

    // =============================================================================
    // MockChannel Tests
    // =============================================================================

    @Test
    fun `test mock channel send and recv`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val mockSend = context["__native_mock_channel_send"] as NativeMezzeFunction
        val mockRecv = context["__native_mock_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        mockSend.implementation(listOf(MezzeRecord(mapOf(
            "ch" to channel,
            "value" to MezzeInt(42)
        ))))

        val result = mockRecv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
        assertEquals(MezzeInt(42), result)
    }

    @Test
    fun `test mock channel recv throws on empty`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val mockRecv = context["__native_mock_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val exception = assertThrows(MezzeException::class.java) {
            mockRecv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
        }

        assertTrue(exception.message?.contains("would block") == true)
    }

    @Test
    fun `test mock recv_any throws when all empty`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val mockRecvAny = context["__native_mock_channel_recv_any"] as NativeMezzeFunction

        val ch1 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch2 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val exception = assertThrows(MezzeException::class.java) {
            mockRecvAny.implementation(listOf(MezzeRecord(mapOf(
                "channels" to MezzeArrayList(mutableListOf(ch1, ch2))
            ))))
        }

        assertTrue(exception.message?.contains("would block") == true)
    }

    @Test
    fun `test mock recv_any_timeout returns None when empty`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val mockRecvAnyTimeout = context["__native_mock_channel_recv_any_timeout"] as NativeMezzeFunction

        val ch1 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch2 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val result = mockRecvAnyTimeout.implementation(listOf(MezzeRecord(mapOf(
            "channels" to MezzeArrayList(mutableListOf(ch1, ch2)),
            "ms" to MezzeInt(100)
        )))) as MezzeVariant

        assertEquals("None", result.constructor)
    }

    // =============================================================================
    // Multi-threaded Tests
    // =============================================================================

    @Test
    fun `test producer consumer pattern`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recv = context["__native_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val itemCount = 100
        val received = AtomicInteger(0)
        val sum = AtomicInteger(0)
        val producerDone = CountDownLatch(1)
        val consumerDone = CountDownLatch(1)

        // Producer thread
        Thread.startVirtualThread {
            for (i in 1..itemCount) {
                send.implementation(listOf(MezzeRecord(mapOf(
                    "ch" to channel,
                    "value" to MezzeInt(i.toLong())
                ))))
            }
            producerDone.countDown()
        }

        // Consumer thread
        Thread.startVirtualThread {
            for (i in 1..itemCount) {
                val value = recv.implementation(listOf(MezzeRecord(mapOf("ch" to channel)))) as MezzeInt
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
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recv = context["__native_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

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
                    send.implementation(listOf(MezzeRecord(mapOf(
                        "ch" to channel,
                        "value" to MezzeInt((producerId * 100 + i).toLong())
                    ))))
                }
                producersDone.countDown()
            }
        }

        // Single consumer thread
        Thread.startVirtualThread {
            for (i in 1..totalItems) {
                recv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
                received.incrementAndGet()
            }
            consumerDone.countDown()
        }

        assertTrue(producersDone.await(5, TimeUnit.SECONDS), "All producers should complete")
        assertTrue(consumerDone.await(5, TimeUnit.SECONDS), "Consumer should complete")

        assertEquals(totalItems, received.get())
    }

    @Test
    fun `test recv_any with concurrent producers`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recvAny = context["__native_channel_recv_any"] as NativeMezzeFunction

        val ch1 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch2 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel
        val ch3 = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val received = AtomicInteger(0)
        val producersDone = CountDownLatch(3)
        val consumerDone = CountDownLatch(1)

        // Producers on different channels
        Thread.startVirtualThread {
            for (i in 1..10) {
                send.implementation(listOf(MezzeRecord(mapOf("ch" to ch1, "value" to MezzeInt(i.toLong())))))
            }
            producersDone.countDown()
        }
        Thread.startVirtualThread {
            for (i in 11..20) {
                send.implementation(listOf(MezzeRecord(mapOf("ch" to ch2, "value" to MezzeInt(i.toLong())))))
            }
            producersDone.countDown()
        }
        Thread.startVirtualThread {
            for (i in 21..30) {
                send.implementation(listOf(MezzeRecord(mapOf("ch" to ch3, "value" to MezzeInt(i.toLong())))))
            }
            producersDone.countDown()
        }

        // Consumer using recv_any
        Thread.startVirtualThread {
            for (i in 1..30) {
                recvAny.implementation(listOf(MezzeRecord(mapOf(
                    "channels" to MezzeArrayList(mutableListOf(ch1, ch2, ch3))
                ))))
                received.incrementAndGet()
            }
            consumerDone.countDown()
        }

        assertTrue(producersDone.await(5, TimeUnit.SECONDS), "All producers should complete")
        assertTrue(consumerDone.await(5, TimeUnit.SECONDS), "Consumer should complete")

        assertEquals(30, received.get())
    }

    @Test
    fun `test channel blocks receiver when empty`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val recv = context["__native_channel_recv"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        val receivedValue = AtomicReference<MezzeValue?>()
        val receiveStarted = CountDownLatch(1)
        val receiveCompleted = CountDownLatch(1)

        // Start receiver thread (will block waiting for value)
        Thread.startVirtualThread {
            receiveStarted.countDown()
            val value = recv.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))
            receivedValue.set(value)
            receiveCompleted.countDown()
        }

        // Wait for receiver to start
        assertTrue(receiveStarted.await(1, TimeUnit.SECONDS))

        // Give it a moment to confirm it's blocking
        Thread.sleep(100)
        assertNull(receivedValue.get(), "Receiver should be blocking")

        // Now send a value
        send.implementation(listOf(MezzeRecord(mapOf(
            "ch" to channel,
            "value" to MezzeInt(42)
        ))))

        // Receiver should complete
        assertTrue(receiveCompleted.await(1, TimeUnit.SECONDS), "Receiver should complete after send")
        assertEquals(MezzeInt(42), receivedValue.get())
    }

    // =============================================================================
    // Display String Tests
    // =============================================================================

    @Test
    fun `test channel display string shows state`() {
        val makeChannel = context["__native_channel_make"] as NativeMezzeFunction
        val send = context["__native_channel_send"] as NativeMezzeFunction
        val close = context["__native_channel_close"] as NativeMezzeFunction

        val channel = makeChannel.implementation(listOf(MezzeRecord(emptyMap()))) as MezzeChannel

        assertTrue(channel.getDisplayString().contains("open"))
        assertTrue(channel.getDisplayString().contains("0"))

        // Add items
        send.implementation(listOf(MezzeRecord(mapOf("ch" to channel, "value" to MezzeInt(1)))))
        send.implementation(listOf(MezzeRecord(mapOf("ch" to channel, "value" to MezzeInt(2)))))

        assertTrue(channel.getDisplayString().contains("2"))

        // Close
        close.implementation(listOf(MezzeRecord(mapOf("ch" to channel))))

        assertTrue(channel.getDisplayString().contains("closed"))
    }
}
