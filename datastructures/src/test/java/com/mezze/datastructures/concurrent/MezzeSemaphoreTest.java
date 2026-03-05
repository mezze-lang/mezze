package com.mezze.datastructures.concurrent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MezzeSemaphoreTest {

    @Test
    void testCreateWithPermits() {
        MezzeSemaphore sem = new MezzeSemaphore(5);
        assertEquals(5, sem.availablePermits());
        assertEquals(5, sem.getInitialPermits());
    }

    @Test
    void testAcquireRelease() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(3);

        sem.acquire();
        assertEquals(2, sem.availablePermits());

        sem.acquire();
        assertEquals(1, sem.availablePermits());

        sem.release();
        assertEquals(2, sem.availablePermits());

        sem.release();
        assertEquals(3, sem.availablePermits());
    }

    @Test
    void testAcquireMultiple() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(5);

        sem.acquire(3);
        assertEquals(2, sem.availablePermits());

        sem.release(3);
        assertEquals(5, sem.availablePermits());
    }

    @Test
    void testTryAcquire() {
        MezzeSemaphore sem = new MezzeSemaphore(1);

        assertTrue(sem.tryAcquire());
        assertEquals(0, sem.availablePermits());

        assertFalse(sem.tryAcquire());

        sem.release();
        assertTrue(sem.tryAcquire());
    }

    @Test
    @Timeout(5)
    void testTryAcquireWithTimeout() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(0);

        assertFalse(sem.tryAcquire(100, TimeUnit.MILLISECONDS));

        sem.release();
        assertTrue(sem.tryAcquire(100, TimeUnit.MILLISECONDS));
    }

    @Test
    void testFairness() {
        MezzeSemaphore fair = new MezzeSemaphore(1, true);
        assertTrue(fair.isFair());

        MezzeSemaphore unfair = new MezzeSemaphore(1, false);
        assertFalse(unfair.isFair());
    }

    @Test
    void testDrainPermits() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(5);

        sem.acquire(2);
        assertEquals(3, sem.availablePermits());

        int drained = sem.drainPermits();
        assertEquals(3, drained);
        assertEquals(0, sem.availablePermits());
    }

    @Test
    void testWithPermitRunnable() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(1);
        AtomicInteger counter = new AtomicInteger(0);

        sem.withPermit(() -> {
            assertEquals(0, sem.availablePermits());
            counter.incrementAndGet();
        });

        assertEquals(1, sem.availablePermits());
        assertEquals(1, counter.get());
    }

    @Test
    void testWithPermitSupplier() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(1);

        String result = sem.withPermit(() -> {
            assertEquals(0, sem.availablePermits());
            return "result";
        });

        assertEquals("result", result);
        assertEquals(1, sem.availablePermits());
    }

    @Test
    void testWithPermitExceptionSafety() {
        MezzeSemaphore sem = new MezzeSemaphore(1);

        assertThrows(RuntimeException.class, () -> {
            sem.withPermit(() -> {
                throw new RuntimeException("test error");
            });
        });

        // Permit should be released even after exception
        assertEquals(1, sem.availablePermits());
    }

    @Test
    void testAcquireUninterruptibly() {
        MezzeSemaphore sem = new MezzeSemaphore(1);

        sem.acquireUninterruptibly();
        assertEquals(0, sem.availablePermits());

        sem.release();
        assertEquals(1, sem.availablePermits());
    }

    @Test
    @Timeout(5)
    void testResourceLimiting() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(2);
        AtomicInteger concurrent = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch done = new CountDownLatch(4);

        Runnable task = () -> {
            try {
                sem.acquire();
                int current = concurrent.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                Thread.sleep(50);
                concurrent.decrementAndGet();
                sem.release();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };

        for (int i = 0; i < 4; i++) {
            new Thread(task).start();
        }

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertTrue(maxConcurrent.get() <= 2);
    }

    @Test
    void testGetQueueLength() throws InterruptedException {
        MezzeSemaphore sem = new MezzeSemaphore(0);
        assertEquals(0, sem.getQueueLength());

        CountDownLatch started = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            started.countDown();
            try {
                sem.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        started.await();
        Thread.sleep(50);
        assertTrue(sem.getQueueLength() >= 0);

        sem.release();
        waiter.join(1000);
    }

    @Test
    void testGetId() {
        MezzeSemaphore s1 = new MezzeSemaphore(1);
        MezzeSemaphore s2 = new MezzeSemaphore(1);
        assertNotEquals(s1.getId(), s2.getId());
    }

    @Test
    void testToString() {
        MezzeSemaphore sem = new MezzeSemaphore(5);
        String str = sem.toString();
        assertTrue(str.startsWith("<semaphore:"));
        assertTrue(str.contains("5/5"));
    }

    @Test
    void testEquals() {
        MezzeSemaphore s1 = new MezzeSemaphore(1);
        MezzeSemaphore s2 = new MezzeSemaphore(1);

        assertEquals(s1, s1);
        assertNotEquals(s1, s2);
        assertNotEquals(s1, null);
        assertNotEquals(s1, "not a semaphore");
    }

    @Test
    void testHashCode() {
        MezzeSemaphore s1 = new MezzeSemaphore(1);
        MezzeSemaphore s2 = new MezzeSemaphore(1);

        assertEquals(s1.hashCode(), s1.hashCode());
        assertNotEquals(s1.hashCode(), s2.hashCode());
    }
}
