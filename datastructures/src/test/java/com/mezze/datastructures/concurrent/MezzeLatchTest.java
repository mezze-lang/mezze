package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MezzeLatchTest {

    @Test
    void testCreateWithCount() {
        MezzeLatch latch = new MezzeLatch(5);
        assertEquals(5, latch.getCount());
        assertEquals(5, latch.getInitialCount());
    }

    @Test
    void testCountDown() {
        MezzeLatch latch = new MezzeLatch(3);

        latch.countDown();
        assertEquals(2, latch.getCount());

        latch.countDown();
        assertEquals(1, latch.getCount());

        latch.countDown();
        assertEquals(0, latch.getCount());

        // Extra countdowns don't go negative
        latch.countDown();
        assertEquals(0, latch.getCount());
    }

    @Test
    void testIsDone() {
        MezzeLatch latch = new MezzeLatch(2);
        assertFalse(latch.isDone());

        latch.countDown();
        assertFalse(latch.isDone());

        latch.countDown();
        assertTrue(latch.isDone());
    }

    @Test
    @Timeout(5)
    void testAwait() throws InterruptedException {
        MezzeLatch latch = new MezzeLatch(1);

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(100);
                latch.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        releaser.start();

        latch.await();
        assertTrue(latch.isDone());
        releaser.join();
    }

    @Test
    @Timeout(5)
    void testAwaitWithTimeout() throws InterruptedException {
        MezzeLatch latch = new MezzeLatch(1);

        assertFalse(latch.await(100, TimeUnit.MILLISECONDS));

        latch.countDown();
        assertTrue(latch.await(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testZeroCountLatch() throws InterruptedException {
        MezzeLatch latch = new MezzeLatch(0);
        assertTrue(latch.isDone());
        latch.await(); // Should return immediately
    }

    @Test
    void testInvalidCount() {
        assertThrows(IllegalArgumentException.class, () -> new MezzeLatch(-1));
    }

    @Test
    @Timeout(5)
    void testMultipleWaiters() throws InterruptedException {
        MezzeLatch latch = new MezzeLatch(1);
        AtomicBoolean waiter1Done = new AtomicBoolean(false);
        AtomicBoolean waiter2Done = new AtomicBoolean(false);

        Thread waiter1 = new Thread(() -> {
            try {
                latch.await();
                waiter1Done.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread waiter2 = new Thread(() -> {
            try {
                latch.await();
                waiter2Done.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        waiter1.start();
        waiter2.start();

        Thread.sleep(100);
        assertFalse(waiter1Done.get());
        assertFalse(waiter2Done.get());

        latch.countDown();

        waiter1.join(1000);
        waiter2.join(1000);

        assertTrue(waiter1Done.get());
        assertTrue(waiter2Done.get());
    }

    @Test
    void testGetId() {
        MezzeLatch l1 = new MezzeLatch(1);
        MezzeLatch l2 = new MezzeLatch(1);
        assertNotEquals(l1.getId(), l2.getId());
    }

    @Test
    void testToString() {
        MezzeLatch latch = new MezzeLatch(5);
        String str = latch.toString();
        assertTrue(str.startsWith("<latch:"));
        assertTrue(str.contains("5/5"));

        latch.countDown();
        latch.countDown();
        assertTrue(latch.toString().contains("3/5"));
    }

    @Test
    void testEquals() {
        MezzeLatch l1 = new MezzeLatch(1);
        MezzeLatch l2 = new MezzeLatch(1);

        assertEquals(l1, l1);
        assertNotEquals(l1, l2);
        assertNotEquals(l1, null);
        assertNotEquals(l1, "not a latch");
    }

    @Test
    void testHashCode() {
        MezzeLatch l1 = new MezzeLatch(1);
        MezzeLatch l2 = new MezzeLatch(1);

        assertEquals(l1.hashCode(), l1.hashCode());
        assertNotEquals(l1.hashCode(), l2.hashCode());
    }
}
