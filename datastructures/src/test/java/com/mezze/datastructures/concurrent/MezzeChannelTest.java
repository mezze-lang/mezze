package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MezzeChannelTest {

    @Test
    void testUnboundedChannel() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        assertEquals(0, channel.getCapacity());

        channel.send(1);
        channel.send(2);
        channel.send(3);

        assertEquals(1, channel.receive());
        assertEquals(2, channel.receive());
        assertEquals(3, channel.receive());
    }

    @Test
    void testBoundedChannel() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>(2);
        assertEquals(2, channel.getCapacity());

        assertTrue(channel.trySend(1));
        assertTrue(channel.trySend(2));
        // Channel is full, trySend should return false
        assertFalse(channel.trySend(3));
    }

    @Test
    void testTryReceiveEmpty() {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        Optional<Integer> result = channel.tryReceive();
        assertTrue(result.isEmpty());
    }

    @Test
    void testTryReceiveWithValue() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        channel.send(42);
        Optional<Integer> result = channel.tryReceive();
        assertTrue(result.isPresent());
        assertEquals(42, result.get());
    }

    @Test
    @Timeout(5)
    void testReceiveWithTimeout() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        Optional<Integer> empty = channel.receive(100, TimeUnit.MILLISECONDS);
        assertTrue(empty.isEmpty());

        channel.send(99);
        Optional<Integer> value = channel.receive(100, TimeUnit.MILLISECONDS);
        assertTrue(value.isPresent());
        assertEquals(99, value.get());
    }

    @Test
    void testClose() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        channel.send(1);
        assertFalse(channel.isClosed());

        channel.close();
        assertTrue(channel.isClosed());

        // Can still receive pending items
        assertEquals(1, channel.receive());

        // But sending throws
        assertThrows(MezzeChannel.ChannelClosedException.class, () -> channel.send(2));
    }

    @Test
    void testTrySendOnClosedChannel() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        channel.close();

        assertFalse(channel.trySend(1));
    }

    @Test
    void testIsDrained() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        channel.send(1);
        assertFalse(channel.isDrained());

        channel.close();
        assertFalse(channel.isDrained()); // Still has item

        channel.receive();
        assertTrue(channel.isDrained()); // Now drained
    }

    @Test
    void testSize() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        assertEquals(0, channel.size());
        assertTrue(channel.isEmpty());

        channel.send(1);
        assertEquals(1, channel.size());
        assertFalse(channel.isEmpty());

        channel.send(2);
        assertEquals(2, channel.size());

        channel.receive();
        assertEquals(1, channel.size());
    }

    @Test
    void testClear() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        channel.send(1);
        channel.send(2);
        channel.send(3);

        assertEquals(3, channel.size());
        channel.clear();
        assertEquals(0, channel.size());
    }

    @Test
    void testGetId() {
        MezzeChannel<Integer> ch1 = new MezzeChannel<>();
        MezzeChannel<Integer> ch2 = new MezzeChannel<>();
        assertNotEquals(ch1.getId(), ch2.getId());
    }

    @Test
    void testToString() {
        MezzeChannel<Integer> channel = new MezzeChannel<>(10);
        String str = channel.toString();
        assertTrue(str.startsWith("<channel:"));
        assertTrue(str.contains("open"));
    }

    @Test
    void testToStringClosed() {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        channel.close();
        assertTrue(channel.toString().contains("closed"));
    }

    @Test
    void testEquals() {
        MezzeChannel<Integer> ch1 = new MezzeChannel<>();
        MezzeChannel<Integer> ch2 = new MezzeChannel<>();

        assertEquals(ch1, ch1);
        assertNotEquals(ch1, ch2);
        assertNotEquals(ch1, null);
        assertNotEquals(ch1, "not a channel");
    }

    @Test
    void testHashCode() {
        MezzeChannel<Integer> ch1 = new MezzeChannel<>();
        MezzeChannel<Integer> ch2 = new MezzeChannel<>();

        assertEquals(ch1.hashCode(), ch1.hashCode());
        assertNotEquals(ch1.hashCode(), ch2.hashCode());
    }

    @Test
    @Timeout(5)
    void testConcurrentSendReceive() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Integer> received = new AtomicReference<>();

        Thread receiver = new Thread(() -> {
            try {
                received.set(channel.receive());
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        receiver.start();

        Thread.sleep(50); // Let receiver start waiting
        channel.send(42);

        assertTrue(latch.await(2, TimeUnit.SECONDS));
        assertEquals(42, received.get());
    }

    @Test
    void testGetQueue() {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        assertNotNull(channel.getQueue());
    }

    @Test
    @Timeout(5)
    void testSendWithTimeout() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>(1);
        assertTrue(channel.send(1, 100, TimeUnit.MILLISECONDS));

        // Channel is full, should timeout
        assertFalse(channel.send(2, 100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testSendWithTimeoutOnClosedChannel() throws InterruptedException {
        MezzeChannel<Integer> channel = new MezzeChannel<>();
        channel.close();
        assertFalse(channel.send(1, 100, TimeUnit.MILLISECONDS));
    }
}
