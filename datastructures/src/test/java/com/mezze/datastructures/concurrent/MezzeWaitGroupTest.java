package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class MezzeWaitGroupTest {

    @Test
    void testCreateWithZeroCount() {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        assertEquals(0, wg.getCount());
        assertTrue(wg.isDone());
    }

    @Test
    void testAddAndDone() {
        MezzeWaitGroup wg = new MezzeWaitGroup();

        wg.add(3);
        assertEquals(3, wg.getCount());
        assertFalse(wg.isDone());

        wg.done();
        assertEquals(2, wg.getCount());

        wg.done();
        assertEquals(1, wg.getCount());

        wg.done();
        assertEquals(0, wg.getCount());
        assertTrue(wg.isDone());
    }

    @Test
    void testAddNegative() {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        wg.add(5);
        wg.add(-2);
        assertEquals(3, wg.getCount());
    }

    @Test
    void testNegativeCountThrows() {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        wg.add(1);

        assertThrows(IllegalStateException.class, () -> wg.add(-2));
    }

    @Test
    @Timeout(5)
    void testAwait() throws InterruptedException {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        wg.add(2);

        Thread worker1 = new Thread(() -> {
            try {
                Thread.sleep(50);
                wg.done();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread worker2 = new Thread(() -> {
            try {
                Thread.sleep(100);
                wg.done();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        worker1.start();
        worker2.start();

        wg.await();
        assertTrue(wg.isDone());
        worker1.join();
        worker2.join();
    }

    @Test
    @Timeout(5)
    void testAwaitWithTimeout() throws InterruptedException {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        wg.add(1);

        assertFalse(wg.await(100, TimeUnit.MILLISECONDS));
        assertFalse(wg.isDone());

        wg.done();
        assertTrue(wg.await(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testAwaitOnEmpty() throws InterruptedException {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        wg.await(); // Should return immediately
        assertTrue(wg.isDone());
    }

    @Test
    @Timeout(5)
    void testDynamicAdd() throws InterruptedException {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        AtomicInteger tasksCompleted = new AtomicInteger(0);

        wg.add(1);

        Thread worker = new Thread(() -> {
            // First task spawns two more
            wg.add(2);
            tasksCompleted.incrementAndGet();
            wg.done();

            // Complete the two spawned tasks
            tasksCompleted.incrementAndGet();
            wg.done();
            tasksCompleted.incrementAndGet();
            wg.done();
        });
        worker.start();

        wg.await();
        assertEquals(3, tasksCompleted.get());
        worker.join();
    }

    @Test
    @Timeout(5)
    void testMultipleWaiters() throws InterruptedException {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        wg.add(1);

        AtomicBoolean waiter1Done = new AtomicBoolean(false);
        AtomicBoolean waiter2Done = new AtomicBoolean(false);

        Thread waiter1 = new Thread(() -> {
            try {
                wg.await();
                waiter1Done.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Thread waiter2 = new Thread(() -> {
            try {
                wg.await();
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

        wg.done();

        waiter1.join(1000);
        waiter2.join(1000);

        assertTrue(waiter1Done.get());
        assertTrue(waiter2Done.get());
    }

    @Test
    void testGetId() {
        MezzeWaitGroup wg1 = new MezzeWaitGroup();
        MezzeWaitGroup wg2 = new MezzeWaitGroup();
        assertNotEquals(wg1.getId(), wg2.getId());
    }

    @Test
    void testToString() {
        MezzeWaitGroup wg = new MezzeWaitGroup();
        String str = wg.toString();
        assertTrue(str.startsWith("<waitgroup:"));
        assertTrue(str.contains(":0>"));

        wg.add(5);
        assertTrue(wg.toString().contains(":5>"));
    }

    @Test
    void testEquals() {
        MezzeWaitGroup wg1 = new MezzeWaitGroup();
        MezzeWaitGroup wg2 = new MezzeWaitGroup();

        assertEquals(wg1, wg1);
        assertNotEquals(wg1, wg2);
        assertNotEquals(wg1, null);
        assertNotEquals(wg1, "not a waitgroup");
    }

    @Test
    void testHashCode() {
        MezzeWaitGroup wg1 = new MezzeWaitGroup();
        MezzeWaitGroup wg2 = new MezzeWaitGroup();

        assertEquals(wg1.hashCode(), wg1.hashCode());
        assertNotEquals(wg1.hashCode(), wg2.hashCode());
    }
}
